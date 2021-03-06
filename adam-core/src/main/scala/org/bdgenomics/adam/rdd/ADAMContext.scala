/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.adam.rdd

import java.io.{ File, FileNotFoundException, InputStream }
import htsjdk.samtools.{ SAMFileHeader, ValidationStringency }
import htsjdk.samtools.util.Locatable
import htsjdk.variant.vcf.{
  VCFHeader,
  VCFCompoundHeaderLine,
  VCFFormatHeaderLine,
  VCFHeaderLine,
  VCFInfoHeaderLine
}
import org.apache.avro.Schema
import org.apache.avro.file.DataFileStream
import org.apache.avro.generic.IndexedRecord
import org.apache.avro.specific.{ SpecificDatumReader, SpecificRecord, SpecificRecordBase }
import org.apache.hadoop.fs.{ FileSystem, Path, PathFilter }
import org.apache.hadoop.io.{ LongWritable, Text }
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat
import org.apache.parquet.avro.{ AvroParquetInputFormat, AvroReadSupport }
import org.apache.parquet.filter2.predicate.FilterPredicate
import org.apache.parquet.hadoop.ParquetInputFormat
import org.apache.parquet.hadoop.util.ContextUtil
import org.apache.spark.SparkContext
import org.apache.spark.rdd.MetricsContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.bdgenomics.adam.converters._
import org.bdgenomics.adam.instrumentation.Timers._
import org.bdgenomics.adam.io._
import org.bdgenomics.adam.models._
import org.bdgenomics.adam.projections.{
  FeatureField,
  Projection
}
import org.bdgenomics.adam.rdd.contig.NucleotideContigFragmentRDD
import org.bdgenomics.adam.rdd.feature._
import org.bdgenomics.adam.rdd.fragment.FragmentRDD
import org.bdgenomics.adam.rdd.read.{ AlignmentRecordRDD, RepairPartitions }
import org.bdgenomics.adam.rdd.variant._
import org.bdgenomics.adam.rich.RichAlignmentRecord
import org.bdgenomics.adam.util.FileExtensions._
import org.bdgenomics.adam.util.{ ReferenceContigMap, ReferenceFile, TwoBitFile }
import org.bdgenomics.formats.avro._
import org.bdgenomics.utils.instrumentation.Metrics
import org.bdgenomics.utils.io.LocalFileByteAccess
import org.bdgenomics.utils.misc.{ HadoopUtil, Logging }
import org.seqdoop.hadoop_bam._
import org.seqdoop.hadoop_bam.util._
import scala.collection.JavaConversions._
import scala.reflect.ClassTag

/**
 * Case class that wraps a reference region for use with the Indexed VCF/BAM loaders.
 *
 * @param rr Reference region to wrap.
 */
private case class LocatableReferenceRegion(rr: ReferenceRegion) extends Locatable {

  /**
   * @return the start position in a 1-based closed coordinate system.
   */
  def getStart(): Int = rr.start.toInt + 1

  /**
   * @return the end position in a 1-based closed coordinate system.
   */
  def getEnd(): Int = rr.end.toInt

  /**
   * @return the reference contig this interval is on.
   */
  def getContig(): String = rr.referenceName
}

/**
 * This singleton provides an implicit conversion from a SparkContext to the
 * ADAMContext, as well as implicit functions for the Pipe API.
 */
object ADAMContext {

  // conversion functions for pipes
  implicit def sameTypeConversionFn[T, U <: GenomicRDD[T, U]](gRdd: U,
                                                              rdd: RDD[T]): U = {
    // hijack the transform function to discard the old RDD
    gRdd.transform(oldRdd => rdd)
  }

  implicit def readsToVCConversionFn(arRdd: AlignmentRecordRDD,
                                     rdd: RDD[VariantContext]): VariantContextRDD = {
    VariantContextRDD(rdd,
      arRdd.sequences,
      arRdd.recordGroups.toSamples)
  }

  implicit def fragmentsToReadsConversionFn(fRdd: FragmentRDD,
                                            rdd: RDD[AlignmentRecord]): AlignmentRecordRDD = {
    AlignmentRecordRDD(rdd, fRdd.sequences, fRdd.recordGroups)
  }

  // Add ADAM Spark context methods
  implicit def sparkContextToADAMContext(sc: SparkContext): ADAMContext = new ADAMContext(sc)

  // Add generic RDD methods for all types of ADAM RDDs
  implicit def rddToADAMRDD[T](rdd: RDD[T])(implicit ev1: T => IndexedRecord, ev2: Manifest[T]): ConcreteADAMRDDFunctions[T] = new ConcreteADAMRDDFunctions(rdd)

  // Add implicits for the rich adam objects
  implicit def recordToRichRecord(record: AlignmentRecord): RichAlignmentRecord = new RichAlignmentRecord(record)
}

/**
 * A filter to run on globs/directories that finds all files with a given name.
 *
 * @param name The name to search for.
 */
private class FileFilter(private val name: String) extends PathFilter {

  /**
   * @param path Path to evaluate.
   * @return Returns true if the pathName of the path matches the name passed
   *   to the constructor.
   */
  def accept(path: Path): Boolean = {
    path.getName == name
  }
}

/**
 * The ADAMContext provides functions on top of a SparkContext for loading genomic data.
 *
 * @param sc The SparkContext to wrap.
 */
class ADAMContext(@transient val sc: SparkContext) extends Serializable with Logging {

  /**
   * @param samHeader The header to extract a sequence dictionary from.
   * @return Returns the dictionary converted to an ADAM model.
   */
  private[rdd] def loadBamDictionary(samHeader: SAMFileHeader): SequenceDictionary = {
    SequenceDictionary(samHeader)
  }

  /**
   * @param samHeader The header to extract a read group dictionary from.
   * @return Returns the dictionary converted to an ADAM model.
   */
  private[rdd] def loadBamReadGroups(samHeader: SAMFileHeader): RecordGroupDictionary = {
    RecordGroupDictionary.fromSAMHeader(samHeader)
  }

  /**
   * @param pathName The path name to load VCF format metadata from.
   *   Globs/directories are supported.
   * @return Returns a tuple of metadata from the VCF header, including the
   *   sequence dictionary and a list of the samples contained in the VCF.
   */
  private[rdd] def loadVcfMetadata(pathName: String): (SequenceDictionary, Seq[Sample], Seq[VCFHeaderLine]) = {
    // get the paths to all vcfs
    val files = getFsAndFiles(new Path(pathName))

    // load yonder the metadata
    files.map(p => loadSingleVcfMetadata(p.toString)).reduce((p1, p2) => {
      (p1._1 ++ p2._1, p1._2 ++ p2._2, p1._3 ++ p2._3)
    })
  }

  /**
   * @param pathName The path name to load VCF format metadata from.
   *   Globs/directories are not supported.
   * @return Returns a tuple of metadata from the VCF header, including the
   *   sequence dictionary and a list of the samples contained in the VCF.
   *
   * @see loadVcfMetadata
   */
  private def loadSingleVcfMetadata(pathName: String): (SequenceDictionary, Seq[Sample], Seq[VCFHeaderLine]) = {
    def headerToMetadata(vcfHeader: VCFHeader): (SequenceDictionary, Seq[Sample], Seq[VCFHeaderLine]) = {
      val sd = SequenceDictionary.fromVCFHeader(vcfHeader)
      val samples = asScalaBuffer(vcfHeader.getGenotypeSamples)
        .map(s => {
          Sample.newBuilder()
            .setSampleId(s)
            .build()
        }).toSeq
      (sd, samples, headerLines(vcfHeader))
    }

    headerToMetadata(readVcfHeader(pathName))
  }

  private def readVcfHeader(pathName: String): VCFHeader = {
    VCFHeaderReader.readHeaderFrom(WrapSeekable.openPath(sc.hadoopConfiguration,
      new Path(pathName)))
  }

  private def cleanAndMixInSupportedLines(
    headerLines: Seq[VCFHeaderLine],
    stringency: ValidationStringency): Seq[VCFHeaderLine] = {

    // dedupe
    val deduped = headerLines.distinct

    def auditLine(line: VCFCompoundHeaderLine,
                  defaultLine: VCFCompoundHeaderLine,
                  replaceFn: (String, VCFCompoundHeaderLine) => VCFCompoundHeaderLine): Option[VCFCompoundHeaderLine] = {
      if (line.getType != defaultLine.getType) {
        val msg = "Field type for provided header line (%s) does not match supported line (%s)".format(
          line, defaultLine)
        if (stringency == ValidationStringency.STRICT) {
          throw new IllegalArgumentException(msg)
        } else {
          if (stringency == ValidationStringency.LENIENT) {
            log.warn(msg)
          }
          Some(replaceFn("BAD_%s".format(line.getID), line))
        }
      } else {
        None
      }
    }

    // remove our supported header lines
    deduped.flatMap(line => line match {
      case fl: VCFFormatHeaderLine => {
        val key = fl.getID
        DefaultHeaderLines.formatHeaderLines
          .find(_.getID == key)
          .fold(Some(fl).asInstanceOf[Option[VCFCompoundHeaderLine]])(defaultLine => {
            auditLine(fl, defaultLine, (newId, oldLine) => {
              new VCFFormatHeaderLine(newId,
                oldLine.getCountType,
                oldLine.getType,
                oldLine.getDescription)
            })
          })
      }
      case il: VCFInfoHeaderLine => {
        val key = il.getID
        DefaultHeaderLines.infoHeaderLines
          .find(_.getID == key)
          .fold(Some(il).asInstanceOf[Option[VCFCompoundHeaderLine]])(defaultLine => {
            auditLine(il, defaultLine, (newId, oldLine) => {
              new VCFInfoHeaderLine(newId,
                oldLine.getCountType,
                oldLine.getType,
                oldLine.getDescription)
            })
          })
      }
      case l => {
        Some(l)
      }
    }) ++ DefaultHeaderLines.allHeaderLines
  }

  private def headerLines(header: VCFHeader): Seq[VCFHeaderLine] = {
    (header.getFilterLines ++
      header.getFormatHeaderLines ++
      header.getInfoHeaderLines ++
      header.getOtherHeaderLines).toSeq
  }

  private def loadHeaderLines(pathName: String): Seq[VCFHeaderLine] = {
    getFsAndFilesWithFilter(pathName, new FileFilter("_header"))
      .map(p => headerLines(readVcfHeader(p.toString)))
      .flatten
      .distinct
  }

  /**
   * @param pathName The path name to load Avro sequence dictionaries from.
   *   Globs/directories are supported.
   * @return Returns a SequenceDictionary.
   */
  private[rdd] def loadAvroSequenceDictionary(pathName: String): SequenceDictionary = {
    getFsAndFilesWithFilter(pathName, new FileFilter("_seqdict.avro"))
      .map(p => loadSingleAvroSequenceDictionary(p.toString))
      .reduce(_ ++ _)
  }

  /**
   * @see loadAvroSequenceDictionary
   *
   * @param pathName The path name to load a single Avro sequence dictionary from.
   *   Globs/directories are not supported.
   * @return Returns a SequenceDictionary.
   */
  private def loadSingleAvroSequenceDictionary(pathName: String): SequenceDictionary = {
    val avroSd = loadAvro[Contig](pathName, Contig.SCHEMA$)
    SequenceDictionary.fromAvro(avroSd)
  }

  /**
   * @param pathName The path name to load Avro samples from.
   *   Globs/directories are supported.
   * @return Returns a Seq of Samples.
   */
  private[rdd] def loadAvroSamples(pathName: String): Seq[Sample] = {
    getFsAndFilesWithFilter(pathName, new FileFilter("_samples.avro"))
      .map(p => loadAvro[Sample](p.toString, Sample.SCHEMA$))
      .reduce(_ ++ _)
  }

  /**
   * @param pathName The path name to load Avro record group dictionaries from.
   *   Globs/directories are supported.
   * @return Returns a RecordGroupDictionary.
   */
  private[rdd] def loadAvroRecordGroupDictionary(pathName: String): RecordGroupDictionary = {
    getFsAndFilesWithFilter(pathName, new FileFilter("_rgdict.avro"))
      .map(p => loadSingleAvroRecordGroupDictionary(p.toString))
      .reduce(_ ++ _)
  }

  /**
   * @see loadAvroRecordGroupDictionary
   *
   * @param pathName The path name to load a single Avro record group dictionary from.
   *   Globs/directories are not supported.
   * @return Returns a RecordGroupDictionary.
   */
  private def loadSingleAvroRecordGroupDictionary(pathName: String): RecordGroupDictionary = {
    val avroRgd = loadAvro[RecordGroupMetadata](pathName,
      RecordGroupMetadata.SCHEMA$)

    // convert avro to record group dictionary
    new RecordGroupDictionary(avroRgd.map(RecordGroup.fromAvro))
  }

  /**
   * Load a path name in Parquet + Avro format into an RDD.
   *
   * @param pathName The path name to load Parquet + Avro formatted data from.
   *   Globs/directories are supported.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @tparam T The type of records to return.
   * @return An RDD with records of the specified type.
   */
  def loadParquet[T](
    pathName: String,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None)(implicit ev1: T => SpecificRecord, ev2: Manifest[T]): RDD[T] = {

    //make sure a type was specified
    //not using require as to make the message clearer
    if (manifest[T] == manifest[scala.Nothing])
      throw new IllegalArgumentException("Type inference failed; when loading please specify a specific type. " +
        "e.g.:\nval reads: RDD[AlignmentRecord] = ...\nbut not\nval reads = ...\nwithout a return type")

    log.info("Reading the ADAM file at %s to create RDD".format(pathName))
    val job = HadoopUtil.newJob(sc)
    ParquetInputFormat.setReadSupportClass(job, classOf[AvroReadSupport[T]])

    optPredicate.foreach { (pred) =>
      log.info("Using the specified push-down predicate")
      ParquetInputFormat.setFilterPredicate(job.getConfiguration, pred)
    }

    if (optProjection.isDefined) {
      log.info("Using the specified projection schema")
      AvroParquetInputFormat.setRequestedProjection(job, optProjection.get)
    }

    val records = sc.newAPIHadoopFile(
      pathName,
      classOf[ParquetInputFormat[T]],
      classOf[Void],
      manifest[T].runtimeClass.asInstanceOf[Class[T]],
      ContextUtil.getConfiguration(job)
    )

    val instrumented = if (Metrics.isRecording) records.instrument() else records
    val mapped = instrumented.map(p => p._2)

    if (optPredicate.isDefined) {
      // Strip the nulls that the predicate returns
      mapped.filter(p => p != null.asInstanceOf[T])
    } else {
      mapped
    }
  }

  /**
   * Elaborates out a directory/glob/plain path.
   *
   * @see getFsAndFiles
   *
   * @param path Path to elaborate.
   * @param fs The underlying file system that this path is on.
   * @return Returns an array of Paths to load.
   * @throws FileNotFoundException if the path does not match any files.
   */
  protected def getFiles(path: Path, fs: FileSystem): Array[Path] = {

    // elaborate out the path; this returns FileStatuses
    val paths = if (fs.isDirectory(path)) fs.listStatus(path) else fs.globStatus(path)

    // the path must match at least one file
    if (paths == null || paths.isEmpty) {
      throw new FileNotFoundException(
        s"Couldn't find any files matching ${path.toUri}. If you are trying to" +
          " glob a directory of Parquet files, you need to glob inside the" +
          " directory as well (e.g., \"glob.me.*.adam/*\", instead of" +
          " \"glob.me.*.adam\"."
      )
    }

    // map the paths returned to their paths
    paths.map(_.getPath)
  }

  /**
   * Elaborates out a directory/glob/plain path.
   *
   * @see getFiles
   *
   * @param path Path to elaborate.
   * @return Returns an array of Paths to load.
   * @throws FileNotFoundException if the path does not match any files.
   */
  protected def getFsAndFiles(path: Path): Array[Path] = {

    // get the underlying fs for the file
    val fs = Option(path.getFileSystem(sc.hadoopConfiguration)).getOrElse(
      throw new FileNotFoundException(
        s"Couldn't find filesystem for ${path.toUri} with Hadoop configuration ${sc.hadoopConfiguration}"
      ))

    getFiles(path, fs)
  }

  /**
   * Elaborates out a directory/glob/plain path name.
   *
   * @see getFiles
   *
   * @param pathName Path name to elaborate.
   * @param filter Filter to discard paths.
   * @return Returns an array of Paths to load.
   * @throws FileNotFoundException if the path does not match any files.
   */
  protected def getFsAndFilesWithFilter(pathName: String, filter: PathFilter): Array[Path] = {

    val path = new Path(pathName)

    // get the underlying fs for the file
    val fs = Option(path.getFileSystem(sc.hadoopConfiguration)).getOrElse(
      throw new FileNotFoundException(
        s"Couldn't find filesystem for ${path.toUri} with Hadoop configuration ${sc.hadoopConfiguration}"
      ))

    // elaborate out the path; this returns FileStatuses
    val paths = if (fs.isDirectory(path)) {
      fs.listStatus(path, filter)
    } else {
      fs.globStatus(path, filter)
    }

    // the path must match at least one file
    if (paths == null || paths.isEmpty) {
      throw new FileNotFoundException(
        s"Couldn't find any files matching ${path.toUri}"
      )
    }

    // map the paths returned to their paths
    paths.map(_.getPath)
  }

  /**
   * Checks to see if a set of BAM/CRAM/SAM files are queryname sorted.
   *
   * If we are loading fragments and the BAM/CRAM/SAM files are sorted by the
   * read names, this implies that all of the reads in a pair are consecutive in
   * the file. If this is the case, we can configure Hadoop-BAM to keep all of
   * the reads from a fragment in a single split. This allows us to eliminate
   * an expensive groupBy when loading a BAM file as fragments.
   *
   * @param pathName The path name to load BAM/CRAM/SAM formatted alignment records from.
   *   Globs/directories are supported.
   * @param stringency The validation stringency to use when validating the
   *   BAM/CRAM/SAM format header. Defaults to ValidationStringency.STRICT.
   * @return Returns true if all files described by the path name are queryname
   *   sorted.
   */
  private[rdd] def filesAreQuerynameSorted(
    pathName: String,
    stringency: ValidationStringency = ValidationStringency.STRICT): Boolean = {

    val path = new Path(pathName)
    val bamFiles = getFsAndFiles(path)
    val filteredFiles = bamFiles.filter(p => {
      val pPath = p.getName()
      isBamExt(pPath) || pPath.startsWith("part-")
    })

    filteredFiles
      .forall(fp => {
        try {
          // the sort order is saved in the file header
          sc.hadoopConfiguration.set(SAMHeaderReader.VALIDATION_STRINGENCY_PROPERTY, stringency.toString)
          val samHeader = SAMHeaderReader.readSAMHeaderFrom(fp, sc.hadoopConfiguration)

          samHeader.getSortOrder == SAMFileHeader.SortOrder.queryname
        } catch {
          case e: Throwable => {
            log.error(
              s"Loading header failed for $fp:n${e.getMessage}\n\t${e.getStackTrace.take(25).map(_.toString).mkString("\n\t")}"
            )
            false
          }
        }
      })
  }

  /**
   * Trim the default compression extension from the specified path name, if it is
   * recognized as compressed by the compression codecs in the Hadoop configuration.
   *
   * @param pathName The path name to trim.
   * @return The path name with the default compression extension trimmed.
   */
  private[rdd] def trimExtensionIfCompressed(pathName: String): String = {
    val codecFactory = new CompressionCodecFactory(sc.hadoopConfiguration)
    val path = new Path(pathName)
    val codec = codecFactory.getCodec(path)
    if (codec == null) {
      pathName
    } else {
      log.info(s"Found compression codec $codec for $pathName in Hadoop configuration.")
      val extension = codec.getDefaultExtension()
      CompressionCodecFactory.removeSuffix(pathName, extension)
    }
  }

  /**
   * Load alignment records from BAM/CRAM/SAM into an AlignmentRecordRDD.
   *
   * This reads the sequence and record group dictionaries from the BAM/CRAM/SAM file
   * header. SAMRecords are read from the file and converted to the
   * AlignmentRecord schema.
   *
   * @param pathName The path name to load BAM/CRAM/SAM formatted alignment records from.
   *   Globs/directories are supported.
   * @return Returns an AlignmentRecordRDD which wraps the RDD of alignment records,
   *   sequence dictionary representing contigs the alignment records may be aligned to,
   *   and the record group dictionary for the alignment records if one is available.
   */
  def loadBam(
    pathName: String,
    validationStringency: ValidationStringency = ValidationStringency.STRICT): AlignmentRecordRDD = LoadBam.time {

    val path = new Path(pathName)
    val bamFiles = getFsAndFiles(path)
    val filteredFiles = bamFiles.filter(p => {
      val pPath = p.getName()
      isBamExt(pPath) || pPath.startsWith("part-")
    })

    require(filteredFiles.nonEmpty,
      "Did not find any files at %s.".format(path))

    val (seqDict, readGroups) =
      filteredFiles
        .flatMap(fp => {
          try {
            // We need to separately read the header, so that we can inject the sequence dictionary
            // data into each individual Read (see the argument to samRecordConverter.convert,
            // below).
            sc.hadoopConfiguration.set(SAMHeaderReader.VALIDATION_STRINGENCY_PROPERTY, validationStringency.toString)
            val samHeader = SAMHeaderReader.readSAMHeaderFrom(fp, sc.hadoopConfiguration)
            log.info("Loaded header from " + fp)
            val sd = loadBamDictionary(samHeader)
            val rg = loadBamReadGroups(samHeader)
            Some((sd, rg))
          } catch {
            case e: Throwable => {
              log.error(
                s"Loading failed for $fp:n${e.getMessage}\n\t${e.getStackTrace.take(25).map(_.toString).mkString("\n\t")}"
              )
              None
            }
          }
        }).reduce((kv1, kv2) => {
          (kv1._1 ++ kv2._1, kv1._2 ++ kv2._2)
        })

    val job = HadoopUtil.newJob(sc)

    // this logic is counterintuitive but important.
    // hadoop-bam does not filter out .bai files, etc. as such, if we have a
    // directory of bam files where all the bams also have bais or md5s etc
    // in the same directory, hadoop-bam will barf. if the directory just
    // contains bams, hadoop-bam is a-ok! i believe that it is better (perf) to
    // just load from a single newAPIHadoopFile call instead of a union across
    // files, so we do that whenever possible
    val records = if (filteredFiles.length != bamFiles.length) {
      sc.union(filteredFiles.map(p => {
        sc.newAPIHadoopFile(p.toString, classOf[AnySAMInputFormat], classOf[LongWritable],
          classOf[SAMRecordWritable], ContextUtil.getConfiguration(job))
      }))
    } else {
      sc.newAPIHadoopFile(pathName, classOf[AnySAMInputFormat], classOf[LongWritable],
        classOf[SAMRecordWritable], ContextUtil.getConfiguration(job))
    }
    if (Metrics.isRecording) records.instrument() else records
    val samRecordConverter = new SAMRecordConverter

    AlignmentRecordRDD(records.map(p => samRecordConverter.convert(p._2.get)),
      seqDict,
      readGroups)
  }

  /**
   * Functions like loadBam, but uses BAM index files to look at fewer blocks,
   * and only returns records within a specified ReferenceRegion. BAM index file required.
   *
   * @param pathName The path name to load indexed BAM formatted alignment records from.
   *   Globs/directories are supported.
   * @param viewRegion The ReferenceRegion we are filtering on.
   * @return Returns an AlignmentRecordRDD which wraps the RDD of alignment records,
   *   sequence dictionary representing contigs the alignment records may be aligned to,
   *   and the record group dictionary for the alignment records if one is available.
   */
  def loadIndexedBam(
    pathName: String,
    viewRegion: ReferenceRegion): AlignmentRecordRDD = {
    loadIndexedBam(pathName, Iterable(viewRegion))
  }

  /**
   * Functions like loadBam, but uses BAM index files to look at fewer blocks,
   * and only returns records within the specified ReferenceRegions. BAM index file required.
   *
   * @param pathName The path name to load indexed BAM formatted alignment records from.
   *   Globs/directories are supported.
   * @param viewRegions Iterable of ReferenceRegion we are filtering on.
   * @return Returns an AlignmentRecordRDD which wraps the RDD of alignment records,
   *   sequence dictionary representing contigs the alignment records may be aligned to,
   *   and the record group dictionary for the alignment records if one is available.
   */
  def loadIndexedBam(
    pathName: String,
    viewRegions: Iterable[ReferenceRegion])(implicit s: DummyImplicit): AlignmentRecordRDD = LoadIndexedBam.time {

    val path = new Path(pathName)
    // todo: can this method handle SAM and CRAM, or just BAM?
    val bamFiles = getFsAndFiles(path).filter(p => p.toString.endsWith(".bam"))

    require(bamFiles.nonEmpty,
      "Did not find any files at %s.".format(path))
    val (seqDict, readGroups) = bamFiles
      .map(fp => {
        // We need to separately read the header, so that we can inject the sequence dictionary
        // data into each individual Read (see the argument to samRecordConverter.convert,
        // below).
        val samHeader = SAMHeaderReader.readSAMHeaderFrom(fp, sc.hadoopConfiguration)

        log.info("Loaded header from " + fp)
        val sd = loadBamDictionary(samHeader)
        val rg = loadBamReadGroups(samHeader)

        (sd, rg)
      }).reduce((kv1, kv2) => {
        (kv1._1 ++ kv2._1, kv1._2 ++ kv2._2)
      })

    val job = HadoopUtil.newJob(sc)
    val conf = ContextUtil.getConfiguration(job)
    BAMInputFormat.setIntervals(conf, viewRegions.toList.map(r => LocatableReferenceRegion(r)))

    val records = sc.union(bamFiles.map(p => {
      sc.newAPIHadoopFile(p.toString, classOf[BAMInputFormat], classOf[LongWritable],
        classOf[SAMRecordWritable], conf)
    }))
    if (Metrics.isRecording) records.instrument() else records
    val samRecordConverter = new SAMRecordConverter
    AlignmentRecordRDD(records.map(p => samRecordConverter.convert(p._2.get)),
      seqDict,
      readGroups)
  }

  /**
   * Load Avro data from a Hadoop File System.
   *
   * This method uses the SparkContext wrapped by this class to identify our
   * underlying file system. We then use the underlying FileSystem imp'l to
   * open the Avro file, and we read the Avro files into a Seq.
   *
   * Frustratingly enough, although all records generated by the Avro IDL
   * compiler have a static SCHEMA$ field, this field does not belong to
   * the SpecificRecordBase abstract class, or the SpecificRecord interface.
   * As such, we must force the user to pass in the schema.
   *
   * @tparam T The type of the specific record we are loading.
   * @param pathName The path name to load Avro records from.
   *   Globs/directories are supported.
   * @param schema Schema of records we are loading.
   * @return Returns a Seq containing the Avro records.
   */
  private def loadAvro[T <: SpecificRecordBase](
    pathName: String,
    schema: Schema)(implicit tTag: ClassTag[T]): Seq[T] = {

    // get our current file system
    val path = new Path(pathName)
    val fs = path.getFileSystem(sc.hadoopConfiguration)

    // get an input stream
    val is = fs.open(path)
      .asInstanceOf[InputStream]

    // set up avro for reading
    val dr = new SpecificDatumReader[T](schema)
    val fr = new DataFileStream[T](is, dr)

    // get iterator and create an empty list
    val iter = fr.iterator
    var list = List.empty[T]

    // !!!!!
    // important implementation note:
    // !!!!!
    //
    // in theory, we should be able to call iter.toSeq to get a Seq of the
    // specific records we are reading. this would allow us to avoid needing
    // to manually pop things into a list.
    //
    // however! this causes odd problems that seem to be related to some sort of
    // lazy execution inside of scala. specifically, if you go
    // iter.toSeq.map(fn) in scala, this seems to be compiled into a lazy data
    // structure where the map call is only executed when the Seq itself is
    // actually accessed (e.g., via seq.apply(i), seq.head, etc.). typically,
    // this would be OK, but if the Seq[T] goes into a spark closure, the closure
    // cleaner will fail with a NotSerializableException, since SpecificRecord's
    // are not java serializable. specifically, we see this happen when using
    // this function to load RecordGroupMetadata when creating a
    // RecordGroupDictionary.
    //
    // good news is, you can work around this by explicitly walking the iterator
    // and building a collection, which is what we do here. this would not be
    // efficient if we were loading a large amount of avro data (since we're
    // loading all the data into memory), but currently, we are just using this
    // code for building sequence/record group dictionaries, which are fairly
    // small (seq dict is O(30) entries, rgd is O(20n) entries, where n is the
    // number of samples).
    while (iter.hasNext) {
      list = iter.next :: list
    }

    // close file
    fr.close()
    is.close()

    // reverse list and return as seq
    list.reverse
      .toSeq
  }

  /**
   * Load a path name in Parquet + Avro format into an AlignmentRecordRDD.
   *
   * @note The sequence dictionary is read from an Avro file stored at
   *   pathName/_seqdict.avro and the record group dictionary is read from an
   *   Avro file stored at pathName/_rgdict.avro. These files are pure Avro,
   *   not Parquet + Avro.
   *
   * @param pathName The path name to load alignment records from.
   *   Globs/directories are supported.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @return Returns an AlignmentRecordRDD which wraps the RDD of alignment records,
   *   sequence dictionary representing contigs the alignment records may be aligned to,
   *   and the record group dictionary for the alignment records if one is available.
   */
  def loadParquetAlignments(
    pathName: String,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None): AlignmentRecordRDD = {

    // load from disk
    val rdd = loadParquet[AlignmentRecord](pathName, optPredicate, optProjection)

    // convert avro to sequence dictionary
    val sd = loadAvroSequenceDictionary(pathName)

    // convert avro to sequence dictionary
    val rgd = loadAvroRecordGroupDictionary(pathName)

    AlignmentRecordRDD(rdd, sd, rgd)
  }

  /**
   * Load unaligned alignment records from interleaved FASTQ into an AlignmentRecordRDD.
   *
   * In interleaved FASTQ, the two reads from a paired sequencing protocol are
   * interleaved in a single file. This is a zipped representation of the
   * typical paired FASTQ.
   *
   * @param pathName The path name to load unaligned alignment records from.
   *   Globs/directories are supported.
   * @return Returns an unaligned AlignmentRecordRDD.
   */
  def loadInterleavedFastq(
    pathName: String): AlignmentRecordRDD = LoadInterleavedFastq.time {

    val job = HadoopUtil.newJob(sc)
    val records = sc.newAPIHadoopFile(
      pathName,
      classOf[InterleavedFastqInputFormat],
      classOf[Void],
      classOf[Text],
      ContextUtil.getConfiguration(job)
    )
    if (Metrics.isRecording) records.instrument() else records

    // convert records
    val fastqRecordConverter = new FastqRecordConverter
    AlignmentRecordRDD.unaligned(records.flatMap(fastqRecordConverter.convertPair))
  }

  /**
   * Load unaligned alignment records from (possibly paired) FASTQ into an AlignmentRecordRDD.
   *
   * @see loadPairedFastq
   * @see loadUnpairedFastq
   *
   * @param pathName1 The path name to load the first set of unaligned alignment records from.
   *   Globs/directories are supported.
   * @param optPathName2 The path name to load the second set of unaligned alignment records from,
   *   if provided. Globs/directories are supported.
   * @param optRecordGroup The optional record group name to associate to the unaligned alignment
   *   records. Defaults to None.
   * @param stringency The validation stringency to use when validating (possibly paired) FASTQ format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns an unaligned AlignmentRecordRDD.
   */
  def loadFastq(
    pathName1: String,
    optPathName2: Option[String],
    optRecordGroup: Option[String] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): AlignmentRecordRDD = LoadFastq.time {

    optPathName2.fold({
      loadUnpairedFastq(pathName1,
        optRecordGroup = optRecordGroup,
        stringency = stringency)
    })(filePath2 => {
      loadPairedFastq(pathName1,
        filePath2,
        optRecordGroup = optRecordGroup,
        stringency = stringency)
    })
  }

  /**
   * Load unaligned alignment records from paired FASTQ into an AlignmentRecordRDD.
   *
   * @param pathName1 The path name to load the first set of unaligned alignment records from.
   *   Globs/directories are supported.
   * @param pathName2 The path name to load the second set of unaligned alignment records from.
   *   Globs/directories are supported.
   * @param optRecordGroup The optional record group name to associate to the unaligned alignment
   *   records. Defaults to None.
   * @param stringency The validation stringency to use when validating paired FASTQ format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns an unaligned AlignmentRecordRDD.
   */
  def loadPairedFastq(
    pathName1: String,
    pathName2: String,
    optRecordGroup: Option[String] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): AlignmentRecordRDD = LoadPairedFastq.time {

    val reads1 = loadUnpairedFastq(
      pathName1,
      setFirstOfPair = true,
      optRecordGroup = optRecordGroup,
      stringency = stringency
    )
    val reads2 = loadUnpairedFastq(
      pathName2,
      setSecondOfPair = true,
      optRecordGroup = optRecordGroup,
      stringency = stringency
    )

    stringency match {
      case ValidationStringency.STRICT | ValidationStringency.LENIENT =>
        val count1 = reads1.rdd.cache.count
        val count2 = reads2.rdd.cache.count

        if (count1 != count2) {
          val msg = s"Fastq 1 ($pathName1) has $count1 reads, fastq 2 ($pathName2) has $count2 reads"
          if (stringency == ValidationStringency.STRICT)
            throw new IllegalArgumentException(msg)
          else {
            // ValidationStringency.LENIENT
            logError(msg)
          }
        }
      case ValidationStringency.SILENT =>
    }

    AlignmentRecordRDD.unaligned(reads1.rdd ++ reads2.rdd)
  }

  /**
   * Load unaligned alignment records from unpaired FASTQ into an AlignmentRecordRDD.
   *
   * @param pathName The path name to load unaligned alignment records from.
   *   Globs/directories are supported.
   * @param setFirstOfPair If true, sets the unaligned alignment record as first from the fragment.
   *   Defaults to false.
   * @param setSecondOfPair If true, sets the unaligned alignment record as second from the fragment.
   *   Defaults to false.
   * @param optRecordGroup The optional record group name to associate to the unaligned alignment
   *   records. Defaults to None.
   * @param stringency The validation stringency to use when validating unpaired FASTQ format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns an unaligned AlignmentRecordRDD.
   */
  def loadUnpairedFastq(
    pathName: String,
    setFirstOfPair: Boolean = false,
    setSecondOfPair: Boolean = false,
    optRecordGroup: Option[String] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): AlignmentRecordRDD = LoadUnpairedFastq.time {

    val job = HadoopUtil.newJob(sc)
    val records = sc.newAPIHadoopFile(
      pathName,
      classOf[SingleFastqInputFormat],
      classOf[Void],
      classOf[Text],
      ContextUtil.getConfiguration(job)
    )
    if (Metrics.isRecording) records.instrument() else records

    // convert records
    val fastqRecordConverter = new FastqRecordConverter
    AlignmentRecordRDD.unaligned(records.map(
      fastqRecordConverter.convertRead(
        _,
        optRecordGroup.map(recordGroup =>
          if (recordGroup.isEmpty)
            pathName.substring(pathName.lastIndexOf("/") + 1)
          else
            recordGroup),
        setFirstOfPair,
        setSecondOfPair,
        stringency
      )
    ))
  }

  /**
   * @param pathName The path name to load VCF variant context records from.
   *   Globs/directories are supported.
   * @param optViewRegions Optional intervals to push down into file using index.
   * @return Returns a raw RDD of (LongWritable, VariantContextWritable)s.
   */
  private def readVcfRecords(
    pathName: String,
    optViewRegions: Option[Iterable[ReferenceRegion]]): RDD[(LongWritable, VariantContextWritable)] = {

    // load vcf data
    val job = HadoopUtil.newJob(sc)
    job.getConfiguration().setStrings("io.compression.codecs",
      classOf[BGZFCodec].getCanonicalName(),
      classOf[BGZFEnhancedGzipCodec].getCanonicalName())

    val conf = ContextUtil.getConfiguration(job)
    optViewRegions.foreach(vr => {
      val intervals = vr.toList.map(r => LocatableReferenceRegion(r))
      VCFInputFormat.setIntervals(conf, intervals)
    })

    sc.newAPIHadoopFile(
      pathName,
      classOf[VCFInputFormat], classOf[LongWritable], classOf[VariantContextWritable],
      conf
    )
  }

  /**
   * Load variant context records from VCF into a VariantContextRDD.
   *
   * @param pathName The path name to load VCF variant context records from.
   *   Globs/directories are supported.
   * @param stringency The validation stringency to use when validating VCF format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns a VariantContextRDD.
   */
  def loadVcf(
    pathName: String,
    stringency: ValidationStringency = ValidationStringency.STRICT): VariantContextRDD = LoadVcf.time {

    // load records from VCF
    val records = readVcfRecords(pathName, None)

    // attach instrumentation
    if (Metrics.isRecording) records.instrument() else records

    // load vcf metadata
    val (sd, samples, headers) = loadVcfMetadata(pathName)

    val vcc = new VariantContextConverter(headers, stringency)
    VariantContextRDD(records.flatMap(p => vcc.convert(p._2.get)),
      sd,
      samples,
      cleanAndMixInSupportedLines(headers, stringency))
  }

  /**
   * Load variant context records from VCF indexed by tabix (tbi) into a VariantContextRDD.
   *
   * @param pathName The path name to load VCF variant context records from.
   *   Globs/directories are supported.
   * @param viewRegion ReferenceRegion we are filtering on.
   * @return Returns a VariantContextRDD.
   */
  // todo: add stringency with default if possible
  def loadIndexedVcf(
    pathName: String,
    viewRegion: ReferenceRegion): VariantContextRDD = {
    loadIndexedVcf(pathName, Iterable(viewRegion))
  }

  /**
   * Load variant context records from VCF indexed by tabix (tbi) into a VariantContextRDD.
   *
   * @param pathName The path name to load VCF variant context records from.
   *   Globs/directories are supported.
   * @param viewRegions Iterator of ReferenceRegions we are filtering on.
   * @param stringency The validation stringency to use when validating VCF format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns a VariantContextRDD.
   */
  def loadIndexedVcf(
    pathName: String,
    viewRegions: Iterable[ReferenceRegion],
    stringency: ValidationStringency = ValidationStringency.STRICT)(implicit s: DummyImplicit): VariantContextRDD = LoadIndexedVcf.time {

    // load records from VCF
    val records = readVcfRecords(pathName, Some(viewRegions))

    // attach instrumentation
    if (Metrics.isRecording) records.instrument() else records

    // load vcf metadata
    val (sd, samples, headers) = loadVcfMetadata(pathName)

    val vcc = new VariantContextConverter(headers, stringency)
    VariantContextRDD(records.flatMap(p => vcc.convert(p._2.get)),
      sd,
      samples,
      cleanAndMixInSupportedLines(headers, stringency))
  }

  /**
   * Load a path name in Parquet + Avro format into a GenotypeRDD.
   *
   * @param pathName The path name to load genotypes from.
   *   Globs/directories are supported.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @return Returns a GenotypeRDD.
   */
  def loadParquetGenotypes(
    pathName: String,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None): GenotypeRDD = {

    val rdd = loadParquet[Genotype](pathName, optPredicate, optProjection)

    // load header lines
    val headers = loadHeaderLines(pathName)

    // load sequence info
    val sd = loadAvroSequenceDictionary(pathName)

    // load avro record group dictionary and convert to samples
    val samples = loadAvroSamples(pathName)

    GenotypeRDD(rdd, sd, samples, headers)
  }

  /**
   * Load a path name in Parquet + Avro format into a VariantRDD.
   *
   * @param pathName The path name to load variants from.
   *   Globs/directories are supported.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @return Returns a VariantRDD.
   */
  def loadParquetVariants(
    pathName: String,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None): VariantRDD = {

    val rdd = loadParquet[Variant](pathName, optPredicate, optProjection)
    val sd = loadAvroSequenceDictionary(pathName)

    // load header lines
    val headers = loadHeaderLines(pathName)

    VariantRDD(rdd, sd, headers)
  }

  /**
   * Load nucleotide contig fragments from FASTA into a NucleotideContigFragmentRDD.
   *
   * @param pathName The path name to load nucleotide contig fragments from.
   *   Globs/directories are supported.
   * @param maximumFragmentLength Maximum fragment length. Defaults to 10000L. Values greater
   *   than 1e9 should be avoided.
   * @return Returns a NucleotideContigFragmentRDD.
   */
  def loadFasta(
    pathName: String,
    maximumFragmentLength: Long = 10000L): NucleotideContigFragmentRDD = LoadFasta.time {

    val fastaData: RDD[(LongWritable, Text)] = sc.newAPIHadoopFile(
      pathName,
      classOf[TextInputFormat],
      classOf[LongWritable],
      classOf[Text]
    )
    if (Metrics.isRecording) fastaData.instrument() else fastaData

    val remapData = fastaData.map(kv => (kv._1.get, kv._2.toString))

    // convert rdd and cache
    val fragmentRdd = FastaConverter(remapData, maximumFragmentLength)
      .cache()

    NucleotideContigFragmentRDD(fragmentRdd)
  }

  /**
   * Load paired unaligned alignment records grouped by sequencing fragment
   * from interleaved FASTQ into an FragmentRDD.
   *
   * In interleaved FASTQ, the two reads from a paired sequencing protocol are
   * interleaved in a single file. This is a zipped representation of the
   * typical paired FASTQ.
   *
   * Fragments represent all of the reads from a single sequenced fragment as
   * a single object, which is a useful representation for some tasks.
   *
   * @param pathName The path name to load unaligned alignment records from.
   *   Globs/directories are supported.
   * @return Returns a FragmentRDD containing the paired reads grouped by
   *   sequencing fragment.
   */
  def loadInterleavedFastqAsFragments(
    pathName: String): FragmentRDD = LoadInterleavedFastqFragments.time {

    val job = HadoopUtil.newJob(sc)
    val records = sc.newAPIHadoopFile(
      pathName,
      classOf[InterleavedFastqInputFormat],
      classOf[Void],
      classOf[Text],
      ContextUtil.getConfiguration(job)
    )
    if (Metrics.isRecording) records.instrument() else records

    // convert records
    val fastqRecordConverter = new FastqRecordConverter
    FragmentRDD.fromRdd(records.map(fastqRecordConverter.convertFragment))
  }

  /**
   * Load features into a FeatureRDD and convert to a CoverageRDD.
   * Coverage is stored in the score field of Feature.
   *
   * Loads path names ending in:
   * * .bed as BED6/12 format,
   * * .gff3 as GFF3 format,
   * * .gtf/.gff as GTF/GFF2 format,
   * * .narrow[pP]eak as NarrowPeak format, and
   * * .interval_list as IntervalList format.
   *
   * If none of these match, fall back to Parquet + Avro.
   *
   * For BED6/12, GFF3, GTF/GFF2, NarrowPeak, and IntervalList formats, compressed files
   * are supported through compression codecs configured in Hadoop, which by default include
   * .gz and .bz2, but can include more.
   *
   * @see loadBed
   * @see loadGtf
   * @see loadGff3
   * @see loadNarrowPeak
   * @see loadIntervalList
   * @see loadParquetFeatures
   *
   * @param pathName The path name to load features from.
   *   Globs/directories are supported, although file extension must be present
   *   for BED6/12, GFF3, GTF/GFF2, NarrowPeak, or IntervalList formats.
   * @param optStorageLevel Optional storage level to use for cache before building the SequenceDictionary.
   *   Defaults to StorageLevel.MEMORY_ONLY.
   * @param optMinPartitions An optional minimum number of partitions to use. For
   *   textual formats, if this is None, fall back to the Spark default
   *   parallelism. Defaults to None.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param stringency The validation stringency to use when validating BED6/12, GFF3,
   *   GTF/GFF2, NarrowPeak, or IntervalList formats. Defaults to ValidationStringency.STRICT.
   * @return Returns a FeatureRDD converted to a CoverageRDD.
   */
  def loadCoverage(
    pathName: String,
    optStorageLevel: Option[StorageLevel] = Some(StorageLevel.MEMORY_ONLY),
    optMinPartitions: Option[Int] = None,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): CoverageRDD = LoadCoverage.time {

    loadFeatures(pathName,
      optStorageLevel = optStorageLevel,
      optMinPartitions = optMinPartitions,
      optPredicate = optPredicate,
      optProjection = optProjection,
      stringency = stringency).toCoverage
  }

  /**
   * Load a path name in Parquet + Avro format into a FeatureRDD and convert to a CoverageRDD.
   * Coverage is stored in the score field of Feature.
   *
   * @param pathName The path name to load features from.
   *   Globs/directories are supported.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @return Returns a FeatureRDD converted to a CoverageRDD.
   */
  def loadParquetCoverage(
    pathName: String,
    optPredicate: Option[FilterPredicate] = None): CoverageRDD = {

    val coverageFields = Projection(FeatureField.contigName, FeatureField.start, FeatureField.end, FeatureField.score)
    loadParquetFeatures(pathName, optPredicate = optPredicate, optProjection = Some(coverageFields)).toCoverage
  }

  /**
   * Load a path name in GFF3 format into a FeatureRDD.
   *
   * @param pathName The path name to load features in GFF3 format from.
   *   Globs/directories are supported.
   * @param optStorageLevel Optional storage level to use for cache before building the SequenceDictionary.
   *   Defaults to StorageLevel.MEMORY_ONLY.
   * @param optMinPartitions An optional minimum number of partitions to load. If
   *   not set, falls back to the configured Spark default parallelism. Defaults to None.
   * @param stringency The validation stringency to use when validating GFF3 format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns a FeatureRDD.
   */
  def loadGff3(
    pathName: String,
    optStorageLevel: Option[StorageLevel] = Some(StorageLevel.MEMORY_ONLY),
    optMinPartitions: Option[Int] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): FeatureRDD = LoadGff3.time {

    val records = sc.textFile(pathName, optMinPartitions.getOrElse(sc.defaultParallelism))
      .flatMap(new GFF3Parser().parse(_, stringency))
    if (Metrics.isRecording) records.instrument() else records
    FeatureRDD.inferSequenceDictionary(records, optStorageLevel = optStorageLevel)
  }

  /**
   * Load a path name in GTF/GFF2 format into a FeatureRDD.
   *
   * @param pathName The path name to load features in GTF/GFF2 format from.
   *   Globs/directories are supported.
   * @param optStorageLevel Optional storage level to use for cache before building the SequenceDictionary.
   *   Defaults to StorageLevel.MEMORY_ONLY.
   * @param optMinPartitions An optional minimum number of partitions to load. If
   *   not set, falls back to the configured Spark default parallelism. Defaults to None.
   * @param stringency The validation stringency to use when validating GTF/GFF2 format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns a FeatureRDD.
   */
  def loadGtf(
    pathName: String,
    optStorageLevel: Option[StorageLevel] = Some(StorageLevel.MEMORY_ONLY),
    optMinPartitions: Option[Int] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): FeatureRDD = LoadGtf.time {

    val records = sc.textFile(pathName, optMinPartitions.getOrElse(sc.defaultParallelism))
      .flatMap(new GTFParser().parse(_, stringency))
    if (Metrics.isRecording) records.instrument() else records
    FeatureRDD.inferSequenceDictionary(records, optStorageLevel = optStorageLevel)
  }

  /**
   * Load a path name in BED6/12 format into a FeatureRDD.
   *
   * @param pathName The path name to load features in BED6/12 format from.
   *   Globs/directories are supported.
   * @param optStorageLevel Optional storage level to use for cache before building the SequenceDictionary.
   *   Defaults to StorageLevel.MEMORY_ONLY.
   * @param optMinPartitions An optional minimum number of partitions to load. If
   *   not set, falls back to the configured Spark default parallelism. Defaults to None.
   * @param stringency The validation stringency to use when validating BED6/12 format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns a FeatureRDD.
   */
  def loadBed(
    pathName: String,
    optStorageLevel: Option[StorageLevel] = Some(StorageLevel.MEMORY_ONLY),
    optMinPartitions: Option[Int] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): FeatureRDD = LoadBed.time {

    val records = sc.textFile(pathName, optMinPartitions.getOrElse(sc.defaultParallelism))
      .flatMap(new BEDParser().parse(_, stringency))
    if (Metrics.isRecording) records.instrument() else records
    FeatureRDD.inferSequenceDictionary(records, optStorageLevel = optStorageLevel)
  }

  /**
   * Load a path name in NarrowPeak format into a FeatureRDD.
   *
   * @param pathName The path name to load features in NarrowPeak format from.
   *   Globs/directories are supported.
   * @param optStorageLevel Optional storage level to use for cache before building the SequenceDictionary.
   *   Defaults to StorageLevel.MEMORY_ONLY.
   * @param optMinPartitions An optional minimum number of partitions to load. If
   *   not set, falls back to the configured Spark default parallelism. Defaults to None.
   * @param stringency The validation stringency to use when validating NarrowPeak format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns a FeatureRDD.
   */
  def loadNarrowPeak(
    pathName: String,
    optStorageLevel: Option[StorageLevel] = Some(StorageLevel.MEMORY_ONLY),
    optMinPartitions: Option[Int] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): FeatureRDD = LoadNarrowPeak.time {

    val records = sc.textFile(pathName, optMinPartitions.getOrElse(sc.defaultParallelism))
      .flatMap(new NarrowPeakParser().parse(_, stringency))
    if (Metrics.isRecording) records.instrument() else records
    FeatureRDD.inferSequenceDictionary(records, optStorageLevel = optStorageLevel)
  }

  /**
   * Load a path name in IntervalList format into a FeatureRDD.
   *
   * @param pathName The path name to load features in IntervalList format from.
   *   Globs/directories are supported.
   * @param optMinPartitions An optional minimum number of partitions to load. If
   *   not set, falls back to the configured Spark default parallelism. Defaults to None.
   * @param stringency The validation stringency to use when validating IntervalList format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns a FeatureRDD.
   */
  def loadIntervalList(
    pathName: String,
    optMinPartitions: Option[Int] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): FeatureRDD = LoadIntervalList.time {

    val parsedLines = sc.textFile(pathName, optMinPartitions.getOrElse(sc.defaultParallelism))
      .map(new IntervalListParser().parseWithHeader(_, stringency))
    val (seqDict, records) = (SequenceDictionary(parsedLines.flatMap(_._1).collect(): _*),
      parsedLines.flatMap(_._2))

    if (Metrics.isRecording) records.instrument() else records
    FeatureRDD(records, seqDict)
  }

  /**
   * Load a path name in Parquet + Avro format into a FeatureRDD.
   *
   * @param pathName The path name to load features from.
   *   Globs/directories are supported.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @return Returns a FeatureRDD.
   */
  def loadParquetFeatures(
    pathName: String,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None): FeatureRDD = {

    val sd = loadAvroSequenceDictionary(pathName)
    val rdd = loadParquet[Feature](pathName, optPredicate, optProjection)
    FeatureRDD(rdd, sd)
  }

  /**
   * Load a path name in Parquet + Avro format into a NucleotideContigFragmentRDD.
   *
   * @param pathName The path name to load nucleotide contig fragments from.
   *   Globs/directories are supported.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @return Returns a NucleotideContigFragmentRDD.
   */
  def loadParquetContigFragments(
    pathName: String,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None): NucleotideContigFragmentRDD = {

    val sd = loadAvroSequenceDictionary(pathName)
    val rdd = loadParquet[NucleotideContigFragment](pathName, optPredicate, optProjection)
    NucleotideContigFragmentRDD(rdd, sd)
  }

  /**
   * Load a path name in Parquet + Avro format into a FragmentRDD.
   *
   * @param pathName The path name to load fragments from.
   *   Globs/directories are supported.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @return Returns a FragmentRDD.
   */
  def loadParquetFragments(
    pathName: String,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None): FragmentRDD = {

    // convert avro to sequence dictionary
    val sd = loadAvroSequenceDictionary(pathName)

    // convert avro to sequence dictionary
    val rgd = loadAvroRecordGroupDictionary(pathName)

    // load fragment data from parquet
    val rdd = loadParquet[Fragment](pathName, optPredicate, optProjection)

    FragmentRDD(rdd, sd, rgd)
  }

  /**
   * Load features into a FeatureRDD.
   *
   * Loads path names ending in:
   * * .bed as BED6/12 format,
   * * .gff3 as GFF3 format,
   * * .gtf/.gff as GTF/GFF2 format,
   * * .narrow[pP]eak as NarrowPeak format, and
   * * .interval_list as IntervalList format.
   *
   * If none of these match, fall back to Parquet + Avro.
   *
   * For BED6/12, GFF3, GTF/GFF2, NarrowPeak, and IntervalList formats, compressed files
   * are supported through compression codecs configured in Hadoop, which by default include
   * .gz and .bz2, but can include more.
   *
   * @see loadBed
   * @see loadGtf
   * @see loadGff3
   * @see loadNarrowPeak
   * @see loadIntervalList
   * @see loadParquetFeatures
   *
   * @param pathName The path name to load features from.
   *   Globs/directories are supported, although file extension must be present
   *   for BED6/12, GFF3, GTF/GFF2, NarrowPeak, or IntervalList formats.
   * @param optStorageLevel Optional storage level to use for cache before building the SequenceDictionary.
   *   Defaults to StorageLevel.MEMORY_ONLY.
   * @param optMinPartitions An optional minimum number of partitions to use. For
   *   textual formats, if this is None, fall back to the Spark default
   *   parallelism. Defaults to None.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param stringency The validation stringency to use when validating BED6/12, GFF3,
   *   GTF/GFF2, NarrowPeak, or IntervalList formats. Defaults to ValidationStringency.STRICT.
   * @return Returns a FeatureRDD.
   */
  def loadFeatures(
    pathName: String,
    optStorageLevel: Option[StorageLevel] = Some(StorageLevel.MEMORY_ONLY),
    optMinPartitions: Option[Int] = None,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): FeatureRDD = LoadFeatures.time {

    val trimmedPathName = trimExtensionIfCompressed(pathName)
    if (isBedExt(trimmedPathName)) {
      log.info(s"Loading $pathName as BED and converting to Features.")
      loadBed(pathName,
        optStorageLevel = optStorageLevel,
        optMinPartitions = optMinPartitions,
        stringency = stringency)
    } else if (isGff3Ext(trimmedPathName)) {
      log.info(s"Loading $pathName as GFF3 and converting to Features.")
      loadGff3(pathName,
        optStorageLevel = optStorageLevel,
        optMinPartitions = optMinPartitions,
        stringency = stringency)
    } else if (isGtfExt(trimmedPathName)) {
      log.info(s"Loading $pathName as GTF/GFF2 and converting to Features.")
      loadGtf(pathName,
        optStorageLevel = optStorageLevel,
        optMinPartitions = optMinPartitions,
        stringency = stringency)
    } else if (isNarrowPeakExt(trimmedPathName)) {
      log.info(s"Loading $pathName as NarrowPeak and converting to Features.")
      loadNarrowPeak(pathName,
        optStorageLevel = optStorageLevel,
        optMinPartitions = optMinPartitions,
        stringency = stringency)
    } else if (isIntervalListExt(trimmedPathName)) {
      log.info(s"Loading $pathName as IntervalList and converting to Features.")
      loadIntervalList(pathName,
        optMinPartitions = optMinPartitions,
        stringency = stringency)
    } else {
      log.info(s"Loading $pathName as Parquet containing Features.")
      loadParquetFeatures(pathName,
        optPredicate = optPredicate,
        optProjection = optProjection)
    }
  }

  /**
   * Load reference sequences into a broadcastable ReferenceFile.
   *
   * If the path name has a .2bit extension, loads a 2bit file. Else, uses loadContigFragments
   * to load the reference as an RDD, which is then collected to the driver.
   *
   * @see loadContigFragments
   *
   * @param pathName The path name to load reference sequences from.
   *   Globs/directories for 2bit format are not supported.
   * @param maximumFragmentLength Maximum fragment length. Defaults to 10000L. Values greater
   *   than 1e9 should be avoided.
   * @return Returns a broadcastable ReferenceFile.
   */
  def loadReferenceFile(
    pathName: String,
    maximumFragmentLength: Long): ReferenceFile = LoadReferenceFile.time {

    if (is2BitExt(pathName)) {
      new TwoBitFile(new LocalFileByteAccess(new File(pathName)))
    } else {
      ReferenceContigMap(loadContigFragments(pathName, maximumFragmentLength = maximumFragmentLength).rdd)
    }
  }

  /**
   * Load nucleotide contig fragments into a NucleotideContigFragmentRDD.
   *
   * If the path name has a .fa/.fasta extension, load as FASTA format.
   * Else, fall back to Parquet + Avro.
   *
   * For FASTA format, compressed files are supported through compression codecs configured
   * in Hadoop, which by default include .gz and .bz2, but can include more.
   *
   * @see loadFasta
   * @see loadParquetContigFragments
   *
   * @param pathName The path name to load nucleotide contig fragments from.
   *   Globs/directories are supported, although file extension must be present
   *   for FASTA format.
   * @param maximumFragmentLength Maximum fragment length. Defaults to 10000L. Values greater
   *   than 1e9 should be avoided.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @return Returns a NucleotideContigFragmentRDD.
   */
  def loadContigFragments(
    pathName: String,
    maximumFragmentLength: Long = 10000L,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None): NucleotideContigFragmentRDD = LoadContigFragments.time {

    val trimmedPathName = trimExtensionIfCompressed(pathName)
    if (isFastaExt(trimmedPathName)) {
      log.info(s"Loading $pathName as FASTA and converting to NucleotideContigFragment.")
      loadFasta(
        pathName,
        maximumFragmentLength
      )
    } else {
      log.info(s"Loading $pathName as Parquet containing NucleotideContigFragments.")
      loadParquetContigFragments(pathName, optPredicate = optPredicate, optProjection = optProjection)
    }
  }

  /**
   * Load genotypes into a GenotypeRDD.
   *
   * If the path name has a .vcf/.vcf.gz/.vcf.bgzf/.vcf.bgz extension, load as VCF format.
   * Else, fall back to Parquet + Avro.
   *
   * @see loadVcf
   * @see loadParquetGenotypes
   *
   * @param pathName The path name to load genotypes from.
   *   Globs/directories are supported, although file extension must be present
   *   for VCF format.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param stringency The validation stringency to use when validating VCF format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns a GenotypeRDD.
   */
  def loadGenotypes(
    pathName: String,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): GenotypeRDD = LoadGenotypes.time {

    if (isVcfExt(pathName)) {
      log.info(s"Loading $pathName as VCF and converting to Genotypes.")
      loadVcf(pathName, stringency).toGenotypeRDD
    } else {
      log.info(s"Loading $pathName as Parquet containing Genotypes. Sequence dictionary for translation is ignored.")
      loadParquetGenotypes(pathName, optPredicate = optPredicate, optProjection = optProjection)
    }
  }

  /**
   * Load variants into a VariantRDD.
   *
   * If the path name has a .vcf/.vcf.gz/.vcf.bgzf/.vcf.bgz extension, load as VCF format.
   * Else, fall back to Parquet + Avro.
   *
   * @see loadVcf
   * @see loadParquetVariants
   *
   * @param pathName The path name to load variants from.
   *   Globs/directories are supported, although file extension must be present for VCF format.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param stringency The validation stringency to use when validating VCF format.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns a VariantRDD.
   */
  def loadVariants(
    pathName: String,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): VariantRDD = LoadVariants.time {

    if (isVcfExt(pathName)) {
      log.info(s"Loading $pathName as VCF and converting to Variants.")
      loadVcf(pathName, stringency).toVariantRDD
    } else {
      log.info(s"Loading $pathName as Parquet containing Variants. Sequence dictionary for translation is ignored.")
      loadParquetVariants(pathName, optPredicate = optPredicate, optProjection = optProjection)
    }
  }

  /**
   * Load alignment records into an AlignmentRecordRDD.
   *
   * Loads path names ending in:
   * * .bam/.cram/.sam as BAM/CRAM/SAM format,
   * * .fa/.fasta as FASTA format,
   * * .fq/.fastq as FASTQ format, and
   * * .ifq as interleaved FASTQ format.
   *
   * If none of these match, fall back to Parquet + Avro.
   *
   * For FASTA, FASTQ, and interleaved FASTQ formats, compressed files are supported
   * through compression codecs configured in Hadoop, which by default include .gz and .bz2,
   * but can include more.
   *
   * @see loadBam
   * @see loadFastq
   * @see loadFasta
   * @see loadInterleavedFastq
   * @see loadParquetAlignments
   *
   * @param pathName The path name to load alignment records from.
   *   Globs/directories are supported, although file extension must be present
   *   for BAM/CRAM/SAM, FASTA, and FASTQ formats.
   * @param optPathName2 The optional path name to load the second set of alignment
   *   records from, if loading paired FASTQ format. Globs/directories are supported,
   *   although file extension must be present. Defaults to None.
   * @param optRecordGroup The optional record group name to associate to the alignment
   *   records. Defaults to None.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param stringency The validation stringency to use when validating BAM/CRAM/SAM or FASTQ formats.
   *   Defaults to ValidationStringency.STRICT.
   * @return Returns an AlignmentRecordRDD which wraps the RDD of alignment records,
   *   sequence dictionary representing contigs the alignment records may be aligned to,
   *   and the record group dictionary for the alignment records if one is available.
   */
  def loadAlignments(
    pathName: String,
    optPathName2: Option[String] = None,
    optRecordGroup: Option[String] = None,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None,
    stringency: ValidationStringency = ValidationStringency.STRICT): AlignmentRecordRDD = LoadAlignments.time {

    val trimmedPathName = trimExtensionIfCompressed(pathName)
    if (isBamExt(trimmedPathName)) {
      log.info(s"Loading $pathName as BAM/CRAM/SAM and converting to AlignmentRecords.")
      loadBam(pathName, stringency)
    } else if (isInterleavedFastqExt(trimmedPathName)) {
      log.info(s"Loading $pathName as interleaved FASTQ and converting to AlignmentRecords.")
      loadInterleavedFastq(pathName)
    } else if (isFastqExt(trimmedPathName)) {
      log.info(s"Loading $pathName as unpaired FASTQ and converting to AlignmentRecords.")
      loadFastq(pathName, optPathName2, optRecordGroup, stringency)
    } else if (isFastaExt(trimmedPathName)) {
      log.info(s"Loading $pathName as FASTA and converting to AlignmentRecords.")
      AlignmentRecordRDD.unaligned(loadFasta(pathName, maximumFragmentLength = 10000L).toReads)
    } else {
      log.info(s"Loading $pathName as Parquet of AlignmentRecords.")
      loadParquetAlignments(pathName, optPredicate = optPredicate, optProjection = optProjection)
    }
  }

  /**
   * Load fragments into a FragmentRDD.
   *
   * Loads path names ending in:
   * * .bam/.cram/.sam as BAM/CRAM/SAM format and
   * * .ifq as interleaved FASTQ format.
   *
   * If none of these match, fall back to Parquet + Avro.
   *
   * For interleaved FASTQ format, compressed files are supported through compression codecs
   * configured in Hadoop, which by default include .gz and .bz2, but can include more.
   *
   * @see loadBam
   * @see loadAlignments
   * @see loadInterleavedFastqAsFragments
   * @see loadParquetFragments
   *
   * @param pathName The path name to load fragments from.
   *   Globs/directories are supported, although file extension must be present
   *   for BAM/CRAM/SAM and FASTQ formats.
   * @param optPredicate An optional pushdown predicate to use when reading Parquet + Avro.
   *   Defaults to None.
   * @param optProjection An option projection schema to use when reading Parquet + Avro.
   *   Defaults to None.
   * @return Returns a FragmentRDD.
   */
  def loadFragments(
    pathName: String,
    optPredicate: Option[FilterPredicate] = None,
    optProjection: Option[Schema] = None): FragmentRDD = LoadFragments.time {

    val trimmedPathName = trimExtensionIfCompressed(pathName)
    if (isBamExt(trimmedPathName)) {
      // check to see if the input files are all queryname sorted
      if (filesAreQuerynameSorted(pathName)) {
        log.info(s"Loading $pathName as queryname sorted BAM/CRAM/SAM and converting to Fragments.")
        loadBam(pathName).transform(RepairPartitions(_))
          .querynameSortedToFragments
      } else {
        log.info(s"Loading $pathName as BAM/CRAM/SAM and converting to Fragments.")
        loadBam(pathName).toFragments
      }
    } else if (isInterleavedFastqExt(trimmedPathName)) {
      log.info(s"Loading $pathName as interleaved FASTQ and converting to Fragments.")
      loadInterleavedFastqAsFragments(pathName)
    } else {
      log.info(s"Loading $pathName as Parquet containing Fragments.")
      loadParquetFragments(pathName, optPredicate = optPredicate, optProjection = optProjection)
    }
  }
}

package shark.execution

import java.util.{ArrayList, Arrays}

import scala.reflect.BeanProperty

import org.apache.hadoop.mapred.InputFormat
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.metastore.api.Constants.META_TABLE_PARTITION_COLUMNS
import org.apache.hadoop.hive.ql.exec.{TableScanOperator => HiveTableScanOperator}
import org.apache.hadoop.hive.ql.exec.Utilities
import org.apache.hadoop.hive.ql.metadata.Partition
import org.apache.hadoop.hive.ql.metadata.Table
import org.apache.hadoop.hive.ql.plan.{PartitionDesc, TableDesc}
import org.apache.hadoop.hive.serde2.objectinspector.{ObjectInspector, ObjectInspectorFactory,
  StructObjectInspector}
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory
import org.apache.hadoop.io.Writable

import org.apache.hadoop.hive.ql.exec.MapSplitPruning
import shark.{SharkConfVars, SharkEnv}
import shark.execution.serialization.XmlSerializer
import shark.memstore.{CacheKey, TableStats, TableStorage}

import spark.RDD
import spark.EnhancedRDD._
import spark.rdd.UnionRDD


class TableScanOperator extends TopOperator[HiveTableScanOperator] with HiveTopOperator {

  @transient var table: Table = _

  @transient var parts: Array[Object] = _
  @BeanProperty var firstConfPartDesc: PartitionDesc  = _
  @BeanProperty var tableDesc: TableDesc = _
  @BeanProperty var localHconf: HiveConf = _

  /**
   * Initialize the hive TableScanOperator. This initialization propagates
   * downstream. When all Hive TableScanOperators are initialized, the entire
   * Hive query plan operators are initialized.
   */
  override def initializeHiveTopOperator() {

    val rowObjectInspector = {
      if (parts == null ) {
        val serializer = tableDesc.getDeserializerClass().newInstance()
        serializer.initialize(hconf, tableDesc.getProperties)
        serializer.getObjectInspector()
      } else {
        val partProps = firstConfPartDesc.getProperties()
        val tableDeser = firstConfPartDesc.getDeserializerClass().newInstance()
        tableDeser.initialize(hconf, partProps)
        val partCols = partProps.getProperty(META_TABLE_PARTITION_COLUMNS)
        val partNames = new ArrayList[String]
        val partObjectInspectors = new ArrayList[ObjectInspector]
        partCols.trim().split("/").foreach{ key =>
          partNames.add(key)
          partObjectInspectors.add(PrimitiveObjectInspectorFactory.javaStringObjectInspector)
        }

        // No need to lock this one (see SharkEnv.objectInspectorLock) because
        // this is called on the master only.
        val partObjectInspector = ObjectInspectorFactory.getStandardStructObjectInspector(
            partNames, partObjectInspectors)
        val oiList = Arrays.asList(
            tableDeser.getObjectInspector().asInstanceOf[StructObjectInspector],
            partObjectInspector.asInstanceOf[StructObjectInspector])
        // new oi is union of table + partition object inspectors
        ObjectInspectorFactory.getUnionStructObjectInspector(oiList)
      }
    }

    setInputObjectInspector(0, rowObjectInspector)
    super.initializeHiveTopOperator()
  }

  override def initializeOnMaster() {
    localHconf = super.hconf
  }

  override def execute(): RDD[_] = {
    assert(parentOperators.size == 0)
    val tableKey = new CacheKey(tableDesc.getTableName.split('.')(1))

    SharkEnv.cache.get(tableKey) match {
      // The RDD already exists in cache manager. Try to load it from memory.
      // In this case, skip the normal execution chain, i.e. skip
      // preprocessRdd, processPartition, postprocessRdd, etc.
      case Some(rdd) => loadRddFromCache(tableKey, rdd)
      // The RDD is new, i.e. reading the table from disk.
      case None => super.execute()
    }
  }

  def loadRddFromCache(tableKey: CacheKey, rdd: RDD[_]): RDD[_] = {
    logInfo("Loading table from cache " + tableKey)

    // Stats used for map pruning.
    val splitToStats: collection.Map[Int, TableStats] = SharkEnv.cache.keyToStats(tableKey)

    // Run map pruning if the flag is set, there exists a filter predicate on
    // the input table and we have statistics on the table.
    val prunedRdd: RDD[_] =
      if (SharkConfVars.getBoolVar(localHconf, SharkConfVars.MAP_PRUNING) &&
          childOperators(0).isInstanceOf[FilterOperator] && splitToStats.size > 0) {

        val startTime = System.currentTimeMillis
        val printPruneDebug = SharkConfVars.getBoolVar(
          localHconf, SharkConfVars.MAP_PRUNING_PRINT_DEBUG)

        // Must initialize the condition evaluator in FilterOperator to get the
        // udfs and object inspectors set.
        val filterOp = childOperators(0).asInstanceOf[FilterOperator]
        filterOp.initializeOnSlave()

        // Do the pruning.
        val prunedRdd = rdd.pruneSplits { split =>
          if (printPruneDebug) {
            logInfo("\nSplit " + split + "\n" + splitToStats(split))
          }
          // Only test for pruning if we have stats on the column.
          val splitStats = splitToStats(split)
          if (splitStats != null && splitStats.stats != null)
            MapSplitPruning.test(splitStats, filterOp.conditionEvaluator)
          else true
        }
        val timeTaken = System.currentTimeMillis - startTime
        logInfo("Map pruning %d splits into %s splits took %d ms".format(
            rdd.splits.size, prunedRdd.splits.size, timeTaken))
        prunedRdd
      } else {
        rdd
      }

    prunedRdd.mapPartitions { iter =>
      if (iter.hasNext) {
        val tableStorage = iter.next.asInstanceOf[TableStorage]
        tableStorage.iterator
      } else {
        Iterator()
      }
    }
  }

  /**
   * Create a RDD representing the table (with or without partitions).
   */
  override def preprocessRdd(rdd: RDD[_]): RDD[_] = {
    if (table.isPartitioned) {
      logInfo("Making %d Hive partitions".format(parts.size))
      makePartitionRDD(rdd)
    } else {
      val tablePath = table.getPath.toString
      val ifc = table.getInputFormatClass
          .asInstanceOf[java.lang.Class[InputFormat[Writable, Writable]]]
      logInfo("Table input: %s".format(tablePath))
      SharkEnv.sc.hadoopFile(
        tablePath, ifc, classOf[Writable], classOf[Writable]).map(_._2)
    }
  }

  override def processPartition(split: Int, iter: Iterator[_]): Iterator[_] = {
    val deserializer = tableDesc.getDeserializerClass().newInstance()
    deserializer.initialize(localHconf, tableDesc.getProperties)
    iter.map { value =>
      value match {
        case rowWithPart: Array[Object] => rowWithPart
        case v: Writable => deserializer.deserialize(v)
      }
    }
  }

  def makePartitionRDD[T](rdd: RDD[T]): RDD[_] = {
    val partitions = parts
    val rdds = new Array[RDD[Any]](partitions.size)

    var i = 0
    partitions.foreach { part =>
      val partition = part.asInstanceOf[Partition]
      val partDesc = Utilities.getPartitionDesc(partition)
      val tablePath = partition.getPartitionPath.toString

      val ifc = partition.getInputFormatClass
        .asInstanceOf[java.lang.Class[InputFormat[Writable, Writable]]]

      val parts = SharkEnv.sc.hadoopFile(
        tablePath, ifc, classOf[Writable], classOf[Writable]).map(_._2)

      val serializedHconf = XmlSerializer.serialize(localHconf, localHconf)
      val partRDD = parts.mapPartitions { iter =>
        // Map each tuple to a row object
        val hconf = XmlSerializer.deserialize(serializedHconf).asInstanceOf[HiveConf]
        val deserializer = partDesc.getDeserializerClass().newInstance()
        deserializer.initialize(hconf, partDesc.getProperties())

        // Get partition field info
        val partSpec = partDesc.getPartSpec()
        val partProps = partDesc.getProperties()

        val partCols = partProps.getProperty(META_TABLE_PARTITION_COLUMNS)
        val partKeys = partCols.trim().split("/")
        val partValues = new ArrayList[String]
        partKeys.foreach { key =>
          if (partSpec == null) {
            partValues.add(new String)
          } else {
            partValues.add(new String(partSpec.get(key)))
          }
        }

        val rowWithPartArr = new Array[Object](2)
        iter.map { value =>
          val deserializedRow = deserializer.deserialize(value) // LazyStruct
          rowWithPartArr.update(0, deserializedRow)
          rowWithPartArr.update(1, partValues)
          rowWithPartArr.asInstanceOf[Object]
        }
      }
      rdds(i) = partRDD.asInstanceOf[RDD[Any]]
      i += 1
    }
    // Even if we don't use any partitions, we still need an empty RDD
    if (rdds.size == 0) {
      SharkEnv.sc.makeRDD(Seq[Object]())
    } else {
      new UnionRDD(rdds(0).context, rdds)
    }
  }

}

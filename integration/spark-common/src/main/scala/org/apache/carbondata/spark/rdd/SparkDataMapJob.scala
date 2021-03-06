/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.carbondata.spark.rdd

import java.text.SimpleDateFormat
import java.util
import java.util.Date

import scala.collection.JavaConverters._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapreduce.{InputSplit, Job, TaskAttemptID, TaskType}
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.spark.{Partition, TaskContext, TaskKilledException}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.util.SparkSQLUtil

import org.apache.carbondata.core.datamap.{AbstractDataMapJob, DistributableDataMapFormat}
import org.apache.carbondata.core.datastore.impl.FileFactory
import org.apache.carbondata.core.indexstore.ExtendedBlocklet
import org.apache.carbondata.core.scan.filter.resolver.FilterResolverIntf

/**
 * Spark job to execute datamap job and prune all the datamaps distributable
 */
class SparkDataMapJob extends AbstractDataMapJob {

  override def execute(dataMapFormat: DistributableDataMapFormat,
      filter: FilterResolverIntf): util.List[ExtendedBlocklet] = {
    new DataMapPruneRDD(SparkSQLUtil.getSparkSession, dataMapFormat, filter).collect()
      .toList.asJava
  }
}

class DataMapRDDPartition(rddId: Int, idx: Int, val inputSplit: InputSplit) extends Partition {
  override def index: Int = idx

  override def hashCode(): Int = 41 * (41 + rddId) + idx
}

/**
 * RDD to prune the datamaps across spark cluster
 * @param ss
 * @param dataMapFormat
 */
class DataMapPruneRDD(
    @transient private val ss: SparkSession,
    dataMapFormat: DistributableDataMapFormat,
    resolverIntf: FilterResolverIntf)
  extends CarbonRDD[(ExtendedBlocklet)](ss, Nil) {

  private val jobTrackerId: String = {
    val formatter = new SimpleDateFormat("yyyyMMddHHmm")
    formatter.format(new Date())
  }

  override def internalCompute(split: Partition,
      context: TaskContext): Iterator[ExtendedBlocklet] = {
    val attemptId = new TaskAttemptID(jobTrackerId, id, TaskType.MAP, split.index, 0)
    val attemptContext = new TaskAttemptContextImpl(FileFactory.getConfiguration, attemptId)
    val inputSplit = split.asInstanceOf[DataMapRDDPartition].inputSplit
    val reader = dataMapFormat.createRecordReader(inputSplit, attemptContext)
    reader.initialize(inputSplit, attemptContext)
    context.addTaskCompletionListener(_ => {
      reader.close()
    })
    val iter = new Iterator[ExtendedBlocklet] {

      private var havePair = false
      private var finished = false

      override def hasNext: Boolean = {
        if (context.isInterrupted) {
          throw new TaskKilledException
        }
        if (!finished && !havePair) {
          finished = !reader.nextKeyValue
          havePair = !finished
        }
        !finished
      }

      override def next(): ExtendedBlocklet = {
        if (!hasNext) {
          throw new java.util.NoSuchElementException("End of stream")
        }
        havePair = false
        val value = reader.getCurrentValue
        value
      }
    }
    iter
  }

  override protected def internalGetPartitions: Array[Partition] = {
    val job = Job.getInstance(FileFactory.getConfiguration)
    val splits = dataMapFormat.getSplits(job)
    splits.asScala.zipWithIndex.map(f => new DataMapRDDPartition(id, f._2, f._1)).toArray
  }
}

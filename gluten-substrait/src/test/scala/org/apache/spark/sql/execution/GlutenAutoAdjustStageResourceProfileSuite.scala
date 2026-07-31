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
package org.apache.spark.sql.execution

import org.apache.gluten.config.GlutenCoreConfig

import org.apache.spark.SparkConf
import org.apache.spark.resource.{ExecutorResourceRequests, ResourceProfile, TaskResourceRequests}
import org.apache.spark.sql.internal.SQLConf

import org.scalatest.funsuite.AnyFunSuite

class GlutenAutoAdjustStageResourceProfileSuite extends AnyFunSuite {

  private val MIB = 1024L * 1024L

  private def sparkConfWith(offHeap: String): SparkConf = new SparkConf(false)
    .set("spark.executor.cores", "4")
    .set("spark.task.cpus", "1")
    .set("spark.memory.offHeap.enabled", "true")
    .set("spark.memory.offHeap.size", offHeap)

  test("updateResourceSetting converts the profile's MiB amount to bytes") {
    // A ResourceProfile records executor memory in MiB, while the configs written here are declared
    // as bytesConf(ByteUnit.BYTE). Writing the amount verbatim shrank the off-heap budget by 2^20,
    // so 20g became 20480 bytes.
    val ereqs = new ExecutorResourceRequests()
    ereqs.cores(4)
    ereqs.offHeapMemory("20g")
    val treqs = new TaskResourceRequests()
    treqs.cpus(1)
    val rp = new ResourceProfile(ereqs.requests, treqs.requests)

    SQLConf.withExistingConf(new SQLConf) {
      GlutenAutoAdjustStageResourceProfile.updateResourceSetting(rp, sparkConfWith("20g"))
      val conf = SQLConf.get
      assert(conf.getConfString(GlutenCoreConfig.NUM_TASK_SLOTS_PER_EXECUTOR.key) == "4")
      assert(
        conf.getConfString(GlutenCoreConfig.COLUMNAR_OFFHEAP_SIZE_IN_BYTES.key) ==
          (20480L * MIB).toString)
      assert(
        conf.getConfString(GlutenCoreConfig.COLUMNAR_TASK_OFFHEAP_SIZE_IN_BYTES.key) ==
          (20480L * MIB / 4).toString)
    }
  }

  test("updateResourceSetting keeps the byte-valued fallback in bytes") {
    // Without an OFFHEAP_MEM request the value comes from spark.memory.offHeap.size, which is
    // already in bytes, so the two branches must agree on the unit.
    val ereqs = new ExecutorResourceRequests()
    ereqs.cores(4)
    val treqs = new TaskResourceRequests()
    treqs.cpus(1)
    val rp = new ResourceProfile(ereqs.requests, treqs.requests)

    SQLConf.withExistingConf(new SQLConf) {
      GlutenAutoAdjustStageResourceProfile.updateResourceSetting(rp, sparkConfWith("20g"))
      val conf = SQLConf.get
      assert(
        conf.getConfString(GlutenCoreConfig.COLUMNAR_OFFHEAP_SIZE_IN_BYTES.key) ==
          (20480L * MIB).toString)
    }
  }
}

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
package org.apache.spark.util

import org.apache.spark.SparkConf

import org.scalatest.funsuite.AnyFunSuite

class SparkResourceUtilSuite extends AnyFunSuite {

  test("getTaskSlots floors at one when task cpus exceed executor cores") {
    // spark.task.cpus > executor cores is an invalid config that Spark rejects later, but Gluten
    // reads task slots during plugin init and divides by the result. Returning 0 here makes that
    // division throw ArithmeticException before Spark's validateTaskCpusLargeEnough can report the
    // real misconfiguration.
    val conf = new SparkConf(false)
      .set("spark.master", "local[1]")
      .set("spark.task.cpus", "2")
    assert(SparkResourceUtil.getTaskSlots(conf) == 1)
  }

  test("getTaskSlots does not divide by zero when task cpus is zero") {
    // spark.task.cpus is read via raw conf.getInt, which bypasses Spark's checkValue(_ > 0), so a
    // zero value must not reach the division.
    val conf = new SparkConf(false)
      .set("spark.master", "local[8]")
      .set("spark.task.cpus", "0")
    assert(SparkResourceUtil.getTaskSlots(conf) == 1)
  }

  test("getTaskSlots divides executor cores by task cpus") {
    val conf = new SparkConf(false)
      .set("spark.master", "local[8]")
      .set("spark.task.cpus", "2")
    assert(SparkResourceUtil.getTaskSlots(conf) == 4)
  }

  test("getTaskSlots returns one slot per core when task cpus defaults to one") {
    val conf = new SparkConf(false).set("spark.master", "local[8]")
    assert(SparkResourceUtil.getTaskSlots(conf) == 8)
  }
}

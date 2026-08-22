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
package org.apache.spark.sql

import org.apache.gluten.config.GlutenConfig
import org.apache.gluten.utils.BackendTestUtils

import org.apache.spark.SparkConf
import org.apache.spark.sql.execution.GlutenImplicits._
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}
import org.apache.spark.sql.test.SharedSparkSession

class GlutenImplicitsTest extends GlutenQueryTest with SharedSparkSession {
  sys.props.put(GlutenConfig.COLUMNAR_TABLE_CACHE_ENABLED.key, "true")

  override protected def sparkConf: SparkConf = {
    // Reuse the session conf every other Gluten SQL suite runs with, since the node counts
    // asserted below depend on it. SharedSparkSession already points the warehouse at a
    // per-class temp dir, so hand that path back to nativeSparkConf rather than inventing one.
    val conf = super.sparkConf
    GlutenSQLTestsBaseTrait
      .nativeSparkConf(conf, conf.get(StaticSQLConf.WAREHOUSE_PATH))
      .set("spark.sql.shuffle.partitions", "5")
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    spark
      .range(10)
      .selectExpr("id as c1", "id % 3 as c2")
      .write
      .format("parquet")
      .saveAsTable("t1")
  }

  override protected def afterAll(): Unit = {
    spark.sql("drop table t1")
    super.afterAll()
  }

  override protected def afterEach(): Unit = {
    spark.catalog.clearCache()
    super.afterEach()
  }

  private def withAQEEnabledAndDisabled(f: => Unit): Unit = {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.ADAPTIVE_EXECUTION_FORCE_APPLY.key -> "true",
      SQLConf.CAN_CHANGE_CACHED_PLAN_OUTPUT_PARTITIONING.key -> "true"
    ) {
      f
    }
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false",
      SQLConf.ADAPTIVE_EXECUTION_FORCE_APPLY.key -> "false",
      SQLConf.CAN_CHANGE_CACHED_PLAN_OUTPUT_PARTITIONING.key -> "false"
    ) {
      f
    }
  }

  test("fallbackSummary with query") {
    withAQEEnabledAndDisabled {
      val df = spark.table("t1").filter(_.getLong(0) > 0)
      assert(df.fallbackSummary().numGlutenNodes == 1, df.fallbackSummary())
      assert(df.fallbackSummary().numFallbackNodes == 1, df.fallbackSummary())
      df.collect()
      assert(df.fallbackSummary().numGlutenNodes == 1, df.fallbackSummary())
      assert(df.fallbackSummary().numFallbackNodes == 1, df.fallbackSummary())
    }
  }

  test("fallbackSummary with shuffle") {
    // ClickHouse reports a different number of Gluten nodes here. The spark33
    // ClickHouseTestSettings excluded this case without recording what CH actually reports, so
    // keep it Velox-only until someone reads the real counts off a ClickHouse run.
    assume(BackendTestUtils.isVeloxBackendLoaded())
    withAQEEnabledAndDisabled {
      val df = spark.sql("SELECT c2 FROM t1 group by c2").filter(_.getLong(0) > 0)
      assert(df.fallbackSummary().numGlutenNodes == 6, df.fallbackSummary())
      assert(df.fallbackSummary().numFallbackNodes == 1, df.fallbackSummary())
      df.collect()
      assert(df.fallbackSummary().numGlutenNodes == 6, df.fallbackSummary())
      assert(df.fallbackSummary().numFallbackNodes == 1, df.fallbackSummary())
    }
  }

  test("fallbackSummary with set command") {
    withAQEEnabledAndDisabled {
      val df = spark.sql("set k=v")
      assert(df.fallbackSummary().numGlutenNodes == 0, df.fallbackSummary())
      assert(df.fallbackSummary().numFallbackNodes == 0, df.fallbackSummary())
    }
  }

  test("fallbackSummary with data write command") {
    withAQEEnabledAndDisabled {
      withTable("tmp") {
        val df = spark.sql("create table tmp using parquet as select * from t1")
        // Spark 3.3 counts one Gluten node here. Since 3.4 the CTAS is executed as an
        // ExecutedCommandExec, which collectFallbackNodes walks past without counting anything,
        // so the summary reports neither a Gluten node nor a fallback node.
        val expectedGlutenNodes = if (isSparkVersionGE("3.4")) 0 else 1
        assert(df.fallbackSummary().numGlutenNodes == expectedGlutenNodes, df.fallbackSummary())
        assert(df.fallbackSummary().numFallbackNodes == 0, df.fallbackSummary())
      }
    }
  }

  test("fallbackSummary with cache") {
    // Velox only, for the same reason as `fallbackSummary with shuffle`.
    assume(BackendTestUtils.isVeloxBackendLoaded())
    withAQEEnabledAndDisabled {
      val df = spark.table("t1").cache().filter(_.getLong(0) > 0)
      assert(df.fallbackSummary().numGlutenNodes == 2, df.fallbackSummary())
      assert(df.fallbackSummary().numFallbackNodes == 1, df.fallbackSummary())
      df.collect()
      assert(df.fallbackSummary().numGlutenNodes == 2, df.fallbackSummary())
      assert(df.fallbackSummary().numFallbackNodes == 1, df.fallbackSummary())
    }
  }

  test("fallbackSummary with cached data and shuffle") {
    // Velox only, for the same reason as `fallbackSummary with shuffle`.
    assume(BackendTestUtils.isVeloxBackendLoaded())
    withAQEEnabledAndDisabled {
      val df = spark.sql("select * from t1").filter(_.getLong(0) > 0).cache.repartition()
      assert(df.fallbackSummary().numGlutenNodes == 7, df.fallbackSummary())
      assert(df.fallbackSummary().numFallbackNodes == 1, df.fallbackSummary())
      df.collect()
      assert(df.fallbackSummary().numGlutenNodes == 7, df.fallbackSummary())
      assert(df.fallbackSummary().numFallbackNodes == 1, df.fallbackSummary())
    }
  }
}

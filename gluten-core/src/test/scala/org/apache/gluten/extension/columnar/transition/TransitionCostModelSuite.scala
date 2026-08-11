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
package org.apache.gluten.extension.columnar.transition

import org.apache.gluten.extension.columnar.cost.LongCostModel

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.execution.{LeafExecNode, SparkPlan, UnaryExecNode}

import org.scalatest.funsuite.AnyFunSuite

class TransitionCostModelSuite extends AnyFunSuite {
  import TransitionCostModelSuite._

  test("costComparator does not overflow on distant node name hash codes") {
    // The tiebreaker used to subtract the two hash codes. "RowToVeloxColumnar" hashes to
    // 2056048280 and "CHColumnarToCarrierRow" to -2037667767, so the true difference is
    // 4093716047, past Int.MaxValue, and the subtraction wrapped to -201251249. That inverted
    // sign makes FloydWarshallGraph#build replace an incumbent path with an equal-cost one it
    // should have kept. 46 of the 110 ordered pairs of Gluten transition node names invert.
    val comparator = TransitionGraph.asTransitionCostModel(TestCostModel).costComparator()
    val higherHash = costOf(Seq("RowToVeloxColumnar"))
    val lowerHash = costOf(Seq("CHColumnarToCarrierRow"))

    assert("RowToVeloxColumnar".hashCode - "CHColumnarToCarrierRow".hashCode < 0)
    assert(comparator.compare(higherHash, lowerHash) > 0)
    assert(comparator.compare(lowerHash, higherHash) < 0)
  }

  test("costComparator prefers the cheaper cost before consulting node names") {
    val comparator = TransitionGraph.asTransitionCostModel(TestCostModel).costComparator()
    val cheap = TransitionGraph.transitionCostForTesting(TestCostModel.costOf(Leaf()), Seq("zzzz"))
    val expensive =
      TransitionGraph.transitionCostForTesting(TestCostModel.costOf(Unary(Leaf())), Seq("AAAA"))
    assert(comparator.compare(cheap, expensive) < 0)
    assert(comparator.compare(expensive, cheap) > 0)
  }

  test("costComparator treats identical node name sequences as equal") {
    val comparator = TransitionGraph.asTransitionCostModel(TestCostModel).costComparator()
    val names = Seq("LoadArrowData", "ColumnarToRow")
    assert(comparator.compare(costOf(names), costOf(names)) == 0)
  }
}

object TransitionCostModelSuite {
  private def costOf(nodeNames: Seq[String]): FloydWarshallGraph.Cost =
    TransitionGraph.transitionCostForTesting(TestCostModel.makeZeroCost(), nodeNames)

  /** Charges one per node, so a deeper plan costs more. */
  private object TestCostModel extends LongCostModel {
    override def selfLongCostOf(node: SparkPlan): Long = 1L
  }

  private case class Leaf() extends LeafExecNode {
    override protected def doExecute(): RDD[InternalRow] = throw new UnsupportedOperationException()
    override def output: Seq[Attribute] = Nil
  }

  private case class Unary(child: SparkPlan) extends UnaryExecNode {
    override protected def doExecute(): RDD[InternalRow] = throw new UnsupportedOperationException()
    override def output: Seq[Attribute] = Nil
    override protected def withNewChildInternal(newChild: SparkPlan): Unary = copy(newChild)
  }
}

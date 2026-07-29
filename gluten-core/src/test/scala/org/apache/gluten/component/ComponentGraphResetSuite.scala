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
package org.apache.gluten.component

import org.apache.gluten.extension.injector.Injector

import org.scalatest.funsuite.AnyFunSuite

class ComponentGraphResetSuite extends AnyFunSuite {
  import ComponentGraphResetSuite._

  test("a cycle registered by an earlier suite does not outlive the reset") {
    // Reproduces what ComponentSuite leaves behind: components wired into a cycle. Without the
    // reset these stay in the JVM-global graph and every later Component#sorted call throws.
    new CycleA().ensureRegistered()
    new CycleB().ensureRegistered()
    assertThrows[UnsupportedOperationException](Component.sortedUnsafe())

    clearAllForTesting()

    // The graph is empty again, so sorting no longer reports the cycle.
    val sorted = Component.sortedUnsafe()
    assert(!sorted.exists(c => c.name() == "reset-A" || c.name() == "reset-B"))
  }
}

object ComponentGraphResetSuite {
  private class CycleA extends Component {
    override def name(): String = "reset-A"
    override def dependencies(): Seq[Class[_ <: Component]] = Seq(classOf[CycleB])
    override def injectRules(injector: Injector): Unit = {}
  }

  private class CycleB extends Component {
    override def name(): String = "reset-B"
    override def dependencies(): Seq[Class[_ <: Component]] = Seq(classOf[CycleA])
    override def injectRules(injector: Injector): Unit = {}
  }
}

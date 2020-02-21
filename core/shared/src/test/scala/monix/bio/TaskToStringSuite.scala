/*
 * Copyright (c) 2019-2020 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.bio

import minitest.SimpleTestSuite
import monix.execution.exceptions.DummyException

object TaskToStringSuite extends SimpleTestSuite {

  def assertContains[E, A](ref: BIO[E, A], startStr: String): Unit = {
    val str = ref.toString
    assert(str.startsWith(startStr), s""""$str".startsWith("$startStr")""")
  }

  test("BIO.Now") {
    val ref = BIO.now(1)
    assertContains(ref, "BIO.Now")
  }

  test("BIO.Error") {
    val ref = BIO.raiseError(DummyException("dummy"))
    assertContains(ref, "BIO.Error")
  }

  test("BIO.Termination") {
    val ref = BIO.terminate(DummyException("dummy"))
    assertContains(ref, "BIO.Termination")
  }

  test("BIO.Eval") {
    val ref = BIO.eval("hello")
    assertContains(ref, "BIO.Eval")
  }

  test("BIO.EvalTotal") {
    val ref = BIO.evalTotal("hello")
    assertContains(ref, "BIO.EvalTotal")
  }

  test("BIO.Async") {
    val ref = BIO.cancelable0[Int, Int]((_, cb) => { cb.onSuccess(1); BIO.unit })
    assertContains(ref, "BIO.Async")
  }

  test("BIO.FlatMap") {
    val ref = BIO.now(1).flatMap(BIO.now)
    assertContains(ref, "BIO.FlatMap")
  }

  test("BIO.Suspend") {
    val ref = Task.defer(BIO.now(1))
    assertContains(ref, "BIO.Suspend")
  }

  test("BIO.SuspendTotal") {
    val ref = BIO.suspendTotal(BIO.now(1))
    assertContains(ref, "BIO.SuspendTotal")
  }

  test("BIO.Map") {
    val ref = BIO.now(1).map(_ + 1)
    assertContains(ref, "BIO.Map")
  }
}

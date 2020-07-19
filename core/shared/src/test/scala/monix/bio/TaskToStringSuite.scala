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

  def assertContains[E, A](ref: IO[E, A], startStr: String): Unit = {
    val str = ref.toString
    assert(str.startsWith(startStr), s""""$str".startsWith("$startStr")""")
  }

  test("IO.Now") {
    val ref = IO.now(1)
    assertContains(ref, "IO.Now")
  }

  test("IO.Error") {
    val ref = IO.raiseError(DummyException("dummy"))
    assertContains(ref, "IO.Error")
  }

  test("IO.Termination") {
    val ref = IO.terminate(DummyException("dummy"))
    assertContains(ref, "IO.Termination")
  }

  test("IO.Eval") {
    val ref = IO.eval("hello")
    assertContains(ref, "IO.Eval")
  }

  test("IO.EvalTotal") {
    val ref = IO.evalTotal("hello")
    assertContains(ref, "IO.EvalTotal")
  }

  test("IO.Async") {
    val ref = IO.cancelable0[Int, Int]((_, cb) => { cb.onSuccess(1); IO.unit })
    assertContains(ref, "IO.Async")
  }

  test("IO.FlatMap") {
    val ref = IO.now(1).flatMap(IO.now)
    assertContains(ref, "IO.FlatMap")
  }

  test("IO.Suspend") {
    val ref = Task.defer(IO.now(1))
    assertContains(ref, "IO.Suspend")
  }

  test("IO.SuspendTotal") {
    val ref = IO.suspendTotal(IO.now(1))
    assertContains(ref, "IO.SuspendTotal")
  }

  test("IO.Map") {
    val ref = IO.now(1).map(_ + 1)
    assertContains(ref, "IO.Map")
  }
}

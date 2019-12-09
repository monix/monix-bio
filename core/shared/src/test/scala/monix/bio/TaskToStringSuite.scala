/*
 * Copyright (c) 2019-2019 by The Monix Project Developers.
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
  def assertContains[E, A](ref: WRYYY[E, A], startStr: String): Unit = {
    val str = ref.toString
    assert(str.startsWith(startStr), s""""$str".startsWith("$startStr")""")
  }

  test("WRYYY.Now") {
    val ref = WRYYY.now(1)
    assertContains(ref, "WRYYY.Now")
  }

  test("WRYYY.Error") {
    val ref = WRYYY.raiseError(DummyException("dummy"))
    assertContains(ref, "WRYYY.Error")
  }

  test("WRYYY.FatalError") {
    val ref = WRYYY.raiseFatalError(DummyException("dummy"))
    assertContains(ref, "WRYYY.FatalError")
  }

  test("WRYYY.Eval") {
    val ref = WRYYY.eval("hello")
    assertContains(ref, "WRYYY.Eval")
  }

  test("WRYYY.Async") {
    val ref = WRYYY.cancelable0[Int, Int]((_, cb) => { cb.onSuccess(1); WRYYY.unit })
    assertContains(ref, "WRYYY.Async")
  }

  test("WRYYY.FlatMap") {
    val ref = WRYYY.now(1).flatMap(WRYYY.now)
    assertContains(ref, "WRYYY.FlatMap")
  }

  test("WRYYY.Suspend") {
    val ref = Task.defer(WRYYY.now(1))
    assertContains(ref, "WRYYY.Suspend")
  }

  test("WRYYY.Map") {
    val ref = WRYYY.now(1).map(_ + 1)
    assertContains(ref, "WRYYY.Map")
  }
}

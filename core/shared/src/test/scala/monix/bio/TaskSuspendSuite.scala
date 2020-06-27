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

import monix.execution.exceptions.DummyException

import scala.util.{Failure, Success}

object TaskSuspendSuite extends BaseTestSuite {
  test("BIO.suspend should suspend evaluation") { implicit s =>
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = BIO.suspend {
      BIO.now(trigger())
    }
    assert(!wasTriggered, "!wasTriggered")

    val f = task.runToFuture
    assert(wasTriggered, "wasTriggered")
    assertEquals(f.value, Some(Success("result")))
  }

  test("BIO.suspend should protect against user code errors") { implicit s =>
    val ex = DummyException("dummy")
    val f = BIO.suspend[Int](throw ex).runToFuture

    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, null)
  }

  test("BIO.suspendTotal should protect against unexpected errors") { implicit s =>
    val ex = DummyException("dummy")
    val f = BIO.suspendTotal[Int, Int](throw ex).redeemCause(_ => 10, identity).runToFuture
    val g = BIO.suspendTotal[Int, Int](throw ex).onErrorHandle(_ => 10).runToFuture

    assertEquals(f.value, Some(Success(10)))
    assertEquals(g.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, null)
  }
}

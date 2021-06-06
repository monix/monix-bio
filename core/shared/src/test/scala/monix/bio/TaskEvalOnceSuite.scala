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

import cats.laws._
import cats.laws.discipline._
import monix.execution.exceptions.DummyException

import scala.util.{Failure, Success}

object TaskEvalOnceSuite extends BaseTestSuite {
  // TODO: needs to be implemented with Coeval and s.tick() removed in other tests
//  test("IO.evalOnce should work synchronously") { implicit s =>
//    var wasTriggered = false
//    def trigger(): String = { wasTriggered = true; "result" }
//
//    val task = Task.evalOnce(trigger())
//    assert(!wasTriggered, "!wasTriggered")
//
//    val f = task.runToFuture
//    assert(wasTriggered, "wasTriggered")
//    assertEquals(f.value, Some(Success("result")))
//  }

  test("IO.evalOnce should protect against user code errors") { implicit s =>
    val ex = DummyException("dummy")
    val f = IO.evalOnce[Int](if (1 == 1) throw ex else 1).runToFuture
    s.tick()

    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, null)
  }

  test("IO.evalOnce.flatMap should be equivalent with Task.evalOnce") { implicit s =>
    val ex = DummyException("dummy")
    val t = IO.evalOnce[Int](if (1 == 1) throw ex else 1).flatMap(IO.now)
    check(t <-> IO.raiseError(ex))
  }

  test("IO.evalOnce.flatMap should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val t = IO.evalOnce(1).flatMap[Throwable, Int](_ => throw ex)
    check(t <-> IO.raiseError(ex))
  }

  test("IO.evalOnce.map should work") { implicit s =>
    check1 { (a: Int) =>
      IO.evalOnce(a).map(_ + 1) <-> IO.evalOnce(a + 1)
    }
  }

  test("IO.evalOnce.flatMap should be tail recursive") { implicit s =>
    def loop(n: Int, idx: Int): Task[Int] =
      IO.evalOnce(idx).flatMap { _ =>
        if (idx < n) loop(n, idx + 1).map(_ + 1)
        else
          IO.evalOnce(idx)
      }

    val iterations = s.executionModel.recommendedBatchSize * 20
    val f = loop(iterations, 0).runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(iterations * 2)))
  }

  // won't pass until it is  implemented with Coeval

//  test("IO.evalOnce should not be cancelable") { implicit s =>
//    val t = IO.evalOnce(10)
//    val f = t.runToFuture
//    f.cancel()
//    s.tick()
//    assertEquals(f.value, Some(Success(Right(10))))
//  }
//
//  test("IO.evalOnce.coeval") { implicit s =>
//    val result = IO.evalOnce(100).runSyncStep
//    assertEquals(result, Right(100))
//  }

  test("IO.EvalOnce.runAsync override") { implicit s =>
    val dummy = DummyException("dummy")
    val task = IO.evalOnce { if (1 == 1) throw dummy else 10 }
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.evalOnce.materialize should work for success") { implicit s =>
    val task = IO.evalOnce(1).materialize
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Success(1))))
  }

  test("IO.evalOnce.materialize should work for failure") { implicit s =>
    val dummy = DummyException("dummy")
    val task = IO.evalOnce[Int](throw dummy).materialize
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Failure(dummy))))
  }
}

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
//  test("BIO.evalOnce should work synchronously") { implicit s =>
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

  test("BIO.evalOnce should protect against user code errors") { implicit s =>
    val ex = DummyException("dummy")
    val f = BIO.evalOnce[Int](if (1 == 1) throw ex else 1).runToFuture
    s.tick()

    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, null)
  }

  test("BIO.evalOnce.flatMap should be equivalent with Task.evalOnce") { implicit s =>
    val ex = DummyException("dummy")
    val t = BIO.evalOnce[Int](if (1 == 1) throw ex else 1).flatMap(BIO.now)
    check(t <-> BIO.raiseError(ex))
  }

  test("BIO.evalOnce.flatMap should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val t = BIO.evalOnce(1).flatMap[Throwable, Int](_ => throw ex)
    check(t <-> BIO.raiseError(ex))
  }

  test("BIO.evalOnce.map should work") { implicit s =>
    check1 { a: Int =>
      BIO.evalOnce(a).map(_ + 1) <-> BIO.evalOnce(a + 1)
    }
  }

  test("BIO.evalOnce.flatMap should be tail recursive") { implicit s =>
    def loop(n: Int, idx: Int): Task[Int] =
      BIO.evalOnce(idx).flatMap { _ =>
        if (idx < n) loop(n, idx + 1).map(_ + 1)
        else
          BIO.evalOnce(idx)
      }

    val iterations = s.executionModel.recommendedBatchSize * 20
    val f = loop(iterations, 0).runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(iterations * 2)))
  }

  // won't pass until it is  implemented with Coeval

//  test("BIO.evalOnce should not be cancelable") { implicit s =>
//    val t = BIO.evalOnce(10)
//    val f = t.runToFuture
//    f.cancel()
//    s.tick()
//    assertEquals(f.value, Some(Success(Right(10))))
//  }
//
//  test("BIO.evalOnce.coeval") { implicit s =>
//    val result = BIO.evalOnce(100).runSyncStep
//    assertEquals(result, Right(100))
//  }

  test("BIO.EvalOnce.runAsync override") { implicit s =>
    val dummy = DummyException("dummy")
    val task = BIO.evalOnce { if (1 == 1) throw dummy else 10 }
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.evalOnce.materialize should work for success") { implicit s =>
    val task = BIO.evalOnce(1).materialize
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Success(Right(1)))))
  }

  test("BIO.evalOnce.materialize should work for failure") { implicit s =>
    val dummy = DummyException("dummy")
    val task = BIO.evalOnce[Int](throw dummy).materialize
    val f = task.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Success(Left(dummy)))))
  }
}

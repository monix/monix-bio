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

object TaskEvalAsyncSuite extends BaseTestSuite {
  test("IO.evalAsync should work, on different thread") { implicit s =>
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = UIO.evalAsync(trigger())
    assert(!wasTriggered, "!wasTriggered")

    val f = task.runToFuture
    assert(!wasTriggered, "!wasTriggered")
    assertEquals(f.value, None)

    s.tick()
    assert(wasTriggered, "wasTriggered")
    assertEquals(f.value, Some(Success("result")))
  }

  test("IO.evalAsync should protect against user code errors") { implicit s =>
    val ex = DummyException("dummy")
    val f = IO.evalAsync[Int](if (1 == 1) throw ex else 1).attempt.runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(Left(ex))))
    assertEquals(s.state.lastReportedError, null)
  }

  test("UIO.evalAsync should protect against user code errors") { implicit s =>
    val ex = DummyException("dummy")
    val f = UIO.evalAsync[Int](if (1 == 1) throw ex else 1).runToFuture

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, null)
  }

  test("IO.evalAsync is equivalent with IO.eval") { implicit s =>
    check1 { (a: Int) =>
      val t1 = {
        var effect = 100
        IO.evalAsync { effect += 100; effect + a }
      }

      val t2 = {
        var effect = 100
        IO.eval { effect += 100; effect + a }
      }

      List(t1 <-> t2, t2 <-> t1)
    }
  }

  test("IO.evalAsync is equivalent with IO.evalOnce on first run") { implicit s =>
    check1 { (a: Int) =>
      val t1 = {
        var effect = 100
        IO.evalAsync { effect += 100; effect + a }
      }

      val t2 = {
        var effect = 100
        IO.evalOnce { effect += 100; effect + a }
      }

      t1 <-> t2
    }
  }

  test("IO.evalAsync.flatMap should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val t = IO.evalAsync(1).flatMap[Throwable, Int](_ => throw ex)
    check(t <-> IO.terminate(ex))
  }

  test("IO.evalAsync should be tail recursive") { implicit s =>
    def loop(n: Int, idx: Int): Task[Int] =
      IO.evalAsync(idx).flatMap { idx =>
        if (idx < n) loop(n, idx + 1).map(_ + 1)
        else
          IO.evalAsync(idx)
      }

    val iterations = s.executionModel.recommendedBatchSize * 20
    val f = loop(iterations, 0).runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(iterations * 2)))
  }

  test("IO.evalAsync.flatten is equivalent with flatMap") { implicit s =>
    check1 { (a: Int) =>
      val t = IO.evalAsync(IO.eval(a))
      t.flatMap(identity) <-> t.flatten
    }
  }

  test("IO.evalAsync.coeval") { implicit s =>
    val f = IO.evalAsync(100).runToFuture
    f.value match {
      case None =>
        s.tick()
        assertEquals(f.value, Some(Success(100)))
      case r @ Some(_) =>
        fail(s"Received incorrect result: $r")
    }
  }
}

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

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskAsyncSuite extends BaseTestSuite {
  test("IO.never should never complete") { implicit s =>
    val t = IO.never[Int]
    val f = t.runToFuture
    s.tick(365.days)
    assertEquals(f.value, None)
  }

  test("IO.async should execute") { implicit s =>
    val task = IO.async0[String, Int] { (ec, cb) =>
      ec.execute { () =>
        cb.onSuccess(1)
      }
    }

    val f = task.attempt.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.async should signal errors in register") { implicit s =>
    val ex = DummyException("dummy")
    val task = IO.async0[String, Int]((_, _) => throw ex)
    val result = task.attempt.runToFuture; s.tick()
    assertEquals(result.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, null)
  }

  test("IO.async should be stack safe") { implicit s =>
    def signal(n: Int) = IO.async0[String, Int]((_, cb) => cb.onSuccess(n))
    def loop(n: Int, acc: Int): IO[String, Int] =
      signal(1).flatMap { x =>
        if (n > 0) loop(n - 1, acc + x)
        else IO.now(acc)
      }

    val f = loop(10000, 0).attempt.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }

  test("IO.async works for immediate successful value") { implicit sc =>
    val task = IO.async[String, Int](_.onSuccess(1))
    assertEquals(task.attempt.runToFuture.value, Some(Success(Right(1))))
  }

  test("IO.async works for immediate error") { implicit sc =>
    val e = "dummy"
    val task = IO.async[String, Int](_.onError(e))
    assertEquals(task.attempt.runToFuture.value, Some(Success(Left(e))))
  }

  test("IO.async works for immediate terminate error") { implicit sc =>
    val e = DummyException("dummy")
    val task = IO.async[Int, Int](_.onTermination(e))
    assertEquals(task.attempt.runToFuture.value, Some(Failure(e)))
  }

  test("IO.async is memory safe in flatMap loops") { implicit sc =>
    def signal(n: Int): UIO[Int] = IO.async(_.onSuccess(n))

    def loop(n: Int, acc: Int): UIO[Int] =
      signal(n).flatMap { n =>
        if (n > 0) loop(n - 1, acc + 1)
        else IO.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(10000)))
  }

  test("IO.async0 works for immediate successful value") { implicit sc =>
    val task = IO.async0[String, Int]((_, cb) => cb.onSuccess(1))
    assertEquals(task.attempt.runToFuture.value, Some(Success(Right(1))))
  }

  test("IO.async0 works for async successful value") { implicit sc =>
    val f = IO
      .async0[String, Int]((s, cb) => s.execute(() => cb.onSuccess(1)))
      .attempt
      .runToFuture

    sc.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.async0 works for async error") { implicit sc =>
    val e = "dummy"
    val f = IO
      .async0[String, Int]((s, cb) => s.execute(() => cb.onError(e)))
      .attempt
      .runToFuture

    sc.tick()
    assertEquals(f.value, Some(Success(Left(e))))
  }

  test("IO.async0 works for async terminate error") { implicit sc =>
    val e = DummyException("dummy")
    val f = IO
      .async0[Int, Int]((s, cb) => s.execute(() => cb.onTermination(e)))
      .attempt
      .runToFuture

    sc.tick()
    assertEquals(f.value, Some(Failure(e)))
  }

  test("IO.async0 is memory safe in synchronous flatMap loops") { implicit sc =>
    def signal(n: Int): UIO[Int] = IO.async0((_, cb) => cb.onSuccess(n))

    def loop(n: Int, acc: Int): UIO[Int] =
      signal(n).flatMap { n =>
        if (n > 0) loop(n - 1, acc + 1)
        else IO.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(10000)))
  }

  test("IO.async0 is memory safe in async flatMap loops") { implicit sc =>
    def signal(n: Int): IO[String, Int] =
      IO.async0((s, cb) => s.execute(() => cb.onSuccess(n)))

    def loop(n: Int, acc: Int): IO[String, Int] =
      signal(n).flatMap { n =>
        if (n > 0) loop(n - 1, acc + 1)
        else IO.now(acc)
      }

    val f = loop(10000, 0).attempt.runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }

}

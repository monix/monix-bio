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
  test("BIO.never should never complete") { implicit s =>
    val t = BIO.never[Int]
    val f = t.runToFuture
    s.tick(365.days)
    assertEquals(f.value, None)
  }

  test("BIO.async should execute") { implicit s =>
    val task = BIO.async0[String, Int] { (ec, cb) =>
      ec.executeAsync { () =>
        cb.onSuccess(1)
      }
    }

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.async should signal errors in register") { implicit s =>
    val ex = DummyException("dummy")
    val task = BIO.async0[String, Int]((_, _) => throw ex)
    val result = task.runToFuture; s.tick()
    assertEquals(result.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, null)
  }

  test("BIO.async should be stack safe") { implicit s =>
    def signal(n: Int) = BIO.async0[String, Int]((_, cb) => cb.onSuccess(n))
    def loop(n: Int, acc: Int): BIO[String, Int] =
      signal(1).flatMap { x =>
        if (n > 0) loop(n - 1, acc + x)
        else BIO.now(acc)
      }

    val f = loop(10000, 0).runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }

  test("BIO.async works for immediate successful value") { implicit sc =>
    val task = BIO.async[String, Int](_.onSuccess(1))
    assertEquals(task.runToFuture.value, Some(Success(Right(1))))
  }

  test("BIO.async works for immediate error") { implicit sc =>
    val e = "dummy"
    val task = BIO.async[String, Int](_.onError(e))
    assertEquals(task.runToFuture.value, Some(Success(Left(e))))
  }

  test("BIO.async works for immediate terminate error") { implicit sc =>
    val e = DummyException("dummy")
    val task = BIO.async[Int, Int](_.onTermination(e))
    assertEquals(task.runToFuture.value, Some(Failure(e)))
  }

  test("BIO.async is memory safe in flatMap loops") { implicit sc =>
    def signal(n: Int): UIO[Int] = BIO.async(_.onSuccess(n))

    def loop(n: Int, acc: Int): UIO[Int] =
      signal(n).flatMap { n =>
        if (n > 0) loop(n - 1, acc + 1)
        else BIO.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }

  test("BIO.async0 works for immediate successful value") { implicit sc =>
    val task = BIO.async0[String, Int]((_, cb) => cb.onSuccess(1))
    assertEquals(task.runToFuture.value, Some(Success(Right(1))))
  }

  test("BIO.async0 works for async successful value") { implicit sc =>
    val f = BIO
      .async0[String, Int]((s, cb) => s.executeAsync(() => cb.onSuccess(1)))
      .runToFuture

    sc.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.async0 works for async error") { implicit sc =>
    val e = "dummy"
    val f = BIO
      .async0[String, Int]((s, cb) => s.executeAsync(() => cb.onError(e)))
      .runToFuture

    sc.tick()
    assertEquals(f.value, Some(Success(Left(e))))
  }

  test("BIO.async0 works for async terminate error") { implicit sc =>
    val e = DummyException("dummy")
    val f = BIO
      .async0[Int, Int]((s, cb) => s.executeAsync(() => cb.onTermination(e)))
      .runToFuture

    sc.tick()
    assertEquals(f.value, Some(Failure(e)))
  }

  test("BIO.async0 is memory safe in synchronous flatMap loops") { implicit sc =>
    def signal(n: Int): UIO[Int] = BIO.async0((_, cb) => cb.onSuccess(n))

    def loop(n: Int, acc: Int): UIO[Int] =
      signal(n).flatMap { n =>
        if (n > 0) loop(n - 1, acc + 1)
        else BIO.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }

  test("BIO.async0 is memory safe in async flatMap loops") { implicit sc =>
    def signal(n: Int): BIO[String, Int] =
      BIO.async0((s, cb) => s.executeAsync(() => cb.onSuccess(n)))

    def loop(n: Int, acc: Int): BIO[String, Int] =
      signal(n).flatMap { n =>
        if (n > 0) loop(n - 1, acc + 1)
        else BIO.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }

}

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

import monix.execution.{Cancelable, CancelableFuture}
import monix.execution.exceptions.DummyException
import monix.execution.internal.Platform

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskFromFutureEitherSuite extends BaseTestSuite {
  test("BIO.fromFutureEither should be faster for completed futures, success") { implicit s =>
    val t = BIO.fromFutureEither(Future.successful(Right(10)))
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("BIO.fromFutureEither should be faster for completed futures, typed error") { implicit s =>
    val dummy = "dummy"
    val t = BIO.fromFutureEither(Future.successful(Left(dummy)))
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO.fromFutureEither should be faster for completed futures, fatal failure") { implicit s =>
    val dummy = DummyException("dummy")
    val t = BIO.fromFutureEither(Future.failed(dummy))
    val f = t.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFutureEither should work onSuccess") { implicit s =>
    val t = BIO.fromFutureEither(Future(Right(10)))
    val f = t.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("BIO.fromFutureEither should work onError") { implicit s =>
    val dummy = "dummy"
    val t = BIO.fromFutureEither(Future(Left(dummy)))
    val f = t.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO.fromFutureEither should work onFatalError") { implicit s =>
    val dummy = DummyException("dummy")
    val t = BIO.fromFutureEither(Future(throw dummy))
    val f = t.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFutureEither should be short-circuited onSuccess") { implicit s =>
    val p = Promise[Either[String, Int]]()
    val t = BIO.fromFutureEither(p.future)
    p.success(Right(10))
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("BIO.fromFutureEither should be short-circuited onError") { implicit s =>
    val p = Promise[Either[String, Int]]()
    val t = BIO.fromFutureEither(p.future)
    p.success(Left("dummy"))
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(Left("dummy"))))
  }

  test("BIO.fromFutureEither should be short-circuited onFatalError") { implicit s =>
    val dummy = DummyException("dummy")
    val p = Promise[Either[String, Int]]()
    val t = BIO.fromFutureEither(p.future)
    p.failure(dummy)
    val f = t.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFutureEither(cancelable) should work for synchronous results onSuccess") { implicit s =>
    val t = BIO.fromFutureEither(CancelableFuture.successful(Right(10)))
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("BIO.fromFutureEither(cancelable) should work for synchronous results onFailure") { implicit s =>
    val dummy = "dummy"
    val t = BIO.fromFutureEither(CancelableFuture.successful(Left(dummy)))
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO.fromFutureEither(cancelable) should work for synchronous results onFatalFailure") { implicit s =>
    val dummy = DummyException("dummy")
    val t = BIO.fromFutureEither(CancelableFuture.failed(dummy))
    val f = t.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFutureEither(cancelable) should be short-circuited onSuccess") { implicit s =>
    val p = Promise[Either[String, Int]]()
    val t = BIO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    p.success(Right(10))
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("BIO.fromFutureEither(cancelable) should be short-circuited onError") { implicit s =>
    val dummy = "dummy"
    val p = Promise[Either[String, Int]]()
    val t = BIO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    p.success(Left(dummy))
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO.fromFutureEither(cancelable) should be short-circuited onFatalError") { implicit s =>
    val dummy = DummyException("dummy")
    val p = Promise[Either[String, Int]]()
    val t = BIO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    p.failure(dummy)
    val f = t.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFutureEither(cancelable) should work onSuccess") { implicit s =>
    val p = Promise[Either[String, Int]]()
    val t = BIO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    val f = t.runToFuture
    s.tick(); p.success(Right(10)); s.tickOne()
    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("BIO.fromFutureEither(cancelable) should work onError") { implicit s =>
    val dummy = "dummy"
    val p = Promise[Either[String, Int]]()
    val t = BIO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    val f = t.runToFuture
    s.tick()
    p.success(Left(dummy))
    s.tickOne()
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO.fromFutureEither(cancelable) should work onFatalError") { implicit s =>
    val dummy = DummyException("dummy")
    val p = Promise[Either[String, Int]]()
    val t = BIO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    val f = t.runToFuture
    s.tick()
    p.failure(dummy)
    s.tickOne()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFutureEither(cancelable) should be cancelable") { implicit s =>
    val source = BIO.now(10).delayExecution(1.second)
    val t = BIO.fromFutureEither(source.runToFuture)
    val f = t.runToFuture; s.tick()
    assertEquals(f.value, None)
    f.cancel()
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("BIO.fromFutureEither should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 5000
    var result = UIO.evalAsync(1).runToFuture
    for (_ <- 0 until count) result = BIO.fromFutureEither(result).runToFuture

    assertEquals(result.value, None)
    s.tick()
    assertEquals(result.value, Some(Success(Right(1))))
  }

  test("BIO.now.fromFutureEither should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 5000
    var result = BIO.now(1).runToFuture
    for (_ <- 0 until count) result = BIO.fromFutureEither(result).runToFuture
    assertEquals(result.value, Some(Success(Right(1))))
  }
}

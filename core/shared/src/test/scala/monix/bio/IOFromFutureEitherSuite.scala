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

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object IOFromFutureEitherSuite extends BaseTestSuite {
  test("IO.fromFutureEither should return success channel for Right") { implicit s =>
    val mkFuture = Future.successful[Either[String, Int]](Right(10))
    val io = IO.fromFutureEither(mkFuture)
    val f = io.attempt.runToFuture

    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("IO.fromFutureEither should return checked failure channel for Left") { implicit s =>
    val mkFuture = Future.successful[Either[String, Int]](Left("uh-oh"))
    val io = IO.fromFutureEither(mkFuture)
    val f = io.attempt.runToFuture

    assertEquals(f.value, Some(Success(Left("uh-oh"))))
  }

  test("IO.fromFutureEither should return unchecked failure channel for a failed Future") { implicit s =>
    val dummy = DummyException("dummy")
    val mkFuture = Future.failed[Either[String, Int]](dummy)
    val io = IO.fromFutureEither(mkFuture)
    val f = io.attempt.runToFuture

    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.fromFutureEither(cancelable) should work for Right synchronous results onSuccess") { implicit s =>
    val mkFuture = CancelableFuture.successful[Either[String, Int]](Right(10))
    val t = IO.fromFutureEither(mkFuture)
    val f = t.attempt.runToFuture

    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("IO.fromFutureEither(cancelable) should work for Left synchronous results onSuccess") { implicit s =>
    val mkFuture = CancelableFuture.successful[Either[String, Int]](Left("uh-oh"))
    val t = IO.fromFutureEither(mkFuture)
    val f = t.attempt.runToFuture

    assertEquals(f.value, Some(Success(Left("uh-oh"))))
  }

  test("IO.fromFutureEither(cancelable) should work for synchronous results onFailure") { implicit s =>
    val dummy = DummyException("dummy")
    val t = IO.fromFutureEither(CancelableFuture.failed(dummy))
    val f = t.attempt.runToFuture

    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.fromFutureEither(cancelable) should be short-circuited for Right onSuccess") { implicit s =>
    val p = Promise[Either[String, Int]]()
    val t = IO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    p.success(Right(10))
    val f = t.attempt.runToFuture

    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("IO.fromFutureEither(cancelable) should be short-circuited for Left onSuccess") { implicit s =>
    val p = Promise[Either[String, Int]]()
    val t = IO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    p.success(Left("uh-oh"))
    val f = t.attempt.runToFuture

    assertEquals(f.value, Some(Success(Left("uh-oh"))))
  }

  test("IO.fromFutureEither(cancelable) should be short-circuited onError") { implicit s =>
    val dummy = DummyException("dummy")
    val p = Promise[Either[String, Int]]()
    val t = IO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    p.failure(dummy)
    val f = t.attempt.runToFuture

    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.fromFutureEither(cancelable) should work onSuccess") { implicit s =>
    val p = Promise[Either[String, Int]]()
    val t = IO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    val f = t.attempt.runToFuture
    s.tick()
    p.success(Right(10))
    s.tickOne()

    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("IO.fromFutureEither(cancelable) should work onError") { implicit s =>
    val dummy = DummyException("dummy")
    val p = Promise[Either[String, Int]]()
    val t = IO.fromFutureEither(CancelableFuture(p.future, Cancelable.empty))
    val f = t.attempt.runToFuture
    s.tick()
    p.failure(dummy)
    s.tickOne()

    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.fromFutureEither(cancelable) should be cancelable") { implicit s =>
    val source = IO.fromEither[String, Int](Right(10)).delayExecution(1.second)
    val t = IO.fromFutureEither(source.attempt.runToFuture)
    val f = t.attempt.runToFuture
    s.tick()
    assertEquals(f.value, None)
    f.cancel()

    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.fromFutureEither should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 5000
    var result = IO.fromEither[String, Int](Right(10)).attempt.runToFuture
    for (_ <- 0 until count) result = IO.fromFutureEither(result).attempt.flatMap(Task.now).runToFuture

    assertEquals(result.value, Some(Success(Right(10))))
  }
}

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
import monix.execution.internal.Platform
import monix.execution.{Cancelable, CancelableFuture}

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object TaskFromFutureSuite extends BaseTestSuite {
  test("BIO.fromFuture should be faster for completed futures, success") { implicit s =>
    val t = BIO.fromFuture(Future.successful(10))
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(10)))
  }

  test("BIO.fromFuture should be faster for completed futures, failure") { implicit s =>
    val dummy = DummyException("dummy")
    val t = BIO.fromFuture(Future.failed(dummy))
    val f = t.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFuture should work onSuccess") { implicit s =>
    val t = BIO.fromFuture(Future(10))
    val f = t.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(10)))
  }

  test("BIO.fromFuture should work onError") { implicit s =>
    val dummy = DummyException("dummy")
    val t = BIO.fromFuture(Future(throw dummy))
    val f = t.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFuture should be short-circuited onSuccess") { implicit s =>
    val p = Promise[Int]()
    val t = BIO.fromFuture(p.future)
    p.success(10)
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(10)))
  }

  test("BIO.fromFuture should be short-circuited onError") { implicit s =>
    val dummy = DummyException("dummy")
    val p = Promise[Int]()
    val t = BIO.fromFuture(p.future)
    p.failure(dummy)
    val f = t.runToFuture
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.fromFuture(cancelable) should work for synchronous results onSuccess") { implicit s =>
    val t = BIO.fromFuture(CancelableFuture.successful(10))
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(10)))
  }

  test("BIO.fromFuture(cancelable) should work for synchronous results onFailure") { implicit s =>
    val dummy = DummyException("dummy")
    val t = BIO.fromFuture(CancelableFuture.failed(dummy))
    val f = t.attempt.runToFuture
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO.fromFuture(cancelable) should be short-circuited onSuccess") { implicit s =>
    val p = Promise[Int]()
    val t = BIO.fromFuture(CancelableFuture(p.future, Cancelable.empty))
    p.success(10)
    val f = t.runToFuture
    assertEquals(f.value, Some(Success(10)))
  }

  test("BIO.fromFuture(cancelable) should be short-circuited onError") { implicit s =>
    val dummy = DummyException("dummy")
    val p = Promise[Int]()
    val t = BIO.fromFuture(CancelableFuture(p.future, Cancelable.empty))
    p.failure(dummy)
    val f = t.attempt.runToFuture
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO.fromFuture(cancelable) should work onSuccess") { implicit s =>
    val p = Promise[Int]()
    val t = BIO.fromFuture(CancelableFuture(p.future, Cancelable.empty))
    val f = t.runToFuture
    s.tick(); p.success(10); s.tickOne()
    assertEquals(f.value, Some(Success(10)))
  }

  test("BIO.fromFuture(cancelable) should work onError") { implicit s =>
    val dummy = DummyException("dummy")
    val p = Promise[Int]()
    val t = BIO.fromFuture(CancelableFuture(p.future, Cancelable.empty))
    val f = t.attempt.runToFuture
    s.tick()
    p.failure(dummy)
    s.tickOne()
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO.fromFuture(cancelable) should be cancelable") { implicit s =>
    val source = BIO.now(10).delayExecution(1.second)
    val t = BIO.fromFuture(source.runToFuture)
    val f = t.runToFuture; s.tick()
    assertEquals(f.value, None)
    f.cancel()
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("BIO.fromFuture should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 5000
    var result = BIO.evalAsync(1).runToFuture
    for (_ <- 0 until count) result = BIO.fromFuture(result).runToFuture

    assertEquals(result.value, None)
    s.tick()
    assertEquals(result.value, Some(Success(1)))
  }

  test("BIO.now.fromFuture should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 5000
    var result = BIO.now(1).runToFuture
    for (_ <- 0 until count) result = BIO.fromFuture(result).flatMap(BIO.now).runToFuture
    assertEquals(result.value, Some(Success(1)))
  }
}

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

import monix.bio.internal.BiCallback
import monix.execution.exceptions.DummyException
import monix.execution.internal.Platform

import scala.concurrent.Promise
import scala.util.{Failure, Success}
import scala.concurrent.duration._

object TaskMemoizeOnSuccessSuite extends BaseTestSuite {
  test("BIO.memoizeOnSuccess should work asynchronously for first subscriber") { implicit s =>
    var effect = 0
    val task = BIO.evalAsync { effect += 1; effect }.memoizeOnSuccess
      .flatMap(BIO.now)
      .flatMap(BIO.now)

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.memoizeOnSuccess should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = BIO.evalAsync { effect += 1; effect }.memoizeOnSuccess
      .flatMap(BIO.now)
      .flatMap(BIO.now)

    task.runToFuture
    s.tick()

    val f1 = task.runToFuture
    assertEquals(f1.value, Some(Success(Right(1))))
    val f2 = task.runToFuture
    assertEquals(f2.value, Some(Success(Right(1))))
  }

  test("BIO.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = BIO.evalAsync(1)
    for (i <- 0 until count) task = task.memoizeOnSuccess

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.flatMap.memoizeOnSuccess should be stack safe, test 1") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = BIO.evalAsync(1)

    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.flatMap(x => BIO.now(x))
    }

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.flatMap.memoizeOnSuccess should be stack safe, test 2") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = BIO.evalAsync(1)
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.flatMap(x => BIO.evalAsync(x))
    }

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.raiseError(error).memoizeOnSuccess should not be idempotent") { implicit s =>
    var effect = 0
    val dummy = "dummy"
    val task = BIO
      .suspendTotal(BIO.raiseError { effect += 1; dummy })
      .memoizeOnSuccess
      .flatMap(BIO.now[Int])
      .flatMap(BIO.now[Int])

    val f1 = task.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)

    val f2 = task.runToFuture; s.tick()
    assertEquals(f2.value, Some(Success(Left(dummy))))
    assertEquals(effect, 2)
  }

  test("BIO.terminate(error).memoizeOnSuccess should not be idempotent") { implicit s =>
    var effect = 0
    val dummy = DummyException("dummy")
    val task = BIO
      .suspendTotal(BIO.terminate { effect += 1; dummy })
      .memoizeOnSuccess
      .flatMap(BIO.now[Int])
      .flatMap(BIO.now[Int])

    val f1 = task.runToFuture; s.tick()
    assertEquals(f1.value, Some(Failure(dummy)))
    assertEquals(effect, 1)

    val f2 = task.runToFuture; s.tick()
    assertEquals(f2.value, Some(Failure(dummy)))
    assertEquals(effect, 2)
  }

  test("BIO.memoizeOnSuccess.materialize") { implicit s =>
    val f = BIO.evalAsync(10).memoizeOnSuccess.materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(Success(Right(10))))))
  }

  test("BIO.raiseError(error).memoizeOnSuccess.materialize") { implicit s =>
    val dummy = "dummy"
    val f = BIO.raiseError(dummy).memoizeOnSuccess.materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(Success(Left(dummy))))))
  }

  test("BIO.terminate(error).memoizeOnSuccess.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = BIO.terminate(dummy).memoizeOnSuccess.materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(Failure(dummy)))))
  }

  test("BIO.eval.memoizeOnSuccess should work for first subscriber") { implicit s =>
    var effect = 0
    val task = BIO.eval { effect += 1; effect }.memoizeOnSuccess

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.eval.memoizeOnSuccess should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = BIO.eval { effect += 1; effect }.memoizeOnSuccess
    task.runToFuture
    s.tick()

    val f1 = task.runToFuture
    assertEquals(f1.value, Some(Success(Right(1))))
    val f2 = task.runToFuture
    assertEquals(f2.value, Some(Success(Right(1))))
  }

  test("BIO.eval(error).memoizeOnSuccess should not be idempotent") { implicit s =>
    var effect = 0
    val dummy = DummyException("dummy")
    val task = BIO.eval[Int] { effect += 1; throw dummy }.memoizeOnSuccess

    val f1 = task.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)

    val f2 = task.runToFuture; s.tick()
    assertEquals(f2.value, Some(Success(Left(dummy))))
    assertEquals(effect, 2)
  }

  test("BIO.eval.memoizeOnSuccess") { implicit s =>
    var effect = 0
    val task = BIO.eval { effect += 1; effect }.memoizeOnSuccess

    val r1 = task.runToFuture
    val r2 = task.runToFuture
    val r3 = task.runToFuture

    s.tickOne()
    assertEquals(r1.value, Some(Success(Right(1))))
    assertEquals(r2.value, Some(Success(Right(1))))
    assertEquals(r3.value, Some(Success(Right(1))))
  }

  test("BIO.eval.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = BIO.eval(1)
    for (i <- 0 until count)
      task = task.memoizeOnSuccess

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.eval.flatMap.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = BIO.eval(1)
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.flatMap(x => BIO.eval(x))
    }

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.defer(evalAlways).memoizeOnSuccess") { implicit s =>
    var effect = 0
    val task = BIO.defer(BIO.eval { effect += 1; effect }).memoizeOnSuccess

    val r1 = task.runToFuture
    val r2 = task.runToFuture
    val r3 = task.runToFuture

    s.tick()
    assertEquals(r1.value, Some(Success(Right(1))))
    assertEquals(r2.value, Some(Success(Right(1))))
    assertEquals(r3.value, Some(Success(Right(1))))
  }

  test("BIO.now.memoizeOnSuccess should work synchronously for first subscriber") { implicit s =>
    var effect = 0
    val task = BIO.now { effect += 1; effect }.memoizeOnSuccess

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.now.memoizeOnSuccess should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = BIO.now { effect += 1; effect }.memoizeOnSuccess

    task.runToFuture
    s.tick()

    val f1 = task.runToFuture
    assertEquals(f1.value, Some(Success(Right(1))))
    val f2 = task.runToFuture
    assertEquals(f2.value, Some(Success(Right(1))))
  }

  test("BIO.raiseError.memoizeOnSuccess should work") { implicit s =>
    val dummy = "dummy"
    val task = BIO.raiseError(dummy).memoizeOnSuccess

    val f1 = task.runToFuture
    assertEquals(f1.value, Some(Success(Left(dummy))))
    val f2 = task.runToFuture
    assertEquals(f2.value, Some(Success(Left(dummy))))
  }

  test("BIO.terminate.memoizeOnSuccess should work") { implicit s =>
    val dummy = DummyException("dummy")
    val task = BIO.terminate(dummy).memoizeOnSuccess

    val f1 = task.runToFuture
    assertEquals(f1.value, Some(Failure(dummy)))
    val f2 = task.runToFuture
    assertEquals(f2.value, Some(Failure(dummy)))
  }

  test("BIO.now.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = BIO.now(1)
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess
    }

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.now.flatMap.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = BIO.now(1)
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.flatMap(x => BIO.now(x))
    }

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.suspend.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = BIO.defer(BIO.now(1))
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.map(x => x)
    }

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.memoizeOnSuccess effects, sequential") { implicit s =>
    var effect = 0
    val task1 = BIO.evalAsync { effect += 1; 3 }.memoizeOnSuccess
    val task2 = task1.map { x =>
      effect += 1; x + 1
    }

    val result1 = task2.runToFuture; s.tick()
    assertEquals(effect, 2)
    assertEquals(result1.value, Some(Success(Right(4))))

    val result2 = task2.runToFuture; s.tick()
    assertEquals(effect, 3)
    assertEquals(result2.value, Some(Success(Right(4))))
  }

  test("BIO.memoizeOnSuccess effects, parallel") { implicit s =>
    var effect = 0
    val task1 = BIO.evalAsync { effect += 1; 3 }.memoizeOnSuccess
    val task2 = task1.map { x =>
      effect += 1; x + 1
    }

    val result1 = task2.runToFuture
    val result2 = task2.runToFuture

    assertEquals(result1.value, None)
    assertEquals(result2.value, None)

    s.tick()
    assertEquals(effect, 3)
    assertEquals(result1.value, Some(Success(Right(4))))
    assertEquals(result2.value, Some(Success(Right(4))))
  }

  test("BIO.suspend.memoizeOnSuccess effects") { implicit s =>
    var effect = 0
    val task1 = BIO.defer { effect += 1; BIO.now(3) }.memoizeOnSuccess
    val task2 = task1.map { x =>
      effect += 1; x + 1
    }

    val result1 = task2.runToFuture; s.tick()
    assertEquals(effect, 2)
    assertEquals(result1.value, Some(Success(Right(4))))

    val result2 = task2.runToFuture; s.tick()
    assertEquals(effect, 3)
    assertEquals(result2.value, Some(Success(Right(4))))
  }

  test("BIO.suspend.flatMap.memoizeOnSuccess effects") { implicit s =>
    var effect = 0
    val task1 = BIO.defer { effect += 1; BIO.now(2) }
      .flatMap(x => BIO.now(x + 1))
      .memoizeOnSuccess
    val task2 = task1.map { x =>
      effect += 1; x + 1
    }

    val result1 = task2.runToFuture; s.tick()
    assertEquals(effect, 2)
    assertEquals(result1.value, Some(Success(Right(4))))

    val result2 = task2.runToFuture; s.tick()
    assertEquals(effect, 3)
    assertEquals(result2.value, Some(Success(Right(4))))

    val result3 = task2.runToFuture; s.tick()
    assertEquals(effect, 4)
    assertEquals(result3.value, Some(Success(Right(4))))
  }

  test("BIO.memoizeOnSuccess should make subsequent subscribers wait for the result, as future") { implicit s =>
    var effect = 0
    val task = BIO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = task.runToFuture
    s.tick()
    assertEquals(first.value, None)

    val second = task.runToFuture
    val third = task.runToFuture

    s.tick()
    assertEquals(second.value, None)
    assertEquals(third.value, None)

    s.tick(1.second)
    assertEquals(first.value, Some(Success(Right(2))))
    assertEquals(second.value, Some(Success(Right(2))))
    assertEquals(third.value, Some(Success(Right(2))))
  }

  test("BIO.memoizeOnSuccess should make subsequent subscribers wait for the result, as callback") { implicit s =>
    var effect = 0
    val task = BIO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = Promise[Either[Cause[Throwable], Int]]()
    task.runAsync(BiCallback.fromPromise(first))

    s.tick()
    assertEquals(first.future.value, None)

    val second = Promise[Either[Cause[Throwable], Int]]()
    task.runAsync(BiCallback.fromPromise(second))
    val third = Promise[Either[Cause[Throwable], Int]]()
    task.runAsync(BiCallback.fromPromise(third))

    s.tick()
    assertEquals(second.future.value, None)
    assertEquals(third.future.value, None)

    s.tick(1.second)
    assertEquals(first.future.value, Some(Success(Right(2))))
    assertEquals(second.future.value, Some(Success(Right(2))))
    assertEquals(third.future.value, Some(Success(Right(2))))
  }

  test("BIO.memoizeOnSuccess should be synchronous for subsequent subscribers, as callback") { implicit s =>
    var effect = 0
    val task = BIO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = Promise[Either[Cause[Throwable], Int]]()
    task.runAsync(BiCallback.fromPromise(first))

    s.tick()
    assertEquals(first.future.value, None)

    s.tick(1.second)
    assertEquals(first.future.value, Some(Success(Right(2))))

    val second = Promise[Either[Cause[Throwable], Int]]()
    task.runAsync(BiCallback.fromPromise(second))
    val third = Promise[Either[Cause[Throwable], Int]]()
    task.runAsync(BiCallback.fromPromise(third))
    assertEquals(second.future.value, Some(Success(Right(2))))
    assertEquals(third.future.value, Some(Success(Right(2))))
  }

  test("BIO.memoizeOnSuccess should be cancellable (future)") { implicit s =>
    var effect = 0
    val task = BIO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = task.runToFuture
    s.tick()
    assertEquals(first.value, None)

    val second = task.runToFuture
    val third = task.runToFuture

    s.tick()
    assertEquals(second.value, None)
    assertEquals(third.value, None)

    third.cancel()
    s.tick()
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")

    s.tick(1.second)
    assertEquals(first.value, Some(Success(Right(2))))
    assertEquals(second.value, Some(Success(Right(2))))
    assertEquals(third.value, None)
    assertEquals(effect, 1)
  }

  test("BIO.memoizeOnSuccess should be cancellable (callback #1)") { implicit s =>
    var effect = 0
    val task = BIO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = Promise[Either[Cause[Throwable], Int]]()
    task.runAsync(BiCallback.fromPromise(first))

    s.tick()
    assertEquals(first.future.value, None)

    val second = Promise[Either[Cause[Throwable], Int]]()
    val c2 = task.runAsync(BiCallback.fromPromise(second))
    val third = Promise[Either[Cause[Throwable], Int]]()
    val c3 = task.runAsync(BiCallback.fromPromise(third))

    s.tick()
    assertEquals(second.future.value, None)
    assertEquals(third.future.value, None)

    c3.cancel()
    s.tick()
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")

    s.tick(1.second)
    assertEquals(first.future.value, Some(Success(Right(2))))
    assertEquals(second.future.value, Some(Success(Right(2))))
    assertEquals(third.future.value, None)
    assertEquals(effect, 1)
  }

  test("BIO.memoizeOnSuccess should be cancellable (callback #2)") { implicit s =>
    var effect = 0
    val task = BIO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess.map(x => x)

    val first = Promise[Either[Cause[Throwable], Int]]()
    task.runAsync(BiCallback.fromPromise(first))

    s.tick()
    assertEquals(first.future.value, None)

    val second = Promise[Either[Cause[Throwable], Int]]()
    task.runAsync(BiCallback.fromPromise(second))
    val third = Promise[Either[Cause[Throwable], Int]]()
    val c3 = task.runAsync(BiCallback.fromPromise(third))

    s.tick()
    assertEquals(second.future.value, None)
    assertEquals(third.future.value, None)

    c3.cancel()
    s.tick()
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")

    s.tick(1.second)
    assertEquals(first.future.value, Some(Success(Right(2))))
    assertEquals(second.future.value, Some(Success(Right(2))))
    assertEquals(third.future.value, None)
    assertEquals(effect, 1)
  }

  test("BIO.memoizeOnSuccess should not be cancelable") { implicit s =>
    var effect = 0
    val task = BIO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess
    val first = task.runToFuture
    val second = task.runToFuture

    s.tick()
    assertEquals(first.value, None)
    assertEquals(second.value, None)
    first.cancel()

    s.tick()
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")
    assertEquals(first.value, None)
    assertEquals(second.value, None)
    assertEquals(effect, 0)

    // -- Second wave:
    val third = task.runToFuture
    val fourth = task.runToFuture

    s.tick(1.second)
    assertEquals(first.value, None)
    assertEquals(second.value, Some(Success(Right(2))))
    assertEquals(third.value, Some(Success(Right(2))))
    assertEquals(fourth.value, Some(Success(Right(2))))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("BIO.evalAsync(error).memoizeOnSuccess can register multiple listeners") { implicit s =>
    val dummy = DummyException("dummy")
    var effect = 0

    val task = BIO[Int] { effect += 1; throw dummy }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = task.runToFuture
    s.tick()
    assertEquals(first.value, None)
    assertEquals(effect, 0)

    val second = task.runToFuture
    val third = task.runToFuture

    s.tick()
    assertEquals(second.value, None)
    assertEquals(third.value, None)

    s.tick(1.second)
    assertEquals(first.value, Some(Success(Left(dummy))))
    assertEquals(second.value, Some(Success(Left(dummy))))
    assertEquals(third.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)

    val fourth = task.runToFuture
    val fifth = task.runToFuture
    s.tick()
    assertEquals(fourth.value, None)
    assertEquals(fifth.value, None)

    s.tick(1.second)
    assertEquals(fourth.value, Some(Success(Left(dummy))))
    assertEquals(fifth.value, Some(Success(Left(dummy))))
    assertEquals(effect, 2)
  }

  test("BIO.eval.memoizeOnSuccess eq Task.eval.memoizeOnSuccess.memoizeOnSuccess") { implicit s =>
    val task = BIO.eval(1).memoizeOnSuccess
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("BIO.eval.memoize eq Task.eval.memoize.memoizeOnSuccess") { implicit s =>
    val task = BIO.eval(1).memoize
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("BIO.eval.map.memoize eq Task.eval.map.memoize.memoizeOnSuccess") { implicit s =>
    val task = BIO.eval(1).map(_ + 1).memoize
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("BIO.now.memoizeOnSuccess eq Task.now") { implicit s =>
    val task = BIO.now(1)
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("BIO.raiseError.memoizeOnSuccess eq Task.raiseError") { implicit s =>
    val task = BIO.raiseError("dummy")
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("BIO.terminate.memoizeOnSuccess eq Task.raiseError") { implicit s =>
    val task = BIO.terminate(DummyException("dummy"))
    assertEquals(task, task.memoizeOnSuccess)
  }
}

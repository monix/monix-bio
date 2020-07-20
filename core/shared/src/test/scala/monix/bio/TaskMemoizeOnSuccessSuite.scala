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

import scala.concurrent.Promise
import scala.util.{Failure, Success}
import scala.concurrent.duration._

object TaskMemoizeOnSuccessSuite extends BaseTestSuite {
  test("IO.memoizeOnSuccess should work asynchronously for first subscriber") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.memoizeOnSuccess
      .flatMap(IO.now)
      .flatMap(IO.now)

    val f = task.attempt.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.memoizeOnSuccess should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.memoizeOnSuccess
      .flatMap(IO.now)
      .flatMap(IO.now)

    task.attempt.runToFuture
    s.tick()

    val f1 = task.attempt.runToFuture
    assertEquals(f1.value, Some(Success(Right(1))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Right(1))))
  }

  test("IO.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.evalAsync(1)
    for (i <- 0 until count) task = task.memoizeOnSuccess

    val f = task.attempt.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.flatMap.memoizeOnSuccess should be stack safe, test 1") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.evalAsync(1)

    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.flatMap(x => IO.now(x))
    }

    val f = task.attempt.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.flatMap.memoizeOnSuccess should be stack safe, test 2") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.evalAsync(1)
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.flatMap(x => IO.evalAsync(x))
    }

    val f = task.attempt.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.raiseError(error).memoizeOnSuccess should not be idempotent") { implicit s =>
    var effect = 0
    val dummy = "dummy"
    val task = IO
      .suspendTotal(IO.raiseError { effect += 1; dummy })
      .memoizeOnSuccess
      .flatMap(IO.now[Int])
      .flatMap(IO.now[Int])

    val f1 = task.attempt.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)

    val f2 = task.attempt.runToFuture; s.tick()
    assertEquals(f2.value, Some(Success(Left(dummy))))
    assertEquals(effect, 2)
  }

  test("IO.terminate(error).memoizeOnSuccess should not be idempotent") { implicit s =>
    var effect = 0
    val dummy = DummyException("dummy")
    val task = IO
      .suspendTotal(IO.terminate { effect += 1; dummy })
      .memoizeOnSuccess
      .flatMap(IO.now[Int])
      .flatMap(IO.now[Int])

    val f1 = task.runToFuture; s.tick()
    assertEquals(f1.value, Some(Failure(dummy)))
    assertEquals(effect, 1)

    val f2 = task.runToFuture; s.tick()
    assertEquals(f2.value, Some(Failure(dummy)))
    assertEquals(effect, 2)
  }

  test("IO.memoizeOnSuccess.materialize") { implicit s =>
    val f = IO.evalAsync(10).memoizeOnSuccess.materialize.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(Success(10)))))
  }

  test("IO.raiseError(error).memoizeOnSuccess.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = IO.raiseError(dummy).memoizeOnSuccess.materialize.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(Failure(dummy)))))
  }

  test("IO.terminate(error).memoizeOnSuccess.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = IO.terminate(dummy).memoizeOnSuccess.materialize.attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.eval.memoizeOnSuccess should work for first subscriber") { implicit s =>
    var effect = 0
    val task = IO.eval { effect += 1; effect }.memoizeOnSuccess

    val f = task.attempt.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.eval.memoizeOnSuccess should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = IO.eval { effect += 1; effect }.memoizeOnSuccess
    task.attempt.runToFuture
    s.tick()

    val f1 = task.attempt.runToFuture
    assertEquals(f1.value, Some(Success(Right(1))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Right(1))))
  }

  test("IO.eval(error).memoizeOnSuccess should not be idempotent") { implicit s =>
    var effect = 0
    val dummy = DummyException("dummy")
    val task = IO.eval[Int] { effect += 1; throw dummy }.memoizeOnSuccess

    val f1 = task.attempt.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)

    val f2 = task.attempt.runToFuture; s.tick()
    assertEquals(f2.value, Some(Success(Left(dummy))))
    assertEquals(effect, 2)
  }

  test("IO.eval.memoizeOnSuccess") { implicit s =>
    var effect = 0
    val task = IO.eval { effect += 1; effect }.memoizeOnSuccess

    val r1 = task.attempt.runToFuture
    val r2 = task.attempt.runToFuture
    val r3 = task.attempt.runToFuture

    s.tickOne()
    assertEquals(r1.value, Some(Success(Right(1))))
    assertEquals(r2.value, Some(Success(Right(1))))
    assertEquals(r3.value, Some(Success(Right(1))))
  }

  test("IO.eval.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.eval(1)
    for (i <- 0 until count)
      task = task.memoizeOnSuccess

    val f = task.attempt.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.eval.flatMap.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.eval(1)
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.flatMap(x => IO.eval(x))
    }

    val f = task.attempt.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.defer(evalAlways).memoizeOnSuccess") { implicit s =>
    var effect = 0
    val task = IO.defer(IO.eval { effect += 1; effect }).memoizeOnSuccess

    val r1 = task.attempt.runToFuture
    val r2 = task.attempt.runToFuture
    val r3 = task.attempt.runToFuture

    s.tick()
    assertEquals(r1.value, Some(Success(Right(1))))
    assertEquals(r2.value, Some(Success(Right(1))))
    assertEquals(r3.value, Some(Success(Right(1))))
  }

  test("IO.evalOnce.memoizeOnSuccess should work for first subscriber") { implicit s =>
    var effect = 0
    val task = IO.evalOnce { effect += 1; effect }.memoizeOnSuccess

    val f = task.attempt.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.evalOnce.memoizeOnSuccess should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = IO.evalOnce { effect += 1; effect }.memoizeOnSuccess
    task.attempt.runToFuture
    s.tick()

    val f1 = task.attempt.runToFuture
    assertEquals(f1.value, Some(Success(Right(1))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Right(1))))
  }

  test("IO.evalOnce(error).memoizeOnSuccess should work") { implicit s =>
    var effect = 0
    val dummy = DummyException("dummy")
    val task = IO.evalOnce[Int] { effect += 1; throw dummy }.memoizeOnSuccess

    val f1 = task.attempt.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)

    val f2 = task.attempt.runToFuture; s.tick()
    assertEquals(f2.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)
  }

  test("IO.evalOnce.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.eval(1)
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess
    }

    val f = task.attempt.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.evalOnce.flatMap.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.eval(1)
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.flatMap(x => IO.evalOnce(x))
    }

    val f = task.attempt.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.now.memoizeOnSuccess should work synchronously for first subscriber") { implicit s =>
    var effect = 0
    val task = IO.now { effect += 1; effect }.memoizeOnSuccess

    val f = task.attempt.runToFuture
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.now.memoizeOnSuccess should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = IO.now { effect += 1; effect }.memoizeOnSuccess

    task.attempt.runToFuture
    s.tick()

    val f1 = task.attempt.runToFuture
    assertEquals(f1.value, Some(Success(Right(1))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Right(1))))
  }

  test("IO.raiseError.memoizeOnSuccess should work") { implicit s =>
    val dummy = "dummy"
    val task = IO.raiseError(dummy).memoizeOnSuccess

    val f1 = task.attempt.runToFuture
    assertEquals(f1.value, Some(Success(Left(dummy))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Left(dummy))))
  }

  test("IO.terminate.memoizeOnSuccess should work") { implicit s =>
    val dummy = DummyException("dummy")
    val task = IO.terminate(dummy).memoizeOnSuccess

    val f1 = task.runToFuture
    assertEquals(f1.value, Some(Failure(dummy)))
    val f2 = task.runToFuture
    assertEquals(f2.value, Some(Failure(dummy)))
  }

  test("IO.now.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.now(1)
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess
    }

    val f = task.attempt.runToFuture
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.now.flatMap.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.now(1)
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.flatMap(x => IO.now(x))
    }

    val f = task.attempt.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.suspend.memoizeOnSuccess should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.defer(IO.now(1))
    for (i <- 0 until count) {
      task = task.memoizeOnSuccess.map(x => x)
    }

    val f = task.attempt.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.memoizeOnSuccess effects, sequential") { implicit s =>
    var effect = 0
    val task1 = IO.evalAsync { effect += 1; 3 }.memoizeOnSuccess
    val task2 = task1.map { x =>
      effect += 1; x + 1
    }

    val result1 = task2.attempt.runToFuture; s.tick()
    assertEquals(effect, 2)
    assertEquals(result1.value, Some(Success(Right(4))))

    val result2 = task2.attempt.runToFuture; s.tick()
    assertEquals(effect, 3)
    assertEquals(result2.value, Some(Success(Right(4))))
  }

  test("IO.memoizeOnSuccess effects, parallel") { implicit s =>
    var effect = 0
    val task1 = IO.evalAsync { effect += 1; 3 }.memoizeOnSuccess
    val task2 = task1.map { x =>
      effect += 1; x + 1
    }

    val result1 = task2.attempt.runToFuture
    val result2 = task2.attempt.runToFuture

    assertEquals(result1.value, None)
    assertEquals(result2.value, None)

    s.tick()
    assertEquals(effect, 3)
    assertEquals(result1.value, Some(Success(Right(4))))
    assertEquals(result2.value, Some(Success(Right(4))))
  }

  test("IO.suspend.memoizeOnSuccess effects") { implicit s =>
    var effect = 0
    val task1 = IO.defer { effect += 1; IO.now(3) }.memoizeOnSuccess
    val task2 = task1.map { x =>
      effect += 1; x + 1
    }

    val result1 = task2.attempt.runToFuture; s.tick()
    assertEquals(effect, 2)
    assertEquals(result1.value, Some(Success(Right(4))))

    val result2 = task2.attempt.runToFuture; s.tick()
    assertEquals(effect, 3)
    assertEquals(result2.value, Some(Success(Right(4))))
  }

  test("IO.suspend.flatMap.memoizeOnSuccess effects") { implicit s =>
    var effect = 0
    val task1 = IO.defer { effect += 1; IO.now(2) }
      .flatMap(x => IO.now(x + 1))
      .memoizeOnSuccess
    val task2 = task1.map { x =>
      effect += 1; x + 1
    }

    val result1 = task2.attempt.runToFuture; s.tick()
    assertEquals(effect, 2)
    assertEquals(result1.value, Some(Success(Right(4))))

    val result2 = task2.attempt.runToFuture; s.tick()
    assertEquals(effect, 3)
    assertEquals(result2.value, Some(Success(Right(4))))

    val result3 = task2.attempt.runToFuture; s.tick()
    assertEquals(effect, 4)
    assertEquals(result3.value, Some(Success(Right(4))))
  }

  test("IO.memoizeOnSuccess should make subsequent subscribers wait for the result, as future") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = task.attempt.runToFuture
    s.tick()
    assertEquals(first.value, None)

    val second = task.attempt.runToFuture
    val third = task.attempt.runToFuture

    s.tick()
    assertEquals(second.value, None)
    assertEquals(third.value, None)

    s.tick(1.second)
    assertEquals(first.value, Some(Success(Right(2))))
    assertEquals(second.value, Some(Success(Right(2))))
    assertEquals(third.value, Some(Success(Right(2))))
  }

  test("IO.memoizeOnSuccess should make subsequent subscribers wait for the result, as callback") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(first))

    s.tick()
    assertEquals(first.future.value, None)

    val second = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(second))
    val third = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(third))

    s.tick()
    assertEquals(second.future.value, None)
    assertEquals(third.future.value, None)

    s.tick(1.second)
    assertEquals(first.future.value, Some(Success(Right(2))))
    assertEquals(second.future.value, Some(Success(Right(2))))
    assertEquals(third.future.value, Some(Success(Right(2))))
  }

  test("IO.memoizeOnSuccess should be synchronous for subsequent subscribers, as callback") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(first))

    s.tick()
    assertEquals(first.future.value, None)

    s.tick(1.second)
    assertEquals(first.future.value, Some(Success(Right(2))))

    val second = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(second))
    val third = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(third))
    assertEquals(second.future.value, Some(Success(Right(2))))
    assertEquals(third.future.value, Some(Success(Right(2))))
  }

  test("IO.memoizeOnSuccess should be cancellable (future)") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = task.attempt.runToFuture
    s.tick()
    assertEquals(first.value, None)

    val second = task.attempt.runToFuture
    val third = task.attempt.runToFuture

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

  test("IO.memoizeOnSuccess should be cancellable (callback #1)") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(first))

    s.tick()
    assertEquals(first.future.value, None)

    val second = Promise[Either[Throwable, Int]]()
    val c2 = task.runAsync(BiCallback.fromPromise(second))
    val third = Promise[Either[Throwable, Int]]()
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

  test("IO.memoizeOnSuccess should be cancellable (callback #2)") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess.map(x => x)

    val first = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(first))

    s.tick()
    assertEquals(first.future.value, None)

    val second = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(second))
    val third = Promise[Either[Throwable, Int]]()
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

  test("IO.memoizeOnSuccess should not be cancelable") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess
    val first = task.attempt.runToFuture
    val second = task.attempt.runToFuture

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
    val third = task.attempt.runToFuture
    val fourth = task.attempt.runToFuture

    s.tick(1.second)
    assertEquals(first.value, None)
    assertEquals(second.value, Some(Success(Right(2))))
    assertEquals(third.value, Some(Success(Right(2))))
    assertEquals(fourth.value, Some(Success(Right(2))))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.evalAsync(error).memoizeOnSuccess can register multiple listeners") { implicit s =>
    val dummy = DummyException("dummy")
    var effect = 0

    val task = IO[Int] { effect += 1; throw dummy }.delayExecution(1.second).map(_ + 1).memoizeOnSuccess

    val first = task.attempt.runToFuture
    s.tick()
    assertEquals(first.value, None)
    assertEquals(effect, 0)

    val second = task.attempt.runToFuture
    val third = task.attempt.runToFuture

    s.tick()
    assertEquals(second.value, None)
    assertEquals(third.value, None)

    s.tick(1.second)
    assertEquals(first.value, Some(Success(Left(dummy))))
    assertEquals(second.value, Some(Success(Left(dummy))))
    assertEquals(third.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)

    val fourth = task.attempt.runToFuture
    val fifth = task.attempt.runToFuture
    s.tick()
    assertEquals(fourth.value, None)
    assertEquals(fifth.value, None)

    s.tick(1.second)
    assertEquals(fourth.value, Some(Success(Left(dummy))))
    assertEquals(fifth.value, Some(Success(Left(dummy))))
    assertEquals(effect, 2)
  }

  test("IO.evalOnce eq IO.evalOnce.memoizeOnSuccess") { implicit s =>
    val task = IO.evalOnce(1)
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("IO.eval.memoizeOnSuccess eq IO.eval.memoizeOnSuccess.memoizeOnSuccess") { implicit s =>
    val task = IO.eval(1).memoizeOnSuccess
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("IO.eval.memoize eq IO.eval.memoize.memoizeOnSuccess") { implicit s =>
    val task = IO.eval(1).memoize
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("IO.eval.map.memoize eq IO.eval.map.memoize.memoizeOnSuccess") { implicit s =>
    val task = IO.eval(1).map(_ + 1).memoize
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("IO.now.memoizeOnSuccess eq IO.now") { implicit s =>
    val task = IO.now(1)
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("IO.raiseError.memoizeOnSuccess eq IO.raiseError") { implicit s =>
    val task = IO.raiseError("dummy")
    assertEquals(task, task.memoizeOnSuccess)
  }

  test("IO.terminate.memoizeOnSuccess eq IO.terminate") { implicit s =>
    val task = IO.terminate(DummyException("dummy"))
    assertEquals(task, task.memoizeOnSuccess)
  }
}

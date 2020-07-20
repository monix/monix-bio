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

object TaskMemoizeSuite extends BaseTestSuite {
  test("IO.memoize should work asynchronously for first subscriber") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.memoize
      .flatMap(Task.now)
      .flatMap(Task.now)

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.memoize should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.memoize
      .flatMap(Task.now)
      .flatMap(Task.now)

    task.runToFuture
    s.tick()

    val f1 = task.runToFuture
    assertEquals(f1.value, Some(Success(1)))
    val f2 = task.runToFuture
    assertEquals(f2.value, Some(Success(1)))
  }

  test("IO.memoize should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.evalAsync(1)
    for (_ <- 0 until count) task = task.memoize

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.flatMap.memoize should be stack safe, test 1") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.evalAsync(1)
    for (_ <- 0 until count) task = task.memoize.flatMap(x => Task.now(x))

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.flatMap.memoize should be stack safe, test 2") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.evalAsync(1)
    for (_ <- 0 until count) task = task.memoize.flatMap(x => Task.evalAsync(x))

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.raiseError(error).memoize should work") { implicit s =>
    var effect = 0
    val dummy = "dummy"
    val task = IO.raiseError { effect += 1; dummy }.memoize
      .flatMap(IO.now[Int])
      .flatMap(IO.now[Int])

    val f1 = task.attempt.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(Left(dummy))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)
  }

  test("IO.terminate(error).memoize should work") { implicit s =>
    var effect = 0
    val dummy = DummyException("dummy")
    val task = IO.terminate { effect += 1; dummy }.memoize
      .flatMap(IO.now[Int])
      .flatMap(IO.now[Int])

    val f1 = task.runToFuture; s.tick()
    assertEquals(f1.value, Some(Failure(dummy)))
    val f2 = task.runToFuture
    assertEquals(f2.value, Some(Failure(dummy)))
    assertEquals(effect, 1)
  }

  test("IO.memoize.materialize") { implicit s =>
    val f = IO.evalAsync(10).memoize.materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Success(10))))
  }

  test("IO.apply(error).memoize.materialize") { implicit s =>
    val dummy = DummyException("dummy")
    val f = IO[Int](throw dummy).memoize.materialize.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Failure(dummy))))
  }

  test("IO.eval.memoize should work for first subscriber") { implicit s =>
    var effect = 0
    val task = IO.eval { effect += 1; effect }.memoize

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.eval.memoize should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = IO.eval { effect += 1; effect }.memoize
    task.runToFuture
    s.tick()

    val f1 = task.runToFuture
    assertEquals(f1.value, Some(Success(1)))
    val f2 = task.runToFuture
    assertEquals(f2.value, Some(Success(1)))
  }

  test("IO.eval(error).memoize should work") { implicit s =>
    var effect = 0
    val dummy = DummyException("dummy")
    val task = IO.eval[Int] { effect += 1; throw dummy }.memoize

    val f1 = task.attempt.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(Left(dummy))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)
  }

  test("IO.eval.memoize") { implicit s =>
    var effect = 0
    val task = IO.eval { effect += 1; effect }.memoize

    val r1 = task.runToFuture
    val r2 = task.runToFuture
    val r3 = task.runToFuture

    s.tickOne()
    assertEquals(r1.value, Some(Success(1)))
    assertEquals(r2.value, Some(Success(1)))
    assertEquals(r3.value, Some(Success(1)))
  }

  test("IO.eval.memoize should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.eval(1)
    for (_ <- 0 until count) task = task.memoize

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.eval.flatMap.memoize should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.eval(1)
    for (_ <- 0 until count) task = task.memoize.flatMap(x => Task.eval(x))

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.defer(evalAlways).memoize") { implicit s =>
    var effect = 0
    val task = IO.defer(IO.eval { effect += 1; effect }).memoize

    val r1 = task.runToFuture
    val r2 = task.runToFuture
    val r3 = task.runToFuture

    s.tick()
    assertEquals(r1.value, Some(Success(1)))
    assertEquals(r2.value, Some(Success(1)))
    assertEquals(r3.value, Some(Success(1)))
  }

  test("IO.evalOnce.memoize should work for first subscriber") { implicit s =>
    var effect = 0
    val task = IO.evalOnce { effect += 1; effect }.memoize

    val f = task.attempt.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.evalOnce.memoize should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = IO.evalOnce { effect += 1; effect }.memoize
    task.runToFuture
    s.tick()

    val f1 = task.attempt.runToFuture
    assertEquals(f1.value, Some(Success(Right(1))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Right(1))))
  }

  test("IO.evalOnce(error).memoize should work") { implicit s =>
    var effect = 0
    val dummy = DummyException("dummy")
    val task = IO.evalOnce[Int] { effect += 1; throw dummy }.memoize

    val f1 = task.attempt.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(Left(dummy))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)
  }

  test("IO.evalOnce.memoize should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.eval(1)
    for (_ <- 0 until count) task = task.memoize

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.evalOnce.flatMap.memoize should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.eval(1)
    for (_ <- 0 until count) task = task.memoize.flatMap(x => IO.evalOnce(x))

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.now.memoize should work synchronously for first subscriber") { implicit s =>
    var effect = 0
    val task = IO.now { effect += 1; effect }.memoize

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.now.memoize should work synchronously for next subscribers") { implicit s =>
    var effect = 0
    val task = IO.now { effect += 1; effect }.memoize
    task.attempt.runToFuture
    s.tick()

    val f1 = task.attempt.runToFuture
    assertEquals(f1.value, Some(Success(Right(1))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Right(1))))
  }

  test("IO.error.memoize should work") { implicit s =>
    val dummy = DummyException("dummy")
    val task = IO.raiseError(dummy).memoize

    val f1 = task.attempt.runToFuture
    assertEquals(f1.value, Some(Success(Left(dummy))))
    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Left(dummy))))
  }

  test("IO.now.memoize should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.now(1)
    for (_ <- 0 until count) task = task.memoize

    val f = task.runToFuture
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.now.flatMap.memoize should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.now(1)
    for (_ <- 0 until count) task = task.memoize.flatMap(x => IO.now(x))

    val f = task.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.defer.memoize should be stack safe") { implicit s =>
    val count = if (Platform.isJVM) 50000 else 5000
    var task = IO.defer(Task.now(1))
    for (_ <- 0 until count) task = task.memoize.map(x => x)

    val f = task.runToFuture; s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.memoize effects, sequential") { implicit s =>
    var effect = 0
    val task1 = IO.evalAsync { effect += 1; 3 }.memoize
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

  test("IO.memoize effects, parallel") { implicit s =>
    var effect = 0
    val task1 = IO.evalAsync { effect += 1; 3 }.memoize
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

  test("IO.suspend.memoize effects") { implicit s =>
    var effect = 0
    val task1 = IO.defer { effect += 1; IO.now(3) }.memoize
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

  test("IO.suspend.flatMap.memoize effects") { implicit s =>
    var effect = 0
    val task1 = IO.defer { effect += 1; IO.now(2) }
      .flatMap(x => IO.now(x + 1))
      .memoize
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

  test("IO.memoize should make subsequent subscribers wait for the result, as future") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoize
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

  test("IO.memoize should make subsequent subscribers wait for the result, as callback") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoize
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

  test("Task.memoize should be synchronous for subsequent subscribers, as callback") { implicit s =>
    var effect = 0
    val task = Task.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoize
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

  test("IO.memoize should not be cancelable (future)") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoize
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

  test("IO.memoize should be cancelable (callback, #1)") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoize
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

  test("IO.memoize should be cancelable (callback, #2)") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoize.map(x => x)
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

  test("IO.memoize should be re-executable after cancel") { implicit s =>
    var effect = 0
    val task = IO.evalAsync { effect += 1; effect }.delayExecution(1.second).map(_ + 1).memoize
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

  test("IO.memoize should not be uninterruptible") { implicit s =>
    val task = IO.evalAsync(1).delayExecution(1.second).uncancelable.memoize
    val f = task.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)

    f.cancel()
    assertEquals(f.value, None)
    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")

    s.tick(1.second)
    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")

    val f2 = task.attempt.runToFuture
    assertEquals(f2.value, Some(Success(Right(1))))
  }

  test("IO.memoize serves error after async boundary") { implicit s =>
    val dummy = DummyException("dummy")
    var effect = 0

    val task = IO.evalAsync { effect += 1; throw dummy }.memoize
    val task2 = IO.evalAsync(1).flatMap(_ => task)

    val f1 = task2.attempt.runToFuture
    s.tick()
    assertEquals(f1.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)

    val f2 = task2.attempt.runToFuture
    s.tick()
    assertEquals(f2.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)
  }

  test("TaskRunLoop.startLightWithBiCallback for success") { implicit s =>
    var effect = 0
    val task = IO.eval { effect += 1; effect }.map(x => x).memoize

    val p1 = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(p1))
    s.tick()
    assertEquals(p1.future.value, Some(Success(Right(1))))

    val p2 = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(p2))
    assertEquals(p2.future.value, Some(Success(Right(1))))
  }

  test("TaskRunLoop.startLightWithBiCallback for failure") { implicit s =>
    var effect = 0
    val dummy = DummyException("dummy")
    val task = IO.eval { effect += 1; throw dummy }.map(x => x).memoize

    val p1 = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(p1))
    s.tick()
    assertEquals(p1.future.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)

    val p2 = Promise[Either[Throwable, Int]]()
    task.runAsync(BiCallback.fromPromise(p2))
    assertEquals(p2.future.value, Some(Success(Left(dummy))))
    assertEquals(effect, 1)
  }

  test("IO.evalOnce eq IO.evalOnce.memoize") { implicit s =>
    val task = IO.evalOnce(1)
    assertEquals(task, task.memoize)
  }

  test("IO.eval.memoize eq IO.eval.memoize.memoize") { implicit s =>
    val task = IO.eval(1).memoize
    assertEquals(task, task.memoize)
  }

  test("IO.eval.map.memoize eq IO.eval.map.memoize.memoize") { implicit s =>
    val task = IO.eval(1).map(_ + 1).memoize
    assertEquals(task, task.memoize)
  }

  test("IO.now.memoize eq IO.now") { implicit s =>
    val task = IO.now(1)
    assertEquals(task, task.memoize)
  }

  test("IO.raiseError.memoize eq IO.raiseError") { implicit s =>
    val task = IO.raiseError("dummy")
    assertEquals(task, task.memoize)
  }

  test("IO.terminate.memoize eq IO.raiseError") { implicit s =>
    val task = IO.terminate(DummyException("dummy"))
    assertEquals(task, task.memoize)
  }

  test("IO.eval.memoizeOnSuccess.memoize !== IO.eval.memoizeOnSuccess") { implicit s =>
    val task = IO.eval(1).memoizeOnSuccess
    assert(task != task.memoize, "task != task.memoize")
  }
}

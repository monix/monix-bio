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

import cats.syntax.either._
import monix.execution.CancelableFuture
import monix.execution.atomic.Atomic
import monix.execution.exceptions.DummyException
import monix.execution.internal.Platform

import scala.concurrent.{Promise, TimeoutException}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskRaceSuite extends BaseTestSuite {

  test("IO.raceMany returns fastest success") { implicit s =>
    val first = IO.fromEither(123.asRight[String]).delayExecution(10.seconds)
    val second = IO.fromEither(456.asRight[String]).delayExecution(1.second)
    val third = IO.fromEither(789.asRight[String]).delayExecution(5.second)
    val race = IO.raceMany(List(first, second, third))
    val f = race.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(456))))
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany returns fastest typed error") { implicit s =>
    val first = IO.fromEither("first error".asLeft[Int]).delayExecution(10.seconds)
    val second = IO.fromEither("second error".asLeft[Int]).delayExecution(1.second)
    val third = IO.fromEither("third error".asLeft[Int]).delayExecution(5.second)
    val race = IO.raceMany(List(first, second, third))
    val f = race.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left("second error"))))
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany returns fastest terminal error") { implicit s =>
    val first = IO.terminate(DummyException("first exception")).delayExecution(10.seconds)
    val second = IO.terminate(DummyException("second exception")).delayExecution(1.second)
    val third = IO.terminate(DummyException("third exception")).delayExecution(5.second)
    val race = IO.raceMany(List(first, second, third))
    val f = race.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Failure(DummyException("second exception"))))
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany returns success that is faster than typed error") { implicit s =>
    val first = IO.fromEither("dummy error".asLeft[Int]).delayExecution(10.seconds)
    val second = IO.fromEither(456.asRight[String]).delayExecution(1.second)
    val race = IO.raceMany(List(first, second))
    val f = race.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(456))))
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany returns success that is faster than terminal error") { implicit s =>
    val first = IO.terminate(DummyException("dummy exception")).delayExecution(10.seconds)
    val second = IO.fromEither(456.asRight[String]).delayExecution(1.second)
    val race = IO.raceMany(List(first, second))
    val f = race.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(456))))
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany returns typed error that is faster than success") { implicit s =>
    val first = IO.fromEither(123.asRight[String]).delayExecution(10.seconds)
    val second = IO.fromEither("dummy error".asLeft[Int]).delayExecution(1.second)
    val race = IO.raceMany(List(first, second))
    val f = race.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left("dummy error"))))
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany returns typed error that is faster than fatal error") { implicit s =>
    val first = IO.terminate(DummyException("dummy exception")).delayExecution(10.seconds)
    val second = IO.fromEither("dummy error".asLeft[Int]).delayExecution(1.second)
    val race = IO.raceMany(List(first, second))
    val f = race.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left("dummy error"))))
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany returns fatal error that is faster than success") { implicit s =>
    val first = IO.fromEither(123.asRight[String]).delayExecution(10.seconds)
    val second = IO.terminate(DummyException("dummy exception")).delayExecution(1.second)
    val race = IO.raceMany(List(first, second))
    val f = race.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Failure(DummyException("dummy exception"))))
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany returns fatal error that is faster than typed error") { implicit s =>
    val first = IO.fromEither("dummy error".asLeft[Int]).delayExecution(10.second)
    val second = IO.terminate(DummyException("dummy exception")).delayExecution(1.second)
    val race = IO.raceMany(List(first, second))
    val f = race.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Failure(DummyException("dummy exception"))))
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany should allow cancelling all tasks") { implicit s =>
    val first = IO.fromEither(123.asRight[String]).delayExecution(10.seconds)
    val second = IO.fromEither("dummy error".asLeft[Int]).delayExecution(1.second)
    val third = IO.terminate(DummyException("dummy exception")).delayExecution(5.second)
    val race = IO.raceMany(List(first, second, third))
    val f = race.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    assert(s.state.tasks.nonEmpty, "no tasks should be canceled yet")

    f.cancel()
    s.tick()
    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "every task should be canceled")
  }

  test("IO.raceMany should be stack safe for sync values") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 10000
    val tasks = (0 until count).map(idx => IO.fromEither(idx.asRight[String]))
    val race = IO.raceMany(tasks)
    val f = race.attempt.runToFuture

    s.tick()
    assert(f.value.isDefined, "fastest task should win the race")
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany should be stack safe for async values") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 10000
    val tasks = (0 until count).map(idx => IO.fromEither(idx.asRight[String]).executeAsync)
    val race = IO.raceMany(tasks)
    val f = race.attempt.runToFuture

    s.tick()
    assert(f.value.isDefined, "fastest task should win the race")
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO.raceMany has a stack safe cancelable") { implicit s =>
    val count = if (Platform.isJVM) 10000 else 1000
    val p = Promise[Int]()
    val tasks = (0 until count).map(_ => IO.never[Int])
    val all = tasks.foldLeft(IO.never[Int])((acc, bio) => IO.raceMany(List(acc, bio)))
    val f = Task.raceMany(List(IO.fromFuture(p.future), all)).runToFuture

    s.tick()
    assertEquals(f.value, None)

    p.success(1)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
    assert(s.state.tasks.isEmpty, "slower tasks should be canceled")
  }

  test("IO#timeout should timeout") { implicit s =>
    val task = IO.evalAsync(1).delayExecution(10.seconds).timeout(1.second)
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Option.empty[Int])))

    assert(s.state.tasks.isEmpty, "Main task was not canceled!")
  }

  test("IO#timeout should mirror the source in case of success") { implicit s =>
    val task = IO.evalAsync(1).delayExecution(1.seconds).timeout(10.second)
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Some(1))))
    assert(s.state.tasks.isEmpty, "timer should be canceled")
  }

  test("IO#timeout should mirror the source in case of error") { implicit s =>
    val ex = DummyException("dummy")
    val task = IO.evalAsync(throw ex).delayExecution(1.seconds).timeout(10.second)
    val f = task.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left(ex))))
    assert(s.state.tasks.isEmpty, "timer should be canceled")
  }

  test("IO#timeout should mirror the source in case of terminal error") { implicit s =>
    val ex = DummyException("dummy")
    val task = UIO.evalAsync(throw ex).delayExecution(1.seconds).timeout(10.second)
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Failure(ex)))
    assert(s.state.tasks.isEmpty, "timer should be canceled")
  }

  test("IO#timeout should cancel both the source and the timer") { implicit s =>
    val task = IO.evalAsync(1).delayExecution(10.seconds).timeout(1.second)
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)
    f.cancel()
    s.tick()

    assertEquals(f.value, None)
  }

  test("IO#timeout with backup should timeout") { implicit s =>
    val task = IO.evalAsync(1).delayExecution(10.seconds).timeoutTo(1.second, IO.evalAsync(99))
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(99)))
  }

  test("IO#timeout with backup should timeout with error") { implicit s =>
    val ex = DummyException("dummy")
    val task = IO.evalAsync(1).delayExecution(10.seconds).timeoutTo(1.second, IO.raiseError(ex))
    val f = task.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("IO#timeout with backup should mirror the source in case of success") { implicit s =>
    val task = IO.evalAsync(1).delayExecution(1.seconds).timeoutTo(10.second, IO.evalAsync(99))
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(1)))
    assert(s.state.tasks.isEmpty, "timer should be canceled")
  }

  test("IO#timeout with backup should mirror the source in case of error") { implicit s =>
    val ex = DummyException("dummy")
    val task = IO.evalAsync(throw ex).delayExecution(1.seconds).timeoutTo(10.second, IO.evalAsync(99))
    val f = task.attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left(ex))))
    assert(s.state.tasks.isEmpty, "timer should be canceled")
  }

  test("IO#timeout should cancel both the source and the timer") { implicit s =>
    val task = IO.evalAsync(1).delayExecution(10.seconds).timeoutTo(1.second, IO.evalAsync(99))
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)
    f.cancel()
    s.tick()

    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "timer should be canceled")
  }

  test("IO#timeout should cancel the backup") { implicit s =>
    val task =
      IO.evalAsync(1).delayExecution(10.seconds).timeoutTo(1.second, IO.evalAsync(99).delayExecution(2.seconds))
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.seconds)
    assertEquals(f.value, None)

    f.cancel(); s.tick()
    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "backup should be canceled")
  }

  test("IO#timeout should not return the source after timeout") { implicit s =>
    val task =
      IO.evalAsync(1).delayExecution(2.seconds).timeoutTo(1.second, IO.evalAsync(99).delayExecution(2.seconds))
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(3.seconds)
    assertEquals(f.value, Some(Success(99)))
  }

  test("IO#timeout should cancel the source after timeout") { implicit s =>
    val backup = IO.evalAsync(99).delayExecution(1.seconds)
    val task = IO.evalAsync(1).delayExecution(5.seconds).timeoutTo(1.second, backup)
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(1.seconds)
    assert(s.state.tasks.size == 1, "source should be canceled after timeout")

    s.tick(1.seconds)
    assert(s.state.tasks.isEmpty, "all task should be completed")
  }

  test("IO#timeoutToL should evaluate as specified: lazy, no memoization") { implicit s =>
    val cnt = Atomic(0L)
    val timeout = UIO(cnt.incrementAndGet().seconds)
    val error = IO.raiseError(DummyException("dummy"))
    val loop = IO(10).delayExecution(2.9.seconds).timeoutToL(timeout, error).onErrorRestart(3)
    val f = loop.runToFuture

    s.tick(1.second)
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)
    s.tick(3.seconds)
    assertEquals(f.value, Some(Success(10)))
  }

  test("IO#timeoutL should evaluate as specified: lazy, with memoization") { implicit s =>
    val cnt = Atomic(0L)
    val timeoutError = new TimeoutException("Task timed-out")
    val timeout = UIO.eval(cnt.incrementAndGet().seconds).memoize
    val loop = IO(10).delayExecution(10.seconds).timeoutToL(timeout, IO.raiseError(timeoutError)).onErrorRestart(3)
    val f = loop.attempt.runToFuture

    s.tick(1.second)
    assertEquals(f.value, None)

    s.tick(1.seconds)
    assertEquals(f.value, None)

    s.tick(1.seconds)
    assertEquals(f.value, None)

    s.tick(1.seconds)
    assert(f.value.isDefined)
    assert(f.value.get.isSuccess)
    assertEquals(f.value.get.get, Left(timeoutError))
  }

  test("IO#timeoutL considers time taken to evaluate the duration task") { implicit s =>
    val timeout = UIO(3.seconds).delayExecution(2.seconds)
    val f = IO(10).delayExecution(4.seconds).timeoutToL(timeout, IO(-10)).runToFuture

    s.tick(2.seconds)
    assertEquals(f.value, None)

    s.tick(1.seconds)
    assertEquals(f.value, Some(Success(-10)))
  }

  test("IO#timeoutL: evaluation time took > timeout => timeout is immediately completed") { implicit s =>
    val timeout = UIO(2.seconds).delayExecution(3.seconds)
    val f = Task(10).delayExecution(4.seconds).timeoutToL(timeout, Task(-10)).runToFuture

    s.tick(3.seconds)
    assertEquals(f.value, Some(Success(-10)))
  }

  test("IO.racePair(a,b) should work if a completes first") { implicit s =>
    val ta: IO[Long, Int] = IO.now(10).delayExecution(1.second)
    val tb: IO[Long, Int] = IO.now(20).delayExecution(2.seconds)

    val t = IO.racePair(ta, tb).flatMap {
      case Left((a, taskB)) =>
        taskB.join.map(b => a + b)
      case Right((taskA, b)) =>
        taskA.join.map(a => a + b)
    }

    val f = t.attempt.runToFuture
    s.tick(1.second)
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(30))))
  }

  test("IO.racePair(a,b) should cancel both") { implicit s =>
    val ta: IO[Long, Int] = IO.now(10).delayExecution(2.second)
    val tb: IO[Long, Int] = IO.now(20).delayExecution(1.seconds)

    val t = IO.racePair(ta, tb)
    val f = t.attempt.runToFuture
    s.tick()
    f.cancel()
    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.racePair(A,B) should not cancel B if A completes first") { implicit s =>
    val ta: IO[Long, Int] = IO.now(10).delayExecution(1.second)
    val tb: IO[Long, Int] = IO.now(20).delayExecution(2.seconds)
    var future = Option.empty[CancelableFuture[Either[Long, Int]]]

    val t = IO.racePair(ta, tb).map {
      case Left((a, taskB)) =>
        future = Some(taskB.join.attempt.runToFuture)
        a
      case Right((taskA, b)) =>
        future = Some(taskA.join.attempt.runToFuture)
        b
    }

    val f = t.attempt.runToFuture
    s.tick(1.second)
    f.cancel()

    assertEquals(f.value, Some(Success(Right(10))))
    assert(future.isDefined, "future.isDefined")
    assertEquals(future.flatMap(_.value), None)

    s.tick(1.second)
    assertEquals(future.flatMap(_.value), Some(Success(Right(20))))
  }

  test("IO.racePair(A,B) should not cancel A if B completes first") { implicit s =>
    val ta: IO[Long, Int] = IO.now(10).delayExecution(2.second)
    val tb: IO[Long, Int] = IO.now(20).delayExecution(1.seconds)
    var future = Option.empty[CancelableFuture[Either[Long, Int]]]

    val t = IO.racePair(ta, tb).map {
      case Left((a, taskB)) =>
        future = Some(taskB.join.attempt.runToFuture)
        a
      case Right((taskA, b)) =>
        future = Some(taskA.join.attempt.runToFuture)
        b
    }

    val f = t.attempt.runToFuture
    s.tick(1.second)
    f.cancel()

    assertEquals(f.value, Some(Success(Right(20))))
    assert(future.isDefined, "future.isDefined")
    assertEquals(future.flatMap(_.value), None)

    s.tick(1.second)
    assertEquals(future.flatMap(_.value), Some(Success(Right(10))))
  }

  test("IO.racePair(A,B) should end both in error if A completes first in error") { implicit s =>
    val dummy = 1204L
    val ta: IO[Long, Int] = IO.raiseError(dummy).delayExecution(1.second)
    val tb: IO[Long, Int] = IO.now(20).delayExecution(2.seconds)

    val t = IO.racePair(ta, tb)
    val f = t.attempt.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left(dummy))))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.racePair(A,B) should end both in terminal error if A completes first in terminal error") { implicit s =>
    val dummy = DummyException("dummy")
    val ta: IO[Long, Int] = IO.terminate(dummy).delayExecution(1.second)
    val tb: IO[Long, Int] = IO.now(20).delayExecution(2.seconds)

    val t = IO.racePair(ta, tb)
    val f = t.attempt.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Failure(dummy)))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.racePair(A,B) should end both in error if B completes first in error") { implicit s =>
    val dummy = 1204L
    val ta: IO[Long, Int] = IO.now(10).delayExecution(2.seconds)
    val tb: IO[Long, Int] = IO.raiseError(dummy).delayExecution(1.second)

    val t = IO.racePair(ta, tb)
    val f = t.attempt.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left(dummy))))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.racePair(A,B) should end both in terminal error if B completes first in terminal error") { implicit s =>
    val dummy = DummyException("dummy")
    val ta: IO[Long, Int] = IO.now(10).delayExecution(2.seconds)
    val tb: IO[Long, Int] = IO.terminate(dummy).delayExecution(1.second)

    val t = IO.racePair(ta, tb)
    val f = t.attempt.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Failure(dummy)))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.racePair(A,B) should work if A completes second in error") { implicit s =>
    val dummy = 10L
    val ta: IO[Long, Int] = IO.raiseError(dummy).delayExecution(2.second)
    val tb: IO[Long, Int] = IO.now(20).delayExecution(1.seconds)

    val t1 = IO.racePair(ta, tb).flatMap {
      case Left((a, taskB)) =>
        taskB.join.map(b => a + b)
      case Right((taskA, b)) =>
        taskA.join.map(a => a + b)
    }

    val t2 = IO.racePair(ta, tb).map {
      case Left((a, _)) => a
      case Right((_, b)) => b
    }

    val f1 = t1.attempt.runToFuture
    val f2 = t2.attempt.runToFuture
    s.tick(2.seconds)

    assertEquals(f1.value, Some(Success(Left(dummy))))
    assertEquals(f2.value, Some(Success(Right(20))))
  }

  test("IO.racePair(A,B) should work if A completes second in terminal error") { implicit s =>
    val dummy = DummyException("dummy")
    val ta: IO[Long, Int] = IO.terminate(dummy).delayExecution(2.second)
    val tb: IO[Long, Int] = IO.now(20).delayExecution(1.seconds)

    val t1 = IO.racePair(ta, tb).flatMap {
      case Left((a, taskB)) =>
        taskB.join.map(b => a + b)
      case Right((taskA, b)) =>
        taskA.join.map(a => a + b)
    }

    val t2 = IO.racePair(ta, tb).map {
      case Left((a, _)) => a
      case Right((_, b)) => b
    }

    val f1 = t1.attempt.runToFuture
    val f2 = t2.attempt.runToFuture
    s.tick(2.seconds)

    assertEquals(f1.value, Some(Failure(dummy)))
    assertEquals(f2.value, Some(Success(Right(20))))
  }

  test("IO.racePair(A,B) should work if B completes second in error") { implicit s =>
    val dummy = "dummy"
    val ta = IO.now(10).delayExecution(1.seconds)
    val tb: IO[String, Int] = IO.raiseError(dummy).delayExecution(2.second)

    val t1 = IO.racePair(ta, tb).flatMap {
      case Left((a, taskB)) =>
        taskB.join.map(b => a + b)
      case Right((taskA, b)) =>
        taskA.join.map(a => a + b)
    }

    val t2 = IO.racePair(ta, tb).map {
      case Left((a, _)) => a
      case Right((_, b)) => b
    }

    val f1 = t1.attempt.runToFuture
    val f2 = t2.attempt.runToFuture
    s.tick(2.seconds)

    assertEquals(f1.value, Some(Success(Left(dummy))))
    assertEquals(f2.value, Some(Success(Right(10))))
  }

  test("IO.racePair should be stack safe, take 1") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 10000
    val tasks: Seq[UIO[Int]] = (0 until count).map(x => UIO.evalAsync(x))
    val init: UIO[Int] = IO.never[Int]

    val sum = tasks.foldLeft(init)((acc, t) =>
      UIO.racePair(acc, t).map {
        case Left((a, _)) => a
        case Right((_, b)) => b
      }
    )

    sum.runToFuture
    s.tick()
  }

  test("IO.racePair should be stack safe, take 2") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 10000
    val tasks = (0 until count).map(x => UIO.eval(x))
    val init = IO.never[Int]

    val sum = tasks.foldLeft(init)((acc, t) =>
      UIO.racePair(acc, t).map {
        case Left((a, _)) => a
        case Right((_, b)) => b
      }
    )

    sum.runToFuture
    s.tick()
  }

  test("IO.racePair has a stack safe cancelable") { implicit sc =>
    val count = if (Platform.isJVM) 10000 else 1000
    val p = Promise[Int]()

    val tasks = (0 until count).map(_ => IO.never[Int])
    val all = tasks.foldLeft(IO.never[Int])((acc, t) =>
      UIO.racePair(acc, t).flatMap {
        case Left((a, fb)) => fb.cancel.map(_ => a)
        case Right((fa, b)) => fa.cancel.map(_ => b)
      }
    )

    val f = IO
      .racePair(IO.fromFuture(p.future), all)
      .flatMap {
        case Left((a, fb)) => fb.cancel.map(_ => a)
        case Right((fa, b)) => fa.cancel.map(_ => b)
      }
      .runToFuture

    sc.tick()
    p.success(1)
    sc.tick()

    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.race(a, b) should work if a completes first") { implicit s =>
    val ta = IO.now(10).delayExecution(1.second)
    val tb = IO.now(20).delayExecution(2.seconds)

    val t = IO.race(ta, tb).map {
      case Left(a) => a
      case Right(b) => b
    }

    val f = t.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Success(10)))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.race(a, b) should work if b completes first") { implicit s =>
    val ta = IO.now(10).delayExecution(2.second)
    val tb = IO.now(20).delayExecution(1.seconds)

    val t = IO.race(ta, tb).map {
      case Left(a) => a
      case Right(b) => b
    }

    val f = t.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Success(20)))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.race(a, b) should cancel both") { implicit s =>
    val ta = IO.now(10).delayExecution(2.second)
    val tb = IO.now(20).delayExecution(1.seconds)

    val t = IO.race(ta, tb)
    val f = t.runToFuture
    s.tick()
    f.cancel()
    assertEquals(f.value, None)
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.race(a, b) should end both in error if `a` completes first in error") { implicit s =>
    val dummy = 2020
    val ta = IO.raiseError(dummy).delayExecution(1.second)
    val tb = IO.now(20).delayExecution(2.seconds)

    val t = IO.race(ta, tb)
    val f = t.attempt.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left(dummy))))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.race(a, b) should end both in error if `a` completes first in terminal error") { implicit s =>
    val dummy = DummyException("dummy")
    val ta = IO.terminate(dummy).delayExecution(1.second)
    val tb = IO.now(20).delayExecution(2.seconds)

    val t = IO.race(ta, tb)
    val f = t.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Failure(dummy)))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.race(a, b) should end both in error if `b` completes first in error") { implicit s =>
    val dummy = 2020
    val ta = IO.now(20).delayExecution(2.seconds)
    val tb = IO.raiseError(dummy).delayExecution(1.second)

    val t = IO.race(ta, tb)
    val f = t.attempt.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Left(dummy))))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.race(a, b) should end both in error if `b` completes first in error") { implicit s =>
    val dummy = DummyException("dummy")
    val ta = IO.now(20).delayExecution(2.seconds)
    val tb = IO.terminate(dummy).delayExecution(1.second)

    val t = IO.race(ta, tb)
    val f = t.runToFuture
    s.tick(1.second)
    assertEquals(f.value, Some(Failure(dummy)))
    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
  }

  test("IO.race(a, b) should work if `a` completes in typed error") { implicit s =>
    val dummy = DummyException("dummy")
    val ta = IO.raiseError(dummy).delayExecution(2.second).uncancelable
    val tb = IO.now(20).delayExecution(1.seconds)

    val task = IO.race(ta, tb).map {
      case Left(a) => a
      case Right(b) => b
    }

    val f = task.runToFuture
    s.tick(2.seconds)

    assertEquals(f.value, Some(Success(20)))
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("IO.race(a, b) should work if `a` completes in unexpected error") { implicit s =>
    val dummy = DummyException("dummy")
    val ta = IO.terminate(dummy).delayExecution(2.second).uncancelable
    val tb = IO.now(20).delayExecution(1.seconds)

    val task = IO.race(ta, tb).map {
      case Left(a) => a
      case Right(b) => b
    }

    val f = task.runToFuture
    s.tick(2.seconds)

    assertEquals(f.value, Some(Success(20)))
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("IO.race(a, b) should work if `b` completes in typed error") { implicit s =>
    val dummy = DummyException("dummy")
    val ta = IO.now(20).delayExecution(1.seconds)
    val tb = IO.raiseError(dummy).delayExecution(2.second).uncancelable

    val task = IO.race(ta, tb).map {
      case Left(a) => a
      case Right(b) => b
    }

    val f = task.runToFuture
    s.tick(2.seconds)

    assertEquals(f.value, Some(Success(20)))
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("IO.race(a, b) should work if `b` completes in unexpected error") { implicit s =>
    val dummy = DummyException("dummy")
    val ta = IO.now(20).delayExecution(1.seconds)
    val tb = IO.terminate(dummy).delayExecution(2.second).uncancelable

    val task = IO.race(ta, tb).map {
      case Left(a) => a
      case Right(b) => b
    }

    val f = task.runToFuture
    s.tick(2.seconds)

    assertEquals(f.value, Some(Success(20)))
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("IO.race should be stack safe, take 1") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 10000
    val tasks = (0 until count).map(x => UIO.evalAsync(x))
    val init = IO.never[Int]

    val sum = tasks.foldLeft(init)((acc, t) =>
      IO.race(acc, t).map {
        case Left(a) => a
        case Right(b) => b
      }
    )

    sum.runToFuture
    s.tick()
  }

  test("IO.race should be stack safe, take 2") { implicit s =>
    val count = if (Platform.isJVM) 100000 else 10000
    val tasks = (0 until count).map(x => IO.evalTotal(x))
    val init = IO.never[Int]

    val sum = tasks.foldLeft(init)((acc, t) =>
      IO.race(acc, t).map {
        case Left(a) => a
        case Right(b) => b
      }
    )

    sum.runToFuture
    s.tick()
  }

  test("Task.race has a stack safe cancelable") { implicit sc =>
    val count = if (Platform.isJVM) 10000 else 1000
    val p = Promise[Int]()

    val tasks = (0 until count).map(_ => IO.never[Int])
    val all = tasks.foldLeft(IO.never[Int])((acc, t) =>
      IO.race(acc, t).map {
        case Left(a) => a
        case Right(b) => b
      }
    )

    val f = IO.race(IO.fromFuture(p.future), all).map { case Left(a) => a; case Right(b) => b }.runToFuture

    sc.tick()
    p.success(1)
    sc.tick()

    assertEquals(f.value, Some(Success(1)))
  }

}

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

import cats.effect.ExitCase
import monix.execution.atomic.AtomicInt
import monix.execution.exceptions.{DummyException, UncaughtErrorException}
import monix.execution.internal.Platform

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskParSequenceNSuite extends BaseTestSuite {

  test("BIO.parSequenceN should execute in parallel bounded by parallelism") { implicit s =>
    val num = AtomicInt(0)
    val task = UIO.evalAsync(num.increment()) >> BIO.sleep(2.seconds)
    val seq = List.fill(100)(task)

    BIO.parSequenceN(5)(seq).runToFuture

    s.tick()
    assertEquals(num.get(), 5)
    s.tick(2.seconds)
    assertEquals(num.get(), 10)
    s.tick(4.seconds)
    assertEquals(num.get(), 20)
    s.tick(34.seconds)
    assertEquals(num.get(), 100)
  }

  test("BIO.parSequenceN should return result in order") { implicit s =>
    val task = 1.until(10).toList.map(UIO.eval(_))
    val res = BIO.parSequenceN(2)(task).runToFuture

    s.tick()
    assertEquals(res.value, Some(Success(List(1, 2, 3, 4, 5, 6, 7, 8, 9))))
  }

  test("BIO.parSequenceN should return empty list") { implicit s =>
    val res = BIO.parSequenceN(2)(List.empty).runToFuture

    s.tick()
    assertEquals(res.value, Some(Success(List.empty)))
  }

  test("BIO.parSequenceN should handle single item") { implicit s =>
    val task = List(Task.eval(1))
    val res = BIO.parSequenceN(2)(task).runToFuture

    s.tick()
    assertEquals(res.value, Some(Success(List(1))))
  }

  test("BIO.parSequenceN should handle parallelism bigger than list") { implicit s =>
    val task = 1.until(5).toList.map(UIO.eval(_))
    val res = BIO.parSequenceN(10)(task).runToFuture

    s.tick()
    assertEquals(res.value, Some(Success(List(1, 2, 3, 4))))
  }

  test("BIO.parSequenceN should onError if one of the tasks terminates in error") { implicit s =>
    val ex = "dummy"
    val seq = Seq(
      BIO.evalAsync(3).delayExecution(2.seconds),
      BIO.evalAsync(2).delayExecution(1.second),
      BIO.raiseError(ex).delayExecution(2.seconds),
      BIO.evalAsync(3).delayExecution(1.seconds)
    )

    val f = BIO.parSequenceN(2)(seq).attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("BIO.parSequenceN should onTerminate if one of the tasks terminates in a fatal error") { implicit s =>
    val ex = DummyException("dummy")
    val seq = Seq(
      UIO.evalAsync(3).delayExecution(2.seconds),
      UIO.evalAsync(2).delayExecution(1.second),
      UIO.evalAsync(throw ex).delayExecution(2.seconds),
      UIO.evalAsync(3).delayExecution(1.seconds)
    )

    val f = BIO.parSequenceN(2)(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.parSequenceN should be canceled") { implicit s =>
    val num = AtomicInt(0)
    val seq = Seq(
      BIO.unit.delayExecution(3.seconds).doOnCancel(UIO.eval(num.increment())),
      UIO.evalAsync(num.increment(10))
    )
    val f = BIO.parSequenceN(1)(seq).runToFuture

    s.tick(2.seconds)
    f.cancel()
    assertEquals(num.get(), 1)

    s.tick(1.day)
    assertEquals(num.get(), 1)
  }

  test("BIO.parSequenceN should be stack safe for synchronous tasks") { implicit s =>
    val count = if (Platform.isJVM) 200000 else 5000
    val tasks = for (_ <- 0 until count) yield Task.now(1)
    val composite = BIO.parSequenceN(count)(tasks).map(_.sum)
    val result = composite.runToFuture
    s.tick()
    assertEquals(result.value, Some(Success(count)))
  }

  test("BIO.parSequenceN runAsync multiple times") { implicit s =>
    var effect = 0
    val task1 = UIO.evalAsync { effect += 1; 3 }.memoize
    val task2 = task1 map { x =>
      effect += 1; x + 1
    }
    val task3 = BIO.parSequenceN(2)(List(task2, task2, task2))

    val result1 = task3.runToFuture; s.tick()
    assertEquals(result1.value, Some(Success(List(4, 4, 4))))
    assertEquals(effect, 1 + 3)

    val result2 = task3.runToFuture; s.tick()
    assertEquals(result2.value, Some(Success(List(4, 4, 4))))
    assertEquals(effect, 1 + 3 + 3)
  }

  test("BIO.parSequenceN should log errors if multiple errors happen") { implicit s =>
    implicit val opts = BIO.defaultOptions.disableAutoCancelableRunLoops

    val ex = "dummy1"
    var errorsThrow = 0

    val task1: BIO[String, Int] = BIO
      .raiseError(ex)
      .executeAsync
      .guaranteeCase {
        case ExitCase.Completed => BIO.unit
        case ExitCase.Error(_) => UIO(errorsThrow += 1)
        case ExitCase.Canceled => BIO.unit
      }
      .uncancelable

    val task2: BIO[String, Int] = BIO
      .raiseError(ex)
      .executeAsync
      .guaranteeCase {
        case ExitCase.Completed => BIO.unit
        case ExitCase.Error(_) => UIO(errorsThrow += 1)
        case ExitCase.Canceled => BIO.unit
      }
      .uncancelable

    val sequence = BIO.parSequenceN(4)(Seq(task1, task2))
    val result = sequence.attempt.runToFutureOpt
    s.tick()

    assertEquals(result.value, Some(Success(Left(ex))))
    assertEquals(s.state.lastReportedError.toString, UncaughtErrorException.wrap(ex).toString)
    assertEquals(errorsThrow, 2)
  }

  test("BIO.parSequenceN should log terminal errors if multiple errors happen") { implicit s =>
    implicit val opts = BIO.defaultOptions.disableAutoCancelableRunLoops

    val ex = DummyException("dummy1")
    var errorsThrow = 0

    val task1: BIO[String, Int] = BIO
      .terminate(ex)
      .executeAsync
      .guaranteeCase {
        case ExitCase.Completed => BIO.unit
        case ExitCase.Error(_) => UIO(errorsThrow += 1)
        case ExitCase.Canceled => BIO.unit
      }
      .uncancelable

    val task2: BIO[String, Int] = BIO
      .terminate(ex)
      .executeAsync
      .guaranteeCase {
        case ExitCase.Completed => BIO.unit
        case ExitCase.Error(_) => UIO(errorsThrow += 1)
        case ExitCase.Canceled => BIO.unit
      }
      .uncancelable

    val sequence = BIO.parSequenceN(4)(Seq(task1, task2))
    val result = sequence.attempt.runToFutureOpt
    s.tick()

    assertEquals(result.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, ex)
    assertEquals(errorsThrow, 2)
  }
}

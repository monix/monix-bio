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
import monix.execution.Callback
import monix.execution.exceptions.{DummyException, UncaughtErrorException}
import monix.execution.internal.Platform

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object TaskGatherUnorderedSuite extends BaseTestSuite {
  test("BIO.gatherUnordered should execute in parallel") { implicit s =>
    val seq = Seq(
      BIO.evalAsync(1).delayExecution(2.seconds),
      BIO.evalAsync(2).delayExecution(1.second),
      BIO.evalAsync(3).delayExecution(3.seconds)
    )
    val f = BIO.gatherUnordered(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(List(3, 1, 2))))
  }

  test("BIO.gatherUnordered should onError if one of the tasks terminates in error") { implicit s =>
    val ex = DummyException("dummy")
    val seq = Seq(
      BIO.evalAsync(3).delayExecution(3.seconds),
      BIO.evalAsync(2).delayExecution(1.second),
      BIO.evalAsync(throw ex).delayExecution(2.seconds),
      BIO.evalAsync(3).delayExecution(1.seconds)
    )

    val f = BIO.gatherUnordered(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.gatherUnordered should onTerminate if one of the tasks terminates in a fatal error") { implicit s =>
    val ex = DummyException("dummy")
    val seq = Seq(
      UIO.evalAsync(3).delayExecution(3.seconds),
      UIO.evalAsync(2).delayExecution(1.second),
      UIO.evalAsync(throw ex).delayExecution(2.seconds),
      UIO.evalAsync(3).delayExecution(1.seconds)
    )

    val f = UIO.gatherUnordered(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.gatherUnordered should be canceled") { implicit s =>
    val seq = Seq(
      UIO.evalAsync(1).delayExecution(2.seconds),
      UIO.evalAsync(2).delayExecution(1.second),
      UIO.evalAsync(3).delayExecution(3.seconds)
    )
    val f = BIO.gatherUnordered(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)

    f.cancel()
    s.tick(1.second)
    assertEquals(f.value, None)
  }

  test("BIO.gatherUnordered should run over an iterable") { implicit s =>
    val count = 10
    val seq = 0 until count
    val it = seq.map(x => BIO.eval(x + 1))
    val sum = BIO.gatherUnordered(it).map(_.sum)

    val result = sum.runToFuture; s.tick()
    assertEquals(result.value.get, Success((count + 1) * count / 2))
  }

  test("BIO.gatherUnordered should be stack-safe on handling many tasks") { implicit s =>
    val count = 10000
    val tasks = (0 until count).map(x => BIO.eval(x))
    val sum = BIO.gatherUnordered(tasks).map(_.sum)

    val result = sum.runToFuture; s.tick()
    assertEquals(result.value.get, Success(count * (count - 1) / 2))
  }

  test("BIO.gatherUnordered should be stack safe on success") { implicit s =>
    def fold[A, B](ta: Task[ListBuffer[A]], tb: Task[A]): Task[ListBuffer[A]] =
      Task.gatherUnordered(List(ta, tb)).map {
        case a :: b :: Nil =>
          val (accR, valueR) = if (a.isInstanceOf[ListBuffer[_]]) (a, b) else (b, a)
          val acc = accR.asInstanceOf[ListBuffer[A]]
          val value = valueR.asInstanceOf[A]
          acc += value
        case _ =>
          throw new RuntimeException("Oops!")
      }

    def gatherSpecial[A](in: Seq[Task[A]]): Task[List[A]] = {
      val init = Task.eval(ListBuffer.empty[A])
      val r = in.foldLeft(init)(fold)
      r.map(_.result())
    }

    val count = if (Platform.isJVM) 100000 else 10000
    val tasks = (0 until count).map(n => Task.eval(n))
    var result = Option.empty[Try[Int]]

    gatherSpecial(tasks)
      .map(_.sum)
      .runAsync(new Callback[Cause[Throwable], Int] {
        override def onSuccess(value: Int): Unit =
          result = Some(Success(value))

        override def onError(e: Cause[Throwable]): Unit =
          result = Some(Failure(e.toThrowable))
      })

    s.tick()
    assertEquals(result, Some(Success(count * (count - 1) / 2)))
  }

  test("BIO.gatherUnordered should log errors if multiple errors happen") { implicit s =>
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

    val gather = BIO.gatherUnordered(Seq(task1, task2))
    val result = gather.attempt.runToFutureOpt
    s.tick()

    assertEquals(result.value, Some(Success(Left(ex))))
    assertEquals(s.state.lastReportedError.toString, UncaughtErrorException.wrap(ex).toString)
    assertEquals(errorsThrow, 2)
  }

  test("BIO.gatherUnordered should log terminal errors if multiple errors happen") { implicit s =>
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

    val gather = BIO.gatherUnordered(Seq(task1, task2))
    val result = gather.attempt.runToFutureOpt
    s.tick()

    assertEquals(result.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, ex)
    assertEquals(errorsThrow, 2)
  }

  test("BIO.gatherUnordered runAsync multiple times") { implicit s =>
    var effect = 0
    val task1 = UIO.evalAsync { effect += 1; 3 }.memoize
    val task2 = task1 map { x =>
      effect += 1; x + 1
    }
    val task3 = BIO.gatherUnordered(List(task2, task2, task2))

    val result1 = task3.runToFuture; s.tick()
    assertEquals(result1.value, Some(Success(List(4, 4, 4))))
    assertEquals(effect, 1 + 3)

    val result2 = task3.runToFuture; s.tick()
    assertEquals(result2.value, Some(Success(List(4, 4, 4))))
    assertEquals(effect, 1 + 3 + 3)
  }
}

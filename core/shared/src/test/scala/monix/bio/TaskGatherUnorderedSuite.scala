/*
 * Copyright (c) 2019-2019 by The Monix Project Developers.
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

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object TaskGatherUnorderedSuite extends BaseTestSuite {
  test("Task.gatherUnordered should execute in parallel") { implicit s =>
    val seq = Seq(
      Task.evalAsync(1).delayExecution(2.seconds),
      Task.evalAsync(2).delayExecution(1.second),
      Task.evalAsync(3).delayExecution(3.seconds)
    )
    val f = Task.gatherUnordered(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(List(3, 1, 2)))))
  }

  test("Task.gatherUnordered should onError if one of the tasks terminates in error") { implicit s =>
    val ex = DummyException("dummy")
    val seq = Seq(
      Task.evalAsync(3).delayExecution(3.seconds),
      Task.evalAsync(2).delayExecution(1.second),
      Task.evalAsync(throw ex).delayExecution(2.seconds),
      Task.evalAsync(3).delayExecution(1.seconds)
    )

    val f = Task.gatherUnordered(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("Task.gatherUnordered should be canceled") { implicit s =>
    val seq = Seq(
      Task.evalAsync(1).delayExecution(2.seconds),
      Task.evalAsync(2).delayExecution(1.second),
      Task.evalAsync(3).delayExecution(3.seconds)
    )
    val f = Task.gatherUnordered(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)

    f.cancel()
    s.tick(1.second)
    assertEquals(f.value, None)
  }

  test("Task.gatherUnordered should run over an iterable") { implicit s =>
    val count = 10
    val seq = 0 until count
    val it = seq.map(x => Task.eval(x + 1))
    val sum = Task.gatherUnordered(it).map(_.sum)

    val result = sum.runToFuture; s.tick()
    assertEquals(result.value.get, Success(Right((count + 1) * count / 2)))
  }

  test("Task.gatherUnordered should be stack-safe on handling many tasks") { implicit s =>
    val count = 10000
    val tasks = (0 until count).map(x => Task.eval(x))
    val sum = Task.gatherUnordered(tasks).map(_.sum)

    val result = sum.runToFuture; s.tick()
    assertEquals(result.value.get, Success(Right(count * (count - 1) / 2)))
  }

  test("Task.gatherUnordered should be stack safe on success") { implicit s =>
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
      .runAsync(new BiCallback[Cause[Throwable], Int] {
        override def onFatalError(e: Throwable): Unit =
          result = Some(Failure(e))

        override def onSuccess(value: Int): Unit =
          result = Some(Success(value))

        override def onError(e: Cause[Throwable]): Unit =
          result = Some(Failure(e.flatten))
      })

    s.tick()
    assertEquals(result, Some(Success(count * (count - 1) / 2)))
  }

//  test("Task.gatherUnordered should log errors if multiple errors happen") { implicit s =>
//    implicit val opts = BIO.defaultOptions.disableAutoCancelableRunLoops
//
//    val ex = DummyException("dummy1")
//    var errorsThrow = 0
//
//    val task1 = Task
//      .raiseError[Int](ex)
//      .executeAsync
//      .doOnFinish { x =>
//        if (x.isDefined) errorsThrow += 1
//        Task.unit
//      }
//      .uncancelable
//
//    val task2 = Task
//      .raiseError[Int](ex)
//      .executeAsync
//      .doOnFinish { x =>
//        if (x.isDefined) errorsThrow += 1
//        Task.unit
//      }
//      .uncancelable
//
//    val gather = Task.gatherUnordered(Seq(task1, task2))
//    val result = gather.runToFutureOpt
//    s.tick()
//
//    assertEquals(result.value, Some(Failure(ex)))
//    assertEquals(s.state.lastReportedError, ex)
//    assertEquals(errorsThrow, 2)
//  }

//  test("Task.gatherUnordered runAsync multiple times") { implicit s =>
//    var effect = 0
//    val task1 = Task.evalAsync { effect += 1; 3 }.memoize
//    val task2 = task1 map { x =>
//      effect += 1; x + 1
//    }
//    val task3 = Task.gatherUnordered(List(task2, task2, task2))
//
//    val result1 = task3.runToFuture; s.tick()
//    assertEquals(result1.value, Some(Success(List(4, 4, 4))))
//    assertEquals(effect, 1 + 3)
//
//    val result2 = task3.runToFuture; s.tick()
//    assertEquals(result2.value, Some(Success(List(4, 4, 4))))
//    assertEquals(effect, 1 + 3 + 3)
//  }
}

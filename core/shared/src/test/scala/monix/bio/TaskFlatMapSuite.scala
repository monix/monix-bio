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

import cats.laws._
import cats.laws.discipline._
import monix.execution.atomic.{Atomic, AtomicInt}
import monix.execution.exceptions.{DummyException, UncaughtErrorException}
import monix.execution.internal.Platform

import scala.util.{Failure, Success, Try}

object TaskFlatMapSuite extends BaseTestSuite {
  test("runAsync flatMap loop is not cancelable if autoCancelableRunLoops=false") { implicit s =>
    implicit val opts = BIO.defaultOptions.disableAutoCancelableRunLoops
    val maxCount = Platform.recommendedBatchSize * 4

    def loop(count: AtomicInt): UIO[Unit] =
      if (count.incrementAndGet() >= maxCount) BIO.unit
      else
        BIO.unit.flatMap(_ => loop(count))

    val atomic = Atomic(0)
    val f = loop(atomic).runToFutureOpt

    f.cancel(); s.tick()
    assertEquals(atomic.get, maxCount)
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("runAsync flatMap loop is cancelable if ExecutionModel permits") { implicit s =>
    val maxCount = Platform.recommendedBatchSize * 4
    val expected = Platform.recommendedBatchSize

    def loop(count: AtomicInt): UIO[Unit] =
      if (count.getAndIncrement() >= maxCount) BIO.unit
      else
        BIO.unit.flatMap(_ => loop(count))

    val atomic = Atomic(0)
    val f = loop(atomic)
      .executeWithOptions(_.enableAutoCancelableRunLoops)
      .runToFuture

    assertEquals(atomic.get, expected)
    f.cancel()
    s.tickOne()
    assertEquals(atomic.get, expected)

    s.tick()
    assertEquals(atomic.get, expected)
    assertEquals(f.value, None)
  }

  test("runAsync(callback) flatMap loop is cancelable if ExecutionModel permits") { implicit s =>
    val maxCount = Platform.recommendedBatchSize * 4
    val expected = Platform.recommendedBatchSize

    def loop(count: AtomicInt): BIO[Int, Unit] =
      if (count.getAndIncrement() >= maxCount) BIO.unit
      else
        BIO.unit.flatMap(_ => loop(count))

    val atomic = Atomic(0)
    var result = Option.empty[Try[Unit]]

    val c = loop(atomic)
      .executeWithOptions(_.enableAutoCancelableRunLoops)
      .runAsync(
        new BiCallback[Cause[Int], Unit] {
          override def onTermination(e: Throwable): Unit =
            result = Some(Failure(e))

          override def onSuccess(value: Unit): Unit =
            result = Some(Success(value))

          override def onError(e: Cause[Int]): Unit =
            result = Some(Failure(e.fold(identity, UncaughtErrorException.wrap)))
        }
      )

    c.cancel()
    s.tickOne()
    assertEquals(atomic.get, expected)

    s.tick()
    assertEquals(atomic.get, expected)
  }

  test("redeemWith derives flatMap") { implicit s =>
    check2 { (fa: BIO[String, Int], f: Int => BIO[String, Int]) =>
      fa.redeemWith(BIO.raiseError, f) <-> fa.flatMap(f)
    }
  }

  test("redeemWith derives onErrorHandleWith") { implicit s =>
    check2 { (fa: BIO[String, Int], f: String => BIO[String, Int]) =>
      fa.redeemWith(f, BIO.pure) <-> fa.onErrorHandleWith(f)
    }
  }

  test("redeem derives map . onErrorHandle") { implicit s =>
    check2 { (fa: BIO[String, Int], f: Int => Int) =>
      fa.redeem(e => throw DummyException(e), f) <-> fa.map(f).onErrorHandle(e => throw DummyException(e))
    }
  }

  test("redeem derives onErrorHandle") { implicit s =>
    check2 { (fa: BIO[String, Int], f: String => Int) =>
      fa.redeem(f, x => x) <-> fa.onErrorHandle(f)
    }
  }

  test("redeemWith can recover") { implicit s =>
    val dummy = "dummy"
    val task = BIO.raiseError(dummy).redeemWith(_ => BIO.now(1), BIO.now)
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("redeem can recover") { implicit s =>
    val dummy = "dummy"
    val task: UIO[Int] = BIO.raiseError(dummy).redeem(_ => 1, identity)
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test(">> is stack safe for infinite loops") { implicit s =>
    var wasCancelled = false
    def looped: UIO[Unit] = BIO.cancelBoundary >> looped
    val future = looped.doOnCancel(UIO { wasCancelled = true }).runToFuture
    future.cancel()
    s.tick()
    assert(wasCancelled)
  }

//  test("flatMapLoop enables loops") { implicit s =>
//    val random = BIO(Random.nextInt())
//    val loop = random.flatMapLoop(Vector.empty[Int]) { (a, list, continue) =>
//      val newList = list :+ a
//      if (newList.length < 5)
//        continue(newList)
//      else
//        BIO.now(newList)
//    }
//    val f = loop.runToFuture
//    s.tick()
//    assert(f.value.isDefined)
//    assert(f.value.get.isSuccess)
//    assertEquals(f.value.get.get.size, 5)
//  }
}

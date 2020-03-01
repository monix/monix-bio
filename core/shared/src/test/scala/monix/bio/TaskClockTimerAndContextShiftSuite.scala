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

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, ContextShift, Timer}
import monix.execution.exceptions.DummyException
import monix.execution.schedulers.TestScheduler

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskClockTimerAndContextShiftSuite extends BaseTestSuite {
  test("BIO.clock is implicit") { _ =>
    assertEquals(BIO.clock[Any], implicitly[Clock[BIO[Any, *]]])
  }

  test("BIO.clock.monotonic") { implicit s =>
    s.tick(1.seconds)

    val f = BIO.clock.monotonic(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.clock.realTime") { implicit s =>
    s.tick(1.seconds)

    val f = BIO.clock.realTime(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.clock(s2).monotonic") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.seconds)

    val f = BIO.clock(s2).monotonic(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.clock(s).realTime") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.seconds)

    val f = BIO.clock(s2).realTime(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.timer is implicit") { implicit s =>
    assertEquals(BIO.timer[Any], implicitly[Timer[BIO[Any, *]]])
    assertEquals(BIO.timer[Any].clock, implicitly[Clock[BIO[Any, *]]])
  }

  test("BIO.timer") { implicit s =>
    val f = BIO.timer.sleep(1.second).runToFuture
    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("BIO.timer(s)") { implicit s =>
    val s2 = TestScheduler()
    val f = BIO.timer(s2).sleep(1.second).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, None)
    s2.tick(1.second)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("BIO.timer(s).clock") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.second)

    val f = BIO.timer(s2).clock.monotonic(TimeUnit.SECONDS).runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.contextShift is implicit") { implicit s =>
    assertEquals(BIO.contextShift[Any], implicitly[ContextShift[BIO[Any, *]]])
  }

  test("BIO.contextShift.shift") { implicit s =>
    val f = BIO.contextShift.shift.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("BIO.contextShift.evalOn(s2)") { implicit s =>
    val s2 = TestScheduler()
    val f = BIO.contextShift.evalOn(s2)(BIO(1)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.contextShift.evalOn(s2) failure") { implicit s =>
    val s2 = TestScheduler()
    val f = BIO.contextShift.evalOn(s2)(BIO.raiseError("dummy")).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Left("dummy"))))
  }

  test("BIO.contextShift.evalOn(s2) fatal failure") { implicit s =>
    val s2 = TestScheduler()
    val dummy = DummyException("dummy")
    val f = BIO.contextShift.evalOn(s2)(BIO.terminate(dummy)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.contextShift.evalOn(s2) uses s2 for async boundaries") { implicit s =>
    val s2 = TestScheduler()
    val f = BIO.contextShift.evalOn(s2)(BIO(1).delayExecution(100.millis)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick(100.millis)
    assertEquals(f.value, None)
    s2.tick(100.millis)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.contextShift.evalOn(s2) injects s2 to BIO.deferAction") { implicit s =>
    val s2 = TestScheduler()

    var wasScheduled = false
    val runnable = new Runnable {
      override def run(): Unit = wasScheduled = true
    }

    val f =
      BIO.contextShift.evalOn(s2)(BIO.deferAction(scheduler => BIO(scheduler.execute(runnable)))).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(wasScheduled, true)
    s.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("BIO.contextShift(s).shift") { implicit s =>
    val f = BIO.contextShift(s).shift.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("BIO.contextShift(s).evalOn(s2)") { implicit s =>
    val s2 = TestScheduler()
    val f = BIO.contextShift(s).evalOn(s2)(BIO(1)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.contextShift(s).evalOn(s2) failure") { implicit s =>
    val s2 = TestScheduler()
    val f = BIO.contextShift(s).evalOn(s2)(BIO.raiseError("dummy")).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Left("dummy"))))
  }

  test("BIO.contextShift(s).evalOn(s2) fatal failure") { implicit s =>
    val s2 = TestScheduler()
    val dummy = DummyException("dummy")
    val f = BIO.contextShift(s).evalOn(s2)(BIO.terminate(dummy)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.contextShift(s).evalOn(s2) uses s2 for async boundaries") { implicit s =>
    val s2 = TestScheduler()
    val f = BIO.contextShift(s).evalOn(s2)(BIO(1).delayExecution(100.millis)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick(100.millis)
    assertEquals(f.value, None)
    s2.tick(100.millis)
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("BIO.contextShift(s).evalOn(s2) injects s2 to BIO.deferAction") { implicit s =>
    val s2 = TestScheduler()

    var wasScheduled = false
    val runnable = new Runnable {
      override def run(): Unit = wasScheduled = true
    }

    val f =
      BIO.contextShift(s).evalOn(s2)(BIO.deferAction(scheduler => BIO(scheduler.execute(runnable)))).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(wasScheduled, true)
    s.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }
}

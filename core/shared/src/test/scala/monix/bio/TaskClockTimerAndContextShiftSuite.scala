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

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, ContextShift, Timer}
import monix.execution.exceptions.DummyException
import monix.execution.schedulers.TestScheduler

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskClockTimerAndContextShiftSuite extends BaseTestSuite {
  test("WRYYY.clock is implicit") { _ =>
    assertEquals(WRYYY.clock[Any],
      implicitly[Clock[WRYYY[Any, ?]]])
  }

  test("WRYYY.clock.monotonic") { implicit s =>
    s.tick(1.seconds)

    val f = WRYYY.clock.monotonic(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("WRYYY.clock.realTime") { implicit s =>
    s.tick(1.seconds)

    val f = WRYYY.clock.realTime(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("WRYYY.clock(s2).monotonic") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.seconds)

    val f = WRYYY.clock(s2).monotonic(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("WRYYY.clock(s).realTime") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.seconds)

    val f = WRYYY.clock(s2).realTime(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("WRYYY.timer is implicit") { implicit s =>
    assertEquals(WRYYY.timer[Any], implicitly[Timer[WRYYY[Any, ?]]])
    assertEquals(WRYYY.timer[Any].clock, implicitly[Clock[WRYYY[Any, ?]]])
  }

  test("WRYYY.timer") { implicit s =>
    val f = WRYYY.timer.sleep(1.second).runToFuture
    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("WRYYY.timer(s)") { implicit s =>
    val s2 = TestScheduler()
    val f = WRYYY.timer(s2).sleep(1.second).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, None)
    s2.tick(1.second)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("WRYYY.timer(s).clock") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.second)

    val f = WRYYY.timer(s2).clock.monotonic(TimeUnit.SECONDS).runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("WRYYY.contextShift is implicit") { implicit s =>
    assertEquals(WRYYY.contextShift[Any], implicitly[ContextShift[WRYYY[Any, ?]]])
  }

  test("WRYYY.contextShift.shift") { implicit s =>
    val f = WRYYY.contextShift.shift.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("WRYYY.contextShift.evalOn(s2)") { implicit s =>
    val s2 = TestScheduler()
    val f = WRYYY.contextShift.evalOn(s2)(WRYYY(1)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("WRYYY.contextShift.evalOn(s2) failure") { implicit s =>
    val s2 = TestScheduler()
    val f = WRYYY.contextShift.evalOn(s2)(WRYYY.raiseError("dummy")).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Left("dummy"))))
  }

  test("WRYYY.contextShift.evalOn(s2) fatal failure") { implicit s =>
    val s2 = TestScheduler()
    val dummy = DummyException("dummy")
    val f = WRYYY.contextShift.evalOn(s2)(WRYYY.raiseFatalError(dummy)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("WRYYY.contextShift.evalOn(s2) uses s2 for async boundaries") { implicit s =>
    val s2 = TestScheduler()
    val f = WRYYY.contextShift.evalOn(s2)(WRYYY(1).delayExecution(100.millis)).runToFuture

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

  test("WRYYY.contextShift.evalOn(s2) injects s2 to WRYYY.deferAction") { implicit s =>
    val s2 = TestScheduler()

    var wasScheduled = false
    val runnable = new Runnable {
      override def run(): Unit = wasScheduled = true
    }

    val f =
      WRYYY.contextShift.evalOn(s2)(WRYYY.deferAction(scheduler => WRYYY(scheduler.execute(runnable)))).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(wasScheduled, true)
    s.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("WRYYY.contextShift(s).shift") { implicit s =>
    val f = WRYYY.contextShift(s).shift.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("WRYYY.contextShift(s).evalOn(s2)") { implicit s =>
    val s2 = TestScheduler()
    val f = WRYYY.contextShift(s).evalOn(s2)(WRYYY(1)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("WRYYY.contextShift(s).evalOn(s2) failure") { implicit s =>
    val s2 = TestScheduler()
    val f = WRYYY.contextShift(s).evalOn(s2)(WRYYY.raiseError("dummy")).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Left("dummy"))))
  }

  test("WRYYY.contextShift(s).evalOn(s2) fatal failure") { implicit s =>
    val s2 = TestScheduler()
    val dummy = DummyException("dummy")
    val f = WRYYY.contextShift(s).evalOn(s2)(WRYYY.raiseFatalError(dummy)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("WRYYY.contextShift(s).evalOn(s2) uses s2 for async boundaries") { implicit s =>
    val s2 = TestScheduler()
    val f = WRYYY.contextShift(s).evalOn(s2)(WRYYY(1).delayExecution(100.millis)).runToFuture

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

  test("WRYYY.contextShift(s).evalOn(s2) injects s2 to WRYYY.deferAction") { implicit s =>
    val s2 = TestScheduler()

    var wasScheduled = false
    val runnable = new Runnable {
      override def run(): Unit = wasScheduled = true
    }

    val f =
      WRYYY.contextShift(s).evalOn(s2)(WRYYY.deferAction(scheduler => WRYYY(scheduler.execute(runnable)))).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(wasScheduled, true)
    s.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }
}

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

import cats.effect.Clock
import monix.execution.exceptions.DummyException
import monix.execution.schedulers.TestScheduler

import scala.concurrent.duration._
import scala.util.{Failure, Success}
import cats.effect.Temporal

object TaskClockTimerAndContextShiftSuite extends BaseTestSuite {
  test("IO.clock is implicit") { _ =>
    assertEquals(IO.clock[Any], implicitly[Clock[IO[Any, *]]])
  }

  test("IO.clock.monotonic") { implicit s =>
    s.tick(1.seconds)

    val f = IO.clock.monotonic(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.clock.realTime") { implicit s =>
    s.tick(1.seconds)

    val f = IO.clock.realTime(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.clock(s2).monotonic") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.seconds)

    val f = IO.clock(s2).monotonic(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.clock(s).realTime") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.seconds)

    val f = IO.clock(s2).realTime(TimeUnit.SECONDS).runToFuture
    s.tick()

    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.timer is implicit") { implicit s =>
    assertEquals(IO.timer[Any], implicitly[Temporal[IO[Any, *]]])
    assertEquals(IO.timer[Any].clock, implicitly[Clock[IO[Any, *]]])
  }

  test("IO.timer") { implicit s =>
    val f = IO.timer.sleep(1.second).runToFuture
    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(())))
  }

  test("IO.timer(s)") { implicit s =>
    val s2 = TestScheduler()
    val f = IO.timer(s2).sleep(1.second).attempt.runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, None)
    s2.tick(1.second)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("IO.timer(s).clock") { implicit s =>
    val s2 = TestScheduler()
    s2.tick(1.second)

    val f = IO.timer(s2).clock.monotonic(TimeUnit.SECONDS).attempt.runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(Right(1))))
  }

  test("IO.contextShift is implicit") { implicit s =>
    assertEquals(IO.contextShift[Any], implicitly[ContextShift[IO[Any, *]]])
  }

  test("IO.contextShift.shift") { implicit s =>
    val f = IO.contextShift.shift.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("IO.contextShift.evalOn(s2)") { implicit s =>
    val s2 = TestScheduler()
    val f = IO.contextShift.evalOn(s2)(IO(1)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.contextShift.evalOn(s2) failure") { implicit s =>
    val s2 = TestScheduler()
    val f = IO.contextShift.evalOn(s2)(IO.raiseError("dummy")).attempt.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Left("dummy"))))
  }

  test("IO.contextShift.evalOn(s2) fatal failure") { implicit s =>
    val s2 = TestScheduler()
    val dummy = DummyException("dummy")
    val f = IO.contextShift.evalOn(s2)(IO.terminate(dummy)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.contextShift.evalOn(s2) uses s2 for async boundaries") { implicit s =>
    val s2 = TestScheduler()
    val f = IO.contextShift.evalOn(s2)(IO(1).delayExecution(100.millis)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick(100.millis)
    assertEquals(f.value, None)
    s2.tick(100.millis)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.contextShift.evalOn(s2) injects s2 to IO.deferAction") { implicit s =>
    val s2 = TestScheduler()

    var wasScheduled = false
    val runnable = new Runnable {
      override def run(): Unit = wasScheduled = true
    }

    val f =
      IO.contextShift.evalOn(s2)(IO.deferAction(scheduler => IO(scheduler.execute(runnable)))).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(wasScheduled, true)
    s.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("IO.contextShift(s).shift") { implicit s =>
    val f = IO.contextShift(s).shift.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(())))
  }

  test("IO.contextShift(s).evalOn(s2)") { implicit s =>
    val s2 = TestScheduler()
    val f = IO.contextShift(s).evalOn(s2)(IO(1)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.contextShift(s).evalOn(s2) failure") { implicit s =>
    val s2 = TestScheduler()
    val f = IO.contextShift(s).evalOn(s2)(IO.raiseError("dummy")).attempt.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(Left("dummy"))))
  }

  test("IO.contextShift(s).evalOn(s2) fatal failure") { implicit s =>
    val s2 = TestScheduler()
    val dummy = DummyException("dummy")
    val f = IO.contextShift(s).evalOn(s2)(IO.terminate(dummy)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.contextShift(s).evalOn(s2) uses s2 for async boundaries") { implicit s =>
    val s2 = TestScheduler()
    val f = IO.contextShift[Throwable](s).evalOn(s2)(IO(1).delayExecution(100.millis)).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    s.tick(100.millis)
    assertEquals(f.value, None)
    s2.tick(100.millis)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("IO.contextShift(s).evalOn(s2) injects s2 to IO.deferAction") { implicit s =>
    val s2 = TestScheduler()

    var wasScheduled = false
    val runnable = new Runnable {
      override def run(): Unit = wasScheduled = true
    }

    val f =
      IO.contextShift(s).evalOn(s2)(IO.deferAction(scheduler => IO(scheduler.execute(runnable)))).runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(wasScheduled, true)
    s.tick()
    assertEquals(f.value, Some(Success(())))
  }
}

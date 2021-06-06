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
import monix.execution.internal.Platform

import scala.util.{Failure, Success}

object TaskMapBothSuite extends BaseTestSuite {
  test("if both tasks are synchronous, then mapBoth forks") { implicit s =>
    val ta = IO.eval(1)
    val tb = IO.eval(2)

    val r = IO.mapBoth(ta, tb)(_ + _)
    val f = r.runToFuture
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(3)))
  }

  test("sum two async tasks") { implicit s =>
    val ta = IO.evalAsync(1)
    val tb = IO.evalAsync(2)

    val r = IO.mapBoth(ta, tb)(_ + _)
    val f = r.runToFuture; s.tick()
    assertEquals(f.value.get, Success(3))
  }

  test("sum two synchronous tasks") { implicit s =>
    val ta = IO.eval(1)
    val tb = IO.eval(2)

    val r = IO.mapBoth(ta, tb)(_ + _)
    val f = r.runToFuture; s.tick()
    assertEquals(f.value.get, Success(3))
  }

  test("should be stack-safe for synchronous tasks") { implicit s =>
    val count = 10000
    val tasks = (0 until count).map(x => IO.eval(x))
    val init = IO.eval(0L)

    val sum = tasks.foldLeft(init)((acc, t) => IO.mapBoth(acc, t)(_ + _))
    val result = sum.runToFuture

    s.tick()
    assertEquals(result.value.get, Success(count * (count - 1) / 2))
  }

  test("should be stack-safe for asynchronous tasks") { implicit s =>
    val count = 10000
    val tasks = (0 until count).map(x => IO.evalAsync(x))
    val init = IO.eval(0L)

    val sum = tasks.foldLeft(init)((acc, t) => IO.mapBoth(acc, t)(_ + _))
    val result = sum.runToFuture

    s.tick()
    assertEquals(result.value.get, Success(count * (count - 1) / 2))
  }

  test("should have a stack safe cancelable") { implicit sc =>
    val count = if (Platform.isJVM) 10000 else 1000

    val tasks = (0 until count).map(_ => IO.never[Int])
    val all = tasks.foldLeft(Task.now(0))((acc, t) => IO.mapBoth(acc, t)(_ + _))
    val f = all.runToFuture

    sc.tick()
    f.cancel()
    sc.tick()

    assert(sc.state.tasks.isEmpty, "tasks.isEmpty")
    assertEquals(f.value, None)
  }

  test("sum random synchronous tasks") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val sum = numbers.foldLeft(Task.now(0))((acc, t) => IO.mapBoth(acc, IO.eval(t))(_ + _))
      sum <-> Task.now(numbers.sum)
    }
  }

  test("sum random asynchronous tasks") { implicit s =>
    check1 { (numbers: List[Int]) =>
      val sum = numbers.foldLeft(Task.evalAsync(0))((acc, t) => IO.mapBoth(acc, IO.evalAsync(t))(_ + _))
      sum <-> Task.evalAsync(numbers.sum)
    }
  }

  test("both task can fail with error") { implicit s =>
    val err1 = new RuntimeException("Error 1")
    val t1 = IO.defer(Task.raiseError[Int](err1)).executeAsync
    val err2 = new RuntimeException("Error 2")
    val t2 = IO.defer(Task.raiseError[Int](err2)).executeAsync

    val fb = IO
      .mapBoth(t1, t2)(_ + _)
      .executeWithOptions(_.disableAutoCancelableRunLoops)
      .runToFuture

    s.tick()
    fb.value match {
      case Some(Failure(`err1`)) =>
        assertEquals(s.state.lastReportedError, err2)
      case Some(Failure(`err2`)) =>
        assertEquals(s.state.lastReportedError, err1)
      case other =>
        fail(s"fb.value is $other")
    }
  }

  test("both task can fail with terminal error") { implicit s =>
    val err1 = new RuntimeException("Error 1")
    val t1: UIO[Int] = UIO.defer(IO.terminate(err1)).executeAsync
    val err2 = new RuntimeException("Error 2")
    val t2: UIO[Int] = UIO.defer(IO.terminate(err2)).executeAsync

    val fb = IO
      .mapBoth(t1, t2)(_ + _)
      .executeWithOptions(_.disableAutoCancelableRunLoops)
      .runToFuture

    s.tick()
    fb.value match {
      case Some(Failure(`err1`)) =>
        assertEquals(s.state.lastReportedError, err2)
      case Some(Failure(`err2`)) =>
        assertEquals(s.state.lastReportedError, err1)
      case other =>
        fail(s"fb.value is $other")
    }
  }
}

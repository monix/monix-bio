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

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskDeferActionSuite extends BaseTestSuite {
  test("IO.deferAction works") { implicit s =>
    def measureLatency[E, A](source: IO[E, A]): IO[E, (A, Long)] =
      IO.deferAction { implicit s =>
        val start = s.clockMonotonic(MILLISECONDS)
        source.map(a => (a, s.clockMonotonic(MILLISECONDS) - start))
      }

    val task = measureLatency(IO.now("hello").delayExecution(1.second))
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, Some(Success(("hello", 1000))))
  }

  test("IO.deferAction works for failed tasks") { implicit s =>
    val dummy = "dummy"
    val task = IO.deferAction(_ => IO.raiseError(dummy))
    val f = task.attempt.runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("IO.deferAction protects against user error") { implicit s =>
    val dummy = DummyException("dummy")
    val task = IO.deferAction(_ => throw dummy)
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.deferAction should properly cast errors") { implicit s =>
    val dummy = DummyException("dummy")
    val task = IO.deferAction[Int, Int](_ => throw dummy)
    val f = task.onErrorHandle(identity).runToFuture

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.deferAction is stack safe") { implicit sc =>
    def loop(n: Int, acc: Int): Task[Int] =
      IO.deferAction { _ =>
        if (n > 0)
          loop(n - 1, acc + 1)
        else
          Task.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(10000)))
  }

  testAsync("IO.deferAction(local.write) works") { _ =>
    import monix.execution.Scheduler.Implicits.global
    implicit val opts = IO.defaultOptions.enableLocalContextPropagation

    val task = for {
      l <- IOLocal(10)
      _ <- IO.deferAction(_ => l.write(100))
      _ <- IO.shift
      v <- l.read
    } yield v

    for (v <- task.runToFutureOpt) yield {
      assertEquals(v, 100)
    }
  }
}

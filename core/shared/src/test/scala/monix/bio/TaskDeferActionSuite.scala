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

import monix.execution.exceptions.DummyException

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TaskDeferActionSuite extends BaseTestSuite {
  test("BIO.deferAction works") { implicit s =>
    def measureLatency[E, A](source: BIO[E, A]): BIO[E, (A, Long)] =
      BIO.deferAction { implicit s =>
        val start = s.clockMonotonic(MILLISECONDS)
        source.map(a => (a, s.clockMonotonic(MILLISECONDS) - start))
      }

    val task = measureLatency(BIO.now("hello").delayExecution(1.second))
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, None)

    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(("hello", 1000)))))
  }

  test("BIO.deferAction works for failed tasks") { implicit s =>
    val dummy = "dummy"
    val task = BIO.deferAction(_ => BIO.raiseError(dummy))
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(Left(dummy))))
  }

  test("BIO.deferAction protects against user error") { implicit s =>
    val dummy = DummyException("dummy")
    val task = BIO.deferAction(_ => throw dummy)
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.deferAction should properly cast errors") { implicit s =>
    val dummy = DummyException("dummy")
    val task = BIO.deferAction[Int, Int](_ => throw dummy)
    val f = task.onErrorHandle(identity).runToFuture

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("BIO.deferAction is stack safe") { implicit sc =>
    def loop(n: Int, acc: Int): Task[Int] =
      BIO.deferAction { _ =>
        if (n > 0)
          loop(n - 1, acc + 1)
        else
          Task.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }

//  testAsync("deferAction(local.write) works") { _ =>
//    import monix.execution.Scheduler.Implicits.global
//    implicit val opts = BIO.defaultOptions.enableLocalContextPropagation
//
//    val task = for {
//      l <- TaskLocal(10)
//      _ <- Task.deferAction(_ => l.write(100))
//      _ <- Task.shift
//      v <- l.read
//    } yield v
//
//    for (v <- task.runToFutureOpt) yield {
//      assertEquals(v, 100)
//    }
//  }
}

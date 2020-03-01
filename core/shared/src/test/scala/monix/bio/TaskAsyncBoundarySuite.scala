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

import monix.execution.schedulers.TestScheduler

import scala.util.Success

object TaskAsyncBoundarySuite extends BaseTestSuite {
  test("BIO.asyncBoundary should work") { implicit s =>
    val io = TestScheduler()
    var effect = 0
    val f = UIO.eval { effect += 1; effect }
      .executeOn(io)
      .asyncBoundary
      .map(_ + 1)
      .runToFuture

    assertEquals(effect, 0)
    s.tick()
    assertEquals(effect, 0)

    io.tick()
    assertEquals(effect, 1)
    assertEquals(f.value, None)

    s.tick()
    assertEquals(f.value, Some(Success(Right(2))))
  }

  test("BIO.asyncBoundary(other) should work") { implicit s1 =>
    val io = TestScheduler()
    val s2 = TestScheduler()

    var effect = 0
    val f = UIO.eval { effect += 1; effect }
      .executeOn(io)
      .asyncBoundary(s2)
      .map(_ + 1)
      .runToFuture

    assertEquals(effect, 0)
    s1.tick()
    assertEquals(effect, 0)

    io.tick()
    assertEquals(effect, 1)
    assertEquals(f.value, None)

    s1.tick()
    assertEquals(f.value, None)
    s2.tick()
    assertEquals(f.value, Some(Success(Right(2))))
  }

  testAsync("BIO.asyncBoundary should preserve locals") { _ =>
    import monix.execution.Scheduler.Implicits.global
    implicit val opts = BIO.defaultOptions.enableLocalContextPropagation

    val task = for {
      l <- TaskLocal(10)
      _ <- l.write(100).asyncBoundary
      v <- l.read
    } yield v

    for (v <- task.runToFutureOpt) yield {
      assertEquals(v, Right(100))
    }
  }

  testAsync("BIO.asyncBoundary(scheduler) should preserve locals") { _ =>
    import monix.execution.Scheduler.Implicits.global
    implicit val opts = BIO.defaultOptions.enableLocalContextPropagation

    val task = for {
      l <- TaskLocal(10)
      _ <- l.write(100).asyncBoundary(global)
      v <- l.read
    } yield v

    for (v <- task.runToFutureOpt) yield {
      assertEquals(v, Right(100))
    }
  }

  test("BIO.asyncBoundary is stack safe in flatMap loops, test 1") { implicit sc =>
    def loop(n: Int, acc: Long): UIO[Long] =
      BIO.unit.asyncBoundary.flatMap { _ =>
        if (n > 0)
          loop(n - 1, acc + 1)
        else
          BIO.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }

  test("BIO.asyncBoundary is stack safe in flatMap loops, test 2") { implicit sc =>
    def loop(n: Int, acc: Long): UIO[Long] =
      BIO.unit.flatMap { _ =>
        if (n > 0)
          loop(n - 1, acc + 1).asyncBoundary
        else
          BIO.now(acc)
      }

    val f = loop(10000, 0).runToFuture
    sc.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }
}

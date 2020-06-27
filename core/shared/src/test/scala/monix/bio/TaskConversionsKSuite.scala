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

import cats.effect.IO
import monix.catnap.SchedulerEffect

import scala.util.Success

object TaskConversionsKSuite extends BaseTestSuite {
  test("BIO.liftTo[IO]") { implicit s =>
    var effect = 0
    val task = BIO { effect += 1; effect }
    val io = BIO.liftTo[IO].apply(task)

    assertEquals(io.unsafeRunSync(), 1)
    assertEquals(io.unsafeRunSync(), 2)
  }

  test("BIO.liftToAsync[IO]") { implicit s =>
    var effect = 0
    val task = BIO { effect += 1; effect }
    val io = BIO.liftToAsync[IO].apply(task)

    assertEquals(io.unsafeRunSync(), 1)
    assertEquals(io.unsafeRunSync(), 2)
  }

  test("BIO.liftToConcurrent[IO]") { implicit s =>
    implicit val cs = SchedulerEffect.contextShift[IO](s)
    var effect = 0
    val task = BIO { effect += 1; effect }
    val io = BIO.liftToConcurrent[IO].apply(task)

    assertEquals(io.unsafeRunSync(), 1)
    assertEquals(io.unsafeRunSync(), 2)
  }

  test("BIO.liftFrom[IO]") { implicit s =>
    var effect = 0
    val io0 = IO { effect += 1; effect }
    val task = BIO.liftFrom[IO].apply(io0)

    val f1 = task.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(1)))
    val f2 = task.runToFuture; s.tick()
    assertEquals(f2.value, Some(Success(2)))
  }

  test("BIO.liftFromEffect[IO]") { implicit s =>
    var effect = 0
    val io0 = IO { effect += 1; effect }
    val task = BIO.liftFromEffect[IO].apply(io0)

    val f1 = task.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(1)))
    val f2 = task.runToFuture; s.tick()
    assertEquals(f2.value, Some(Success(2)))
  }

  test("BIO.liftFromConcurrentEffect[IO]") { implicit s =>
    implicit val cs = SchedulerEffect.contextShift[IO](s)

    var effect = 0
    val io0 = IO { effect += 1; effect }
    val task = BIO.liftFromConcurrentEffect[IO].apply(io0)

    val f1 = task.runToFuture; s.tick()
    assertEquals(f1.value, Some(Success(1)))
    val f2 = task.runToFuture; s.tick()
    assertEquals(f2.value, Some(Success(2)))
  }
}

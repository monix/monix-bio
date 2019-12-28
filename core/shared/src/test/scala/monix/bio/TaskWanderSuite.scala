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
import monix.execution.internal.Platform

import concurrent.duration._
import scala.util.{Failure, Success}

object TaskWanderSuite extends BaseTestSuite {
  test("BIO.wander should execute in parallel for async tasks") { implicit s =>
    val seq = Seq((1, 2), (2, 1), (3, 3))
    val f = BIO
      .wander(seq) {
        case (i, d) =>
          BIO.evalAsync(i + 1).delayExecution(d.seconds)
      }
      .runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(Seq(2, 3, 4)))))
  }

  test("BIO.wander should onError if one of the tasks terminates in error") { implicit s =>
    val ex = DummyException("dummy")
    val seq = Seq((1, 3), (-1, 1), (3, 2), (3, 1))
    val f = BIO
      .wander(seq) {
        case (i, d) =>
          BIO
            .evalAsync(if (i < 0) throw ex else i + 1)
            .delayExecution(d.seconds)
      }
      .runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("BIO.wander should be canceled") { implicit s =>
    val seq = Seq((1, 2), (2, 1), (3, 3))
    val f = BIO
      .wander(seq) {
        case (i, d) => BIO.evalAsync(i + 1).delayExecution(d.seconds)
      }
      .runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)

    f.cancel()
    s.tick(1.second)
    assertEquals(f.value, None)
  }

  test("BIO.wander should be stack safe for synchronous tasks") { implicit s =>
    val count = if (Platform.isJVM) 200000 else 5000
    val seq = for (i <- 0 until count) yield 1
    val composite = BIO.wander(seq)(BIO.now).map(_.sum)
    val result = composite.runToFuture
    s.tick()
    assertEquals(result.value, Some(Success(Right(count))))
  }

  // test("Task.wander runAsync multiple times") { implicit s =>
  //   var effect = 0

  //   val task1 = BIO.evalAsync { effect += 1; 3 }.memoize

  //   val task2 = BIO.wander(Seq(0, 0, 0)) { _ =>
  //     task1 map { x =>
  //       effect += 1; x + 1
  //     }
  //   }

  //   val result1 = task2.runToFuture; s.tick()
  //   assertEquals(result1.value, Some(Success(List(4, 4, 4))))
  //   assertEquals(effect, 1 + 3)

  //   val result2 = task2.runToFuture; s.tick()
  //   assertEquals(result2.value, Some(Success(List(4, 4, 4))))
  //   assertEquals(effect, 1 + 3 + 3)
  // }

  test("Task.wander should wrap exceptions in the function") { implicit s =>
    val ex = DummyException("dummy")
    val task1 = BIO.wander(Seq(0)) { i =>
      throw ex
      BIO.now(i)
    }

    val result1 = task1.runToFuture; s.tick()
    assertEquals(result1.value, Some(Failure((ex))))
  }
}

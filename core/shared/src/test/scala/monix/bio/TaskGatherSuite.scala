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

import scala.concurrent.duration._
import scala.util.Success

object TaskGatherSuite extends BaseTestSuite {
  test("Task.gather should execute in parallel for async tasks") { implicit s =>
    val seq = Seq(
      Task.evalAsync(1).delayExecution(2.seconds),
      Task.evalAsync(2).delayExecution(1.second),
      Task.evalAsync(3).delayExecution(3.seconds)
    )
    val f = BIO.gather(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Right(Seq(1, 2, 3)))))
  }

  test("Task.gather should onError if one of the tasks terminates in error") { implicit s =>
    val ex = DummyException("dummy")
    val seq = Seq(
      Task.evalAsync(3).delayExecution(3.seconds),
      Task.evalAsync(2).delayExecution(1.second),
      Task.evalAsync(throw ex).delayExecution(2.seconds),
      Task.evalAsync(3).delayExecution(1.seconds)
    )

    val f = BIO.gather(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("Task.gather should be canceled") { implicit s =>
    val seq = Seq(
      Task.evalAsync(1).delayExecution(2.seconds),
      Task.evalAsync(2).delayExecution(1.second),
      Task.evalAsync(3).delayExecution(3.seconds)
    )
    val f = BIO.gather(seq).runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)

    f.cancel()
    s.tick(1.second)
    assertEquals(f.value, None)
  }

  test("Task.gather should be stack safe for synchronous tasks") { implicit s =>
    val count = if (Platform.isJVM) 200000 else 5000
    val tasks = for (_ <- 0 until count) yield Task.now(1)
    val composite = BIO.gather(tasks).map(_.sum)
    val result = composite.runToFuture
    s.tick()
    assertEquals(result.value, Some(Success(Right(count))))
  }

  // TODO: uncomment once memoize is added
//  test("Task.gather runAsync multiple times") { implicit s =>
//    var effect = 0
//    val task1 = Task.evalAsync { effect += 1; 3 }.memoize
//    val task2 = task1 map { x =>
//      effect += 1; x + 1
//    }
//    val task3 = Task.gather(List(task2, task2, task2))
//
//    val result1 = task3.runToFuture; s.tick()
//    assertEquals(result1.value, Some(Success(List(4, 4, 4))))
//    assertEquals(effect, 1 + 3)
//
//    val result2 = task3.runToFuture; s.tick()
//    assertEquals(result2.value, Some(Success(List(4, 4, 4))))
//    assertEquals(effect, 1 + 3 + 3)
//  }
}

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

import monix.execution.ExecutionModel.AlwaysAsyncExecution

import scala.util.Success

object TaskExecutionModelSuite extends BaseTestSuite {
  test("BIO.now.executeWithModel(AlwaysAsyncExecution) should work") { implicit s =>
    val task = BIO.now(1).executeWithModel(AlwaysAsyncExecution)
    val f = task.runToFuture

    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO.now.runAsync (CancelableFuture) should not be async with AlwaysAsyncExecution") { s =>
    implicit val s2 = s.withExecutionModel(AlwaysAsyncExecution)
    val task = BIO.now(1)
    val f = task.runToFuture
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO.eval.executeWithModel(AlwaysAsyncExecution) should work") { implicit s =>
    val task = BIO.eval(1).executeWithModel(AlwaysAsyncExecution)
    val f = task.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO.eval should be async with AlwaysAsyncExecution") { s =>
    implicit val s2 = s.withExecutionModel(AlwaysAsyncExecution)
    val task = BIO.eval(1)
    val f = task.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("BIO.now.flatMap loops should work with AlwaysAsyncExecution") { s =>
    implicit val s2 = s.withExecutionModel(AlwaysAsyncExecution)

    def loop(count: Int): UIO[Int] =
      BIO.now(count).flatMap { nr =>
        if (nr > 0) loop(count - 1)
        else BIO.now(nr)
      }

    val task = loop(100)
    val f = task.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(0)))
  }

  test("BIO.eval.flatMap loops should work with AlwaysAsyncExecution") { s =>
    implicit val s2 = s.withExecutionModel(AlwaysAsyncExecution)

    def loop(count: Int): BIO.Unsafe[Int] =
      BIO.eval(count).flatMap { nr =>
        if (nr > 0) loop(count - 1)
        else BIO.eval(nr)
      }

    val task = loop(100)
    val f = task.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(0)))
  }

  test("BIO.flatMap loops should work with AlwaysAsyncExecution") { s =>
    implicit val s2 = s.withExecutionModel(AlwaysAsyncExecution)

    def loop(count: Int): BIO.Unsafe[Int] =
      BIO.evalAsync(count).flatMap { nr =>
        if (nr > 0) loop(count - 1)
        else BIO.evalAsync(nr)
      }

    val task = loop(100)
    val f = task.runToFuture

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(0)))
  }
}

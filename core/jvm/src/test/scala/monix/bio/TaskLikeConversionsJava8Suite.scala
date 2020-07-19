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

import java.util.concurrent.CompletableFuture

import monix.execution.exceptions.DummyException

import scala.util.{Failure, Success}

object TaskLikeConversionsJava8Suite extends BaseTestSuite {

  test("IO.from converts successful CompletableFuture") { implicit s =>
    val future = new CompletableFuture[Int]()
    val f = IO.from(future).runToFuture

    s.tick()
    assertEquals(f.value, None)

    future.complete(123)
    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("IO.from converts failed CompletableFuture") { implicit s =>
    val future = new CompletableFuture[Int]()
    val f = IO.from(future).runToFuture

    s.tick()
    assertEquals(f.value, None)

    val dummy = DummyException("dummy")
    future.completeExceptionally(dummy)

    s.tick()
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("IO.from preserves cancellability of CompletableFuture") { implicit s =>
    val future = new CompletableFuture[Int]()
    val f = IO.from(future).runToFuture

    s.tick()
    assertEquals(f.value, None)

    f.cancel()
    s.tick()
    assert(future.isDone)
    assert(future.isCancelled)
    assertEquals(f.value, None)
  }

}

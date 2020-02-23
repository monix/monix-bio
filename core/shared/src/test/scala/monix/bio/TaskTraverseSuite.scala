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

object TaskTraverseSuite extends BaseTestSuite {
  test("BIO.traverse should not execute in parallel") { implicit s =>
    val seq = Seq((1, 2), (2, 1), (3, 3))
    val f = BIO
      .traverse(seq) {
        case (i, d) =>
          UIO.evalAsync(i + 1).delayExecution(d.seconds)
      }
      .runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, None)
    s.tick(3.second)
    assertEquals(f.value, Some(Success(Right(Seq(2, 3, 4)))))
  }

  test("BIO.traverse should onError if one of the tasks terminates in error") { implicit s =>
    val ex = 1111
    val seq = Seq((1, 2), (-1, 0), (3, 3), (3, 1))
    val f = BIO
      .traverse(seq) {
        case (i, d) =>
          BIO
            .suspendTotal(if (i < 0) BIO.raiseError(ex) else BIO.now(i + 1))
            .delayExecution(d.seconds)
      }
      .runToFuture

    // First
    s.tick(1.second)
    assertEquals(f.value, None)
    // Second
    s.tick(2.second)
    assertEquals(f.value, Some(Success(Left(ex))))
  }

  test("BIO.traverse should onError if one of the tasks terminates in error") { implicit s =>
    val ex = DummyException("dummy")
    val seq = Seq((1, 2), (-1, 0), (3, 3), (3, 1))
    val f = BIO
      .traverse(seq) {
        case (i, d) =>
          UIO
            .evalAsync(if (i < 0) throw ex else i + 1)
            .delayExecution(d.seconds)
      }
      .runToFuture

    // First
    s.tick(1.second)
    assertEquals(f.value, None)
    // Second
    s.tick(2.second)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.traverse should be canceled") { implicit s =>
    val seq = Seq((1, 2), (2, 1), (3, 3))
    val f = BIO
      .traverse(seq) {
        case (i, d) => UIO.evalAsync(i + 1).delayExecution(d.seconds)
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
}

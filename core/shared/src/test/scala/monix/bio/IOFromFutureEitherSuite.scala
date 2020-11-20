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

import scala.concurrent.Future
import scala.util.{Failure, Success}

object IOFromFutureEitherSuite extends BaseTestSuite {
  test("IO.fromFutureEither returns success channel for Right") { implicit s =>
    val mkFuture = Future.successful[Either[String, Int]](Right(10))
    val io = IO.fromFutureEither(mkFuture)
    val f = io.attempt.runToFuture

    assertEquals(f.value, Some(Success(Right(10))))
  }

  test("IO.fromFutureEither returns checked failure channel for Left") { implicit s =>
    val mkFuture = Future.successful[Either[String, Int]](Left("uh-oh"))
    val io = IO.fromFutureEither(mkFuture)
    val f = io.attempt.runToFuture

    assertEquals(f.value, Some(Success(Left("uh-oh"))))
  }

  test("IO.fromFutureEither returns unchecked failure channel for a failed Future") { implicit s =>
    val exception = new Exception("something fatal")
    val mkFuture = Future.failed[Either[String, Int]](exception)
    val io = IO.fromFutureEither(mkFuture)
    val f = io.attempt.runToFuture

    assertEquals(f.value, Some(Failure(exception)))
  }
}

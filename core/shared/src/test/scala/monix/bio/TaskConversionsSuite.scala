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

import cats.effect.IO
import monix.execution.exceptions.DummyException

import scala.util.{Failure, Success}

object TaskConversionsSuite extends BaseTestSuite {

  test("BIO.toAsync converts successful tasks") { implicit s =>
    val io = BIO.now(123).executeAsync.toAsync[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Success(123)))
  }

  test("BIO.toAsync converts tasks with typed errors") { implicit s =>
    val ex = DummyException("Error")
    val io = BIO.raiseError(ex).executeAsync.toAsync[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.toAsync converts tasks with fatal errors") { implicit s =>
    val ex = DummyException("Fatal")
    val io = BIO.raiseFatalError(ex).executeAsync.toAsync[IO]
    val f = io.unsafeToFuture()

    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

}

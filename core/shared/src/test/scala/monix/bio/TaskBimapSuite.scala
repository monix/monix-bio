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

import monix.bio.BIO.{Error, Now}

import scala.util.Success

object TaskBimapSuite extends BaseTestSuite {
  test("it should map the success channel") { implicit s =>
    val f = Now(1)
      .bimap(identity, _ => "Success")
      .runToFuture

    assertEquals(f.value, Some(Success(Right("Success"))))
  }

  test("it should map the error channel") { implicit s =>
    val f = Error(1)
      .bimap(_ => "Error", identity)
      .runToFuture

    assertEquals(f.value, Some(Success(Left("Error"))))
  }
}

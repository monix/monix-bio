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

object IODoOnFinishSuite extends BaseTestSuite {
  test("IO#doOnFinish should work on successful task") { implicit s =>
    var effect = 0
    val successfulIO = UIO(10).delayExecution(1.millisecond)
    val bioWithFinisher = successfulIO.doOnFinish {
      case None => UIO.delay { effect += 2 }
      case _ => UIO.delay { effect += 3 }
    }

    val bioExec = bioWithFinisher.runToFuture

    s.tick(1.millisecond)
    assertEquals(effect, 2)
    assertEquals(bioExec.value, Some(Success(10)))
  }

  test("IO#doOnFinish should work on task with raised error") { implicit s =>
    var effect = 0
    val errorValue = "StringError"
    val errorProneIO = IO.raiseError(errorValue)
    val bioWithFinisher = errorProneIO.doOnFinish {
      case Some(Cause.Error(`errorValue`)) => UIO.delay { effect += 2 }
      case _ => UIO.delay { effect += 3 }
    }

    val bioExec = bioWithFinisher.attempt.runToFuture

    s.tick(1.millisecond)
    assertEquals(effect, 2)
    assertEquals(bioExec.value, Some(Success(Left(errorValue))))
  }

  test("IO#doOnFinish should work on terminated task") { implicit s =>
    var effect = 0
    val fatalError = DummyException("Coincidence")
    val fatalIO = IO
      .now(10)
      .delayExecution(1.millisecond)
      .map(_ => throw fatalError)
    val bioWithFinisher = fatalIO.doOnFinish {
      case Some(Cause.Termination(`fatalError`)) => UIO.delay { effect += 2 }
      case _ => UIO.delay { effect += 3 }
    }

    val bioExec = bioWithFinisher.runToFuture

    s.tick(1.millisecond)
    assertEquals(effect, 2)
    assertEquals(bioExec.value, Some(Failure(fatalError)))
  }

  test("IO#doOnFinish should not trigger any finishing action when IO is cancelled") { implicit s =>
    var effect = 0
    val bioToCancel = IO
      .now(10)
      .delayExecution(10.millisecond)
      .doOnFinish(_ => UIO.delay { effect += 2 })

    bioToCancel.runToFuture.cancel()
    s.tick(10.millisecond)
    assertEquals(effect, 0)
  }
}

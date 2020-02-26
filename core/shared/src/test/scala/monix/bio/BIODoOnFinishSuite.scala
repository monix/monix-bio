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

object BIODoOnFinishSuite extends BaseTestSuite {
  test("BIO#doOnFinish should work on successful task"){ implicit s =>
    var effect = 0
    val successfulBIO = UIO(10).delayExecution(1.millisecond)
    val bioWithFinisher = successfulBIO.doOnFinish {
      case None => UIO.delay { effect += 2 }
      case _ => UIO.delay { effect += 3 }
    }

    val bioExec = bioWithFinisher.runToFuture

    s.tick(1.millisecond)
    assertEquals(effect, 2)
    assertEquals(bioExec.value, Some(Success(Right(10))))
  }

  test("BIO#doOnFinish should work on task with raised error"){ implicit s =>
    var effect = 0
    val errorValue = "StringError"
    val errorProneBIO = BIO.raiseError(errorValue)
    val bioWithFinisher = errorProneBIO.doOnFinish {
      case Some(Cause.Error(`errorValue`)) => UIO.delay { effect += 2 }
      case _ => UIO.delay { effect += 3 }
    }

    val bioExec = bioWithFinisher.runToFuture

    s.tick(1.millisecond)
    assertEquals(effect, 2)
    assertEquals(bioExec.value, Some(Success(Left(errorValue))))
  }

  test("BIO#doOnFinish should work on terminated task"){ implicit s =>
    var effect = 0
    val fatalError = DummyException("Coincidence")
    val fatalBIO = BIO.now(10)
      .delayExecution(1.millisecond)
      .map(_ => throw fatalError)
    val bioWithFinisher = fatalBIO.doOnFinish {
      case Some(Cause.Termination(`fatalError`)) => UIO.delay { effect += 2 }
      case _ => UIO.delay { effect += 3 }
    }

    val bioExec = bioWithFinisher.runToFuture

    s.tick(1.millisecond)
    assertEquals(effect, 2)
    assertEquals(bioExec.value, Some(Failure(fatalError)))
  }

  test("BIO#doOnFinish should not trigger any finishing action when BIO is cancelled"){ implicit s =>
    var effect = 0
    val bioToCancel = BIO.now(10)
      .delayExecution(10.millisecond)
      .doOnFinish(_ => UIO.delay { effect += 2 })

    bioToCancel.runToFuture.cancel()
    s.tick(10.millisecond)
    assertEquals(effect, 0)
  }
}

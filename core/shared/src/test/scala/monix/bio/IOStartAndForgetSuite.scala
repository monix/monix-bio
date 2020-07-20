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
import monix.execution.internal.Platform

import scala.concurrent.duration._
import scala.util.Success

object IOStartAndForgetSuite extends BaseTestSuite {

  test("IO#startAndForget triggers execution in background thread") { implicit sc =>
    var counter = 0
    val bio = IO.eval { counter += 1; counter }

    val main = for {
      _ <- bio.delayExecution(10.millisecond).startAndForget
      _ <- bio.delayExecution(10.millisecond).startAndForget
    } yield ()

    val f = main.runToFuture
    assertEquals(f.value, Some(Success(())))
    assertEquals(counter, 0)

    sc.tick(10.millisecond)
    assertEquals(counter, 2)
  }

  test("IO#startAndForget does not affect execution of main thread with raised errors") { implicit sc =>
    val errorProneIO = IO.raiseError[String]("Failed")
    val successfulIO = IO.now(10).delayExecution(5.millisecond)

    val result = for {
      _     <- errorProneIO.startAndForget
      value <- successfulIO
    } yield value

    val f = result.runToFuture
    sc.tick(5.millisecond)
    assertEquals(f.value, Some(Success(10)))
  }

  test("IO#startAndForget triggers fatal errors in background thread") { implicit sc =>
    val fatalError = new DummyException()
    val successfulIO = IO.now(20)
    val fatalIO = IO.terminate(fatalError)

    val result = for {
      _     <- fatalIO.startAndForget
      value <- successfulIO
    } yield value

    val f = result.runToFuture
    sc.tick()
    assertEquals(f.value, Some(Success(20)))
    assertEquals(sc.state.lastReportedError, fatalError)
  }

  test("IO#startAndForget is stack safe") { implicit sc =>
    val count = if (Platform.isJVM) 100000 else 5000

    var bio: Task[Any] = IO.evalAsync(1)
    for (_ <- 0 until count) bio = bio.startAndForget
    for (_ <- 0 until count) bio = bio.flatMap(_ => IO.unit)

    val f = bio.runToFuture
    sc.tick()
    assertEquals(f.value, Some(Success(())))
  }

}

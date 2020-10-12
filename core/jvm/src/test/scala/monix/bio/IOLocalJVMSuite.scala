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

import minitest.platform.Await
import monix.execution.Scheduler

import scala.concurrent.duration.Duration

object IOLocalJVMSuite extends SimpleIOTestSuite {
  implicit val ec: Scheduler = monix.execution.Scheduler.Implicits.global
  implicit val opts: IO.Options = IO.defaultOptions.enableLocalContextPropagation

  test("TaskLocal.isolate can be nested with executeWithOptions") {
    val t = for {
      local <- IOLocal(0)
      _ <- IOLocal.isolate {
        for {
          _ <- local.write(100)
          _ <- IOLocal.isolate(local.write(200))
          x <- local.read
          _ <- Task(assertEquals(x, 100))
          _ <- local.bind(x * 2)(local.read.map(localValue => assertEquals(x * 2, localValue)))
          _ <- Task(assertEquals(x, 100))
        } yield ()
      }
      y <- local.read
      _ <- Task(assertEquals(y, 0))
    } yield ()

    Await.result(t.executeWithOptions(_.enableLocalContextPropagation).runToFuture, Duration.Inf)
    t.executeWithOptions(_.enableLocalContextPropagation).runSyncUnsafe()
    ()
  }

}

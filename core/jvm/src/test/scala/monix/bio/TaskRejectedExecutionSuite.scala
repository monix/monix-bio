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

import java.util.concurrent.RejectedExecutionException

import minitest.SimpleTestSuite
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object TaskRejectedExecutionSuite extends SimpleTestSuite {

  val limited = Scheduler(new ExecutionContext {
    def execute(runnable: Runnable): Unit = throw new RejectedExecutionException()

    def reportFailure(cause: Throwable): Unit =
      fail("Exceptions should not be reported using scheduler")
  })

  def testRejected[A](task: Task[A]): Unit =
    intercept[RejectedExecutionException] {

      val f = Future.traverse(1 to 10) { _ =>
        task.runToFuture(limited, implicitly[<:<[Throwable, Throwable]])
      }

      Await.result(f, 3.seconds)
    }

  test("Tasks should propagate RejectedExecutionException") {
    testRejected(Task.pure(0).executeAsync)
    testRejected(Task.shift(limited))
    testRejected(Task.pure(0).asyncBoundary(limited))
    testRejected(Task.pure(0).executeOn(limited))
    testRejected(Task.async0[Unit]((_, cb) => global.execute(() => cb.onSuccess(()))))
  }
}

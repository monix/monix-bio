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

import monix.execution.ExecutionModel
import monix.execution.ExecutionModel.{AlwaysAsyncExecution, SynchronousExecution}

import scala.util.Success

object TaskExecuteWithModelSuite extends BaseTestSuite {

  def readModel: UIO[ExecutionModel] =
    BIO.deferAction(s => BIO.now(s.executionModel))

  test("executeWithModel works") { implicit sc =>
    assertEquals(sc.executionModel, ExecutionModel.Default)

    val f1 = readModel.executeWithModel(SynchronousExecution).runToFuture
    assertEquals(f1.value, Some(Success(Right(SynchronousExecution))))

    val f2 = readModel.executeWithModel(AlwaysAsyncExecution).runToFuture
    sc.tick()
    assertEquals(f2.value, Some(Success(Right(AlwaysAsyncExecution))))
  }

  testAsync("local.write.executeWithModel(AlwaysAsyncExecution) works") { _ =>
    import monix.execution.Scheduler.Implicits.global
    implicit val opts = BIO.defaultOptions.enableLocalContextPropagation

    val task = for {
      l <- TaskLocal(10)
      _ <- l.write(100).executeWithModel(AlwaysAsyncExecution)
      _ <- UIO.shift
      v <- l.read
    } yield v

    for (v <- task.runToFutureOpt) yield {
      assertEquals(v, Right(100))
    }
  }

  testAsync("local.write.executeWithModel(SynchronousExecution) works") { _ =>
    import monix.execution.Scheduler.Implicits.global
    implicit val opts = BIO.defaultOptions.enableLocalContextPropagation

    val task = for {
      l <- TaskLocal(10)
      _ <- l.write(100).executeWithModel(SynchronousExecution)
      _ <- UIO.shift
      v <- l.read
    } yield v

    for (v <- task.runToFutureOpt) yield {
      assertEquals(v, Right(100))
    }
  }

  test("executeWithModel is stack safe in flatMap loops") { implicit sc =>
    def loop(n: Int, acc: Long): UIO[Long] =
      BIO.unit.executeWithModel(SynchronousExecution).flatMap { _ =>
        if (n > 0)
          loop(n - 1, acc + 1)
        else
          BIO.now(acc)
      }

    val f = loop(10000, 0).runToFuture; sc.tick()
    assertEquals(f.value, Some(Success(Right(10000))))
  }
}

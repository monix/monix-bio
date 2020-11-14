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

import cats.Eq
import cats.effect.laws.discipline.{ConcurrentEffectTests, ConcurrentTests}
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline.{BifunctorTests, CoflatMapTests, CommutativeApplicativeTests, ParallelTests}
import monix.bio.IO.Options
import monix.execution.exceptions.UncaughtErrorException
import monix.execution.schedulers.TestScheduler

import scala.concurrent.{Future, Promise}
import scala.util.Either

/** Type class tests for Task that use an alternative `Eq`, making
  * use of Task's `runAsync(callback)`.
  */
object TypeClassLawsForTaskWithCallbackSuite
    extends BaseTypeClassLawsForTaskWithCallbackSuite()(IO.defaultOptions.disableAutoCancelableRunLoops)

/** Type class tests for Task that use an alternative `Eq`, making
  * use of Task's `runAsync(callback)` and that evaluate the tasks
  * in auto-cancelable mode.
  */
object TypeClassLawsForTaskAutoCancelableWithCallbackSuite
    extends BaseTypeClassLawsForTaskWithCallbackSuite()(
      IO.defaultOptions.enableAutoCancelableRunLoops
    )

class BaseTypeClassLawsForTaskWithCallbackSuite(implicit opts: IO.Options) extends BaseLawsSuite {
  override implicit def equalityIO[E, A](implicit
    A: Eq[A],
    E: Eq[E],
    ec: TestScheduler,
    opts: Options
  ): Eq[IO[E, A]] = {
    Eq.by { task =>
      val p = Promise[Either[E, A]]()
      task.runAsyncOpt {
        case Left(e) => p.failure(UncaughtErrorException.wrap(e)) // todo: should it be failure or left
        case Right(a) => p.success(Right(a))
      }
      p.future
    }
  }

  override implicit def equalityUIO[A](implicit
    A: Eq[A],
    sc: TestScheduler,
    opts: IO.Options = IO.defaultOptions
  ): Eq[UIO[A]] = {
    Eq.by[UIO[A], Future[A]] { task =>
      val p = Promise[A]()
      task.runAsyncOpt {
        case Left(e) => p.failure(UncaughtErrorException.wrap(e))
        case Right(a) => p.success(a)
      }
      p.future
    }(equalityFuture)
  }

  override implicit def equalityTaskPar[E, A](implicit
    A: Eq[A],
    E: Eq[E],
    ec: TestScheduler,
    opts: Options
  ): Eq[IO.Par[E, A]] = {

    import IO.Par.unwrap
    Eq.by { task =>
      val p = Promise[Either[E, A]]()
      unwrap(task).runAsyncOpt {
        case Left(e) => p.failure(UncaughtErrorException.wrap(e))
        case Right(a) => p.success(Right(a))
      }
      p.future
    }
  }

  checkAllAsync("CoflatMap[Task]") { implicit ec =>
    CoflatMapTests[Task].coflatMap[Int, Int, Int]
  }

  checkAllAsync("Concurrent[Task]") { implicit ec =>
    ConcurrentTests[Task].async[Int, Int, Int]
  }

  checkAllAsync("ConcurrentEffect[Task]") { implicit ec =>
    ConcurrentEffectTests[Task].effect[Int, Int, Int]
  }

  checkAllAsync("CommutativeApplicative[IO.Par]") { implicit ec =>
    CommutativeApplicativeTests[IO.Par[String, *]].commutativeApplicative[Int, Int, Int]
  }

  checkAllAsync("Parallel[Task, Task.Par]") { implicit ec =>
    ParallelTests[Task, IO.Par[Throwable, *]].parallel[Int, Int]
  }

  checkAllAsync("Monoid[IO[Throwable, Int]]") { implicit ec =>
    MonoidTests[IO[Throwable, Int]].monoid
  }

  checkAllAsync("Bifunctor[IO[String, Int]]") { implicit ec =>
    BifunctorTests[IO].bifunctor[String, String, String, Int, Int, Int]
  }
}

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

import cats.effect.laws.discipline.{ConcurrentEffectTests, ConcurrentTests}
import cats.laws.discipline.{ApplicativeTests, BifunctorTests, CoflatMapTests, ParallelTests}
import cats.{Applicative, Eq}
import monix.bio.BIO.Options
import monix.bio.instances.CatsParallelForTask
import monix.bio.internal.TaskRunLoop.WrappedException
import monix.execution.schedulers.TestScheduler
import scala.concurrent.{Future, Promise}
import scala.util.Either

/**
  * Type class tests for Task that use an alternative `Eq`, making
  * use of Task's `runAsync(callback)`.
  */
object TypeClassLawsForTaskWithCallbackSuite
    extends BaseTypeClassLawsForTaskWithCallbackSuite()(BIO.defaultOptions.disableAutoCancelableRunLoops)

/**
  * Type class tests for Task that use an alternative `Eq`, making
  * use of Task's `runAsync(callback)` and that evaluate the tasks
  * in auto-cancelable mode.
  */
object TypeClassLawsForTaskAutoCancelableWithCallbackSuite
    extends BaseTypeClassLawsForTaskWithCallbackSuite()(
      BIO.defaultOptions.enableAutoCancelableRunLoops
    )

class BaseTypeClassLawsForTaskWithCallbackSuite(implicit opts: BIO.Options) extends BaseLawsSuite {

  implicit val ap: Applicative[BIO.Par[Throwable, ?]] = new CatsParallelForTask[Throwable].applicative

  override implicit def equalityWRYYY[E, A](
    implicit
    A: Eq[A],
    E: Eq[E],
    ec: TestScheduler,
    opts: Options): Eq[BIO[E, A]] = {
    Eq.by { task =>
      val p = Promise[Either[E, A]]()
      task.runAsyncOpt {
        case Left(e) => p.failure(WrappedException.wrap(e)) // todo: should it be failure or left
        case Right(a) => p.success(Right(a))
      }
      p.future
    }
  }

  override implicit def equalityUIO[A](
    implicit
    A: Eq[A],
    sc: TestScheduler,
    opts: BIO.Options = BIO.defaultOptions): Eq[UIO[A]] = {
    Eq.by[UIO[A], Future[A]] { task =>
      val p = Promise[A]()
      task.runAsyncOpt {
        case Left(e) => p.failure(WrappedException.wrap(e))
        case Right(a) => p.success(a)
      }
      p.future
    }(equalityFuture)
  }

  override implicit def equalityTaskPar[E, A](
    implicit
    A: Eq[A],
    E: Eq[E],
    ec: TestScheduler,
    opts: Options): Eq[BIO.Par[E, A]] = {

    import BIO.Par.unwrap
    Eq.by { task =>
      val p = Promise[Either[E, A]]()
      unwrap(task).runAsyncOpt {
        case Left(e) => p.failure(WrappedException.wrap(e))
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

  checkAllAsync("Applicative[Task.Par]") { implicit ec =>
    ApplicativeTests[BIO.Par[Throwable, ?]].applicative[Int, Int, Int]
  }

  checkAllAsync("Parallel[Task, Task.Par]") { implicit ec =>
    ParallelTests[Task, BIO.Par[Throwable, ?]].parallel[Int, Int]
  }

//  checkAllAsync("Monoid[Task[Int]]") { implicit ec =>
//    MonoidTests[Task[Int]].monoid
//  }

  checkAllAsync("Bifunctor[BIO[String, Int]]") { implicit ec =>
    BifunctorTests[BIO].bifunctor[String, String, String, Int, Int, Int]
  }
}

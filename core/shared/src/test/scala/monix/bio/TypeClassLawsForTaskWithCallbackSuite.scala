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
import monix.bio.BIO.Options
import monix.execution.exceptions.UncaughtErrorException
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
  override implicit def equalityBIO[E, A](
    implicit
    A: Eq[A],
    E: Eq[E],
    ec: TestScheduler,
    opts: Options
  ): Eq[BIO[E, A]] = {
    Eq.by { task =>
      val p = Promise[Either[E, A]]()
      task.runAsyncOpt {
        case Left(e) => p.failure(UncaughtErrorException.wrap(e)) // todo: should it be failure or left
        case Right(a) => p.success(Right(a))
      }
      p.future
    }
  }

  override implicit def equalityUIO[A](
    implicit
    A: Eq[A],
    sc: TestScheduler,
    opts: BIO.Options = BIO.defaultOptions
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

  override implicit def equalityTaskPar[E, A](
    implicit
    A: Eq[A],
    E: Eq[E],
    ec: TestScheduler,
    opts: Options
  ): Eq[BIO.Par[E, A]] = {

    import BIO.Par.unwrap
    Eq.by { task =>
      val p = Promise[Either[E, A]]()
      unwrap(task).runAsyncOpt {
        case Left(e) => p.failure(UncaughtErrorException.wrap(e))
        case Right(a) => p.success(Right(a))
      }
      p.future
    }
  }

  checkAllAsync("CoflatMap[BIO.Unsafe]") { implicit ec =>
    CoflatMapTests[BIO.Unsafe].coflatMap[Int, Int, Int]
  }

  checkAllAsync("Concurrent[BIO.Unsafe]") { implicit ec =>
    ConcurrentTests[BIO.Unsafe].async[Int, Int, Int]
  }

  checkAllAsync("ConcurrentEffect[BIO.Unsafe]") { implicit ec =>
    ConcurrentEffectTests[BIO.Unsafe].effect[Int, Int, Int]
  }

  checkAllAsync("CommutativeApplicative[BIO.Par]") { implicit ec =>
    CommutativeApplicativeTests[BIO.Par[String, *]].commutativeApplicative[Int, Int, Int]
  }

  checkAllAsync("Parallel[BIO.Unsafe, BIO.Par]") { implicit ec =>
    ParallelTests[BIO.Unsafe, BIO.Par[Throwable, *]].parallel[Int, Int]
  }

  checkAllAsync("Monoid[BIO[Throwable, Int]]") { implicit ec =>
    MonoidTests[BIO[Throwable, Int]].monoid
  }

  checkAllAsync("Bifunctor[BIO[String, Int]]") { implicit ec =>
    BifunctorTests[BIO].bifunctor[String, String, String, Int, Int, Int]
  }
}

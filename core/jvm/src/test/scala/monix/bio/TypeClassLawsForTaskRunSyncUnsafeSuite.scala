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

import cats.effect.IO
import cats.effect.laws.discipline._
import cats.laws.discipline.{ApplicativeTests, BifunctorTests, CoflatMapTests, ParallelTests}
import cats.kernel.laws.discipline.MonoidTests
import cats.{Applicative, Eq}
import monix.bio.instances.CatsParallelForTask
import monix.execution.{Scheduler, UncaughtExceptionReporter}
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import scala.util.Try

/**
  * Type class tests for Task that use an alternative `Eq`, making
  * use of Task's `runSyncUnsafe`.
  */
object TypeClassLawsForTaskRunSyncUnsafeSuite
    extends BaseTypeClassLawsForTaskRunSyncUnsafeSuite()(
      Task.defaultOptions.disableAutoCancelableRunLoops
    )

/**
  * Type class tests for Task that use an alternative `Eq`, making
  * use of Task's `runSyncUnsafe`, with the tasks being evaluated
  * in auto-cancelable mode.
  */
object TypeClassLawsForTaskAutoCancelableRunSyncUnsafeSuite
    extends BaseTypeClassLawsForTaskRunSyncUnsafeSuite()(
      Task.defaultOptions.enableAutoCancelableRunLoops
    )

class BaseTypeClassLawsForTaskRunSyncUnsafeSuite(implicit opts: Task.Options)
    extends monix.execution.BaseLawsSuite with ArbitraryInstancesBase {

  implicit val sc = Scheduler(global, UncaughtExceptionReporter(_ => ()))
  implicit val cs = IO.contextShift(sc)
  implicit val ap: Applicative[Task.Par[Throwable, *]] = new CatsParallelForTask[Throwable].applicative

  val timeout = {
    if (System.getenv("TRAVIS") == "true" || System.getenv("CI") == "true")
      5.minutes
    else
      5.seconds
  }

  implicit val params = Parameters(
    // Disabling non-terminating tests (that test equivalence with Task.never)
    // because they'd behave really badly with an Eq[Task.Unsafe] that depends on
    // blocking threads
    allowNonTerminationLaws = false,
    stackSafeIterationsCount = 10000
  )

  implicit def equalityBIO[E, A](implicit eqA: Eq[A], eqE: Eq[E]): Eq[Task[E, A]] =
    Eq.instance { (a, b) =>
      val ta = Try(a.attempt.runSyncUnsafeOpt(timeout))
      val tb = Try(b.attempt.runSyncUnsafeOpt(timeout))

      equalityTry[Either[E, A]].eqv(ta, tb)
    }

  implicit def equalityTask[A](implicit A: Eq[A]): Eq[Task.Unsafe[A]] =
    Eq.instance { (a, b) =>
      val ta = Try(a.runSyncUnsafeOpt(timeout))
      val tb = Try(b.runSyncUnsafeOpt(timeout))
      equalityTry[A].eqv(ta, tb)
    }

  implicit def equalityTaskPar[A](implicit A: Eq[A]): Eq[Task.Par[Throwable, A]] =
    Eq.instance { (a, b) =>
      import Task.Par.unwrap
      val ta = Try(unwrap(a).runSyncUnsafeOpt(timeout))
      val tb = Try(unwrap(b).runSyncUnsafeOpt(timeout))
      equalityTry[A].eqv(ta, tb)
    }

  implicit def equalityIO[A](implicit A: Eq[A]): Eq[IO[A]] =
    Eq.instance { (a, b) =>
      val ta = Try(a.unsafeRunTimed(timeout).get)
      val tb = Try(b.unsafeRunTimed(timeout).get)
      equalityTry[A].eqv(ta, tb)
    }

  checkAll("CoflatMap[Task.Unsafe]", CoflatMapTests[Task.Unsafe].coflatMap[Int, Int, Int])

  checkAll("Concurrent[Task.Unsafe]", ConcurrentTests[Task.Unsafe].concurrent[Int, Int, Int])

  checkAll("ConcurrentEffect[Task.Unsafe]", ConcurrentEffectTests[Task.Unsafe].concurrentEffect[Int, Int, Int])

  checkAll("Applicative[Task.Par]", ApplicativeTests[Task.Par[Throwable, *]].applicative[Int, Int, Int])

  checkAll("Parallel[Task.Unsafe, Task.Par]", ParallelTests[Task.Unsafe, Task.Par[Throwable, *]].parallel[Int, Int])

  checkAll("Monoid[Task[Throwable, Int]]", MonoidTests[Task[Throwable, Int]].monoid)

  checkAllAsync("Bifunctor[Task[String, Int]]") { implicit ec =>
    BifunctorTests[Task].bifunctor[String, String, String, Int, Int, Int]
  }
}

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

import cats.Eq
import cats.effect.IO
import cats.effect.laws.discipline.Parameters
import cats.effect.laws.discipline.arbitrary.{catsEffectLawsArbitraryForIO, catsEffectLawsCogenForIO}
import monix.bio.internal.TaskCreate
import monix.execution.atomic.Atomic
import monix.execution.exceptions.DummyException
import monix.execution.internal.Platform
import monix.execution.schedulers.TestScheduler
import org.scalacheck.Arbitrary.{arbitrary => getArbitrary}
import org.scalacheck.{Arbitrary, Cogen, Gen}

import scala.util.Either

/**
  * Base trait to inherit in all `monix-bio` tests that use ScalaCheck.
  */
trait BaseLawsSuite extends monix.execution.BaseLawsSuite with ArbitraryInstances {

  /**
    * Customizes Cats-Effect's default params.
    *
    * At the moment of writing, these match the defaults, but it's
    * better to specify these explicitly.
    */
  implicit val params: Parameters =
    Parameters(stackSafeIterationsCount = if (Platform.isJVM) 10000 else 100, allowNonTerminationLaws = true)
}

trait ArbitraryInstances extends ArbitraryInstancesBase {

  implicit def equalityWRYYY[E, A](
    implicit
    A: Eq[A],
    E: Eq[E],
    sc: TestScheduler,
    opts: WRYYY.Options = WRYYY.defaultOptions): Eq[WRYYY[E, A]] = {

    new Eq[WRYYY[E, A]] {
      def eqv(lh: WRYYY[E, A], rh: WRYYY[E, A]): Boolean =
        equalityFutureEither(A, E, sc).eqv(lh.runToFutureOpt, rh.runToFutureOpt)
    }
  }

  implicit def equalityUIO[A](
    implicit
    A: Eq[A],
    sc: TestScheduler,
    opts: WRYYY.Options = WRYYY.defaultOptions): Eq[UIO[A]] = {

    new Eq[UIO[A]] {
      def eqv(lh: UIO[A], rh: UIO[A]): Boolean =
        equalityFuture(A, sc).eqv(
          lh.runToFutureOpt.map(_.getOrElse(throw DummyException("UIO had error"))),
          rh.runToFutureOpt.map(_.getOrElse(throw DummyException("UIO had error")))
        )
    }
  }

  implicit def equalityTaskPar[E, A](
    implicit
    A: Eq[A],
    E: Eq[E],
    ec: TestScheduler,
    opts: WRYYY.Options = WRYYY.defaultOptions): Eq[WRYYY.Par[E, A]] = {
    new Eq[WRYYY.Par[E, A]] {
      import WRYYY.Par.unwrap
      def eqv(lh: WRYYY.Par[E, A], rh: WRYYY.Par[E, A]): Boolean =
        Eq[WRYYY[E, A]].eqv(unwrap(lh), unwrap(rh))
    }
  }

  implicit def equalityIO[A](implicit A: Eq[A], ec: TestScheduler): Eq[IO[A]] =
    new Eq[IO[A]] {

      def eqv(x: IO[A], y: IO[A]): Boolean =
        equalityFuture[A].eqv(x.unsafeToFuture(), y.unsafeToFuture())
    }
}

trait ArbitraryInstancesBase extends monix.execution.ArbitraryInstances {

  implicit def arbitraryTask[E: Arbitrary, A: Arbitrary: Cogen]: Arbitrary[WRYYY[E, A]] = {
    def genPure: Gen[WRYYY[E, A]] =
      getArbitrary[A].map(WRYYY.pure)

    def genEvalAsync: Gen[WRYYY[E, A]] =
      getArbitrary[A].map(WRYYY.evalAsync(_).onErrorHandleWith(ex => WRYYY.raiseFatalError(ex)))

    def genEval: Gen[WRYYY[E, A]] =
      Gen.frequency(
        1 -> getArbitrary[A].map(WRYYY.eval(_).onErrorHandleWith(ex => WRYYY.raiseFatalError(ex))),
        1 -> getArbitrary[A].map(WRYYY(_).onErrorHandleWith(ex => WRYYY.raiseFatalError(ex)))
      )

    def genFail: Gen[WRYYY[E, A]] =
      getArbitrary[E].map(WRYYY.raiseError)

    def genAsync: Gen[WRYYY[E, A]] =
      getArbitrary[(Either[E, A] => Unit) => Unit].map(TaskCreate.async)

    def genCancelable: Gen[WRYYY[E, A]] =
      for (a <- getArbitrary[A]) yield TaskCreate.cancelable0[E, A] { (sc, cb) =>
        val isActive = Atomic(true)
        sc.executeAsync { () =>
          if (isActive.getAndSet(false))
            cb.onSuccess(a)
        }
        UIO.eval(isActive.set(false))
      }

    def genNestedAsync: Gen[WRYYY[E, A]] =
      getArbitrary[(Either[E, WRYYY[E, A]] => Unit) => Unit]
        .map(k => TaskCreate.async(k).flatMap(x => x))

    def genBindSuspend: Gen[WRYYY[E, A]] =
      getArbitrary[A].map(WRYYY.evalAsync(_).onErrorHandleWith(ex => WRYYY.raiseFatalError(ex)).flatMap(WRYYY.pure))

    def genSimpleTask = Gen.frequency(
      1 -> genPure,
      1 -> genEval,
      1 -> genEvalAsync,
      1 -> genFail,
      1 -> genAsync,
      1 -> genNestedAsync,
      1 -> genBindSuspend
    )

    def genContextSwitch: Gen[WRYYY[E, A]] =
      for (t <- genSimpleTask) yield {
        WRYYY.ContextSwitch[E, A](t, x => x.copy(), (_, _, old, _) => old)
      }

    def genFlatMap: Gen[WRYYY[E, A]] =
      for {
        ioa <- genSimpleTask
        f <- getArbitrary[A => WRYYY[E, A]]
      } yield ioa.flatMap(f)

    def getMapOne: Gen[WRYYY[E, A]] =
      for {
        ioa <- genSimpleTask
        f <- getArbitrary[A => A]
      } yield ioa.map(f)

    def getMapTwo: Gen[WRYYY[E, A]] =
      for {
        ioa <- genSimpleTask
        f1 <- getArbitrary[A => A]
        f2 <- getArbitrary[A => A]
      } yield ioa.map(f1).map(f2)

    Arbitrary(
      Gen.frequency(
        1 -> genPure,
        1 -> genEvalAsync,
        1 -> genEval,
        1 -> genFail,
        1 -> genContextSwitch,
        1 -> genCancelable,
        1 -> genBindSuspend,
        1 -> genAsync,
        1 -> genNestedAsync,
        1 -> getMapOne,
        1 -> getMapTwo,
        2 -> genFlatMap
      ))
  }

  implicit def arbitraryUIO[A: Arbitrary: Cogen]: Arbitrary[UIO[A]] = {
    Arbitrary(getArbitrary[A].map(UIO(_)))
  }

  implicit def arbitraryUIOf[A: Arbitrary: Cogen, B: Arbitrary: Cogen]: Arbitrary[A => UIO[B]] = {
    Arbitrary(getArbitrary[A => B].map ( f => a => UIO(f(a))))
  }

  implicit def arbitraryTaskPar[E: Arbitrary, A: Arbitrary: Cogen]: Arbitrary[WRYYY.Par[E, A]] =
    Arbitrary(arbitraryTask[E, A].arbitrary.map(WRYYY.Par(_)))

  implicit def arbitraryIO[A: Arbitrary: Cogen]: Arbitrary[IO[A]] =
    catsEffectLawsArbitraryForIO

  implicit def arbitraryExToA[A](implicit A: Arbitrary[A]): Arbitrary[Throwable => A] =
    Arbitrary {
      val fun = implicitly[Arbitrary[Int => A]]
      for (f <- fun.arbitrary) yield (t: Throwable) => f(t.hashCode())
    }

  implicit def arbitraryPfExToA[A](implicit A: Arbitrary[A]): Arbitrary[PartialFunction[Throwable, A]] =
    Arbitrary {
      val fun = implicitly[Arbitrary[Int => A]]
      for (f <- fun.arbitrary) yield { case (t: Throwable) => f(t.hashCode()) }
    }

  implicit def arbitraryTaskToLong[A, B](implicit A: Arbitrary[A], B: Arbitrary[B]): Arbitrary[Task[A] => B] =
    Arbitrary {
      for (b <- B.arbitrary) yield (_: Task[A]) => b
    }

  implicit def arbitraryIOToLong[A, B](implicit A: Arbitrary[A], B: Arbitrary[B]): Arbitrary[IO[A] => B] =
    Arbitrary {
      for (b <- B.arbitrary) yield (_: IO[A]) => b
    }

  implicit def cogenForTask[E, A]: Cogen[WRYYY[E, A]] =
    Cogen[Unit].contramap(_ => ())

  implicit def cogenForIO[A: Cogen]: Cogen[IO[A]] =
    catsEffectLawsCogenForIO
}

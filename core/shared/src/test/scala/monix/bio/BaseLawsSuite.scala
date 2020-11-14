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
import cats.effect.{IO => CIO}
import cats.effect.laws.discipline.Parameters
import cats.effect.laws.discipline.arbitrary.{catsEffectLawsArbitraryForIO, catsEffectLawsCogenForIO}
import monix.bio.internal.TaskCreate
import monix.execution.atomic.Atomic
import monix.execution.internal.Platform
import monix.execution.schedulers.TestScheduler
import org.scalacheck.Arbitrary.{arbitrary => getArbitrary}
import org.scalacheck.{Arbitrary, Cogen, Gen}

import scala.util.Either

/** Base trait to inherit in all `monix-bio` tests that use ScalaCheck.
  */
trait BaseLawsSuite extends monix.execution.BaseLawsSuite with ArbitraryInstances {

  /** Customizes Cats-Effect's default params.
    *
    * At the moment of writing, these match the defaults, but it's
    * better to specify these explicitly.
    */
  implicit val params: Parameters =
    Parameters(stackSafeIterationsCount = if (Platform.isJVM) 10000 else 100, allowNonTerminationLaws = true)
}

trait ArbitraryInstances extends ArbitraryInstancesBase {

  implicit def equalityIO[E, A](implicit
    A: Eq[A],
    E: Eq[E],
    sc: TestScheduler,
    opts: IO.Options = IO.defaultOptions
  ): Eq[IO[E, A]] = {

    new Eq[IO[E, A]] {
      def eqv(lh: IO[E, A], rh: IO[E, A]): Boolean =
        equalityFutureEither(A, E, sc).eqv(lh.attempt.runToFutureOpt, rh.attempt.runToFutureOpt)
    }
  }

  implicit def equalityUIO[A](implicit
    A: Eq[A],
    sc: TestScheduler,
    opts: IO.Options = IO.defaultOptions
  ): Eq[UIO[A]] = {

    new Eq[UIO[A]] {
      def eqv(lh: UIO[A], rh: UIO[A]): Boolean =
        equalityFuture(A, sc).eqv(
          lh.runToFutureOpt,
          rh.runToFutureOpt
        )
    }
  }

  implicit def equalityTaskPar[E, A](implicit
    A: Eq[A],
    E: Eq[E],
    ec: TestScheduler,
    opts: IO.Options = IO.defaultOptions
  ): Eq[IO.Par[E, A]] = {
    new Eq[IO.Par[E, A]] {
      import IO.Par.unwrap
      def eqv(lh: IO.Par[E, A], rh: IO.Par[E, A]): Boolean =
        Eq[IO[E, A]].eqv(unwrap(lh), unwrap(rh))
    }
  }

  implicit def equalityCIO[A](implicit A: Eq[A], ec: TestScheduler): Eq[CIO[A]] =
    new Eq[CIO[A]] {

      def eqv(x: CIO[A], y: CIO[A]): Boolean =
        equalityFuture[A].eqv(x.unsafeToFuture(), y.unsafeToFuture())
    }
}

trait ArbitraryInstancesBase extends monix.execution.ArbitraryInstances {

  implicit def arbitraryTask[E: Arbitrary, A: Arbitrary: Cogen]: Arbitrary[IO[E, A]] = {
    def genPure: Gen[IO[E, A]] =
      getArbitrary[A].map(IO.pure)

    def genEvalAsync: Gen[IO[E, A]] =
      getArbitrary[A].map(UIO.evalAsync(_))

    def genEval: Gen[IO[E, A]] =
      Gen.frequency(
        1 -> getArbitrary[A].map(IO.evalTotal(_)),
        1 -> getArbitrary[A].map(UIO(_))
      )

    def genError: Gen[IO[E, A]] =
      getArbitrary[E].map(IO.raiseError)

    def genTerminate: Gen[IO[E, A]] =
      getArbitrary[Throwable].map(IO.terminate)

    def genAsync: Gen[IO[E, A]] =
      getArbitrary[(Either[Cause[E], A] => Unit) => Unit].map(TaskCreate.async)

    def genCancelable: Gen[IO[E, A]] =
      for (a <- getArbitrary[A]) yield TaskCreate.cancelable0[E, A] { (sc, cb) =>
        val isActive = Atomic(true)
        sc.executeAsync { () =>
          if (isActive.getAndSet(false))
            cb.onSuccess(a)
        }
        UIO.eval(isActive.set(false))
      }

    def genNestedAsync: Gen[IO[E, A]] =
      getArbitrary[(Either[Cause[E], IO[E, A]] => Unit) => Unit]
        .map(k => TaskCreate.async(k).flatMap(x => x))

    def genBindSuspend: Gen[IO[E, A]] =
      getArbitrary[A].map(UIO.evalAsync(_).flatMap(IO.pure))

    def genSimpleTask =
      Gen.frequency(
        1 -> genPure,
        1 -> genEval,
        1 -> genEvalAsync,
        1 -> genError,
        1 -> genTerminate,
        1 -> genAsync,
        1 -> genNestedAsync,
        1 -> genBindSuspend
      )

    def genContextSwitch: Gen[IO[E, A]] =
      for (t <- genSimpleTask) yield {
        IO.ContextSwitch[E, A](t, x => x.copy(), (_, _, old, _) => old)
      }

    def genFlatMap: Gen[IO[E, A]] =
      for {
        ioa <- genSimpleTask
        f   <- getArbitrary[A => IO[E, A]]
      } yield ioa.flatMap(f)

    def getMapOne: Gen[IO[E, A]] =
      for {
        ioa <- genSimpleTask
        f   <- getArbitrary[A => A]
      } yield ioa.map(f)

    def getMapTwo: Gen[IO[E, A]] =
      for {
        ioa <- genSimpleTask
        f1  <- getArbitrary[A => A]
        f2  <- getArbitrary[A => A]
      } yield ioa.map(f1).map(f2)

    Arbitrary(
      Gen.frequency(
        1 -> genPure,
        1 -> genEvalAsync,
        1 -> genEval,
        1 -> genError,
        1 -> genTerminate,
        1 -> genContextSwitch,
        1 -> genCancelable,
        1 -> genBindSuspend,
        1 -> genAsync,
        1 -> genNestedAsync,
        1 -> getMapOne,
        1 -> getMapTwo,
        2 -> genFlatMap
      )
    )
  }

  implicit def arbitraryUIO[A: Arbitrary: Cogen]: Arbitrary[UIO[A]] = {
    Arbitrary(getArbitrary[A].map(UIO(_)))
  }

  implicit def arbitraryCause[E: Arbitrary]: Arbitrary[Cause[E]] = {
    Arbitrary {
      implicitly[Arbitrary[Either[Throwable, E]]].arbitrary.map {
        case Left(value) => Cause.Termination(value)
        case Right(value) => Cause.Error(value)
      }
    }
  }

  implicit def arbitraryUIOf[A: Arbitrary: Cogen, B: Arbitrary: Cogen]: Arbitrary[A => UIO[B]] = {
    Arbitrary(getArbitrary[A => B].map(f => a => UIO(f(a))))
  }

  implicit def arbitraryTaskPar[E: Arbitrary, A: Arbitrary: Cogen]: Arbitrary[IO.Par[E, A]] =
    Arbitrary(arbitraryTask[E, A].arbitrary.map(IO.Par(_)))

  implicit def arbitraryIO[A: Arbitrary: Cogen]: Arbitrary[CIO[A]] =
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

  implicit def arbitraryIOToLong[A, B](implicit A: Arbitrary[A], B: Arbitrary[B]): Arbitrary[CIO[A] => B] =
    Arbitrary {
      for (b <- B.arbitrary) yield (_: CIO[A]) => b
    }

  implicit def cogenForTask[E, A]: Cogen[IO[E, A]] =
    Cogen[Unit].contramap(_ => ())

  implicit def cogenForIO[A: Cogen]: Cogen[CIO[A]] =
    catsEffectLawsCogenForIO
}

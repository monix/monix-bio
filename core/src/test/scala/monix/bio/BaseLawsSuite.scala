package monix.bio

import cats.Eq
import cats.effect.laws.discipline.Parameters
import cats.effect.laws.discipline.arbitrary.{catsEffectLawsArbitraryForIO, catsEffectLawsCogenForIO}
import cats.effect.{Async, IO}
import monix.execution.atomic.Atomic
import monix.execution.internal.Platform
import monix.execution.schedulers.TestScheduler
import org.scalacheck.Arbitrary.{arbitrary => getArbitrary}
import org.scalacheck.{Arbitrary, Cogen, Gen}

import scala.util.{Either, Success, Try}

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
  implicit def equalityTask[A, E](
                                implicit
                                A: Eq[A],
                                sc: TestScheduler,
                                opts: WRYYY.Options = WRYYY.defaultOptions): Eq[WRYYY[E, A]] = {

    new Eq[WRYYY[E, A]] {
      def eqv(lh: WRYYY[E, A], rh: WRYYY[E, A]): Boolean =
        equalityFuture(A, sc).eqv(lh.runToFutureOpt, rh.runToFutureOpt)
    }
  }

//  implicit def equalityTaskPar[A](
//                                   implicit
//                                   A: Eq[A],
//                                   ec: TestScheduler,
//                                   opts: Task.Options = Task.defaultOptions): Eq[Task.Par[A]] = {
//
//    new Eq[Task.Par[A]] {
//      import Task.Par.unwrap
//      def eqv(lh: Task.Par[A], rh: Task.Par[A]): Boolean =
//        Eq[Task[A]].eqv(unwrap(lh), unwrap(rh))
//    }
//  }

  implicit def equalityIO[A](implicit A: Eq[A], ec: TestScheduler): Eq[IO[A]] =
    new Eq[IO[A]] {
      def eqv(x: IO[A], y: IO[A]): Boolean =
        equalityFuture[A].eqv(x.unsafeToFuture(), y.unsafeToFuture())
    }
}

// TODO: Gen [E, A] ?
trait ArbitraryInstancesBase extends monix.execution.ArbitraryInstances {

  implicit def arbitraryTask[A: Arbitrary: Cogen]: Arbitrary[Task[A]] = {
    def genPure: Gen[Task[A]] =
      getArbitrary[A].map(WRYYY.pure)

    def genEvalAsync: Gen[Task[A]] =
      getArbitrary[A].map(WRYYY.evalAsync(_))

    def genEval: Gen[Task[A]] =
      Gen.frequency(
        1 -> getArbitrary[A].map(WRYYY.eval(_)),
        1 -> getArbitrary[A].map(WRYYY(_))
      )

    def genFail: Gen[Task[A]] =
      getArbitrary[Throwable].map(WRYYY.raiseError)

//    def genAsync: Gen[Task[A]] =
//      getArbitrary[(Either[Throwable, A] => Unit) => Unit].map(Async[Task].async)

//    def genCancelable: Gen[Task[A]] =
//      for (a <- getArbitrary[A]) yield Task.cancelable0[A] { (sc, cb) =>
//        val isActive = Atomic(true)
//        sc.executeAsync { () =>
//          if (isActive.getAndSet(false))
//            cb.onSuccess(a)
//        }
//        WRYYY(isActive.set(false))
//      }

//    def genNestedAsync: Gen[Task[A]] =
//      getArbitrary[(Either[Throwable, Task[A]] => Unit) => Unit]
//        .map(k => Async[Task].async(k).flatMap(x => x))

    def genBindSuspend: Gen[Task[A]] =
      getArbitrary[A].map(WRYYY.evalAsync(_).flatMap(WRYYY.pure))

    def genSimpleTask = Gen.frequency(
      1 -> genPure,
      1 -> genEval,
      1 -> genEvalAsync,
      1 -> genFail,
//      1 -> genAsync,
//      1 -> genNestedAsync,
      1 -> genBindSuspend
    )

    def genContextSwitch: Gen[Task[A]] =
      for (t <- genSimpleTask) yield {
        WRYYY.ContextSwitch[Throwable, A](t, x => x.copy(), (_, _, old, _) => old)
      }

    def genFlatMap: Gen[Task[A]] =
      for {
        ioa <- genSimpleTask
        f   <- getArbitrary[A => Task[A]]
      } yield ioa.flatMap(f)

    def getMapOne: Gen[Task[A]] =
      for {
        ioa <- genSimpleTask
        f   <- getArbitrary[A => A]
      } yield ioa.map(f)

    def getMapTwo: Gen[Task[A]] =
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
        1 -> genFail,
        1 -> genContextSwitch,
//        1 -> genCancelable,
        1 -> genBindSuspend,
//        1 -> genAsync,
//        1 -> genNestedAsync,
        1 -> getMapOne,
        1 -> getMapTwo,
        2 -> genFlatMap
      ))
  }

//  implicit def arbitraryTaskPar[A: Arbitrary: Cogen]: Arbitrary[Task.Par[A]] =
//    Arbitrary(arbitraryTask[A].arbitrary.map(Task.Par(_)))

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
}
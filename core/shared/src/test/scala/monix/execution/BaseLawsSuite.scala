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

package monix.execution

import cats.Eq
import cats.laws._
import minitest.SimpleTestSuite
import minitest.laws.Checkers
import monix.execution.exceptions.DummyException
import org.scalacheck.Test.Parameters
import monix.execution.internal.Platform
import monix.execution.schedulers.TestScheduler
import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}
import org.typelevel.discipline.Laws
import scala.concurrent.duration._
import scala.concurrent.{ExecutionException, Future}
import scala.util.{Failure, Success, Try}
import scala.language.implicitConversions

trait BaseLawsSuite extends SimpleTestSuite with Checkers with ArbitraryInstances {

  override lazy val checkConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(if (Platform.isJVM) 100 else 10)
      .withMaxDiscardRatio(if (Platform.isJVM) 5.0f else 50.0f)

  lazy val slowCheckConfig: Parameters =
    Parameters.default
      .withMinSuccessfulTests(10)
      .withMaxDiscardRatio(50.0f)
      .withMaxSize(6)

  def checkAll(name: String, ruleSet: Laws#RuleSet, config: Parameters = checkConfig): Unit = {
    for ((id, prop: Prop) <- ruleSet.all.properties)
      test(name + "." + id) {
        check(prop)
      }
  }

  def checkAllAsync(name: String, config: Parameters = checkConfig)(f: TestScheduler => Laws#RuleSet): Unit = {

    val s = TestScheduler()
    val ruleSet = f(s)

    for ((id, prop: Prop) <- ruleSet.all.properties)
      test(name + "." + id) {
        s.tick(1.day)
        check(prop)
      }
  }
}

trait ArbitraryInstances extends ArbitraryInstancesBase {

  /** Syntax for equivalence in tests. */
  implicit def isEqListToProp[A](list: List[IsEq[A]])(implicit A: Eq[A]): Prop =
    Prop(list.forall(isEq => A.eqv(isEq.lhs, isEq.rhs)))

  implicit def equalityCancelableFuture[E, A](implicit A: Eq[A], E: Eq[E], ec: TestScheduler): Eq[CancelableFuture[Either[E, A]]] =
    new Eq[CancelableFuture[Either[E, A]]] {
      val inst = equalityFutureEither[E, A]

      def eqv(x: CancelableFuture[Either[E, A]], y: CancelableFuture[Either[E, A]]) =
        inst.eqv(x, y)
    }

  implicit def arbitraryCancelableFuture[A](implicit A: Arbitrary[A], ec: Scheduler): Arbitrary[CancelableFuture[A]] =
    Arbitrary {
      for {
        a <- A.arbitrary
        future <- Gen.oneOf(
          CancelableFuture.pure(a),
          CancelableFuture.raiseError(DummyException(a.toString)),
          CancelableFuture.async[A](cb => { cb(Success(a)); Cancelable.empty }),
          CancelableFuture.async[A](cb => { cb(Failure(DummyException(a.toString))); Cancelable.empty }),
          CancelableFuture.pure(a).flatMap(CancelableFuture.pure)
        )
      } yield future
    }

  implicit def arbitraryThrowable: Arbitrary[Throwable] =
    Arbitrary {
      val msg = implicitly[Arbitrary[Int]]
      for (a <- msg.arbitrary) yield DummyException(a.toString)
    }

  implicit def cogenForCancelableFuture[A]: Cogen[CancelableFuture[A]] =
    Cogen[Unit].contramap(_ => ())
}

trait ArbitraryInstancesBase extends cats.instances.AllInstances with TestUtils {

  implicit def equalityFutureEither[E, A](implicit A: Eq[A], E: Eq[E], ec: TestScheduler): Eq[Future[Either[E, A]]] =
    new Eq[Future[Either[E, A]]] {

      def eqv(x: Future[Either[E, A]], y: Future[Either[E, A]]): Boolean = {
        silenceSystemErr {
          // Executes the whole pending queue of runnables
          ec.tick(1.day, maxImmediateTasks = Some(500000))
          x.value match {
            case None =>
              y.value.isEmpty
            case Some(Success(Right(a))) =>
              y.value match {
                case Some(Success(Right(b))) => A.eqv(a, b)
                case _ => false
              }
            case Some(Success(Left(a))) =>
              y.value match {
                case Some(Success(Left(b))) => (a.isInstanceOf[Throwable] && b.isInstanceOf[Throwable]) || E.eqv(a, b)
                case _ => false
              }
            case Some(Failure(_)) =>
              y.value match {
                case Some(Failure(_)) =>
                  // Exceptions aren't values, it's too hard to reason about
                  // throwable equality and all exceptions are essentially
                  // yielding non-terminating futures and tasks from a type
                  // theory point of view, so we simply consider them all equal
                  true
                case _ =>
                  false
              }
          }
        }
      }
    }

  def equalityFuture[A](implicit A: Eq[A], ec: TestScheduler): Eq[Future[A]] =
    new Eq[Future[A]] {
      def eqv(x: Future[A], y: Future[A]): Boolean = {
        silenceSystemErr {
          // Executes the whole pending queue of runnables
          ec.tick(1.day, maxImmediateTasks = Some(500000))

          x.value match {
            case None =>
              y.value.isEmpty
            case Some(Success(a)) =>
              y.value match {
                case Some(Success(b)) => A.eqv(a, b)
                case _ => false
              }
            case Some(Failure(_)) =>
              y.value match {
                case Some(Failure(_)) =>
                  // Exceptions aren't values, it's too hard to reason about
                  // throwable equality and all exceptions are essentially
                  // yielding non-terminating futures and tasks from a type
                  // theory point of view, so we simply consider them all equal
                  true
                case _ =>
                  false
              }
          }
        }
      }
    }

  implicit lazy val equalityThrowable = new Eq[Throwable] {

    override def eqv(x: Throwable, y: Throwable): Boolean = {
      val ex1 = extractEx(x)
      val ex2 = extractEx(y)
      ex1.getClass == ex2.getClass && ex1.getMessage == ex2.getMessage
    }

    // Unwraps exceptions that got caught by Future's implementation
    // and that got wrapped in ExecutionException (`Future(throw ex)`)
    def extractEx(ex: Throwable): Throwable =
      ex match {
        case ref: ExecutionException =>
          Option(ref.getCause).getOrElse(ref)
        case _ =>
          ex
      }
  }

  implicit def equalityTry[A: Eq]: Eq[Try[A]] =
    new Eq[Try[A]] {
      val optA = implicitly[Eq[Option[A]]]
      val optT = implicitly[Eq[Option[Throwable]]]

      def eqv(x: Try[A], y: Try[A]): Boolean =
        if (x.isSuccess) optA.eqv(x.toOption, y.toOption)
        else y.isFailure
    }

  implicit def cogenForThrowable: Cogen[Throwable] =
    Cogen[String].contramap(_.toString)

  implicit def cogenForFuture[A]: Cogen[Future[A]] =
    Cogen[Unit].contramap(_ => ())
}

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.util.control.NonFatal

/**
  * INTERNAL API — test utilities.
  */
trait TestUtils {

  /**
    * Silences `System.err`, only printing the output in case exceptions are
    * thrown by the executed `thunk`.
    */
  def silenceSystemErr[A](thunk: => A): A = synchronized {
    // Silencing System.err
    val oldErr = System.err
    val outStream = new ByteArrayOutputStream()
    val fakeErr = new PrintStream(outStream)
    System.setErr(fakeErr)
    try {
      val result = thunk
      System.setErr(oldErr)
      result
    } catch {
      case NonFatal(e) =>
        System.setErr(oldErr)
        // In case of errors, print whatever was caught
        fakeErr.close()
        val out = outStream.toString("utf-8")
        if (out.nonEmpty) oldErr.println(out)
        throw e
    }
  }

  /**
    * Catches `System.err` output, for testing purposes.
    */
  def catchSystemErr(thunk: => Unit): String = synchronized {
    val oldErr = System.err
    val outStream = new ByteArrayOutputStream()
    val fakeErr = new PrintStream(outStream)
    System.setErr(fakeErr)
    try {
      thunk
    } finally {
      System.setErr(oldErr)
      fakeErr.close()
    }
    outStream.toString("utf-8")
  }
}

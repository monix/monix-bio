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

import monix.bio.compat.internal.newBuilder
import monix.bio.internal._
import monix.execution.{CancelablePromise, Scheduler}
import monix.execution.compat.BuildFrom

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

object UIO {

  /**
   * @see See [[monix.bio.BIO.apply]]
   */
  def apply[A](a: => A): UIO[A] =
    BIO.EvalTotal(a _)

  /**
   * @see See [[monix.bio.BIO.now]]
   */
  def now[A](a: A): UIO[A] =
    BIO.now(a)

  /**
   * @see See [[monix.bio.BIO.pure]]
   */
  def pure[A](a: A): UIO[A] =
    BIO.pure(a)

  /**
   * @see See [[monix.bio.BIO.terminate]]
   */
  def terminate(ex: Throwable): UIO[Nothing] =
    BIO.terminate(ex)

  /**
   * @see See [[monix.bio.BIO.defer]]
   */
  def defer[A](fa: => UIO[A]): UIO[A] =
    BIO.deferTotal(fa)

  /**
   * @see See [[monix.bio.BIO.deferTotal]]
   */
  def deferTotal[A](fa: => UIO[A]): UIO[A] =
    BIO.deferTotal(fa)

  /**
   * @see See [[monix.bio.BIO.deferAction]]
   */
  def deferAction[A](f: Scheduler => UIO[A]): UIO[A] =
    BIO.deferAction(f)

  /**
   * @see See [[monix.bio.BIO.deferFutureEither]]
   */
  def deferFutureEither[A](fa: => Future[Either[Nothing, A]]): UIO[A] =
    BIO.deferFutureEither(fa)

  /**
   * @see See [[monix.bio.BIO.suspend]]
   */
  def suspend[A](fa: => UIO[A]): UIO[A] =
    BIO.suspendTotal(fa)

  /**
   * @see See [[monix.bio.BIO.suspendTotal]]
   */
  def suspendTotal[A](fa: => UIO[A]): UIO[A] =
    BIO.suspendTotal(fa)

  /**
   * @see See [[monix.bio.BIO.eval]]
   */
  def eval[A](a: => A): UIO[A] =
    BIO.EvalTotal(a _)

  /**
   * @see See [[monix.bio.BIO.evalTotal]]
   */
  def evalTotal[A](a: => A): UIO[A] =
    BIO.EvalTotal(a _)

  /**
   * @see See [[monix.bio.BIO.evalAsync]]
   */
  def evalAsync[A](a: => A): UIO[A] =
    BIO.EvalTotal(a _).executeAsync

  /**
   * @see See [[monix.bio.BIO.delay]]
   */
  def delay[A](a: => A): UIO[A] =
    eval(a)

  /**
   * @see See [[monix.bio.BIO.never]]
   */
  val never: UIO[Nothing] =
    BIO.never

  /**
   * @see See [[monix.bio.BIO.tailRecM]]
   */
  def tailRecM[A, B](a: A)(f: A => UIO[Either[A, B]]): UIO[B] =
    defer(f(a)).flatMap {
      case Left(continueA) => tailRecM(continueA)(f)
      case Right(b)        => now(b)
    }

  /**
   * @see See [[monix.bio.BIO.unit]]
   */
  val unit: UIO[Unit] =
    BIO.unit

  /**
   * @see See [[monix.bio.BIO.cancelBoundary]]
   */
  val cancelBoundary: UIO[Unit] =
    BIO.cancelBoundary

  /**
   * @see See [[monix.bio.BIO.fromFutureEither]]
   */
  def fromFutureEither[A](f: Future[Either[Nothing, A]]): UIO[A] =
    BIO.fromFutureEither(f)

  /**
   * @see See [[monix.bio.BIO.fromCancelablePromiseEither]]
   */
  def fromCancelablePromiseEither[A](p: CancelablePromise[Either[Nothing, A]]): UIO[A] =
    BIO.fromCancelablePromiseEither(p)

  /**
   * @see See [[monix.bio.BIO.race]]
   */
  def race[A, B](fa: UIO[A], fb: UIO[B]): UIO[Either[A, B]] =
    TaskRace(fa, fb)

  /**
   * @see See [[monix.bio.BIO.racePair]]
   */
  def racePair[A, B](fa: UIO[A], fb: UIO[B]): UIO[Either[(A, Fiber[Nothing, B]), (Fiber[Nothing, A], B)]] =
    TaskRacePair(fa, fb)

  /**
    * @see See [[monix.bio.BIO.rethrow]]
    */
  final def rethrow[A](fa: UIO[Either[Nothing, A]]): UIO[A] =
    fa.rethrow

  /**
    * @see See doctodo monix.bio.BIO.shift
    */
  val shift: UIO[Unit] =
    BIO.shift

  /**
    * @see See doctodo monix.bio.BIO.shift
    */
  def shift(ec: ExecutionContext): UIO[Unit] =
    BIO.shift(ec)

  /**
   * @see See [[monix.bio.BIO.sleep]]
   */
  def sleep(timespan: FiniteDuration): UIO[Unit] =
    BIO.sleep(timespan)

  /**
   * @see See [[monix.bio.BIO.sequence]]
   */
  def sequence[A, M[X] <: Iterable[X]](in: M[UIO[A]])(implicit bf: BuildFrom[M[UIO[A]], A, M[A]]): UIO[M[A]] =
    TaskSequence.list[Nothing, A, M](in)(bf)

  /**
   * @see See [[monix.bio.BIO.traverse]]
   */
  def traverse[A, B, M[X] <: Iterable[X]](in: M[A])(f: A => UIO[B])(implicit bf: BuildFrom[M[A], B, M[B]]): UIO[M[B]] =
    TaskSequence.traverse(in, f)(bf)

  /**
   * @see See [[monix.bio.BIO.gather]]
   */
  def gather[A, M[X] <: Iterable[X]](in: M[UIO[A]])(implicit bf: BuildFrom[M[UIO[A]], A, M[A]]): UIO[M[A]] =
    TaskGather[Nothing, A, M](in, () => newBuilder(bf, in))

  /**
   * @see See [[monix.bio.BIO.gatherN]]
   */
  def gatherN[A](parallelism: Int)(in: Iterable[UIO[A]]): UIO[List[A]] =
    TaskGatherN[Nothing, A](parallelism, in)

  /**
   * @see See [[monix.bio.BIO.gatherUnordered]]
   */
  def gatherUnordered[A](in: Iterable[UIO[A]]): UIO[List[A]] =
    TaskGatherUnordered[Nothing, A](in)

  /**
   * @see See [[monix.bio.BIO.mapBoth]]
   */
  def mapBoth[A1, A2, R](fa1: UIO[A1], fa2: UIO[A2])(f: (A1, A2) => R): UIO[R] =
    TaskMapBoth(fa1, fa2)(f)
}

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

import cats.effect.{CancelToken, ConcurrentEffect, Effect}
import monix.bio.BIO.AsyncBuilder
import monix.bio.internal.{TaskCreate, TaskFromFuture}
import monix.execution.compat.BuildFrom
import monix.execution.{Callback, CancelablePromise, Scheduler}
import monix.catnap.FutureLift
import monix.execution.{Callback, CancelablePromise, Scheduler}
import org.reactivestreams.Publisher

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object Task {

  /**
   * @see See [[monix.bio.BIO.apply]]
   */
  def apply[A](a: => A): Task[A] =
    BIO.eval(a)

  /**
   * @see See [[monix.bio.BIO.now]]
   */
  def now[A](a: A): Task[A] =
    BIO.now(a)

  /**
   * @see See [[monix.bio.BIO.pure]]
   */
  def pure[A](a: A): Task[A] =
    BIO.pure(a)

  /**
   * @see See [[monix.bio.BIO.raiseError]]
   */
  def raiseError[A](ex: Throwable): Task[A] =
    BIO.raiseError(ex)

  /**
   * @see See [[monix.bio.BIO.terminate]]
   */
  def terminate[A](ex: Throwable): Task[A] =
    BIO.terminate(ex)

  /**
   * @see See [[monix.bio.BIO.defer]]
   */
  def defer[A](fa: => Task[A]): Task[A] =
    BIO.defer(fa)

  /**
   * @see See [[monix.bio.BIO.deferAction]]
   */
  def deferAction[A](f: Scheduler => Task[A]): Task[A] =
    BIO.deferAction(f)

  /**
   * @see See [[monix.bio.BIO.deferFuture]]
   */
  def deferFuture[A](fa: => Future[A]): Task[A] =
    defer(fromFuture(fa))

  /**
   * @see See [[monix.bio.BIO.deferFutureAction]]
   */
  def deferFutureAction[A](f: Scheduler => Future[A]): Task[A] =
    TaskFromFuture.deferAction(f)

  /**
   * @see See [[monix.bio.BIO.suspend]]
   */
  def suspend[A](fa: => Task[A]): Task[A] =
    BIO.suspend(fa)

  /**
   * @see See [[monix.bio.BIO.eval]]
   */
  def eval[A](a: => A): Task[A] =
    BIO.eval(a)

  /**
   * @see See [[monix.bio.BIO.evalAsync]]
   */
  def evalAsync[A](a: => A): Task[A] =
    BIO.evalAsync(a)

  /**
   * @see See [[monix.bio.BIO.delay]]
   */
  def delay[A](a: => A): Task[A] =
    BIO.delay(a)

  /**
   * @see See [[monix.bio.BIO.never]]
   */
  def never[A]: Task[A] =
    BIO.never

  /**
   * @see See [[monix.bio.BIO.from]]
   */
  def from[F[_], A](fa: F[A])(implicit F: TaskLike[F]): Task[A] =
    BIO.from(fa)

  /**
   * @see See [[monix.bio.BIO.fromReactivePublisher]]
   */
  def fromReactivePublisher[A](source: Publisher[A]): Task[Option[A]] =
    BIO.fromReactivePublisher(source)

  /**
   * @see See [[monix.bio.BIO.fromConcurrentEffect]]
   */
  def fromConcurrentEffect[F[_], A](fa: F[A])(implicit F: ConcurrentEffect[F]): Task[A] =
    BIO.fromConcurrentEffect(fa)

  /**
   * @see See [[monix.bio.BIO.fromEffect]]
   */
  def fromEffect[F[_], A](fa: F[A])(implicit F: Effect[F]): Task[A] =
    BIO.fromEffect(fa)

  /**
   * @see See [[monix.bio.BIO.fromTry]]
   */
  def fromTry[A](a: Try[A]): Task[A] =
    BIO.fromTry(a)

  /**
   * @see See [[monix.bio.BIO.fromEither]]
   */
  def fromEither[A](a: Either[Throwable, A]): Task[A] =
    BIO.fromEither(a)

  /**
   * @see See [[monix.bio.BIO.tailRecM]]
   */
  def tailRecM[A, B](a: A)(f: A => Task[Either[A, B]]): Task[B] =
    BIO.tailRecM(a)(f)

  /**
   * @see See [[monix.bio.BIO.unit]]
   */
  val unit: Task[Unit] =
    BIO.unit

  /**
   * @see See [[monix.bio.BIO.async]]
   */
  def async[A](register: Callback[Throwable, A] => Unit): Task[A] =
    TaskCreate.async(register)

  /**
   * @see See [[monix.bio.BIO.async0]]
   */
  def async0[A](register: (Scheduler, Callback[Throwable, A]) => Unit): Task[A] =
    TaskCreate.async0(register)

  /**
   * @see See [[monix.bio.BIO.asyncF]]
   */
  def asyncF[A](register: Callback[Throwable, A] => Task[Unit]): Task[A] =
    TaskCreate.asyncF(register)

  /**
   * @see See [[monix.bio.BIO.cancelable]]
   */
  def cancelable[A](register: Callback[Throwable, A] => CancelToken[Task]): Task[A] =
    cancelable0((_, cb) => register(cb))

  /**
   * @see See [[monix.bio.BIO.cancelable0]]
   */
  def cancelable0[A](register: (Scheduler, Callback[Throwable, A]) => CancelToken[Task]): Task[A] =
    TaskCreate.cancelable0(register)

  /**
   * @see See [[monix.bio.BIO.cancelBoundary]]
   */
  val cancelBoundary: Task[Unit] =
    BIO.cancelBoundary

  /**
   * @see See [[monix.bio.BIO.create]]
   */
  def create[A]: AsyncBuilder.CreatePartiallyApplied[Throwable, A] =
    BIO.create[Throwable, A]

  /**
   * @see See [[monix.bio.BIO.fromFuture]]
   */
  def fromFuture[A](f: Future[A]): Task[A] =
    BIO.fromFuture(f)

  /**
   * @see See [[monix.bio.BIO.fromCancelablePromise]]
   */
  def fromCancelablePromise[A](p: CancelablePromise[A]): Task[A] =
    BIO.fromCancelablePromise(p)

  /**
   * @see See [[monix.bio.BIO.fromFutureLike]]
   */
  def fromFutureLike[F[_], A](tfa: Task[F[A]])(implicit F: FutureLift[Task, F]): Task[A] =
    BIO.fromFutureLike(tfa)

  /**
   * @see See [[monix.bio.BIO.race]]
   */
  def race[A, B](fa: Task[A], fb: Task[B]): Task[Either[A, B]] =
    BIO.race(fa, fb)

  /**
   * @see See [[monix.bio.BIO.racePair]]
   */
  def racePair[A, B](fa: Task[A], fb: Task[B]): Task[Either[(A, Fiber[Throwable, B]), (Fiber[Throwable, A], B)]] =
    BIO.racePair(fa, fb)

  /**
    * @see See [[[monix.bio.BIO$.shift:monix\.bio\.UIO*]]]
    */
  val shift: Task[Unit] =
    BIO.shift

  /**
    * @see See [[[monix.bio.BIO$.shift(ec:scala\.concurrent\.ExecutionContext*]]]
    */
  def shift(ec: ExecutionContext): Task[Unit] =
    BIO.shift(ec)

  /**
   * @see See [[monix.bio.BIO.sleep]]
   */
  def sleep(timespan: FiniteDuration): Task[Unit] =
    BIO.sleep(timespan)

  /**
   * @see See [[monix.bio.BIO.sequence]]
   */
  def sequence[A, M[X] <: Iterable[X]](in: M[Task[A]])(implicit bf: BuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] =
    BIO.sequence(in)

  /**
   * @see See [[monix.bio.BIO.traverse]]
   */
  def traverse[A, B, M[X] <: Iterable[X]](in: M[A])(f: A => Task[B])(implicit bf: BuildFrom[M[A], B, M[B]]): Task[M[B]] =
    BIO.traverse(in)(f)

  /**
   * @see See [[monix.bio.BIO.gather]]
   */
  def gather[A, M[X] <: Iterable[X]](in: M[Task[A]])(implicit bf: BuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] =
    BIO.gather(in)

  /**
   * @see See [[monix.bio.BIO.gatherN]]
   */
  def gatherN[E, A](parallelism: Int)(in: Iterable[Task[A]]): Task[List[A]] =
    BIO.gatherN(parallelism)(in)

  /**
   * @see See [[monix.bio.BIO.gatherUnordered]]
   */
  def gatherUnordered[A](in: Iterable[Task[A]]): Task[List[A]] =
    BIO.gatherUnordered(in)

  /**
   * @see See [[monix.bio.BIO.mapBoth]]
   */
  def mapBoth[A1, A2, R](fa1: Task[A1], fa2: Task[A2])(f: (A1, A2) => R): Task[R] =
    BIO.mapBoth(fa1, fa2)(f)

  /**
   * @see See [[monix.bio.BIO.map2]]
   */
  def map2[A1, A2, R](fa1: Task[A1], fa2: Task[A2])(f: (A1, A2) => R): Task[R] =
    BIO.map2(fa1, fa2)(f)

  /**
   * @see See [[monix.bio.BIO.map3]]
   */
  def map3[E, A1, A2, A3, R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3])(f: (A1, A2, A3) => R): Task[R] =
    BIO.map3(fa1, fa2, fa3)(f)

  /**
   * @see See [[monix.bio.BIO.map4]]
   */
  def map4[E, A1, A2, A3, A4, R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4])(f: (A1, A2, A3, A4) => R): Task[R] =
    BIO.map4(fa1, fa2, fa3, fa4)(f)

  /**
   * @see See [[monix.bio.BIO.map5]]
   */
  def map5[E, A1, A2, A3, A4, A5, R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5])(
    f: (A1, A2, A3, A4, A5) => R): Task[R] =
    BIO.map5(fa1, fa2, fa3, fa4, fa5)(f)

  /**
   * @see See [[monix.bio.BIO.map6]]
   */
  def map6[E, A1, A2, A3, A4, A5, A6, R](
      fa1: Task[A1],
      fa2: Task[A2],
      fa3: Task[A3],
      fa4: Task[A4],
      fa5: Task[A5],
      fa6: Task[A6])(f: (A1, A2, A3, A4, A5, A6) => R): Task[R] =
    BIO.map6(fa1, fa2, fa3, fa4, fa5, fa6)(f)
}

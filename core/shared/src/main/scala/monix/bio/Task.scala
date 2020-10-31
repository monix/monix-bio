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
import cats.~>
import monix.bio.IO.AsyncBuilder
import monix.bio.internal.{TaskCreate, TaskDeprecated, TaskFromFuture}
import monix.catnap.FutureLift
import monix.execution.{CancelablePromise, Scheduler}
import org.reactivestreams.Publisher
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import monix.bio.tracing.TaskTrace

object Task extends TaskDeprecated.Companion {

  /**
    * @see See [[monix.bio.IO.apply]]
    */
  def apply[A](a: => A): Task[A] =
    IO.eval(a)

  /**
    * @see See [[monix.bio.IO.now]]
    */
  def now[A](a: A): Task[A] =
    IO.now(a)

  /**
    * @see See [[monix.bio.IO.pure]]
    */
  def pure[A](a: A): Task[A] =
    IO.pure(a)

  /**
    * @see See [[monix.bio.IO.raiseError]]
    */
  def raiseError[A](ex: Throwable): Task[A] =
    IO.raiseError(ex)

  /**
    * @see See [[monix.bio.IO.terminate]]
    */
  def terminate[A](ex: Throwable): Task[A] =
    IO.terminate(ex)

  /**
    * @see See [[monix.bio.IO.defer]]
    */
  def defer[A](fa: => Task[A]): Task[A] =
    IO.defer(fa)

  /**
    * @see See [[monix.bio.IO.deferAction]]
    */
  def deferAction[A](f: Scheduler => Task[A]): Task[A] =
    IO.deferAction(f)

  /**
    * @see See [[monix.bio.IO.deferFuture]]
    */
  def deferFuture[A](fa: => Future[A]): Task[A] =
    defer(fromFuture(fa))

  /**
    * @see See [[monix.bio.IO.deferFutureAction]]
    */
  def deferFutureAction[A](f: Scheduler => Future[A]): Task[A] =
    TaskFromFuture.deferAction(f)

  /**
    * @see See [[monix.bio.IO.suspend]]
    */
  def suspend[A](fa: => Task[A]): Task[A] =
    IO.suspend(fa)

  /**
    * @see See [[monix.bio.IO.evalOnce]]
    */
  def evalOnce[A](a: => A): Task[A] =
    IO.evalOnce(a)

  /**
    * @see See [[monix.bio.IO.eval]]
    */
  def eval[A](a: => A): Task[A] =
    IO.eval(a)

  /**
    * @see See [[monix.bio.IO.evalAsync]]
    */
  def evalAsync[A](a: => A): Task[A] =
    IO.evalAsync(a)

  /**
    * @see See [[monix.bio.IO.delay]]
    */
  def delay[A](a: => A): Task[A] =
    IO.delay(a)

  /**
    * @see See [[monix.bio.IO.never]]
    */
  def never[A]: Task[A] =
    IO.never

  /**
    * @see See [[monix.bio.IO.from]]
    */
  def from[F[_], A](fa: F[A])(implicit F: IOLike[F]): Task[A] =
    IO.from(fa)

  /**
    * @see See [[monix.bio.IO.fromReactivePublisher]]
    */
  def fromReactivePublisher[A](source: Publisher[A]): Task[Option[A]] =
    IO.fromReactivePublisher(source)

  /**
    * @see See [[monix.bio.IO.fromConcurrentEffect]]
    */
  def fromConcurrentEffect[F[_], A](fa: F[A])(implicit F: ConcurrentEffect[F]): Task[A] =
    IO.fromConcurrentEffect(fa)

  /**
    * @see See [[monix.bio.IO.fromEffect]]
    */
  def fromEffect[F[_], A](fa: F[A])(implicit F: Effect[F]): Task[A] =
    IO.fromEffect(fa)

  /**
    * @see See [[monix.bio.IO.fromTry]]
    */
  def fromTry[A](a: Try[A]): Task[A] =
    IO.fromTry(a)

  /**
    * @see See [[monix.bio.IO.fromEither]]
    */
  def fromEither[A](a: Either[Throwable, A]): Task[A] =
    IO.fromEither(a)

  /**
    * @see See [[monix.bio.IO.tailRecM]]
    */
  def tailRecM[A, B](a: A)(f: A => Task[Either[A, B]]): Task[B] =
    IO.tailRecM(a)(f)

  /**
    * @see See [[monix.bio.IO.unit]]
    */
  val unit: Task[Unit] =
    IO.unit

  /**
    * @see See [[monix.bio.IO.async]]
    */
  def async[A](register: BiCallback[Throwable, A] => Unit): Task[A] =
    TaskCreate.async(register)

  /**
    * @see See [[monix.bio.IO.async0]]
    */
  def async0[A](register: (Scheduler, BiCallback[Throwable, A]) => Unit): Task[A] =
    TaskCreate.async0(register)

  /**
    * @see See [[monix.bio.IO.asyncF]]
    */
  def asyncF[A](register: BiCallback[Throwable, A] => Task[Unit]): Task[A] =
    TaskCreate.asyncF(register)

  /**
    * @see See [[monix.bio.IO.cancelable]]
    */
  def cancelable[A](register: BiCallback[Throwable, A] => CancelToken[Task]): Task[A] =
    cancelable0((_, cb) => register(cb))

  /**
    * @see See [[monix.bio.IO.cancelable0]]
    */
  def cancelable0[A](register: (Scheduler, BiCallback[Throwable, A]) => CancelToken[Task]): Task[A] =
    TaskCreate.cancelable0(register)

  /**
    * @see See [[monix.bio.IO.cancelBoundary]]
    */
  val cancelBoundary: Task[Unit] =
    IO.cancelBoundary

  /**
    * @see See [[monix.bio.IO.create]]
    */
  def create[A]: AsyncBuilder.CreatePartiallyApplied[Throwable, A] =
    IO.create[Throwable, A]

  /**
    * @see See [[monix.bio.IO.fromFuture]]
    */
  def fromFuture[A](f: Future[A]): Task[A] =
    IO.fromFuture(f)

  /**
    * @see See [[monix.bio.IO.fromCancelablePromise]]
    */
  def fromCancelablePromise[A](p: CancelablePromise[A]): Task[A] =
    IO.fromCancelablePromise(p)

  /**
    * @see See [[monix.bio.IO.fromFutureLike]]
    */
  def fromFutureLike[F[_], A](tfa: Task[F[A]])(implicit F: FutureLift[Task, F]): Task[A] =
    IO.fromFutureLike(tfa)

  /**
    * @see See [[monix.bio.IO.race]]
    */
  def race[A, B](fa: Task[A], fb: Task[B]): Task[Either[A, B]] =
    IO.race(fa, fb)

  /**
    * @see See [[monix.bio.IO.raceMany]]
    */
  def raceMany[A](tasks: Iterable[Task[A]]): Task[A] =
    IO.raceMany(tasks)

  /**
    * @see See [[monix.bio.IO.racePair]]
    */
  def racePair[A, B](fa: Task[A], fb: Task[B]): Task[Either[(A, Fiber[Throwable, B]), (Fiber[Throwable, A], B)]] =
    IO.racePair(fa, fb)

  /**
    * @see See [[monix.bio.IO.rethrow]]
    */
  def rethrow[A](fa: Task[Either[Throwable, A]]): Task[A] =
    fa.rethrow

  /**
    * @see See [[monix.bio.IO.shift]]
    */
  val shift: Task[Unit] =
    IO.shift

  /**
    * @see See [[monix.bio.IO.shift]]
    */
  def shift(ec: ExecutionContext): Task[Unit] =
    IO.shift(ec)

  /**
    * @see See [[monix.bio.IO.sleep]]
    */
  def sleep(timespan: FiniteDuration): Task[Unit] =
    IO.sleep(timespan)

  /**
    * @see See [[monix.bio.IO.sequence]]
    */
  def sequence[A](in: Iterable[Task[A]]): Task[List[A]] =
    IO.sequence(in)

  /**
    * @see See [[monix.bio.IO.traverse]]
    */
  def traverse[A, B](
    in: Iterable[A]
  )(f: A => Task[B]): Task[List[B]] =
    IO.traverse(in)(f)

  /**
    * @see See [[monix.bio.IO.none]]
    */
  def none[A]: Task[Option[A]] = IO.none

  /**
    * @see See [[monix.bio.IO.some]]
    */
  def some[A](a: A): Task[Option[A]] = IO.some(a)

  /**
    * @see See [[monix.bio.IO.left]]
    */
  def left[A, B](a: A): Task[Either[A, B]] = IO.left(a)

  /**
    * @see See [[monix.bio.IO.right]]
    */
  def right[A, B](b: B): Task[Either[A, B]] = IO.right(b)

  /**
    * @see See [[monix.bio.IO.when]]
    */
  def when(cond: Boolean)(action: => Task[Unit]): Task[Unit] =
    IO.when(cond)(action)

  /**
    * @see See [[monix.bio.IO.unless]]
    */
  def unless(cond: Boolean)(action: => Task[Unit]): Task[Unit] =
    IO.unless(cond)(action)

  /**
    * @see See [[monix.bio.IO.raiseWhen]]
    */
  def raiseWhen(cond: Boolean)(e: => Throwable): Task[Unit] =
    IO.raiseWhen(cond)(e)

  /**
    * @see See [[monix.bio.IO.raiseUnless]]
    */
  def raiseUnless(cond: Boolean)(e: => Throwable): Task[Unit] =
    IO.raiseUnless(cond)(e)

  /**
    * @see See [[monix.bio.IO.parSequence]]
    */
  def parSequence[A](in: Iterable[Task[A]]): Task[List[A]] =
    IO.parSequence(in)

  /**
    * @see [[monix.bio.IO.parTraverse]]
    */
  def parTraverse[A, B](
    in: Iterable[A]
  )(f: A => Task[B]): Task[List[B]] =
    IO.parTraverse(in)(f)

  /**
    * @see See [[monix.bio.IO.parSequenceN]]
    */
  def parSequenceN[A](parallelism: Int)(in: Iterable[Task[A]]): Task[List[A]] =
    IO.parSequenceN(parallelism)(in)

  /**
    * @see See [[monix.bio.IO.parTraverseN]]
    */
  def parTraverseN[A, B](parallelism: Int)(in: Iterable[A])(f: A => Task[B]): Task[List[B]] =
    IO.parTraverseN(parallelism)(in)(f)

  /**
    * @see See [[monix.bio.IO.parSequenceUnordered]]
    */
  def parSequenceUnordered[A](in: Iterable[Task[A]]): Task[List[A]] =
    IO.parSequenceUnordered(in)

  /**
    * @see [[monix.bio.IO.parTraverseUnordered]]
    */
  def parTraverseUnordered[A, B](in: Iterable[A])(f: A => Task[B]): Task[List[B]] =
    IO.parTraverseUnordered(in)(f)

  /**
    * @see See [[monix.bio.IO.mapBoth]]
    */
  def mapBoth[A1, A2, R](fa1: Task[A1], fa2: Task[A2])(f: (A1, A2) => R): Task[R] =
    IO.mapBoth(fa1, fa2)(f)

  /**
    * @see See [[monix.bio.IO.map2]]
    */
  def map2[A1, A2, R](fa1: Task[A1], fa2: Task[A2])(f: (A1, A2) => R): Task[R] =
    IO.map2(fa1, fa2)(f)

  /**
    * @see See [[monix.bio.IO.map3]]
    */
  def map3[A1, A2, A3, R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3])(f: (A1, A2, A3) => R): Task[R] =
    IO.map3(fa1, fa2, fa3)(f)

  /**
    * @see See [[monix.bio.IO.map4]]
    */
  def map4[A1, A2, A3, A4, R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4])(
    f: (A1, A2, A3, A4) => R
  ): Task[R] =
    IO.map4(fa1, fa2, fa3, fa4)(f)

  /**
    * @see See [[monix.bio.IO.map5]]
    */
  def map5[A1, A2, A3, A4, A5, R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5])(
    f: (A1, A2, A3, A4, A5) => R
  ): Task[R] =
    IO.map5(fa1, fa2, fa3, fa4, fa5)(f)

  /**
    * @see See [[monix.bio.IO.map6]]
    */
  def map6[A1, A2, A3, A4, A5, A6, R](
    fa1: Task[A1],
    fa2: Task[A2],
    fa3: Task[A3],
    fa4: Task[A4],
    fa5: Task[A5],
    fa6: Task[A6]
  )(f: (A1, A2, A3, A4, A5, A6) => R): Task[R] =
    IO.map6(fa1, fa2, fa3, fa4, fa5, fa6)(f)

  /**
    * @see See [[monix.bio.IO.parMap2]]
    */
  def parMap2[A1, A2, R](fa1: Task[A1], fa2: Task[A2])(f: (A1, A2) => R): Task[R] =
    Task.mapBoth(fa1, fa2)(f)

  /**
    * @see See [[monix.bio.IO.parMap3]]
    */
  def parMap3[A1, A2, A3, R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3])(f: (A1, A2, A3) => R): Task[R] = {
    val fa12 = parZip2(fa1, fa2)
    parMap2(fa12, fa3) { case ((a1, a2), a3) => f(a1, a2, a3) }
  }

  /**
    * @see See [[monix.bio.IO.parMap4]]
    */
  def parMap4[A1, A2, A3, A4, R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4])(
    f: (A1, A2, A3, A4) => R
  ): Task[R] = {
    val fa123 = parZip3(fa1, fa2, fa3)
    parMap2(fa123, fa4) { case ((a1, a2, a3), a4) => f(a1, a2, a3, a4) }
  }

  /**
    * @see See [[monix.bio.IO.parMap5]]
    */
  def parMap5[A1, A2, A3, A4, A5, R](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4], fa5: Task[A5])(
    f: (A1, A2, A3, A4, A5) => R
  ): Task[R] = {
    val fa1234 = parZip4(fa1, fa2, fa3, fa4)
    parMap2(fa1234, fa5) { case ((a1, a2, a3, a4), a5) => f(a1, a2, a3, a4, a5) }
  }

  /**
    * @see See [[IO.parMap6]]
    */
  def parMap6[A1, A2, A3, A4, A5, A6, R](
    fa1: Task[A1],
    fa2: Task[A2],
    fa3: Task[A3],
    fa4: Task[A4],
    fa5: Task[A5],
    fa6: Task[A6]
  )(f: (A1, A2, A3, A4, A5, A6) => R): Task[R] = {
    val fa12345 = parZip5(fa1, fa2, fa3, fa4, fa5)
    parMap2(fa12345, fa6) { case ((a1, a2, a3, a4, a5), a6) => f(a1, a2, a3, a4, a5, a6) }
  }

  /**
    * @see See [[IO.parZip2]]
    */
  def parZip2[A1, A2, R](fa1: Task[A1], fa2: Task[A2]): Task[(A1, A2)] =
    IO.mapBoth(fa1, fa2)((_, _))

  /**
    * @see See [[IO.parZip3]]
    */
  def parZip3[A1, A2, A3](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3]): Task[(A1, A2, A3)] =
    parMap3(fa1, fa2, fa3)((a1, a2, a3) => (a1, a2, a3))

  /**
    * @see See [[IO.parZip4]]
    */
  def parZip4[A1, A2, A3, A4](fa1: Task[A1], fa2: Task[A2], fa3: Task[A3], fa4: Task[A4]): Task[(A1, A2, A3, A4)] =
    parMap4(fa1, fa2, fa3, fa4)((a1, a2, a3, a4) => (a1, a2, a3, a4))

  /**
    * @see See [[IO.parZip5]]
    */
  def parZip5[A1, A2, A3, A4, A5](
    fa1: Task[A1],
    fa2: Task[A2],
    fa3: Task[A3],
    fa4: Task[A4],
    fa5: Task[A5]
  ): Task[(A1, A2, A3, A4, A5)] =
    parMap5(fa1, fa2, fa3, fa4, fa5)((a1, a2, a3, a4, a5) => (a1, a2, a3, a4, a5))

  /**
    * @see See [[IO.parZip6]]
    */
  def parZip6[A1, A2, A3, A4, A5, A6](
    fa1: Task[A1],
    fa2: Task[A2],
    fa3: Task[A3],
    fa4: Task[A4],
    fa5: Task[A5],
    fa6: Task[A6]
  ): Task[(A1, A2, A3, A4, A5, A6)] =
    parMap6(fa1, fa2, fa3, fa4, fa5, fa6)((a1, a2, a3, a4, a5, a6) => (a1, a2, a3, a4, a5, a6))

  /**
    * @see See [[monix.bio.IO.liftTo]]
    */
  def liftTo[F[_]](implicit F: IOLift[F]): (Task ~> F) =
    IO.liftTo[F]

  /**
    * @see See [[monix.bio.IO.liftToAsync]]
    */
  def liftToAsync[F[_]](implicit F: cats.effect.Async[F], eff: cats.effect.Effect[Task]): (Task ~> F) =
    IO.liftToAsync[F]

  /**
    * @see See [[monix.bio.IO.liftToConcurrent]]
    */
  def liftToConcurrent[F[_]](implicit
    F: cats.effect.Concurrent[F],
    eff: cats.effect.ConcurrentEffect[Task]
  ): (Task ~> F) =
    IO.liftToConcurrent[F]

  /**
    * @see See [[monix.bio.IO.liftFrom]]
    */
  def liftFrom[F[_]](implicit F: IOLike[F]): (F ~> Task) =
    IO.liftFrom[F]

  /**
    * @see See [[monix.bio.IO.liftFromConcurrentEffect]]
    */
  def liftFromConcurrentEffect[F[_]](implicit F: ConcurrentEffect[F]): (F ~> Task) =
    IO.liftFromConcurrentEffect[F]

  /**
    * @see See [[monix.bio.IO.liftFromEffect]]
    */
  def liftFromEffect[F[_]](implicit F: Effect[F]): (F ~> Task) =
    IO.liftFromEffect[F]

  /**
    * @see See [[monix.bio.IO.liftFromEffect]]
    */
  val trace: UIO[TaskTrace] = IO.trace
}

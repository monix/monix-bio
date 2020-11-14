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

import monix.bio.internal._
import monix.execution.{CancelablePromise, Scheduler}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import monix.bio.tracing.IOTrace

object UIO extends UIODeprecated.Companion {

  /** @see See [[monix.bio.IO.apply]]
    */
  def apply[A](a: => A): UIO[A] =
    IO.EvalTotal(a _)

  /** @see See [[monix.bio.IO.now]]
    */
  def now[A](a: A): UIO[A] =
    IO.now(a)

  /** @see See [[monix.bio.IO.pure]]
    */
  def pure[A](a: A): UIO[A] =
    IO.pure(a)

  /** @see See [[monix.bio.IO.terminate]]
    */
  def terminate(ex: Throwable): UIO[Nothing] =
    IO.terminate(ex)

  /** @see See [[monix.bio.IO.defer]]
    */
  def defer[A](fa: => UIO[A]): UIO[A] =
    IO.deferTotal(fa)

  /** @see See [[monix.bio.IO.deferTotal]]
    */
  def deferTotal[A](fa: => UIO[A]): UIO[A] =
    IO.deferTotal(fa)

  /** @see See [[monix.bio.IO.deferAction]]
    */
  def deferAction[A](f: Scheduler => UIO[A]): UIO[A] =
    IO.deferAction(f)

  /** @see See [[monix.bio.IO.suspend]]
    */
  def suspend[A](fa: => UIO[A]): UIO[A] =
    IO.suspendTotal(fa)

  /** @see See [[monix.bio.IO.suspendTotal]]
    */
  def suspendTotal[A](fa: => UIO[A]): UIO[A] =
    IO.suspendTotal(fa)

  /** @see See [[monix.bio.IO.eval]]
    */
  def eval[A](a: => A): UIO[A] =
    IO.EvalTotal(a _)

  /** @see See [[monix.bio.IO.evalTotal]]
    */
  def evalTotal[A](a: => A): UIO[A] =
    IO.EvalTotal(a _)

  /** @see See [[monix.bio.IO.evalAsync]]
    */
  def evalAsync[A](a: => A): UIO[A] =
    UIOEvalAsync(a _)

  /** @see See [[monix.bio.IO.delay]]
    */
  def delay[A](a: => A): UIO[A] =
    eval(a)

  /** @see See [[monix.bio.IO.never]]
    */
  val never: UIO[Nothing] =
    IO.never

  /** @see See [[monix.bio.IO.tailRecM]]
    */
  def tailRecM[A, B](a: A)(f: A => UIO[Either[A, B]]): UIO[B] =
    defer(f(a)).flatMap {
      case Left(continueA) => tailRecM(continueA)(f)
      case Right(b) => now(b)
    }

  /** @see See [[monix.bio.IO.unit]]
    */
  val unit: UIO[Unit] =
    IO.unit

  /** @see See [[monix.bio.IO.cancelBoundary]]
    */
  val cancelBoundary: UIO[Unit] =
    IO.cancelBoundary

  /** @see See [[monix.bio.IO.fromCancelablePromiseEither]]
    */
  def fromCancelablePromiseEither[A](p: CancelablePromise[Either[Nothing, A]]): UIO[A] =
    IO.fromCancelablePromiseEither(p)

  /** @see See [[monix.bio.IO.race]]
    */
  def race[A, B](fa: UIO[A], fb: UIO[B]): UIO[Either[A, B]] =
    TaskRace(fa, fb)

  /** @see See [[monix.bio.IO.raceMany]]
    */
  def raceMany[A](tasks: Iterable[UIO[A]]): UIO[A] =
    TaskRaceList(tasks)

  /** @see See [[monix.bio.IO.racePair]]
    */
  def racePair[A, B](fa: UIO[A], fb: UIO[B]): UIO[Either[(A, Fiber[Nothing, B]), (Fiber[Nothing, A], B)]] =
    TaskRacePair(fa, fb)

  /** @see See [[monix.bio.IO.rethrow]]
    */
  def rethrow[A](fa: UIO[Either[Nothing, A]]): UIO[A] =
    fa.rethrow

  /** @see See [[[monix.bio.IO$.shift:monix\.bio\.UIO*]]]
    */
  val shift: UIO[Unit] =
    IO.shift

  /** @see See [[[monix.bio.IO$.shift(ec:scala\.concurrent\.ExecutionContext*]]]
    */
  def shift(ec: ExecutionContext): UIO[Unit] =
    IO.shift(ec)

  /** @see See [[monix.bio.IO.sleep]]
    */
  def sleep(timespan: FiniteDuration): UIO[Unit] =
    IO.sleep(timespan)

  /** @see See [[monix.bio.IO.sequence]]
    */
  def sequence[A](in: Iterable[UIO[A]]): UIO[List[A]] =
    TaskSequence.list[Nothing, A](in)

  /** @see See [[monix.bio.IO.traverse]]
    */
  def traverse[A, B](in: Iterable[A])(f: A => UIO[B]): UIO[List[B]] =
    TaskSequence.traverse(in, f)

  /** @see See [[monix.bio.IO.none]]
    */
  def none[A]: UIO[Option[A]] = IO.none

  /** @see See [[monix.bio.IO.some]]
    */
  def some[A](a: A): UIO[Option[A]] = IO.some(a)

  /** @see See [[monix.bio.IO.left]]
    */
  def left[A, B](a: A): UIO[Either[A, B]] = IO.left(a)

  /** @see See [[monix.bio.IO.right]]
    */
  def right[A, B](b: B): UIO[Either[A, B]] = IO.right(b)

  /** @see See [[monix.bio.IO.when]]
    */
  def when(cond: Boolean)(action: => UIO[Unit]): UIO[Unit] =
    IO.when(cond)(action)

  /** @see See [[monix.bio.IO.unless]]
    */
  def unless(cond: Boolean)(action: => UIO[Unit]): UIO[Unit] =
    IO.unless(cond)(action)

  /** @see See [[monix.bio.IO.parSequence]]
    */
  def parSequence[A](in: Iterable[UIO[A]]): UIO[List[A]] =
    TaskParSequence[Nothing, A](in)

  /** @see [[monix.bio.IO.parTraverse]]
    */
  def parTraverse[A, B](
    in: Iterable[A]
  )(f: A => UIO[B]): UIO[List[B]] =
    IO.parTraverse(in)(f)

  /** @see See [[monix.bio.IO.parSequenceN]]
    */
  def parSequenceN[A](parallelism: Int)(in: Iterable[UIO[A]]): UIO[List[A]] =
    TaskParSequenceN[Nothing, A](parallelism, in)

  /** @see See [[monix.bio.IO.parTraverseN]]
    */
  def parTraverseN[A, B](parallelism: Int)(in: Iterable[A])(f: A => UIO[B]): UIO[List[B]] =
    IO.parTraverseN(parallelism)(in)(f)

  /** @see See [[monix.bio.IO.parSequenceUnordered]]
    */
  def parSequenceUnordered[A](in: Iterable[UIO[A]]): UIO[List[A]] =
    TaskParSequenceUnordered[Nothing, A](in)

  /** @see [[monix.bio.IO.parTraverseUnordered]]
    */
  def parTraverseUnordered[A, B](in: Iterable[A])(f: A => UIO[B]): UIO[List[B]] =
    IO.parTraverseUnordered(in)(f)

  /** @see See [[monix.bio.IO.mapBoth]]
    */
  def mapBoth[A1, A2, R](fa1: UIO[A1], fa2: UIO[A2])(f: (A1, A2) => R): UIO[R] =
    TaskMapBoth(fa1, fa2)(f)

  /** @see See [[monix.bio.IO.map2]]
    */
  def map2[A1, A2, R](fa1: UIO[A1], fa2: UIO[A2])(f: (A1, A2) => R): UIO[R] =
    IO.map2(fa1, fa2)(f)

  /** @see See [[monix.bio.IO.map3]]
    */
  def map3[A1, A2, A3, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3])(f: (A1, A2, A3) => R): UIO[R] =
    IO.map3(fa1, fa2, fa3)(f)

  /** @see See [[monix.bio.IO.map4]]
    */
  def map4[A1, A2, A3, A4, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3], fa4: UIO[A4])(
    f: (A1, A2, A3, A4) => R
  ): UIO[R] =
    IO.map4(fa1, fa2, fa3, fa4)(f)

  /** @see See [[monix.bio.IO.map5]]
    */
  def map5[A1, A2, A3, A4, A5, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3], fa4: UIO[A4], fa5: UIO[A5])(
    f: (A1, A2, A3, A4, A5) => R
  ): UIO[R] =
    IO.map5(fa1, fa2, fa3, fa4, fa5)(f)

  /** @see See [[monix.bio.IO.map6]]
    */
  def map6[A1, A2, A3, A4, A5, A6, R](
    fa1: UIO[A1],
    fa2: UIO[A2],
    fa3: UIO[A3],
    fa4: UIO[A4],
    fa5: UIO[A5],
    fa6: UIO[A6]
  )(f: (A1, A2, A3, A4, A5, A6) => R): UIO[R] =
    IO.map6(fa1, fa2, fa3, fa4, fa5, fa6)(f)

  /** @see See [[monix.bio.IO.parMap2]]
    */
  def parMap2[A1, A2, R](fa1: UIO[A1], fa2: UIO[A2])(f: (A1, A2) => R): UIO[R] =
    UIO.mapBoth(fa1, fa2)(f)

  /** @see See [[monix.bio.IO.parMap3]]
    */
  def parMap3[A1, A2, A3, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3])(f: (A1, A2, A3) => R): UIO[R] = {
    val fa12 = parZip2(fa1, fa2)
    parMap2(fa12, fa3) { case ((a1, a2), a3) => f(a1, a2, a3) }
  }

  /** @see See [[monix.bio.IO.parMap4]]
    */
  def parMap4[A1, A2, A3, A4, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3], fa4: UIO[A4])(
    f: (A1, A2, A3, A4) => R
  ): UIO[R] = {
    val fa123 = parZip3(fa1, fa2, fa3)
    parMap2(fa123, fa4) { case ((a1, a2, a3), a4) => f(a1, a2, a3, a4) }
  }

  /** @see See [[monix.bio.IO.parMap5]]
    */
  def parMap5[A1, A2, A3, A4, A5, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3], fa4: UIO[A4], fa5: UIO[A5])(
    f: (A1, A2, A3, A4, A5) => R
  ): UIO[R] = {
    val fa1234 = parZip4(fa1, fa2, fa3, fa4)
    parMap2(fa1234, fa5) { case ((a1, a2, a3, a4), a5) => f(a1, a2, a3, a4, a5) }
  }

  /** @see See [[IO.parMap6]]
    */
  def parMap6[A1, A2, A3, A4, A5, A6, R](
    fa1: UIO[A1],
    fa2: UIO[A2],
    fa3: UIO[A3],
    fa4: UIO[A4],
    fa5: UIO[A5],
    fa6: UIO[A6]
  )(f: (A1, A2, A3, A4, A5, A6) => R): UIO[R] = {
    val fa12345 = parZip5(fa1, fa2, fa3, fa4, fa5)
    parMap2(fa12345, fa6) { case ((a1, a2, a3, a4, a5), a6) => f(a1, a2, a3, a4, a5, a6) }
  }

  /** @see See [[IO.parZip2]]
    */
  def parZip2[A1, A2, R](fa1: UIO[A1], fa2: UIO[A2]): UIO[(A1, A2)] =
    IO.mapBoth(fa1, fa2)((_, _))

  /** @see See [[IO.parZip3]]
    */
  def parZip3[A1, A2, A3](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3]): UIO[(A1, A2, A3)] =
    parMap3(fa1, fa2, fa3)((a1, a2, a3) => (a1, a2, a3))

  /** @see See [[IO.parZip4]]
    */
  def parZip4[A1, A2, A3, A4](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3], fa4: UIO[A4]): UIO[(A1, A2, A3, A4)] =
    parMap4(fa1, fa2, fa3, fa4)((a1, a2, a3, a4) => (a1, a2, a3, a4))

  /** @see See [[IO.parZip5]]
    */
  def parZip5[A1, A2, A3, A4, A5](
    fa1: UIO[A1],
    fa2: UIO[A2],
    fa3: UIO[A3],
    fa4: UIO[A4],
    fa5: UIO[A5]
  ): UIO[(A1, A2, A3, A4, A5)] =
    parMap5(fa1, fa2, fa3, fa4, fa5)((a1, a2, a3, a4, a5) => (a1, a2, a3, a4, a5))

  /** @see See [[IO.parZip6]]
    */
  def parZip6[A1, A2, A3, A4, A5, A6](
    fa1: UIO[A1],
    fa2: UIO[A2],
    fa3: UIO[A3],
    fa4: UIO[A4],
    fa5: UIO[A5],
    fa6: UIO[A6]
  ): UIO[(A1, A2, A3, A4, A5, A6)] =
    parMap6(fa1, fa2, fa3, fa4, fa5, fa6)((a1, a2, a3, a4, a5, a6) => (a1, a2, a3, a4, a5, a6))

  /** @see See [[monix.bio.IO.liftFromEffect]]
    */
  val trace: UIO[IOTrace] = IO.trace
}

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

object UIO extends UIODeprecated.Companion {

  /**
    * @see See [[monix.bio.Task.apply]]
    */
  def apply[A](a: => A): UIO[A] =
    Task.EvalTotal(a _)

  /**
    * @see See [[monix.bio.Task.now]]
    */
  def now[A](a: A): UIO[A] =
    Task.now(a)

  /**
    * @see See [[monix.bio.Task.pure]]
    */
  def pure[A](a: A): UIO[A] =
    Task.pure(a)

  /**
    * @see See [[monix.bio.Task.terminate]]
    */
  def terminate(ex: Throwable): UIO[Nothing] =
    Task.terminate(ex)

  /**
    * @see See [[monix.bio.Task.defer]]
    */
  def defer[A](fa: => UIO[A]): UIO[A] =
    Task.deferTotal(fa)

  /**
    * @see See [[monix.bio.Task.deferTotal]]
    */
  def deferTotal[A](fa: => UIO[A]): UIO[A] =
    Task.deferTotal(fa)

  /**
    * @see See [[monix.bio.Task.deferAction]]
    */
  def deferAction[A](f: Scheduler => UIO[A]): UIO[A] =
    Task.deferAction(f)

  /**
    * @see See [[monix.bio.Task.suspend]]
    */
  def suspend[A](fa: => UIO[A]): UIO[A] =
    Task.suspendTotal(fa)

  /**
    * @see See [[monix.bio.Task.suspendTotal]]
    */
  def suspendTotal[A](fa: => UIO[A]): UIO[A] =
    Task.suspendTotal(fa)

  /**
    * @see See [[monix.bio.Task.eval]]
    */
  def eval[A](a: => A): UIO[A] =
    Task.EvalTotal(a _)

  /**
    * @see See [[monix.bio.Task.evalTotal]]
    */
  def evalTotal[A](a: => A): UIO[A] =
    Task.EvalTotal(a _)

  /**
    * @see See [[monix.bio.Task.evalAsync]]
    */
  def evalAsync[A](a: => A): UIO[A] =
    UIOEvalAsync(a _)

  /**
    * @see See [[monix.bio.Task.delay]]
    */
  def delay[A](a: => A): UIO[A] =
    eval(a)

  /**
    * @see See [[monix.bio.Task.never]]
    */
  val never: UIO[Nothing] =
    Task.never

  /**
    * @see See [[monix.bio.Task.tailRecM]]
    */
  def tailRecM[A, B](a: A)(f: A => UIO[Either[A, B]]): UIO[B] =
    defer(f(a)).flatMap {
      case Left(continueA) => tailRecM(continueA)(f)
      case Right(b) => now(b)
    }

  /**
    * @see See [[monix.bio.Task.unit]]
    */
  val unit: UIO[Unit] =
    Task.unit

  /**
    * @see See [[monix.bio.Task.cancelBoundary]]
    */
  val cancelBoundary: UIO[Unit] =
    Task.cancelBoundary

  /**
    * @see See [[monix.bio.Task.fromCancelablePromiseEither]]
    */
  def fromCancelablePromiseEither[A](p: CancelablePromise[Either[Nothing, A]]): UIO[A] =
    Task.fromCancelablePromiseEither(p)

  /**
    * @see See [[monix.bio.Task.race]]
    */
  def race[A, B](fa: UIO[A], fb: UIO[B]): UIO[Either[A, B]] =
    TaskRace(fa, fb)

  /**
    * @see See [[monix.bio.Task.raceMany]]
    */
  def raceMany[A](tasks: Iterable[UIO[A]]): UIO[A] =
    TaskRaceList(tasks)

  /**
    * @see See [[monix.bio.Task.racePair]]
    */
  def racePair[A, B](fa: UIO[A], fb: UIO[B]): UIO[Either[(A, Fiber[Nothing, B]), (Fiber[Nothing, A], B)]] =
    TaskRacePair(fa, fb)

  /**
    * @see See [[monix.bio.Task.rethrow]]
    */
  def rethrow[A](fa: UIO[Either[Nothing, A]]): UIO[A] =
    fa.rethrow

  /**
    * @see See [[[monix.bio.Task$.shift:monix\.bio\.UIO*]]]
    */
  val shift: UIO[Unit] =
    Task.shift

  /**
    * @see See [[[monix.bio.Task$.shift(ec:scala\.concurrent\.ExecutionContext*]]]
    */
  def shift(ec: ExecutionContext): UIO[Unit] =
    Task.shift(ec)

  /**
    * @see See [[monix.bio.Task.sleep]]
    */
  def sleep(timespan: FiniteDuration): UIO[Unit] =
    Task.sleep(timespan)

  /**
    * @see See [[monix.bio.Task.sequence]]
    */
  def sequence[A](in: Iterable[UIO[A]]): UIO[List[A]] =
    TaskSequence.list[Nothing, A](in)

  /**
    * @see See [[monix.bio.Task.traverse]]
    */
  def traverse[A, B](in: Iterable[A])(f: A => UIO[B]): UIO[List[B]] =
    TaskSequence.traverse(in, f)

  /**
    * @see See [[monix.bio.Task.parSequence]]
    */
  def parSequence[A](in: Iterable[UIO[A]]): UIO[List[A]] =
    TaskParSequence[Nothing, A](in)

  /**
    * @see [[monix.bio.Task.parTraverse]]
    */
  def parTraverse[A, B](
    in: Iterable[A]
  )(f: A => UIO[B]): UIO[List[B]] =
    Task.parTraverse(in)(f)

  /**
    * @see See [[monix.bio.Task.parSequenceN]]
    */
  def parSequenceN[A](parallelism: Int)(in: Iterable[UIO[A]]): UIO[List[A]] =
    TaskParSequenceN[Nothing, A](parallelism, in)

  /**
    * @see See [[monix.bio.Task.parTraverseN]]
    */
  def parTraverseN[A, B](parallelism: Int)(in: Iterable[A])(f: A => UIO[B]): UIO[List[B]] =
    Task.parTraverseN(parallelism)(in)(f)

  /**
    * @see See [[monix.bio.Task.parSequenceUnordered]]
    */
  def parSequenceUnordered[A](in: Iterable[UIO[A]]): UIO[List[A]] =
    TaskParSequenceUnordered[Nothing, A](in)

  /**
    * @see [[monix.bio.Task.parTraverseUnordered]]
    */
  def parTraverseUnordered[A, B](in: Iterable[A])(f: A => UIO[B]): UIO[List[B]] =
    Task.parTraverseUnordered(in)(f)

  /**
    * @see See [[monix.bio.Task.mapBoth]]
    */
  def mapBoth[A1, A2, R](fa1: UIO[A1], fa2: UIO[A2])(f: (A1, A2) => R): UIO[R] =
    TaskMapBoth(fa1, fa2)(f)

  /**
    * @see See [[monix.bio.Task.map2]]
    */
  def map2[A1, A2, R](fa1: UIO[A1], fa2: UIO[A2])(f: (A1, A2) => R): UIO[R] =
    Task.map2(fa1, fa2)(f)

  /**
    * @see See [[monix.bio.Task.map3]]
    */
  def map3[A1, A2, A3, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3])(f: (A1, A2, A3) => R): UIO[R] =
    Task.map3(fa1, fa2, fa3)(f)

  /**
    * @see See [[monix.bio.Task.map4]]
    */
  def map4[A1, A2, A3, A4, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3], fa4: UIO[A4])(
    f: (A1, A2, A3, A4) => R
  ): UIO[R] =
    Task.map4(fa1, fa2, fa3, fa4)(f)

  /**
    * @see See [[monix.bio.Task.map5]]
    */
  def map5[A1, A2, A3, A4, A5, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3], fa4: UIO[A4], fa5: UIO[A5])(
    f: (A1, A2, A3, A4, A5) => R
  ): UIO[R] =
    Task.map5(fa1, fa2, fa3, fa4, fa5)(f)

  /**
    * @see See [[monix.bio.Task.map6]]
    */
  def map6[A1, A2, A3, A4, A5, A6, R](
    fa1: UIO[A1],
    fa2: UIO[A2],
    fa3: UIO[A3],
    fa4: UIO[A4],
    fa5: UIO[A5],
    fa6: UIO[A6]
  )(f: (A1, A2, A3, A4, A5, A6) => R): UIO[R] =
    Task.map6(fa1, fa2, fa3, fa4, fa5, fa6)(f)

  /**
    * @see See [[monix.bio.Task.parMap2]]
    */
  def parMap2[A1, A2, R](fa1: UIO[A1], fa2: UIO[A2])(f: (A1, A2) => R): UIO[R] =
    UIO.mapBoth(fa1, fa2)(f)

  /**
    * @see See [[monix.bio.Task.parMap3]]
    */
  def parMap3[A1, A2, A3, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3])(f: (A1, A2, A3) => R): UIO[R] = {
    val fa12 = parZip2(fa1, fa2)
    parMap2(fa12, fa3) { case ((a1, a2), a3) => f(a1, a2, a3) }
  }

  /**
    * @see See [[monix.bio.Task.parMap4]]
    */
  def parMap4[A1, A2, A3, A4, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3], fa4: UIO[A4])(
    f: (A1, A2, A3, A4) => R
  ): UIO[R] = {
    val fa123 = parZip3(fa1, fa2, fa3)
    parMap2(fa123, fa4) { case ((a1, a2, a3), a4) => f(a1, a2, a3, a4) }
  }

  /**
    * @see See [[monix.bio.Task.parMap5]]
    */
  def parMap5[A1, A2, A3, A4, A5, R](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3], fa4: UIO[A4], fa5: UIO[A5])(
    f: (A1, A2, A3, A4, A5) => R
  ): UIO[R] = {
    val fa1234 = parZip4(fa1, fa2, fa3, fa4)
    parMap2(fa1234, fa5) { case ((a1, a2, a3, a4), a5) => f(a1, a2, a3, a4, a5) }
  }

  /**
    * @see See [[Task.parMap6]]
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

  /**
    * @see See [[Task.parZip2]]
    */
  def parZip2[A1, A2, R](fa1: UIO[A1], fa2: UIO[A2]): UIO[(A1, A2)] =
    Task.mapBoth(fa1, fa2)((_, _))

  /**
    * @see See [[Task.parZip3]]
    */
  def parZip3[A1, A2, A3](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3]): UIO[(A1, A2, A3)] =
    parMap3(fa1, fa2, fa3)((a1, a2, a3) => (a1, a2, a3))

  /**
    * @see See [[Task.parZip4]]
    */
  def parZip4[A1, A2, A3, A4](fa1: UIO[A1], fa2: UIO[A2], fa3: UIO[A3], fa4: UIO[A4]): UIO[(A1, A2, A3, A4)] =
    parMap4(fa1, fa2, fa3, fa4)((a1, a2, a3, a4) => (a1, a2, a3, a4))

  /**
    * @see See [[Task.parZip5]]
    */
  def parZip5[A1, A2, A3, A4, A5](
    fa1: UIO[A1],
    fa2: UIO[A2],
    fa3: UIO[A3],
    fa4: UIO[A4],
    fa5: UIO[A5]
  ): UIO[(A1, A2, A3, A4, A5)] =
    parMap5(fa1, fa2, fa3, fa4, fa5)((a1, a2, a3, a4, a5) => (a1, a2, a3, a4, a5))

  /**
    * @see See [[Task.parZip6]]
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
}

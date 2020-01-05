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

import monix.bio.BIO.Options
import monix.bio.compat.internal.newBuilder
import monix.bio.internal.{TaskGather, TaskMapBoth, TaskRace, TaskRacePair, TaskSequence, TaskShift}
import monix.execution.Scheduler
import monix.execution.compat.BuildFrom

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object UIO {

  /**
   * @see See [[monix.bio.BIO.apply]]
   */
  def apply[A](a: => A): UIO[A] =
    BIO.Eval(a _)

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
   * @see See [[monix.bio.BIO.raiseFatalError]]
   */
  def raiseFatalError(ex: Throwable): UIO[Nothing] =
    BIO.raiseFatalError(ex)

  /**
   * @see See [[monix.bio.BIO.defer]]
   */
  def defer[A](fa: => UIO[A]): UIO[A] =
    BIO.defer(fa)

  /**
   * @see See [[monix.bio.BIO.deferAction]]
   */
  def deferAction[E, A](f: Scheduler => UIO[A]): UIO[A] =
    BIO.deferAction(f)

  /**
   * @see See [[monix.bio.BIO.suspend]]
   */
  def suspend[A](fa: => UIO[A]): UIO[A] =
    BIO.suspend(fa)

  /**
   * @see See [[monix.bio.BIO.eval]]
   */
  def eval[A](a: => A): UIO[A] =
    BIO.Eval(a _)

  /**
   * @see See [[monix.bio.BIO.evalAsync]]
   */
  def evalAsync[A](a: => A): UIO[A] =
    BIO.Eval(a _).executeAsync

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
   * @see See [[monix.bio.BIO.race]]
   */
  def race[A, B](fa: UIO[A], fb: UIO[B]): UIO[Either[A, B]] =
    TaskRace(fa, fb)

  /**
   * @see See [[monix.bio.BIO.shift]]
   */
  val shift: UIO[Unit] =
    BIO.shift

  /**
   * @see See [[monix.bio.BIO.shift]]
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
    TaskSequence.list(in)(bf)

  /**
   * @see See [[monix.bio.BIO.traverse]]
   */
  def traverse[A, B, M[X] <: Iterable[X]](in: M[A])(f: A => UIO[B])(implicit bf: BuildFrom[M[A], B, M[B]]): UIO[M[B]] =
    TaskSequence.traverse(in, f)(bf)

  /**
   * @see See [[monix.bio.BIO.mapBoth]]
   */
  def mapBoth[A1, A2, R](fa1: UIO[A1], fa2: UIO[A2])(f: (A1, A2) => R): UIO[R] =
    TaskMapBoth(fa1, fa2)(f)

  /**
   * @see See [[monix.bio.BIO.readOptions]]
   */
  val readOptions: UIO[Options] =
    BIO.readOptions
}

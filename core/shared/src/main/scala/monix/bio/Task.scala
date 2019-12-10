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

import cats.effect.CancelToken
import monix.bio.WRYYY.AsyncBuilder
import monix.bio.internal.{TaskCreate, TaskFromFuture}
import monix.execution.{Callback, Scheduler}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object Task {
  /**
    * @see See [[monix.bio.WRYYY.apply]]
    */
  def apply[A](a: => A): Task[A] =
    WRYYY.eval(a)

  /**
    * @see See [[monix.bio.WRYYY.now]]
    */
  def now[A](a: A): Task[A] =
    WRYYY.now(a)

  /**
    * @see See [[monix.bio.WRYYY.pure]]
    */
  def pure[A](a: A): Task[A] =
    WRYYY.pure(a)

  /**
    * @see See [[monix.bio.WRYYY.raiseError]]
    */
  def raiseError[A](ex: Throwable): Task[A] =
    WRYYY.raiseError(ex)

  /**
    * @see See [[monix.bio.WRYYY.raiseFatalError]]
    */
  def raiseFatalError(ex: Throwable): Task[Nothing] =
    WRYYY.raiseFatalError(ex)

  /**
    * @see See [[monix.bio.WRYYY.defer]]
    */
  def defer[A](fa: => Task[A]): Task[A] =
    WRYYY.defer(fa)

  /**
    * @see See [[monix.bio.WRYYY.deferAction]]
    */
  def deferAction[A](f: Scheduler => Task[A]): Task[A] =
    WRYYY.deferAction(f)

  /**
    * @see See [[monix.bio.WRYYY.deferFuture]]
    */
  def deferFuture[A](fa: => Future[A]): Task[A] =
    defer(fromFuture(fa))

  /**
    * @see See [[monix.bio.WRYYY.deferFutureAction]]
    */
  def deferFutureAction[A](f: Scheduler => Future[A]): Task[A] =
    TaskFromFuture.deferAction(f)

  /**
    * @see See [[monix.bio.WRYYY.suspend]]
    */
  def suspend[A](fa: => Task[A]): Task[A] =
    WRYYY.suspend(fa)

  /**
    * @see See [[monix.bio.WRYYY.eval]]
    */
  def eval[A](a: => A): Task[A] =
    WRYYY.eval(a)

  /**
    * @see See [[monix.bio.WRYYY.evalAsync]]
    */
  def evalAsync[A](a: => A): Task[A] =
    WRYYY.evalAsync(a)

  /**
    * @see See [[monix.bio.WRYYY.delay]]
    */
  def delay[A](a: => A): Task[A] =
    WRYYY.delay(a)

  /**
    * @see See [[monix.bio.WRYYY.never]]
    */
  def never[A]: Task[A] =
    WRYYY.never

  /**
    * @see See [[monix.bio.WRYYY.fromTry]]
    */
  def fromTry[A](a: Try[A]): Task[A] =
    WRYYY.fromTry(a)

  /**
    * @see See [[monix.bio.WRYYY.fromEither]]
    */
  def fromEither[A](a: Either[Throwable, A]): Task[A] =
    WRYYY.fromEither(a)

  /**
    * @see See [[monix.bio.WRYYY.unit]]
    */
  val unit: Task[Unit] =
    WRYYY.unit

  /**
    * @see See [[monix.bio.WRYYY.async]]
    */
  def async[A](register: Callback[Throwable, A] => Unit): Task[A] =
    TaskCreate.async(register)

  /**
    * @see See [[monix.bio.WRYYY.async0]]
    */
  def async0[A](register: (Scheduler, Callback[Throwable, A]) => Unit): Task[A] =
    TaskCreate.async0(register)

  /**
    * @see See [[monix.bio.WRYYY.asyncF]]
    */
  def asyncF[A](register: Callback[Throwable, A] => Task[Unit]): Task[A] =
    TaskCreate.asyncF(register)

  /**
    * @see See [[monix.bio.WRYYY.cancelable]]
    */
  def cancelable[A](register: Callback[Throwable, A] => CancelToken[Task]): Task[A] =
    cancelable0((_, cb) => register(cb))

  /**
    * @see See [[monix.bio.WRYYY.cancelable0]]
    */
  def cancelable0[A](register: (Scheduler, Callback[Throwable, A]) => CancelToken[Task]): Task[A] =
    TaskCreate.cancelable0(register)

  /**
    * @see See [[monix.bio.WRYYY.cancelBoundary]]
    */
  val cancelBoundary: Task[Unit] =
    WRYYY.cancelBoundary

  /**
    * @see See [[monix.bio.WRYYY.create]]
    */
  def create[A]: AsyncBuilder.CreatePartiallyApplied[Throwable, A] =
    WRYYY.create[Throwable, A]

  /**
    * @see See [[monix.bio.WRYYY.fromFuture]]
    */
  def fromFuture[A](f: Future[A]): Task[A] =
    TaskFromFuture.strict(f)

  /**
    * @see See [[monix.bio.WRYYY.race]]
    */
  def race[A, B](fa: Task[A], fb: Task[B]): Task[Either[A, B]] =
    WRYYY.race(fa, fb)

  /**
    * @see See [[monix.bio.WRYYY.racePair]]
    */
  def racePair[A, B](fa: Task[A], fb: Task[B]): Task[Either[(A, Fiber[Throwable, B]), (Fiber[Throwable, A], B)]] =
    WRYYY.racePair(fa, fb)

  /**
    * @see See [[monix.bio.WRYYY.shift]]
    */
  val shift: Task[Unit] =
    WRYYY.shift

  /**
    * @see See [[monix.bio.WRYYY.shift]]
    */
  def shift(ec: ExecutionContext): Task[Unit] =
    WRYYY.shift(ec)

  /**
    * @see See [[monix.bio.WRYYY.sleep]]
    */
  def sleep(timespan: FiniteDuration): Task[Unit] =
    WRYYY.sleep(timespan)

  /**
    * @see See [[monix.bio.WRYYY.gatherUnordered]]
    */
  def gatherUnordered[A](in: Iterable[Task[A]]): Task[List[A]] =
    WRYYY.gatherUnordered(in)
}

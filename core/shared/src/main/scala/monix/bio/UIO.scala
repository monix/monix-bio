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

import scala.concurrent.duration.FiniteDuration

object UIO {
  /**
    * @see See [[monix.bio.WRYYY.apply]]
    */
  def apply[A](a: => A): UIO[A] =
    WRYYY.Eval(a _)

  /**
    * @see See [[monix.bio.WRYYY.now]]
    */
  def now[A](a: A): UIO[A] =
    WRYYY.now(a)

  /**
    * @see See [[monix.bio.WRYYY.pure]]
    */
  def pure[A](a: A): UIO[A] =
    WRYYY.pure(a)

  /**
    * @see See [[monix.bio.WRYYY.raiseFatalError]]
    */
  def raiseFatalError(ex: Throwable): UIO[Nothing] =
    WRYYY.raiseFatalError(ex)

  /**
    * @see See [[monix.bio.WRYYY.defer]]
    */
  def defer[A](fa: => UIO[A]): UIO[A] =
    WRYYY.defer(fa)

  /**
    * @see See [[monix.bio.WRYYY.suspend]]
    */
  def suspend[A](fa: => UIO[A]): UIO[A] =
    WRYYY.suspend(fa)

  /**
    * @see See [[monix.bio.WRYYY.eval]]
    */
  def eval[A](a: => A): UIO[A] =
    WRYYY.Eval(a _)

  /**
    * @see See [[monix.bio.WRYYY.evalAsync]]
    */
  def evalAsync[A](a: => A): UIO[A] =
    WRYYY.Eval(a _).executeAsync

  /**
    * @see See [[monix.bio.WRYYY.never]]
    */
  val never: UIO[Nothing] =
    WRYYY.never

  /**
    * @see See [[monix.bio.WRYYY.unit]]
    */
  val unit: UIO[Unit] =
    WRYYY.unit

  /**
    * @see See [[monix.bio.WRYYY.sleep]]
    */
  def sleep(timespan: FiniteDuration): UIO[Unit] =
    WRYYY.sleep(timespan)
}

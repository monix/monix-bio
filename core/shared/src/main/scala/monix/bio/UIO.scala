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
    * @see See [[monix.bio.BIO.never]]
    */
  val never: UIO[Nothing] =
    BIO.never

  /**
    * @see See [[monix.bio.BIO.unit]]
    */
  val unit: UIO[Unit] =
    BIO.unit

  /**
    * @see See [[monix.bio.BIO.sleep]]
    */
  def sleep(timespan: FiniteDuration): UIO[Unit] =
    BIO.sleep(timespan)
}

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
import scala.util.Try

object Task {

  def now[A](a: A): Task[A] =
    WRYYY.now(a)

  def pure[A](a: A): Task[A] =
    WRYYY.pure(a)

  def apply[A](a: => A): Task[A] =
    WRYYY.eval(a)

  def eval[A](a: => A): Task[A] =
    WRYYY.eval(a)

  def evalAsync[A](a: => A): Task[A] =
    WRYYY.evalAsync(a)

  def suspend[A](fa: => Task[A]): Task[A] =
    WRYYY.suspend(fa)

  def defer[A](fa: => Task[A]): Task[A] =
    WRYYY.defer(fa)

  def raiseError[A](ex: Throwable): Task[A] =
    WRYYY.raiseError(ex)

  val unit: Task[Unit] =
    WRYYY.unit

  def never[A]: Task[A] =
    WRYYY.never

  val cancelBoundary: Task[Unit] =
    WRYYY.cancelBoundary

  def fromTry[A](a: Try[A]): Task[A] =
    WRYYY.fromTry(a)

  def race[A, B](fa: Task[A], fb: Task[B]): Task[Either[A, B]] =
    WRYYY.race(fa, fb)

  def racePair[A, B](fa: Task[A], fb: Task[B]): Task[Either[(A, Fiber[Throwable, B]), (Fiber[Throwable, A], B)]] =
    WRYYY.racePair(fa, fb)

  def sleep(timespan: FiniteDuration): Task[Unit] =
    WRYYY.sleep(timespan)
}

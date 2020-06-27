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

package instances

import cats.effect.{Async, CancelToken, Concurrent, ExitCase}
import monix.bio.internal.TaskCreate

/** Cats type class instance of [[monix.bio.Task Task]]
  * for  `cats.effect.Async` and `CoflatMap` (and implicitly for
  * `Applicative`, `Monad`, `MonadError`, etc).
  *
  * References:
  *
  *  - [[https://typelevel.org/cats/ typelevel/cats]]
  *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
  */
class CatsAsyncForTask extends CatsBaseForTask[Throwable] with Async[BIO.Unsafe] {

  override def delay[A](thunk: => A): BIO.Unsafe[A] =
    BIO.eval(thunk)

  override def suspend[A](fa: => BIO.Unsafe[A]): BIO.Unsafe[A] =
    BIO.defer(fa)

  override def async[A](k: (Either[Throwable, A] => Unit) => Unit): BIO.Unsafe[A] = {
    TaskCreate.async(cb => k(BiCallback.toEither(cb)))
  }

  override def bracket[A, B](acquire: BIO.Unsafe[A])(use: A => BIO.Unsafe[B])(release: A => BIO.Unsafe[Unit]): BIO.Unsafe[B] =
    acquire.bracket(use)(release.andThen(_.onErrorHandleWith(BIO.terminate)))

  override def bracketCase[A, B](
    acquire: BIO.Unsafe[A]
  )(use: A => BIO.Unsafe[B])(release: (A, ExitCase[Throwable]) => BIO.Unsafe[Unit]): BIO.Unsafe[B] =
    acquire.bracketCase(use)((a, exit) => release(a, exitCaseFromCause(exit)).onErrorHandleWith(BIO.terminate))

  override def asyncF[A](k: (Either[Throwable, A] => Unit) => BIO.Unsafe[Unit]): BIO.Unsafe[A] =
    TaskCreate.asyncF(cb => k(BiCallback.toEither(cb)))

  override def guarantee[A](acquire: BIO.Unsafe[A])(finalizer: BIO.Unsafe[Unit]): BIO.Unsafe[A] =
    acquire.guarantee(finalizer.onErrorHandleWith(BIO.terminate))

  override def guaranteeCase[A](acquire: BIO.Unsafe[A])(finalizer: ExitCase[Throwable] => BIO.Unsafe[Unit]): BIO.Unsafe[A] =
    acquire.guaranteeCase(exit => finalizer(exitCaseFromCause(exit)).onErrorHandleWith(BIO.terminate))
}

/** Cats type class instance of [[monix.bio.BIO BIO]]
  * for  `cats.effect.Concurrent`.
  *
  * References:
  *
  *  - [[https://typelevel.org/cats/ typelevel/cats]]
  *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
  */
class CatsConcurrentForTask extends CatsAsyncForTask with Concurrent[BIO.Unsafe] {

  override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[BIO.Unsafe]): BIO.Unsafe[A] =
    TaskCreate.cancelableEffect(k)

  override def uncancelable[A](fa: BIO.Unsafe[A]): BIO.Unsafe[A] =
    fa.uncancelable

  override def start[A](fa: BIO.Unsafe[A]): BIO.Unsafe[Fiber[Throwable, A]] =
    fa.start

  override def racePair[A, B](
    fa: BIO.Unsafe[A],
    fb: BIO.Unsafe[B]
  ): BIO.Unsafe[Either[(A, Fiber[Throwable, B]), (Fiber[Throwable, A], B)]] =
    BIO.racePair(fa, fb)

  override def race[A, B](fa: BIO.Unsafe[A], fb: BIO.Unsafe[B]): BIO.Unsafe[Either[A, B]] =
    BIO.race(fa, fb)
}

/** Default and reusable instance for [[CatsConcurrentForTask]].
  *
  * Globally available in scope, as it is returned by
  * [[monix.bio.BIO.catsAsync BIO.catsAsync]].
  */
object CatsConcurrentForTask extends CatsConcurrentForTask

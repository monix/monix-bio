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

import cats.effect.{Fiber => _, _}
import monix.bio.internal.TaskEffect
import monix.execution.Scheduler

/** Cats type class instances of [[monix.bio.Task Task]] for
  * `cats.effect.Effect` (and implicitly for `Applicative`, `Monad`,
  * `MonadError`, `Sync`, etc).
  *
  * Note this is a separate class from [[CatsAsyncForTask]], because we
  * need an implicit [[monix.execution.Scheduler Scheduler]] in scope
  * in order to trigger the execution of a `Task`. However we cannot
  * inherit directly from `CatsAsyncForTask`, because it would create
  * conflicts due to that one having a higher priority but being a
  * super-type.
  *
  * References:
  *
  *  - [[https://typelevel.org/cats/ typelevel/cats]]
  *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
  */
class CatsEffectForTask(implicit s: Scheduler, opts: Task.Options)
    extends CatsBaseForTask[Throwable] with Effect[Task.Unsafe] {

  /** We need to mixin [[CatsAsyncForTask]], because if we
    * inherit directly from it, the implicits priorities don't
    * work, triggering conflicts.
    */
  private[this] val F = CatsConcurrentForTask

  override def runAsync[A](fa: Task.Unsafe[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
    TaskEffect.runAsync(fa)(cb)

  override def delay[A](thunk: => A): Task.Unsafe[A] =
    F.delay(thunk)

  override def suspend[A](fa: => Task.Unsafe[A]): Task.Unsafe[A] =
    F.suspend(fa)

  override def async[A](k: ((Either[Throwable, A]) => Unit) => Unit): Task.Unsafe[A] =
    F.async(k)

  override def asyncF[A](k: (Either[Throwable, A] => Unit) => Task.Unsafe[Unit]): Task.Unsafe[A] =
    F.asyncF(k)

  override def bracket[A, B](
    acquire: Task.Unsafe[A]
  )(use: A => Task.Unsafe[B])(release: A => Task.Unsafe[Unit]): Task.Unsafe[B] =
    F.bracket(acquire)(use)(release)

  override def bracketCase[A, B](
    acquire: Task.Unsafe[A]
  )(use: A => Task.Unsafe[B])(release: (A, ExitCase[Throwable]) => Task.Unsafe[Unit]): Task.Unsafe[B] =
    F.bracketCase(acquire)(use)(release)
}

/** Cats type class instances of [[monix.bio.Task Task]] for
  * `cats.effect.ConcurrentEffect`.
  *
  * Note this is a separate class from [[CatsConcurrentForTask]], because
  * we need an implicit [[monix.execution.Scheduler Scheduler]] in scope
  * in order to trigger the execution of a `Task`. However we cannot
  * inherit directly from `CatsConcurrentForTask`, because it would create
  * conflicts due to that one having a higher priority but being a
  * super-type.
  *
  * References:
  *
  *  - [[https://typelevel.org/cats/ typelevel/cats]]
  *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
  */
class CatsConcurrentEffectForTask(implicit s: Scheduler, opts: Task.Options)
    extends CatsEffectForTask with ConcurrentEffect[Task.Unsafe] {

  /** We need to mixin [[CatsAsyncForTask]], because if we
    * inherit directly from it, the implicits priorities don't
    * work, triggering conflicts.
    */
  private[this] val F = CatsConcurrentForTask

  override def runCancelable[A](
    fa: Task.Unsafe[A]
  )(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[Task.Unsafe]] =
    TaskEffect.runCancelable(fa)(cb)

  override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[Task.Unsafe]): Task.Unsafe[A] =
    F.cancelable(k)

  override def uncancelable[A](fa: Task.Unsafe[A]): Task.Unsafe[A] =
    F.uncancelable(fa)

  override def start[A](fa: Task.Unsafe[A]): Task.Unsafe[Fiber[Throwable, A]] =
    F.start(fa)

  override def racePair[A, B](
    fa: Task.Unsafe[A],
    fb: Task.Unsafe[B]
  ): Task.Unsafe[Either[(A, Fiber[Throwable, B]), (Fiber[Throwable, A], B)]] =
    F.racePair(fa, fb)

  override def race[A, B](fa: Task.Unsafe[A], fb: Task.Unsafe[B]): Task.Unsafe[Either[A, B]] =
    F.race(fa, fb)
}

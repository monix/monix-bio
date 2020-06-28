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

package monix.bio.instances

import cats.{Bifunctor, CoflatMap, Eval, MonadError, SemigroupK}
import monix.bio.{Task, UIO}

import scala.util.Try

/** Cats type class instances for [[monix.bio.Task Task]]
  * for  `cats.MonadError`, `CoflatMap`, `SemigroupK` and `Bifunctor`
  * (and implicitly for `Applicative`, `Monad`, etc).
  *
  * References:
  *
  *  - [[https://typelevel.org/cats/ typelevel/cats]]
  *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
  */
class CatsBaseForTask[E]
    extends MonadError[Task[E, *], E] with CoflatMap[Task[E, *]] with SemigroupK[Task[E, *]] with Bifunctor[Task] {

  override def pure[A](a: A): UIO[A] =
    Task.pure(a)

  override val unit: UIO[Unit] =
    Task.unit

  override def flatMap[A, B](fa: Task[E, A])(f: (A) => Task[E, B]): Task[E, B] =
    fa.flatMap(f)

  override def flatten[A](ffa: Task[E, Task[E, A]]): Task[E, A] =
    ffa.flatten

  override def tailRecM[A, B](a: A)(f: (A) => Task[E, Either[A, B]]): Task[E, B] =
    Task.tailRecM(a)(f)

  override def ap[A, B](ff: Task[E, (A) => B])(fa: Task[E, A]): Task[E, B] =
    for (f <- ff; a <- fa) yield f(a)

  override def map2[A, B, Z](fa: Task[E, A], fb: Task[E, B])(f: (A, B) => Z): Task[E, Z] =
    for (a <- fa; b <- fb) yield f(a, b)

  override def product[A, B](fa: Task[E, A], fb: Task[E, B]): Task[E, (A, B)] =
    for (a <- fa; b <- fb) yield (a, b)

  override def map[A, B](fa: Task[E, A])(f: (A) => B): Task[E, B] =
    fa.map(f)

  override def raiseError[A](e: E): Task[E, A] =
    Task.raiseError(e)

  override def handleError[A](fa: Task[E, A])(f: (E) => A): Task[E, A] =
    fa.onErrorHandle(f)

  override def handleErrorWith[A](fa: Task[E, A])(f: (E) => Task[E, A]): Task[E, A] =
    fa.onErrorHandleWith(f)

  override def recover[A](fa: Task[E, A])(pf: PartialFunction[E, A]): Task[E, A] =
    fa.onErrorRecover(pf)

  override def recoverWith[A](fa: Task[E, A])(pf: PartialFunction[E, Task[E, A]]): Task[E, A] =
    fa.onErrorRecoverWith(pf)

  override def attempt[A](fa: Task[E, A]): Task[E, Either[E, A]] =
    fa.attempt

  override def catchNonFatal[A](a: => A)(implicit ev: <:<[Throwable, E]): Task[E, A] =
    Task.eval(a).asInstanceOf[Task[E, A]]

  override def catchNonFatalEval[A](a: Eval[A])(implicit ev: <:<[Throwable, E]): Task[E, A] =
    Task.eval(a.value).asInstanceOf[Task[E, A]]

  override def fromTry[A](t: Try[A])(implicit ev: <:<[Throwable, E]): Task[E, A] =
    Task.fromTry(t).asInstanceOf[Task[E, A]]

  override def coflatMap[A, B](fa: Task[E, A])(f: (Task[E, A]) => B): Task[E, B] =
    fa.start.map(fiber => f(fiber.join))

  override def coflatten[A](fa: Task[E, A]): Task[E, Task[E, A]] =
    fa.start.map(_.join)

  override def combineK[A](ta: Task[E, A], tb: Task[E, A]): Task[E, A] =
    ta.onErrorHandleWith(_ => tb)

  override def bimap[A, B, C, D](fab: Task[A, B])(f: A => C, g: B => D): Task[C, D] =
    fab.bimap(f, g)
}

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
import monix.bio.{IO, UIO}

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
    extends MonadError[IO[E, *], E] with CoflatMap[IO[E, *]] with SemigroupK[IO[E, *]] with Bifunctor[IO] {

  override def pure[A](a: A): UIO[A] =
    IO.pure(a)

  override val unit: UIO[Unit] =
    IO.unit

  override def flatMap[A, B](fa: IO[E, A])(f: (A) => IO[E, B]): IO[E, B] =
    fa.flatMap(f)

  override def flatten[A](ffa: IO[E, IO[E, A]]): IO[E, A] =
    ffa.flatten

  override def tailRecM[A, B](a: A)(f: (A) => IO[E, Either[A, B]]): IO[E, B] =
    IO.tailRecM(a)(f)

  override def ap[A, B](ff: IO[E, (A) => B])(fa: IO[E, A]): IO[E, B] =
    for (f <- ff; a <- fa) yield f(a)

  override def map2[A, B, Z](fa: IO[E, A], fb: IO[E, B])(f: (A, B) => Z): IO[E, Z] =
    for (a <- fa; b <- fb) yield f(a, b)

  override def product[A, B](fa: IO[E, A], fb: IO[E, B]): IO[E, (A, B)] =
    for (a <- fa; b <- fb) yield (a, b)

  override def map[A, B](fa: IO[E, A])(f: (A) => B): IO[E, B] =
    fa.map(f)

  override def raiseError[A](e: E): IO[E, A] =
    IO.raiseError(e)

  override def handleError[A](fa: IO[E, A])(f: (E) => A): IO[E, A] =
    fa.onErrorHandle(f)

  override def handleErrorWith[A](fa: IO[E, A])(f: (E) => IO[E, A]): IO[E, A] =
    fa.onErrorHandleWith(f)

  override def recover[A](fa: IO[E, A])(pf: PartialFunction[E, A]): IO[E, A] =
    fa.onErrorRecover(pf)

  override def recoverWith[A](fa: IO[E, A])(pf: PartialFunction[E, IO[E, A]]): IO[E, A] =
    fa.onErrorRecoverWith(pf)

  override def attempt[A](fa: IO[E, A]): IO[E, Either[E, A]] =
    fa.attempt

  override def catchNonFatal[A](a: => A)(implicit ev: <:<[Throwable, E]): IO[E, A] =
    IO.eval(a).asInstanceOf[IO[E, A]]

  override def catchNonFatalEval[A](a: Eval[A])(implicit ev: <:<[Throwable, E]): IO[E, A] =
    IO.eval(a.value).asInstanceOf[IO[E, A]]

  override def fromTry[A](t: Try[A])(implicit ev: <:<[Throwable, E]): IO[E, A] =
    IO.fromTry(t).asInstanceOf[IO[E, A]]

  override def coflatMap[A, B](fa: IO[E, A])(f: (IO[E, A]) => B): IO[E, B] =
    fa.start.map(fiber => f(fiber.join))

  override def coflatten[A](fa: IO[E, A]): IO[E, IO[E, A]] =
    fa.start.map(_.join)

  override def combineK[A](ta: IO[E, A], tb: IO[E, A]): IO[E, A] =
    ta.onErrorHandleWith(_ => tb)

  override def bimap[A, B, C, D](fab: IO[A, B])(f: A => C, g: B => D): IO[C, D] =
    fab.bimap(f, g)
}

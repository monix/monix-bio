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
import monix.bio.{BIO, UIO}

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
    extends MonadError[BIO[E, *], E] with CoflatMap[BIO[E, *]] with SemigroupK[BIO[E, *]] with Bifunctor[BIO] {

  override def pure[A](a: A): UIO[A] =
    BIO.pure(a)

  override val unit: UIO[Unit] =
    BIO.unit

  override def flatMap[A, B](fa: BIO[E, A])(f: (A) => BIO[E, B]): BIO[E, B] =
    fa.flatMap(f)

  override def flatten[A](ffa: BIO[E, BIO[E, A]]): BIO[E, A] =
    ffa.flatten

  override def tailRecM[A, B](a: A)(f: (A) => BIO[E, Either[A, B]]): BIO[E, B] =
    BIO.tailRecM(a)(f)

  override def ap[A, B](ff: BIO[E, (A) => B])(fa: BIO[E, A]): BIO[E, B] =
    for (f <- ff; a <- fa) yield f(a)

  override def map2[A, B, Z](fa: BIO[E, A], fb: BIO[E, B])(f: (A, B) => Z): BIO[E, Z] =
    for (a <- fa; b <- fb) yield f(a, b)

  override def product[A, B](fa: BIO[E, A], fb: BIO[E, B]): BIO[E, (A, B)] =
    for (a <- fa; b <- fb) yield (a, b)

  override def map[A, B](fa: BIO[E, A])(f: (A) => B): BIO[E, B] =
    fa.map(f)

  override def raiseError[A](e: E): BIO[E, A] =
    BIO.raiseError(e)

  override def handleError[A](fa: BIO[E, A])(f: (E) => A): BIO[E, A] =
    fa.onErrorHandle(f)

  override def handleErrorWith[A](fa: BIO[E, A])(f: (E) => BIO[E, A]): BIO[E, A] =
    fa.onErrorHandleWith(f)

  override def recover[A](fa: BIO[E, A])(pf: PartialFunction[E, A]): BIO[E, A] =
    fa.onErrorRecover(pf)

  override def recoverWith[A](fa: BIO[E, A])(pf: PartialFunction[E, BIO[E, A]]): BIO[E, A] =
    fa.onErrorRecoverWith(pf)

  override def attempt[A](fa: BIO[E, A]): BIO[E, Either[E, A]] =
    fa.attempt

  override def catchNonFatal[A](a: => A)(implicit ev: <:<[Throwable, E]): BIO[E, A] =
    BIO.eval(a).asInstanceOf[BIO[E, A]]

  override def catchNonFatalEval[A](a: Eval[A])(implicit ev: <:<[Throwable, E]): BIO[E, A] =
    BIO.eval(a.value).asInstanceOf[BIO[E, A]]

  override def fromTry[A](t: Try[A])(implicit ev: <:<[Throwable, E]): BIO[E, A] =
    BIO.fromTry(t).asInstanceOf[BIO[E, A]]

  override def coflatMap[A, B](fa: BIO[E, A])(f: (BIO[E, A]) => B): BIO[E, B] =
    fa.start.map(fiber => f(fiber.join))

  override def coflatten[A](fa: BIO[E, A]): BIO[E, BIO[E, A]] =
    fa.start.map(_.join)

  override def combineK[A](ta: BIO[E, A], tb: BIO[E, A]): BIO[E, A] =
    ta.onErrorHandleWith(_ => tb)

  override def bimap[A, B, C, D](fab: BIO[A, B])(f: A => C, g: B => D): BIO[C, D] =
    fab.bimap(f, g)
}

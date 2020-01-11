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

package monix.bio.instances

import cats.{~>, CommutativeApplicative, Monad, Parallel}
import monix.bio.BIO

/** `cats.Parallel` type class instance for [[monix.bio.BIO BIO]].
  *
  * A `cats.Parallel` instances means that `Task` can be used for
  * processing tasks in parallel (with non-deterministic effects
  * ordering).
  *
  * References:
  *
  *  - [[https://typelevel.org/cats/ typelevel/cats]]
  *  - [[https://github.com/typelevel/cats-effect typelevel/cats-effect]]
  */
class CatsParallelForTask[E] extends Parallel[BIO[E, ?]] {
  override type F[A] = BIO.Par[E, A]

  override val applicative: CommutativeApplicative[BIO.Par[E, ?]] = new NondetApplicative[E]
  override val monad: Monad[BIO[E, ?]] = new CatsBaseForTask[E]

  override val sequential: BIO.Par[E, ?] ~> BIO[E, ?] = new (BIO.Par[E, ?] ~> BIO[E, ?]) {
    def apply[A](fa: BIO.Par[E, A]): BIO[E, A] = BIO.Par.unwrap(fa)
  }

  override val parallel: BIO[E, ?] ~> BIO.Par[E, ?] = new (BIO[E, ?] ~> BIO.Par[E, ?]) {
    def apply[A](fa: BIO[E, A]): BIO.Par[E, A] = BIO.Par.apply(fa)
  }
}

private class NondetApplicative[E] extends CommutativeApplicative[BIO.Par[E, ?]] {

  import BIO.Par.{unwrap, apply => par}

  override def ap[A, B](ff: BIO.Par[E, A => B])(fa: BIO.Par[E, A]): BIO.Par[E, B] =
    par(BIO.mapBoth(unwrap(ff), unwrap(fa))(_(_)))

  override def map2[A, B, Z](fa: BIO.Par[E, A], fb: BIO.Par[E, B])(f: (A, B) => Z): BIO.Par[E, Z] =
    par(BIO.mapBoth(unwrap(fa), unwrap(fb))(f))

  override def product[A, B](fa: BIO.Par[E, A], fb: BIO.Par[E, B]): BIO.Par[E, (A, B)] =
    par(BIO.mapBoth(unwrap(fa), unwrap(fb))((_, _)))

  override def pure[A](a: A): BIO.Par[E, A] =
    par(BIO.now(a))

  override val unit: BIO.Par[E, Unit] =
    par(BIO.unit)

  override def map[A, B](fa: BIO.Par[E, A])(f: A => B): BIO.Par[E, B] =
    par(unwrap(fa).map(f))
}

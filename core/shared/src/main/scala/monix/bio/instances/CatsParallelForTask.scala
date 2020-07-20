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

import cats.{~>, CommutativeApplicative, Monad, Parallel}
import monix.bio.IO

/** `cats.Parallel` type class instance for [[monix.bio.IO IO]].
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
class CatsParallelForTask[E] extends Parallel[IO[E, *]] {
  override type F[A] = IO.Par[E, A]

  override val applicative: CommutativeApplicative[IO.Par[E, *]] = new NondetApplicative[E]
  override val monad: Monad[IO[E, *]] = new CatsBaseForTask[E]

  override val sequential: IO.Par[E, *] ~> IO[E, *] = new (IO.Par[E, *] ~> IO[E, *]) {
    def apply[A](fa: IO.Par[E, A]): IO[E, A] = IO.Par.unwrap(fa)
  }

  override val parallel: IO[E, *] ~> IO.Par[E, *] = new (IO[E, *] ~> IO.Par[E, *]) {
    def apply[A](fa: IO[E, A]): IO.Par[E, A] = IO.Par.apply(fa)
  }
}

private class NondetApplicative[E] extends CommutativeApplicative[IO.Par[E, *]] {

  import IO.Par.{unwrap, apply => par}

  override def ap[A, B](ff: IO.Par[E, A => B])(fa: IO.Par[E, A]): IO.Par[E, B] =
    par(IO.mapBoth(unwrap(ff), unwrap(fa))(_(_)))

  override def map2[A, B, Z](fa: IO.Par[E, A], fb: IO.Par[E, B])(f: (A, B) => Z): IO.Par[E, Z] =
    par(IO.mapBoth(unwrap(fa), unwrap(fb))(f))

  override def product[A, B](fa: IO.Par[E, A], fb: IO.Par[E, B]): IO.Par[E, (A, B)] =
    par(IO.mapBoth(unwrap(fa), unwrap(fb))((_, _)))

  override def pure[A](a: A): IO.Par[E, A] =
    par(IO.now(a))

  override val unit: IO.Par[E, Unit] =
    par(IO.unit)

  override def map[A, B](fa: IO.Par[E, A])(f: A => B): IO.Par[E, B] =
    par(unwrap(fa).map(f))
}

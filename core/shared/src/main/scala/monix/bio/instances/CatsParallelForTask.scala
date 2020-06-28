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
import monix.bio.Task

/** `cats.Parallel` type class instance for [[monix.bio.Task Task]].
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
class CatsParallelForTask[E] extends Parallel[Task[E, *]] {
  override type F[A] = Task.Par[E, A]

  override val applicative: CommutativeApplicative[Task.Par[E, *]] = new NondetApplicative[E]
  override val monad: Monad[Task[E, *]] = new CatsBaseForTask[E]

  override val sequential: Task.Par[E, *] ~> Task[E, *] = new (Task.Par[E, *] ~> Task[E, *]) {
    def apply[A](fa: Task.Par[E, A]): Task[E, A] = Task.Par.unwrap(fa)
  }

  override val parallel: Task[E, *] ~> Task.Par[E, *] = new (Task[E, *] ~> Task.Par[E, *]) {
    def apply[A](fa: Task[E, A]): Task.Par[E, A] = Task.Par.apply(fa)
  }
}

private class NondetApplicative[E] extends CommutativeApplicative[Task.Par[E, *]] {

  import Task.Par.{unwrap, apply => par}

  override def ap[A, B](ff: Task.Par[E, A => B])(fa: Task.Par[E, A]): Task.Par[E, B] =
    par(Task.mapBoth(unwrap(ff), unwrap(fa))(_(_)))

  override def map2[A, B, Z](fa: Task.Par[E, A], fb: Task.Par[E, B])(f: (A, B) => Z): Task.Par[E, Z] =
    par(Task.mapBoth(unwrap(fa), unwrap(fb))(f))

  override def product[A, B](fa: Task.Par[E, A], fb: Task.Par[E, B]): Task.Par[E, (A, B)] =
    par(Task.mapBoth(unwrap(fa), unwrap(fb))((_, _)))

  override def pure[A](a: A): Task.Par[E, A] =
    par(Task.now(a))

  override val unit: Task.Par[E, Unit] =
    par(Task.unit)

  override def map[A, B](fa: Task.Par[E, A])(f: A => B): Task.Par[E, B] =
    par(unwrap(fa).map(f))
}

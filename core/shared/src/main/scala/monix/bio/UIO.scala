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

object UIO {

  def apply[A](a: => A): UIO[A] =
    WRYYY.suspend(WRYYY.pure(a))

  def eval[A](a: => A): UIO[A] =
    apply(a)

  def evalAsync[A](a: => A): UIO[A] =
    WRYYY.suspend(WRYYY.pure(a)).executeAsync

  def suspend[A](fa: => UIO[A]): UIO[A] =
    WRYYY.suspend(fa)

  val never: UIO[Nothing] =
    WRYYY.never

  val unit: UIO[Unit] =
    WRYYY.unit
}

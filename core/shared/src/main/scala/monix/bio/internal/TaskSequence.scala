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

package monix.bio.internal

import monix.bio.WRYYY
import monix.execution.compat.BuildFrom
import monix.execution.compat.internal._

import scala.collection.mutable

private[bio] object TaskSequence {

  /** Implementation for `Task.sequence`. */
  def list[E, A, M[X] <: Iterable[X]](in: M[WRYYY[E, A]])(
    implicit bf: BuildFrom[M[WRYYY[E, A]], A, M[A]]): WRYYY[E, M[A]] = {

    def loop(cursor: Iterator[WRYYY[E, A]], acc: mutable.Builder[A, M[A]]): WRYYY[E, M[A]] = {
      if (cursor.hasNext) {
        val next = cursor.next()
        next.flatMap { a =>
          loop(cursor, acc += a)
        }
      } else {
        WRYYY.now(acc.result())
      }
    }

    WRYYY.defer {
      val cursor: Iterator[WRYYY[E, A]] = toIterator(in)
      loop(cursor, newBuilder(bf, in))
    }
  }

  /** Implementation for `Task.traverse`. */
  def traverse[E, A, B, M[X] <: Iterable[X]](in: M[A], f: A => WRYYY[E, B])(
    implicit bf: BuildFrom[M[A], B, M[B]]): WRYYY[E, M[B]] = {

    def loop(cursor: Iterator[A], acc: mutable.Builder[B, M[B]]): WRYYY[E, M[B]] = {
      if (cursor.hasNext) {
        val next = f(cursor.next())
        next.flatMap { a =>
          loop(cursor, acc += a)
        }
      } else {
        WRYYY.now(acc.result())
      }
    }

    WRYYY.defer {
      loop(toIterator(in), newBuilder(bf, in))
    }
  }
}

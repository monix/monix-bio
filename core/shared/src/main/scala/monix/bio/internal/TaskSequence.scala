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

package monix.bio.internal

import monix.bio.IO
import monix.execution.compat.internal._

import scala.collection.mutable

private[bio] object TaskSequence {

  /** Implementation for `Task.sequence`. */
  def list[E, A](in: Iterable[IO[E, A]]): IO[E, List[A]] = {

    def loop(cursor: Iterator[IO[E, A]], acc: mutable.Builder[A, List[A]]): IO[E, List[A]] = {
      if (cursor.hasNext) {
        val next = cursor.next()
        next.flatMap { a =>
          loop(cursor, acc += a)
        }
      } else {
        IO.now(acc.result())
      }
    }

    IO.suspendTotal {
      val cursor: Iterator[IO[E, A]] = toIterator(in)
      loop(cursor, List.newBuilder)
    }
  }

  /** Implementation for `Task.traverse`. */
  def traverse[E, A, B](in: Iterable[A], f: A => IO[E, B]): IO[E, List[B]] = {

    def loop(cursor: Iterator[A], acc: mutable.Builder[B, List[B]]): IO[E, List[B]] = {
      if (cursor.hasNext) {
        val next = f(cursor.next())
        next.flatMap { a =>
          loop(cursor, acc += a)
        }
      } else {
        IO.now(acc.result())
      }
    }

    IO.suspendTotal {
      loop(toIterator(in), List.newBuilder)
    }
  }
}

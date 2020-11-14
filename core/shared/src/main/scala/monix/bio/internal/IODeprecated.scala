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

private[bio] object IODeprecated {

  /** Extension methods describing deprecated `IO` operations.
    */
  private[bio] abstract class Companion {

    /** DEPRECATED — renamed to [[IO.parSequence]]. */
    @deprecated("Use parSequence", "0.1.0")
    def gather[E, A](
      in: Iterable[IO[E, A]]
    ): IO[E, List[A]] = {
      // $COVERAGE-OFF$
      IO.parSequence(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[IO.parSequenceN]] */
    @deprecated("Use parSequenceN", "0.1.0")
    def gatherN[E, A](parallelism: Int)(in: Iterable[IO[E, A]]): IO[E, List[A]] = {
      // $COVERAGE-OFF$
      IO.parSequenceN(parallelism)(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[IO.parSequenceUnordered]] */
    @deprecated("Use parSequenceUnordered", "0.1.0")
    def gatherUnordered[E, A](in: Iterable[IO[E, A]]): IO[E, List[A]] = {
      // $COVERAGE-OFF$
      IO.parSequenceUnordered(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[IO.parTraverse]] */
    @deprecated("Use parTraverse", "0.1.0")
    def wander[E, A, B](
      in: Iterable[A]
    )(f: A => IO[E, B]): IO[E, List[B]] = {
      // $COVERAGE-OFF$
      IO.parTraverse(in)(f)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[IO.parTraverseN]] */
    @deprecated("Use parTraverseN", "0.1.0")
    def wanderN[E, A, B](parallelism: Int)(in: Iterable[A])(f: A => IO[E, B]): IO[E, List[B]] = {
      // $COVERAGE-OFF$
      IO.parTraverseN(parallelism)(in)(f)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[IO.parTraverseUnordered]] */
    @deprecated("Use parTraverseUnordered", "3.2.0")
    def wanderUnordered[E, A, B, M[X] <: Iterable[X]](in: M[A])(f: A => IO[E, B]): IO[E, List[B]] = {
      // $COVERAGE-OFF$
      IO.parTraverseUnordered(in)(f)
      // $COVERAGE-ON$
    }
  }
}

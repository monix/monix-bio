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

import monix.bio.BIO

private[bio] object BIODeprecated {

  /**
    * Extension methods describing deprecated `BIO` operations.
    */
  private[bio] abstract class Companion {

    /** DEPRECATED — renamed to [[BIO.parSequence]]. */
    @deprecated("Use parSequence", "0.1.0")
    def gather[E, A](
      in: Iterable[BIO[E, A]]
    ): BIO[E, List[A]] = {
      // $COVERAGE-OFF$
      BIO.parSequence(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[BIO.parSequenceN]] */
    @deprecated("Use parSequenceN", "0.1.0")
    def gatherN[E, A](parallelism: Int)(in: Iterable[BIO[E, A]]): BIO[E, List[A]] = {
      // $COVERAGE-OFF$
      BIO.parSequenceN(parallelism)(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[BIO.parSequenceUnordered]] */
    @deprecated("Use parSequenceUnordered", "0.1.0")
    def gatherUnordered[E, A](in: Iterable[BIO[E, A]]): BIO[E, List[A]] = {
      // $COVERAGE-OFF$
      BIO.parSequenceUnordered(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[BIO.parTraverse]] */
    @deprecated("Use parTraverse", "0.1.0")
    def wander[E, A, B](
      in: Iterable[A]
    )(f: A => BIO[E, B]): BIO[E, List[B]] = {
      // $COVERAGE-OFF$
      BIO.parTraverse(in)(f)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[BIO.parTraverseN]] */
    @deprecated("Use parTraverseN", "0.1.0")
    def wanderN[E, A, B](parallelism: Int)(in: Iterable[A])(f: A => BIO[E, B]): BIO[E, List[B]] = {
      // $COVERAGE-OFF$
      BIO.parTraverseN(parallelism)(in)(f)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[BIO.parTraverseUnordered]] */
    @deprecated("Use parTraverseUnordered", "3.2.0")
    def wanderUnordered[E, A, B, M[X] <: Iterable[X]](in: M[A])(f: A => BIO[E, B]): BIO[E, List[B]] = {
      // $COVERAGE-OFF$
      BIO.parTraverseUnordered(in)(f)
      // $COVERAGE-ON$
    }
  }
}

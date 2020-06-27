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

import monix.bio.{BIO, UIO}

private[bio] object UIODeprecated {

  /**
    * Extension methods describing deprecated `UIO` operations.
    */
  private[bio] abstract class Companion {

    /** DEPRECATED — renamed to [[UIO.parSequence]]. */
    @deprecated("Use parSequence", "0.1.0")
    def gather[A](in: Iterable[UIO[A]]): UIO[List[A]] = {
      // $COVERAGE-OFF$
      UIO.parSequence(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[UIO.parSequenceN]] */
    @deprecated("Use parSequenceN", "0.1.0")
    def gatherN[A](parallelism: Int)(in: Iterable[UIO[A]]): UIO[List[A]] = {
      // $COVERAGE-OFF$
      UIO.parSequenceN(parallelism)(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[UIO.parSequenceUnordered]] */
    @deprecated("Use parSequenceUnordered", "0.1.0")
    def gatherUnordered[A](in: Iterable[UIO[A]]): UIO[List[A]] = {
      // $COVERAGE-OFF$
      UIO.parSequenceUnordered(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[UIO.parTraverse]] */
    @deprecated("Use parTraverse", "0.1.0")
    def wander[A, B](
      in: Iterable[A]
    )(f: A => UIO[B]): UIO[List[B]] = {
      // $COVERAGE-OFF$
      UIO.parTraverse(in)(f)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[UIO.parTraverseN]] */
    @deprecated("Use parTraverseN", "0.1.0")
    def wanderN[A, B](parallelism: Int)(in: Iterable[A])(f: A => UIO[B]): UIO[List[B]] = {
      // $COVERAGE-OFF$
      BIO.parTraverseN(parallelism)(in)(f)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[BIO.parTraverseUnordered]] */
    @deprecated("Use parTraverseUnordered", "3.2.0")
    def wanderUnordered[A, B, M[X] <: Iterable[X]](in: M[A])(f: A => UIO[B]): UIO[List[B]] = {
      // $COVERAGE-OFF$
      UIO.parTraverseUnordered(in)(f)
      // $COVERAGE-ON$
    }
  }
}

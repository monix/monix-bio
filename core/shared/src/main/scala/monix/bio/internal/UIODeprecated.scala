package monix.bio.internal

import monix.bio.{BIO, UIO}
import monix.execution.compat.BuildFrom

private[bio] object UIODeprecated {

  /**
    * Extension methods describing deprecated `UIO` operations.
    */
  private[bio] abstract class Companion {

    /** DEPRECATED — renamed to [[UIO.parSequence]]. */
    @deprecated("Use parSequence", "0.1.0")
    def gather[A, M[X] <: Iterable[X]](in: M[UIO[A]])(implicit bf: BuildFrom[M[UIO[A]], A, M[A]]): UIO[M[A]] = {
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
    def wander[A, B, M[X] <: Iterable[X]](in: M[A])(f: A => UIO[B])(implicit bf: BuildFrom[M[A], B, M[B]]): UIO[M[B]] = {
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

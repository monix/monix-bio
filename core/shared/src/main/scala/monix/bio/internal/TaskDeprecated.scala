package monix.bio.internal

import monix.bio.{BIO, Task}
import monix.execution.compat.BuildFrom

private[bio] object TaskDeprecated {

  /**
    * Extension methods describing deprecated `Task` operations.
    */
  private[bio] abstract class Companion {

    /** DEPRECATED — renamed to [[Task.parSequence]]. */
    @deprecated("Use parSequence", "0.1.0")
    def gather[A, M[X] <: Iterable[X]](in: M[Task[A]])(implicit bf: BuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] = {
      // $COVERAGE-OFF$
      Task.parSequence(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[Task.parSequenceN]] */
    @deprecated("Use parSequenceN", "0.1.0")
    def gatherN[A](parallelism: Int)(in: Iterable[Task[A]]): Task[List[A]] = {
      // $COVERAGE-OFF$
      Task.parSequenceN(parallelism)(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[Task.parSequenceUnordered]] */
    @deprecated("Use parSequenceUnordered", "0.1.0")
    def gatherUnordered[A](in: Iterable[Task[A]]): Task[List[A]] = {
      // $COVERAGE-OFF$
      Task.parSequenceUnordered(in)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[Task.parTraverse]] */
    @deprecated("Use parTraverse", "0.1.0")
    def wander[A, B, M[X] <: Iterable[X]](in: M[A])(f: A => Task[B])(implicit bf: BuildFrom[M[A], B, M[B]]): Task[M[B]] = {
      // $COVERAGE-OFF$
      Task.parTraverse(in)(f)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[Task.parTraverseN]] */
    @deprecated("Use parTraverseN", "0.1.0")
    def wanderN[A, B](parallelism: Int)(in: Iterable[A])(f: A => Task[B]): Task[List[B]] = {
      // $COVERAGE-OFF$
      Task.parTraverseN(parallelism)(in)(f)
      // $COVERAGE-ON$
    }

    /** DEPRECATED — renamed to [[BIO.parTraverseUnordered]] */
    @deprecated("Use parTraverseUnordered", "3.2.0")
    def wanderUnordered[A, B, M[X] <: Iterable[X]](in: M[A])(f: A => Task[B]): Task[List[B]] = {
      // $COVERAGE-OFF$
      Task.parTraverseUnordered(in)(f)
      // $COVERAGE-ON$
    }
  }



}

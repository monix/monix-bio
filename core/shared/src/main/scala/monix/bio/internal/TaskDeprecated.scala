package monix.bio
package internal

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
      BIO.parTraverseN(parallelism)(in)(f)
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

  /**
    * Extension methods describing deprecated `UIO` operations.
    */
  private[bio] abstract class `UIO.Companion` {
    
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

  /**
    * Extension methods describing deprecated `BIO` operations.
    */
  private[bio] abstract class `BIO.Companion` {
    /** DEPRECATED — renamed to [[BIO.parSequence]]. */
    @deprecated("Use parSequence", "0.1.0")
    def gather[E, A, M[X] <: Iterable[X]](in: M[BIO[E, A]])(implicit bf: BuildFrom[M[BIO[E, A]], A, M[A]]): BIO[E, M[A]] = {
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
    def wander[E, A, B, M[X] <: Iterable[X]](in: M[A])(f: A => BIO[E, B])(implicit bf: BuildFrom[M[A], B, M[B]]): BIO[E, M[B]] = {
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

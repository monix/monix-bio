package monix.bio.internal

import monix.execution.Callback
import monix.bio.WRYYY
import monix.bio.internal.TaskRunLoop.WrappedException
import monix.execution.atomic.Atomic

private[bio] object TaskRace {
  /**
    * Implementation for `Task.race`.
    */
  def apply[E, A, B](fa: WRYYY[E, A], fb: WRYYY[E, B]): WRYYY[E, Either[A, B]] =
    WRYYY.Async(
      new Register(fa, fb),
      trampolineBefore = true,
      trampolineAfter = true
    )

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is a task that forks on evaluation.
  //
  // N.B. the contract is that the injected callback gets called after
  // a full async boundary!
  private final class Register[E, A, B](fa: WRYYY[E, A], fb: WRYYY[E, B]) extends ForkedRegister[E, Either[A, B]] {

    def apply(context: WRYYY.Context[E], cb: Callback[E, Either[A, B]]): Unit = {
      implicit val sc = context.scheduler
      val conn = context.connection

      val isActive = Atomic(true)
      val connA = TaskConnection[E]()
      val connB = TaskConnection[E]()
      conn.pushConnections(connA, connB)

      val contextA = context.withConnection(connA)
      val contextB = context.withConnection(connB)

      // First task: A
      WRYYY.unsafeStartEnsureAsync(
        fa,
        contextA,
        new Callback[E, A] {
          def onSuccess(valueA: A): Unit =
            if (isActive.getAndSet(false)) {
              connB.cancel.runAsyncAndForget
              conn.pop()
              cb.onSuccess(Left(valueA))
            }

          def onError(ex: E): Unit =
            if (isActive.getAndSet(false)) {
              conn.pop()
              connB.cancel.runAsyncAndForget
              cb.onError(ex)
            } else {
              // TODO: unify reporting of failures like that (failure after success)
              sc.reportFailure(WrappedException(ex))
            }
        }
      )

      // Second task: B
      WRYYY.unsafeStartEnsureAsync(
        fb,
        contextB,
        new Callback[E, B] {
          def onSuccess(valueB: B): Unit =
            if (isActive.getAndSet(false)) {
              connA.cancel.runAsyncAndForget
              conn.pop()
              cb.onSuccess(Right(valueB))
            }

          def onError(ex: E): Unit =
            if (isActive.getAndSet(false)) {
              conn.pop()
              connA.cancel.runAsyncAndForget
              cb.onError(ex)
            } else {
              // TODO: unify reporting of failures like that (failure after success)
              sc.reportFailure(WrappedException(ex))
            }
        }
      )
    }
  }
}
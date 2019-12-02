package monix.bio
package internal

import monix.execution.Callback
import monix.execution.atomic.Atomic

import scala.concurrent.Promise

private[bio] object TaskRacePair {
  // Type aliasing the result only b/c it's a mouthful
  type RaceEither[E, A, B] = Either[(A, Fiber[E, B]), (Fiber[E, A], B)]

  /**
    * Implementation for `Task.racePair`.
    */
  def apply[E, A, B](fa: WRYYY[E, A], fb: WRYYY[E, B]): WRYYY[E, RaceEither[E, A, B]] =
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
  private final class Register[E, A, B](fa: WRYYY[E, A], fb: WRYYY[E, B]) extends ForkedRegister[E, RaceEither[E, A, B]] {

    def apply(context: WRYYY.Context[E], cb: Callback[E, RaceEither[E, A, B]]): Unit = {
      implicit val s = context.scheduler
      val conn = context.connection

      val pa = Promise[Either[E, A]]()
      val pb = Promise[Either[E, B]]()

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
              val fiberB = Fiber.fromPromise(pb, connB)
              conn.pop()
              cb.onSuccess(Left((valueA, fiberB)))
            } else {
              pa.success(Right(valueA))
            }

          def onError(ex: E): Unit =
            if (isActive.getAndSet(false)) {
              conn.pop()
              connB.cancel.runAsyncAndForget
              cb.onError(ex)
            } else {
              pa.success(Left(ex))
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
              val fiberA = Fiber.fromPromise(pa, connA)
              conn.pop()
              cb.onSuccess(Right((fiberA, valueB)))
            } else {
              pb.success(Right(valueB))
            }

          def onError(ex: E): Unit =
            if (isActive.getAndSet(false)) {
              conn.pop()
              connA.cancel.runAsyncAndForget
              cb.onError(ex)
            } else {
              // TODO: should it be trySuccess?
              pb.success(Left(ex))
            }
        }
      )
    }
  }
}

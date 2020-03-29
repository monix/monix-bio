package monix.bio.internal

import monix.bio.{BIO, BiCallback, UIO}

import scala.util.control.NonFatal

private[bio] object UIOEvalAsync {

  /**
    * Implementation for `UIO.evalAsync`.
    */
  def apply[A](a: () => A): UIO[A] =
    BIO.Async[Nothing, A](
      new EvalAsyncRegister[A](a).apply,
      trampolineAfter = false,
      trampolineBefore = false,
      restoreLocals = false
    )

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is a task that forks on evaluation
  private final class EvalAsyncRegister[A](a: () => A) extends ForkedRegister[Nothing, A] {

    def apply(ctx: BIO.Context[Nothing], cb: BiCallback[Nothing, A]): Unit =
      ctx.scheduler.executeAsync(() => {
        ctx.frameRef.reset()
        var streamError = true
        try {
          val result = a()
          streamError = false
          cb.onSuccess(result)
        } catch {
          case e if streamError && NonFatal(e) =>
            cb.onTermination(e)
        }
      })
  }
}

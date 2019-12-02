package monix.bio.internal

import monix.bio.WRYYY
import monix.bio.WRYYY.{Async, Context}
import monix.execution.{Callback, Scheduler}

private[bio] object TaskExecuteOn {
  /**
    * Implementation for `Task.executeOn`.
    */
  def apply[E, A](source: WRYYY[E, A], s: Scheduler, forceAsync: Boolean): WRYYY[E, A] = {
    val withTrampoline = !forceAsync
    val start =
      if (forceAsync) new AsyncRegister(source, s)
      else new TrampolinedStart(source, s)

    Async(
      start,
      trampolineBefore = withTrampoline,
      trampolineAfter = withTrampoline,
      restoreLocals = false
    )
  }

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is task that forks on evaluation
  private final class AsyncRegister[E, A](source: WRYYY[E, A], s: Scheduler) extends ForkedRegister[E, A] {
    def apply(ctx: Context[E], cb: Callback[E, A]): Unit = {
      val oldS = ctx.scheduler
      val ctx2 = ctx.withScheduler(s)

      // TODO: figure out what to do with rejected execution exception
//      try {
        WRYYY.unsafeStartAsync(
          source,
          ctx2,
          new Callback[E, A] with Runnable {
            private[this] var value: A = _
            private[this] var error: E = _

            def onSuccess(value: A): Unit = {
              this.value = value
              oldS.execute(this)
            }

            def onError(ex: E): Unit = {
              this.error = ex
              oldS.execute(this)
            }

            def run() = {
              // error ne null
              if (error != null) cb.onError(error)
              else cb.onSuccess(value)
            }
          }
        )
//      } catch {
//        case e: RejectedExecutionException =>
//          Callback.signalErrorTrampolined(cb, e)
//      }
    }
  }

  private final class TrampolinedStart[E, A](source: WRYYY[E, A], s: Scheduler)
    extends ((Context[E], Callback[E, A]) => Unit) {

    def apply(ctx: Context[E], cb: Callback[E, A]): Unit = {
      val ctx2 = ctx.withScheduler(s)
//      try {
        WRYYY.unsafeStartNow(source, ctx2, cb)
//      } catch {
//        case e: RejectedExecutionException =>
//          Callback.signalErrorTrampolined(cb, e)
//      }
    }
  }
}

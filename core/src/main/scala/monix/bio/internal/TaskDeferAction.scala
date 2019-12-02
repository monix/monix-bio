package monix.bio
package internal

import monix.bio.WRYYY.Context
import monix.execution.{Callback, Scheduler}

private[bio] object TaskDeferAction {
  /** Implementation for `Task.deferAction`. */
  def apply[E, A](f: Scheduler => WRYYY[E, A]): WRYYY[E, A] = {
    val start = (context: Context[E], callback: Callback[E, A]) => {
      implicit val ec = context.scheduler
//      var streamErrors = true

      // TODO: what if f(ec) fails?
//      try {
        val fa = f(ec)
//        streamErrors = false
        WRYYY.unsafeStartNow(fa, context, callback)
//      } catch {
//        case ex if NonFatal(ex) =>
//          if (streamErrors)
//            callback.onError(ex)
//          else {
//            // $COVERAGE-OFF$
//            ec.reportFailure(ex)
//            // $COVERAGE-ON$
//          }
//      }
    }

    WRYYY.Async(
      start,
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = false
    )
  }
}

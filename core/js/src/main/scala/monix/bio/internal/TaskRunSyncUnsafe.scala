package monix.bio.internal

import monix.bio.WRYYY
import monix.execution.Scheduler

import scala.concurrent.duration.Duration

private[bio] object TaskRunSyncUnsafe {
  /** Implementation of `Task.runSyncUnsafe`, meant to throw an
    * "unsupported exception", since JavaScript cannot support it.
    */
  def apply[E, A](source: WRYYY[E, A], timeout: Duration, scheduler: Scheduler, opts: WRYYY.Options): A = {
    // $COVERAGE-OFF$
    throw new UnsupportedOperationException("runSyncUnsafe isn't supported on top of JavaScript")
    // $COVERAGE-ON$
  }
}


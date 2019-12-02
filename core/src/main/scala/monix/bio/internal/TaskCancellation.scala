package monix.bio
package internal

import cats.effect.CancelToken
import monix.bio.WRYYY.{Async, Context}
import monix.bio.internal.TaskRunLoop.WrappedException
import monix.execution.{Callback, Scheduler}
import monix.execution.atomic.{Atomic, AtomicBoolean}
import monix.execution.schedulers.TrampolinedRunnable

private[bio] object TaskCancellation {
  /**
    * Implementation for `Task.uncancelable`.
    */
  def uncancelable[E, A](fa: WRYYY[E, A]): WRYYY[E, A] =
    WRYYY.ContextSwitch[E, A](fa, withConnectionUncancelable, restoreConnection)

  /**
    * Implementation for `Task.onCancelRaiseError`.
    */
  def raiseError[E, A](fa: WRYYY[E, A], e: E): WRYYY[E, A] = {
    val start = (ctx: Context[E], cb: Callback[E, A]) => {
      implicit val sc = ctx.scheduler
      val canCall = Atomic(true)
      // We need a special connection because the main one will be reset on
      // cancellation and this can interfere with the cancellation of `fa`
      val connChild = TaskConnection[E]()
      val conn = ctx.connection
      // Registering a special cancelable that will trigger error on cancel.
      // Note the pair `conn.pop` happens in `RaiseCallback`.
      conn.push(raiseCancelable(canCall, conn, connChild, cb, e))
      // Registering a callback that races against the cancelable we
      // registered above
      val cb2 = new RaiseCallback[E, A](canCall, conn, cb)
      WRYYY.unsafeStartNow(fa, ctx, cb2)
    }
    Async(start, trampolineBefore = true, trampolineAfter = false, restoreLocals = false)
  }

  private final class RaiseCallback[E, A](
                                        waitsForResult: AtomicBoolean,
                                        conn: TaskConnection[E],
                                        cb: Callback[E, A]
                                      )(implicit s: Scheduler)
    extends Callback[E, A] with TrampolinedRunnable {

    private[this] var value: A = _
    private[this] var error: E = _

    def run(): Unit = {
      val e = error
      if (e != null) cb.onError(e)
      else cb.onSuccess(value)
    }

    def onSuccess(value: A): Unit =
      if (waitsForResult.getAndSet(false)) {
        conn.pop()
        this.value = value
        s.execute(this)
      }

    def onError(e: E): Unit =
      if (waitsForResult.getAndSet(false)) {
        conn.pop()
        this.error = e
        s.execute(this)
      } else {
        // TODO: unify reporting of failures like that (failure after success)
        s.reportFailure(WrappedException(e))
      }
  }

  private def raiseCancelable[E, A](
                                  waitsForResult: AtomicBoolean,
                                  conn: TaskConnection[E],
                                  conn2: TaskConnection[E],
                                  cb: Callback[E, A],
                                  e: E): CancelToken[WRYYY[E, ?]] = {

    WRYYY.suspend {
      if (waitsForResult.getAndSet(false))
        conn2.cancel.map { _ =>
          conn.tryReactivate()
          cb.onError(e)
        } else
        WRYYY.unit
    }
  }

  // TODO: could it be a val again?
  private[this] def withConnectionUncancelable[E]: Context[E] => Context[E] =
    ct => {
      ct.withConnection(TaskConnection.uncancelable)
        .withOptions(ct.options.disableAutoCancelableRunLoops)
    }

  private[this] def restoreConnection[E]: (Any, E, Context[E], Context[E]) => Context[E] =
    (_, _, old, ct) => {
      ct.withConnection(old.connection)
        .withOptions(old.options)
    }
}
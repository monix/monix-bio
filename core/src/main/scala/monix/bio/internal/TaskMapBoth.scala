package monix.bio.internal

import monix.bio.WRYYY
import monix.bio.WRYYY.{Async, Context}
import monix.bio.internal.TaskRunLoop.WrappedException
import monix.execution.Ack.Stop
import monix.execution.{Callback, Scheduler}
import monix.execution.atomic.PaddingStrategy.LeftRight128
import monix.execution.atomic.{Atomic, AtomicAny}

import scala.annotation.tailrec
import scala.util.control.NonFatal

private[bio] object TaskMapBoth {
  /**
    * Implementation for `Task.mapBoth`.
    */
  def apply[E, A1, A2, R](fa1: WRYYY[E, A1], fa2: WRYYY[E, A2])(f: (A1, A2) => R): WRYYY[E, R] = {
    Async(new Register(fa1, fa2, f), trampolineBefore = true, trampolineAfter = true, restoreLocals = true)
  }

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is a task that forks on evaluation.
  //
  // N.B. the contract is that the injected callback gets called after
  // a full async boundary!
  private final class Register[E, A1, A2, R](fa1: WRYYY[E, A1], fa2: WRYYY[E, A2], f: (A1, A2) => R) extends ForkedRegister[E, R] {

    /* For signaling the values after the successful completion of both tasks. */
    def sendSignal(mainConn: TaskConnection[E], cb: Callback[E, R], a1: A1, a2: A2)(
      implicit s: Scheduler): Unit = {

      // TODO: should be fatal
      var streamErrors = true
      try {
        val r = f(a1, a2)
        streamErrors = false
        mainConn.pop()
        cb.onSuccess(r)
      } catch {
        case NonFatal(ex) if streamErrors =>
//          // Both tasks completed by this point, so we don't need
//          // to worry about the `state` being a `Stop`
          mainConn.pop()
//          cb.onError(ex)
      }
    }

    /* For signaling an error. */
    @tailrec def sendError(
                            mainConn: TaskConnection[E],
                            state: AtomicAny[AnyRef],
                            cb: Callback[E, R],
                            ex: E)(implicit s: Scheduler): Unit = {

      // Guarding the contract of the callback, as we cannot send an error
      // if an error has already happened because of the other task
      state.get match {
        case Stop =>
          // TODO: report exception
          // We've got nowhere to send the error, so report it
          s.reportFailure(WrappedException(ex))
        case other =>
          if (!state.compareAndSet(other, Stop))
            sendError(mainConn, state, cb, ex)(s) // retry
          else {
            mainConn.pop().runAsyncAndForget
            cb.onError(ex)
          }
      }
    }

    def apply(context: Context[E], cb: Callback[E, R]): Unit = {
      implicit val s = context.scheduler
      val mainConn = context.connection
      // for synchronizing the results
      val state = Atomic.withPadding(null: AnyRef, LeftRight128)

      val task1 = TaskConnection[E]()
      val task2 = TaskConnection[E]()
      val context1 = context.withConnection(task1)
      val context2 = context.withConnection(task2)
      mainConn.pushConnections(task1, task2)

      // Light asynchronous boundary; with most scheduler implementations
      // it will not fork a new (logical) thread!
      WRYYY.unsafeStartEnsureAsync(
        fa1,
        context1,
        new Callback[E, A1] {
          @tailrec def onSuccess(a1: A1): Unit =
            state.get match {
              case null => // null means this is the first task to complete
                if (!state.compareAndSet(null, Left(a1))) onSuccess(a1)
              case Right(a2) => // the other task completed, so we can send
                sendSignal(mainConn, cb, a1, a2.asInstanceOf[A2])(s)
              case Stop => // the other task triggered an error
                () // do nothing
              case s @ Left(_) =>
                // This task has triggered multiple onSuccess calls
                // violating the protocol. Should never happen.
                // TODO: handle multiple cb called
                throw new IllegalStateException(s.toString)
              //                onError(new IllegalStateException(s.toString))
            }

          def onError(ex: E): Unit =
            sendError(mainConn, state, cb, ex)(s)
        }
      )

      // Start first task with a "hard" async boundary to ensure parallel evaluation
      WRYYY.unsafeStartEnsureAsync(
        fa2,
        context2,
        new Callback[E, A2] {
          @tailrec def onSuccess(a2: A2): Unit =
            state.get match {
              case null => // null means this is the first task to complete
                if (!state.compareAndSet(null, Right(a2))) onSuccess(a2)
              case Left(a1) => // the other task completed, so we can send
                sendSignal(mainConn, cb, a1.asInstanceOf[A1], a2)(s)
              case Stop => // the other task triggered an error
                () // do nothing
              case s @ Right(_) =>
                // This task has triggered multiple onSuccess calls
                // violating the protocol. Should never happen.
                throw new IllegalStateException(s.toString)
//                onError(new IllegalStateException(s.toString))
            }

          def onError(ex: E): Unit =
            sendError(mainConn, state, cb, ex)(s)
        }
      )
    }
  }
}
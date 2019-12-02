package monix.bio
package internal

import cats.effect.{CancelToken, IO, SyncIO}
import monix.execution.Callback
import monix.execution.Scheduler
import monix.execution.internal.AttemptCallback.noop

import scala.util.control.NonFatal

/** INTERNAL API
  *
  * `Task` integration utilities for the `cats.effect.ConcurrentEffect`
  * instance, provided in `monix.eval.instances`.
  */
private[bio] object TaskEffect {
  /**
    * `cats.effect.Effect#runAsync`
    */
  def runAsync[A](fa: Task[A])(cb: Either[Throwable, A] => IO[Unit])(
    implicit s: Scheduler,
    opts: WRYYY.Options
  ): SyncIO[Unit] = SyncIO {
    execute(fa, cb); ()
  }

  /**
    * `cats.effect.ConcurrentEffect#runCancelable`
    */
  def runCancelable[A](fa: Task[A])(cb: Either[Throwable, A] => IO[Unit])(
    implicit s: Scheduler,
    opts: WRYYY.Options
  ): SyncIO[CancelToken[Task]] = SyncIO {
    execute(fa, cb)
  }

  private def execute[A](fa: Task[A], cb: Either[Throwable, A] => IO[Unit])(
    implicit s: Scheduler,
    opts: WRYYY.Options) = {

    fa.runAsyncOptF(new Callback[Throwable, A] {
      private def signal(value: Either[Throwable, A]): Unit =
        try cb(value).unsafeRunAsync(noop)
        catch { case NonFatal(e) => s.reportFailure(e) }

      def onSuccess(value: A): Unit =
        signal(Right(value))
      def onError(e: Throwable): Unit =
        signal(Left(e))
    })
  }
}

/*
 * Copyright (c) 2019-2020 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.bio.internal

import cats.effect.CancelToken
import monix.bio.{BIO, BiCallback, UIO}
import monix.bio.BIO.{Async, Context}
import monix.execution.exceptions.UncaughtErrorException
import monix.execution.Scheduler

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

private[bio] object TaskParSequence {

  /**
    * Implementation for `Task.parSequence`
    */
  def apply[E, A, M[X] <: Iterable[X]](
    in: Iterable[BIO[E, A]],
    makeBuilder: () => mutable.Builder[A, M[A]]
  ): BIO[E, M[A]] = {
    Async(
      new Register(in, makeBuilder),
      trampolineBefore = true,
      trampolineAfter = true,
      restoreLocals = true
    )
  }

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is a task that forks on evaluation.
  //
  // N.B. the contract is that the injected callback gets called after
  // a full async boundary!
  private final class Register[E, A, M[X] <: Iterable[X]](
    in: Iterable[BIO[E, A]],
    makeBuilder: () => mutable.Builder[A, M[A]]
  ) extends ForkedRegister[E, M[A]] {

    def apply(context: Context[E], finalCallback: BiCallback[E, M[A]]): Unit = {
      // We need a monitor to synchronize on, per evaluation!
      val lock = new AnyRef
      val mainConn = context.connection

      var tasks: Array[BIO[E, A]] = null
      var results: Array[AnyRef] = null
      var tasksCount = 0
      var completed = 0

      // If this variable is false, then a task ended in error.
      // MUST BE synchronized by `lock`!
      var isActive = true

      // MUST BE synchronized by `lock`!
      // MUST NOT BE called if isActive == false!
      def maybeSignalFinal(mainConn: TaskConnection[E], finalCallback: BiCallback[E, M[A]]): Unit = {

        completed += 1
        if (completed >= tasksCount) {
          isActive = false
          mainConn.pop()

          val builder = makeBuilder()
          var idx = 0
          while (idx < results.length) {
            builder += results(idx).asInstanceOf[A]
            idx += 1
          }

          tasks = null // GC relief
          results = null // GC relief
          finalCallback.onSuccess(builder.result())
        }
      }

      // MUST BE synchronized by `lock`!
      def reportError(mainConn: TaskConnection[E], ex: E)(implicit s: Scheduler): Unit = {

        if (isActive) {
          isActive = false
          mainConn
            .pop()
            .map { _ =>
              tasks = null // GC relief
              results = null // GC relief
              finalCallback.onError(ex)
            }
            .runAsyncAndForget

        } else {
          s.reportFailure(UncaughtErrorException.wrap(ex))
        }
      }

      // MUST BE synchronized by `lock`!
      def reportTermination(mainConn: TaskConnection[E], ex: Throwable)(implicit s: Scheduler): Unit = {

        if (isActive) {
          isActive = false
          // This should cancel our CompositeCancelable
          mainConn.pop().runAsyncAndForget
          tasks = null // GC relief
          results = null // GC relief
          finalCallback.onTermination(ex)
        } else {
          s.reportFailure(ex)
        }
      }

      try {
        implicit val s = context.scheduler
        tasks = in.toArray
        tasksCount = tasks.length

        if (tasksCount == 0) {
          // With no tasks available, we need to return an empty sequence;
          // Needs to ensure full async delivery due to implementing ForkedStart!
          context.scheduler.executeAsync(() => finalCallback.onSuccess(makeBuilder().result()))
        } else if (tasksCount == 1) {
          // If it's a single task, then execute it directly
          val source = tasks(0).map(r => (makeBuilder() += r).result())
          // Needs to ensure full async delivery due to implementing ForkedStart!
          BIO.unsafeStartEnsureAsync(source, context, finalCallback)
        } else {
          results = new Array[AnyRef](tasksCount)

          // Collecting all cancelables in a buffer, because adding
          // cancelables one by one in our `CompositeCancelable` is
          // expensive, so we do it at the end
          val allCancelables = ListBuffer.empty[CancelToken[UIO]]

          // We need a composite because we are potentially starting tasks
          // in parallel and thus we need to cancel everything
          val composite = TaskConnectionComposite[E]()
          mainConn.push(composite.cancel)

          var idx = 0
          while (idx < tasksCount && isActive) {
            val currentTask = idx
            val stacked = TaskConnection[E]()
            val childContext = context.withConnection(stacked)
            allCancelables += stacked.cancel

            // Light asynchronous boundary
            BIO.unsafeStartEnsureAsync(
              tasks(idx),
              childContext,
              new BiCallback[E, A] {
                def onSuccess(value: A): Unit =
                  lock.synchronized {
                    if (isActive) {
                      results(currentTask) = value.asInstanceOf[AnyRef]
                      maybeSignalFinal(mainConn, finalCallback)
                    }
                  }

                def onError(ex: E): Unit =
                  lock.synchronized(reportError(mainConn, ex))

                override def onTermination(e: Throwable): Unit =
                  lock.synchronized(reportTermination(mainConn, e))
              }
            )

            idx += 1
          }

          // Note that if an error happened, this should cancel all
          // active tasks.
          composite.addAll(allCancelables)
          ()
        }
      } catch {
        case ex if NonFatal(ex) =>
          // We are still under the lock.synchronize block
          // so this call is safe
          reportTermination(context.connection, ex)(context.scheduler)
      }
    }
  }
}

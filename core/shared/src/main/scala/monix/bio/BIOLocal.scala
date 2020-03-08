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

package monix.bio

import monix.execution.exceptions.APIContractViolationException
import monix.execution.misc.Local

/** A `BIOLocal` is like a
  * [[monix.execution.misc.ThreadLocal ThreadLocal]]
  * that is pure and with a flexible scope, being processed in the
  * context of the [[Task]] data type.
  *
  * This data type wraps [[monix.execution.misc.Local]].
  *
  * Just like a `ThreadLocal`, usage of a `BIOLocal` is safe,
  * the state of all current locals being transported over
  * async boundaries (aka when threads get forked) by the `Task`
  * run-loop implementation, but only when the `Task` reference
  * gets executed with [[BIO.Options.localContextPropagation]]
  * set to `true`.
  *
  * One way to achieve this is with [[BIO.executeWithOptions]],
  * a single call is sufficient just before `runAsync`:
  *
  * {{{
  *   import monix.execution.Scheduler.Implicits.global
  *
  *   val t = Task(42)
  *   t.executeWithOptions(_.enableLocalContextPropagation)
  *     // triggers the actual execution
  *     .runToFuture
  * }}}
  *
  * Another possibility is to use [[BIO.runToFutureOpt]] or
  * [[BIO.runToFutureOpt]] instead of `runAsync` and specify the set of
  * options implicitly:
  *
  * {{{
  *   {
  *     implicit val options = BIO.defaultOptions.enableLocalContextPropagation
  *
  *     // Options passed implicitly
  *     val f = t.runToFutureOpt
  *   }
  * }}}
  *
  * Full example:
  *
  * {{{
  *   import monix.bio.{UIO, BIOLocal}
  *
  *   val task: UIO[Unit] =
  *     for {
  *       local <- BIOLocal(0)
  *       value1 <- local.read // value1 == 0
  *       _ <- local.write(100)
  *       value2 <- local.read // value2 == 100
  *       value3 <- local.bind(200)(local.read.map(_ * 2)) // value3 == 200 * 2
  *       value4 <- local.read // value4 == 100
  *       _ <- local.clear
  *       value5 <- local.read // value5 == 0
  *     } yield {
  *       // Should print 0, 100, 400, 100, 0
  *       println("value1: " + value1)
  *       println("value2: " + value2)
  *       println("value3: " + value3)
  *       println("value4: " + value4)
  *       println("value5: " + value5)
  *     }
  *
  *   // For transporting locals over async boundaries defined by
  *   // Task, any Scheduler will do, however for transporting locals
  *   // over async boundaries managed by Future and others, you need
  *   // a `TracingScheduler` here:
  *   import monix.execution.Scheduler.Implicits.global
  *
  *   // Needs enabling the "localContextPropagation" option
  *   // just before execution
  *   implicit val opts = BIO.defaultOptions.enableLocalContextPropagation
  *
  *   // Triggering actual execution
  *   val result = task.runToFutureOpt
  * }}}
  */
final class BIOLocal[A] private (ref: Local[A]) {
  import BIOLocal.checkPropagation

  /** Returns [[monix.execution.misc.Local]] instance used in this [[BIOLocal]].
    *
    * Note that `BIOLocal.bind` will restore the original local value
    * on the thread where the `Task's` run-loop ends up so it might lead
    * to leaving local modified in other thread.
    */
  def local: UIO[Local[A]] =
    checkPropagation(UIO(ref))

  /** Returns the current local value (in the `BIO` context). */
  def read: UIO[A] =
    checkPropagation(UIO(ref.get))

  /** Updates the local value. */
  def write(value: A): UIO[Unit] =
    checkPropagation(UIO(ref.update(value)))

  /** Clears the local value, making it return its `default`. */
  def clear: UIO[Unit] =
    checkPropagation(UIO(ref.clear()))

  /** Binds the local var to a `value` for the duration of the given
    * `task` execution.
    *
    * {{{
    *   // Should yield 200 on execution, regardless of what value
    *   // we have in `local` at the time of evaluation
    *   val task: UIO[Int] =
    *     for {
    *       local <- BIOLocal(0)
    *       value <- local.bind(100)(local.read.map(_ * 2))
    *     } yield value
    * }}}
    *
    * @see [[bindL]] for the version with a lazy `value`.
    *
    * @param value is the value to be set in this local var when the
    *        task evaluation is triggered (aka lazily)
    *
    * @param task is the [[BIO]] to wrap, having the given `value`
    *        as the response to [[read]] queries and transported
    *        over asynchronous boundaries — on finish the local gets
    *        reset to the previous value
    */
  def bind[E, R](value: A)(task: BIO[E, R]): BIO[E, R] =
    bindL(UIO.now(value))(task)

  /** Binds the local var to a `value` for the duration of the given
    * `task` execution, the `value` itself being lazily evaluated
    * in the [[BIO]] context.
    *
    * {{{
    *   // Should yield 200 on execution, regardless of what value
    *   // we have in `local` at the time of evaluation
    *   val task: UIO[Int] =
    *     for {
    *       local <- BIOLocal(0)
    *       value <- local.bindL(UIO.eval(100))(local.read.map(_ * 2))
    *     } yield value
    * }}}
    *
    * @see [[bind]] for the version with a strict `value`.
    *
    * @param value is the value to be set in this local var when the
    *        task evaluation is triggered (aka lazily)
    *
    * @param task is the [[BIO]] to wrap, having the given `value`
    *        as the response to [[read]] queries and transported
    *        over asynchronous boundaries — on finish the local gets
    *        reset to the previous value
    */
  def bindL[E, R](value: BIO[E, A])(task: BIO[E, R]): BIO[E, R] =
    local.flatMap { r =>
      val saved = Local.getContext()
      value.bracket { v =>
        Local.setContext(saved.bind(r.key, Some(v)))
        task
      }(_ => restore(saved))
    }

  /** Clears the local var to the default for the duration of the
    * given `task` execution.
    *
    * {{{
    *   // Should yield 0 on execution, regardless of what value
    *   // we have in `local` at the time of evaluation
    *   val task: UIO[Int] =
    *     for {
    *       local <- BIOLocal(0)
    *       value <- local.bindClear(local.read.map(_ * 2))
    *     } yield value
    * }}}
    *
    * @param task is the [[BIO]] to wrap, having the local cleared,
    *        returning the default as the response to [[read]] queries
    *        and transported over asynchronous boundaries — on finish
    *        the local gets reset to the previous value
    */
  def bindClear[E, R](task: BIO[E, R]): BIO[E, R] =
    local.flatMap { r =>
      val saved = Local.getContext()

      UIO.unit.bracket { _ =>
        Local.setContext(saved.bind(r.key, None))
        task
      }(_ => restore(saved))
    }

  private def restore(value: Local.Context): UIO[Unit] =
    UIO(Local.setContext(value))
}

/**
  * Builders for [[BIOLocal]]
  *
  * @define refTransparent [[BIO]] returned by this operation
  *                        produces a new [[BIOLocal]] each time it is evaluated.
  *                        To share a state between multiple consumers, pass
  *                        [[BIOLocal]] values around as plain parameters,
  *                        instead of keeping shared state.
  *
  *                        Another possibility is to use [[BIO.memoize]], but note
  *                        that this breaks referential transparency and can be
  *                        problematic for example in terms of enabled [[BIO.Options]],
  *                        which don't survive the memoization process.
  */
object BIOLocal {
  /** Builds a [[BIOLocal]] reference with the given default.
    *
    * $refTransparent
    *
    * @param default is a value that gets returned in case the
    *                local was never updated (with [[BIOLocal.write write]])
    *                or in case it was cleared (with [[BIOLocal.clear]])
    */
  def apply[A](default: A): UIO[BIOLocal[A]] =
    checkPropagation(UIO.eval(new BIOLocal(Local(default))))

  /** Wraps a [[monix.execution.misc.Local Local]] reference
    * (given in the `Task` context) in a [[BIOLocal]] value.
    *
    * $refTransparent
    */
  def wrap[E, A](local: BIO[E, Local[A]]): BIO[E, BIOLocal[A]] =
    checkPropagation(local.map(new BIOLocal(_)))

  /** Wraps a provided `task`, such that any changes to any BIOLocal variable
    * during its execution will not be observable outside of that Task.
    */
  def isolate[E, A](task: BIO[E, A]): BIO[E, A] = checkPropagation {
    UIO {
      val current = Local.getContext()
      Local.setContext(current.isolate())
      current
    }.bracket(_ => task)(backup => UIO(Local.setContext(backup)))
  }

  private def checkPropagation[E, A](fa: BIO[E, A]): BIO[E, A] =
    BIO.ContextSwitch(fa, checkPropagationRef.asInstanceOf[BIO.Context[E] => BIO.Context[E]], null)

  private[this] val checkPropagationRef: BIO.Context[Any] => BIO.Context[Any] =
    ctx => {
      if (!ctx.options.localContextPropagation) {
        throw new APIContractViolationException(
          "Support for BIOLocal usage isn't active! " +
            "See documentation at: https://monix.io/api/current/monix/eval/TaskLocal.html"
        )
      }
      ctx
    }
}

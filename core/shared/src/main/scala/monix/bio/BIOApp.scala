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

import cats.effect.{ConcurrentEffect, ExitCode, IOApp}
import monix.catnap.SchedulerEffect
import monix.bio.instances.CatsConcurrentEffectForTask
import monix.execution.Scheduler
import cats.effect.Temporal

/** Safe `App` type that executes a [[IO]].  Shutdown occurs after
  * the `IO` completes, as follows:
  *
  * - If completed with `ExitCode.Success`, the main method exits and
  *   shutdown is handled by the platform.
  *
  * - If completed with any other `ExitCode`, `sys.exit` is called
  *   with the specified code.
  *
  * - If the `IO` raises an error, the stack trace is printed to
  *   standard error and `sys.exit(1)` is called.
  *
  * When a shutdown is requested via a signal, the `IO` is canceled and
  * we wait for the `IO` to release any resources.  The process exits
  * with the numeric value of the signal plus 128.
  *
  * {{{
  *   import cats.effect._
  *   import cats.implicits._
  *   import monix.bio._
  *
  *   object MyApp extends BIOApp {
  *     def run(args: List[String]): UIO[ExitCode] =
  *       args.headOption match {
  *         case Some(name) =>
  *           UIO(println(s"Hello, \\${name}.")).as(ExitCode.Success)
  *         case None =>
  *           UIO(System.err.println("Usage: MyApp name")).as(ExitCode(2))
  *       }
  *   }
  * }}}
  *
  * N.B. this is homologous with
  * [[https://typelevel.org/cats-effect/datatypes/ioapp.html cats.effect.IOApp]],
  * but meant for usage with [[IO]].
  *
  * Works on top of JavaScript as well ;-)
  */
trait BIOApp {
  // To implement ...
  def run(args: List[String]): UIO[ExitCode]

  /** Scheduler for executing the [[Task]] action.
    * Defaults to `global`, but can be overridden.
    */
  protected def scheduler: Scheduler = Scheduler.global

  /** [[monix.bio.IO.Options Options]] for executing the
    * [[Task]] action. The default value is defined in
    * [[monix.bio.IO.defaultOptions defaultOptions]],
    * but can be overridden.
    */
  protected def options: IO.Options = IO.defaultOptions.withSchedulerFeatures(scheduler)

  /** Provides the
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent-effect.html cats.effect.ConcurrentEffect]]
    * instance of this runtime environment.
    */
  protected implicit lazy val catsEffect: ConcurrentEffect[Task] =
    new CatsConcurrentEffectForTask()(scheduler, options)

  final def main(args: Array[String]): Unit = {
    val self = this
    val app = new IOApp {
      override implicit lazy val contextShift: ContextShift[cats.effect.IO] =
        SchedulerEffect.contextShift[cats.effect.IO](scheduler)(cats.effect.IO.ioEffect)
      override implicit lazy val timer: Temporal[cats.effect.IO] =
        SchedulerEffect.timerLiftIO[cats.effect.IO](scheduler)(cats.effect.IO.ioEffect)
      def run(args: List[String]): cats.effect.IO[ExitCode] =
        self.run(args).to[cats.effect.IO]
    }
    app.main(args)
  }
}

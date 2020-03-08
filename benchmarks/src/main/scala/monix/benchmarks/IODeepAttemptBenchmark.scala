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

package monix.benchmarks

import java.util.concurrent.TimeUnit

import monix.execution.exceptions.UncaughtErrorException
import org.openjdk.jmh.annotations._

/** To run the benchmark from within SBT:
  *
  *     benchmarks/jmh:run .*.IODeepAttemptBenchmark
  *     The above test will take default values as "10 iterations", "10 warm-up iterations",
  *     "2 forks", "1 thread".
  *
  *     Or to specify custom values use below format:
  *
  *     benchmarks/jmh:run -i 20 -wi 20 -f 4 -t 2 *.IODeepAttemptBenchmark
  *
  * Please note that benchmarks should be usually executed at least in
  * 10 iterations (as a rule of thumb), but more is better.
  */
@Measurement(iterations = 15, time = 5, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@Threads(1)
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class IODeepAttemptBenchmark {
  case class TypedError(message: String)

  @Param(Array("1000"))
  var depth: Int = _

  def halfway = depth / 2

  @Benchmark
  def futureDeepAttempt(): BigInt = {
    import scala.concurrent.{Await, Future}
    import scala.concurrent.duration.Duration.Inf

    def descend(n: Int): Future[BigInt] =
      if (n == depth) Future.failed(new Error("Oh noes!"))
      else if (n == halfway) descend(n + 1).recover { case _ => 50 }
      else descend(n + 1).map(_ + n)

    Await.result(descend(0), Inf)
  }

  @Benchmark
  def monixDeepAttempt(): BigInt = {
    import monix.eval.Task

    def descend(n: Int): Task[BigInt] =
      if (n == depth) Task.raiseError(new Error("Oh noes!"))
      else if (n == halfway) descend(n + 1).redeem(_ => 50, identity)
      else descend(n + 1).map(_ + n)

    descend(0).runSyncUnsafe()
  }

  @Benchmark
  def monixBioDeepAttemptTyped(): BigInt = {
    import monix.bio.BIO

    def descend(n: Int): BIO[TypedError, BigInt] =
      if (n == depth) BIO.raiseError(TypedError("Oh noes!"))
      else if (n == halfway) descend(n + 1).redeem(_ => 50, identity)
      else descend(n + 1).map(_ + n)

    descend(0).mapError(UncaughtErrorException.wrap).runSyncUnsafe()
  }

  @Benchmark
  def monixBioDeepAttempt(): BigInt = {
    import monix.bio.Task

    def descend(n: Int): Task[BigInt] =
      if (n == depth) Task.raiseError(new Error("Oh noes!"))
      else if (n == halfway) descend(n + 1).redeem(_ => 50, identity)
      else descend(n + 1).map(_ + n)

    descend(0).runSyncUnsafe()
  }

  @Benchmark
  def zioDeepAttemptTyped(): BigInt = {
    import zio.IO

    def descend(n: Int): IO[TypedError, BigInt] =
      if (n == depth) IO.fail(TypedError("Oh noes!"))
      else if (n == halfway) descend(n + 1).fold[BigInt](_ => 50, identity)
      else descend(n + 1).map(_ + n)

    zioUntracedRuntime.unsafeRun(descend(0))
  }

  @Benchmark
  def zioDeepAttempt(): BigInt = {
    import zio.IO

    def descend(n: Int): IO[Error, BigInt] =
      if (n == depth) IO.fail(new Error("Oh noes!"))
      else if (n == halfway) descend(n + 1).fold[BigInt](_ => 50, identity)
      else descend(n + 1).map(_ + n)

    zioUntracedRuntime.unsafeRun(descend(0))
  }

  @Benchmark
  def catsDeepAttempt(): BigInt = {
    import cats.effect._

    def descend(n: Int): IO[BigInt] =
      if (n == depth) IO.raiseError(new Error("Oh noes!"))
      else if (n == halfway) descend(n + 1).redeem(_ => 50, identity)
      else descend(n + 1).map(_ + n)

    descend(0).unsafeRunSync()
  }
}

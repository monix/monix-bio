/*
 * Copyright (c) 2019-2019 by The Monix Project Developers.
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

import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.util.Success

/** To run the benchmark from within SBT:
  *
  *     benchmarks/jmh:run .*.IOShallowAttemptBenchmark
  *     The above test will take default values as "10 iterations", "10 warm-up iterations",
  *     "2 forks", "1 thread".
  *
  *     Or to specify custom values use below format:
  *
  *     benchmarks/jmh:run -i 20 -wi 20 -f 4 -t 2 *.IOShallowAttemptBenchmark
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
class IOShallowAttemptBenchmark {
  case class TypedError(message: String)

  @Param(Array("1000"))
  var depth: Int = _

  @Benchmark
  def futureShallowAttempt(): BigInt = {
    import scala.concurrent.Future
    import scala.concurrent.duration.Duration.Inf

    def throwup(n: Int): Future[BigInt] =
      if (n == 0) throwup(n + 1).transform(_ => 0, identity)
      else if (n == depth) Future(1)
      else throwup(n + 1).transformWith {
        case Success(_) => Future.successful(0)
        case _ => Future.failed(new Exception("Oh noes!"))
      }

    Await.result(throwup(0), Inf)
  }

  @Benchmark
  def monixShallowAttempt(): BigInt = {
    import monix.eval.Task

    def throwup(n: Int): Task[BigInt] =
      if (n == 0) throwup(n + 1).redeem(_ => 0, identity)
      else if (n == depth) Task(1)
      else throwup(n + 1).redeemWith(_ => Task.now(0), _ => Task.raiseError(new Error("Oh noes!")))

    throwup(0).runSyncUnsafe()
  }

  @Benchmark
  def monixBioShallowAttemptTyped(): BigInt = {
    import monix.bio.BIO

    def throwup(n: Int): BIO[TypedError, BigInt] =
      if (n == 0) throwup(n + 1).redeem(_ => 50, identity)
      else if (n == depth) monix.bio.UIO(1)
      else throwup(n + 1).redeemWith(_ => BIO.now(0), _ => BIO.raiseError(TypedError("Oh noes!")))

    throwup(0).runSyncUnsafe()
  }

  @Benchmark
  def monixBioShallowAttempt(): BigInt = {
    import monix.bio.BIO

    def throwup(n: Int): BIO[Error, BigInt] =
      if (n == 0) throwup(n + 1).redeem(_ => 50, identity)
      else if (n == depth) monix.bio.UIO(1)
      else throwup(n + 1).redeemWith(_ => BIO.now(0), _ => BIO.raiseError(new Error("Oh noes!")))

    throwup(0).runSyncUnsafe()
  }

  @Benchmark
  def zioShallowAttemptTyped(): BigInt = {
    import zio.IO

    def throwup(n: Int): IO[TypedError, BigInt] =
      if (n == 0) throwup(n + 1).fold[BigInt](_ => 50, identity)
      else if (n == depth) IO.effectTotal(1)
      else throwup(n + 1).foldM[Any, TypedError, BigInt](_ => IO.succeed(0), _ => IO.fail(TypedError("Oh noes!")))

    zioUntracedRuntime.unsafeRun(throwup(0))
  }

  @Benchmark
  def zioShallowAttempt(): BigInt = {
    import zio.IO

    def throwup(n: Int): IO[Error, BigInt] =
      if (n == 0) throwup(n + 1).fold[BigInt](_ => 50, identity)
      else if (n == depth) IO.effectTotal(1)
      else throwup(n + 1).foldM[Any, Error, BigInt](_ => IO.succeed(0), _ => IO.fail(new Error("Oh noes!")))

    zioUntracedRuntime.unsafeRun(throwup(0))
  }

  @Benchmark
  def catsShallowAttempt(): BigInt = {
    import cats.effect.IO

    def throwup(n: Int): IO[BigInt] =
      if (n == 0) throwup(n + 1).redeem(_ => 0, identity)
      else if (n == depth) IO(1)
      else throwup(n + 1).redeemWith(_ => IO.pure(0), _ => IO.raiseError(new Error("Oh noes!")))

    throwup(0).unsafeRunSync()
  }
}

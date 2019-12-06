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

/** To run the benchmark from within SBT:
  *
  *     benchmarks/jmh:run .*.IODeepFlatMapBenchmark
  *     The above test will take default values as "10 iterations", "10 warm-up iterations",
  *     "2 forks", "1 thread".
  *
  *     Or to specify custom values use below format:
  *
  *     benchmarks/jmh:run -i 20 -wi 20 -f 4 -t 2 *.IODeepFlatMapBenchmark
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
class IODeepFlatMapBenchmark {
  @Param(Array("20"))
  var depth: Int = _

  @Benchmark
  def futureDeepFlatMap(): BigInt = {
    import scala.concurrent.Future
    import scala.concurrent.duration.Duration.Inf

    def fib(n: Int): Future[BigInt] =
      if (n <= 1) Future(n)
      else
        fib(n - 1).flatMap { a =>
          fib(n - 2).flatMap(b => Future(a + b))
        }

    Await.result(fib(depth), Inf)
  }

  @Benchmark
  def monixDeepFlatMap(): BigInt = {
  import monix.eval.Task

    def fib(n: Int): Task[BigInt] =
      if (n <= 1) Task.eval(n)
      else
        fib(n - 1).flatMap { a =>
          fib(n - 2).flatMap(b => Task.eval(a + b))
        }

    fib(depth).runSyncUnsafe()
  }

  @Benchmark
  def monixBioDeepFlatMap(): BigInt = {
    import monix.bio.UIO

    def fib(n: Int): UIO[BigInt] =
      if (n <= 1) UIO.eval(n)
      else
        fib(n - 1).flatMap { a =>
          fib(n - 2).flatMap(b => UIO.eval(a + b))
        }

    fib(depth).runSyncUnsafe()
  }

  @Benchmark
  def zioDeepFlatMap(): BigInt = {
    import zio.{UIO, ZIO}

    def fib(n: Int): UIO[BigInt] =
      if (n <= 1) ZIO.effectTotal[BigInt](n)
      else
        fib(n - 1).flatMap { a =>
          fib(n - 2).flatMap(b => ZIO.effectTotal(a + b))
        }

    zioUntracedRuntime.unsafeRun(fib(depth))
  }

  @Benchmark
  def catsDeepFlatMap(): BigInt = {
    import cats.effect._

    def fib(n: Int): IO[BigInt] =
      if (n <= 1) IO(n)
      else
        fib(n - 1).flatMap { a =>
          fib(n - 2).flatMap(b => IO(a + b))
        }

    fib(depth).unsafeRunSync
  }
}

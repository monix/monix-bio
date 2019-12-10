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

/** To run the benchmark from within SBT:
  *
  *     benchmarks/jmh:run .*.IOEmptyRaceBenchmark
  *     The above test will take default values as "10 iterations", "10 warm-up iterations",
  *     "2 forks", "1 thread".
  *
  *     Or to specify custom values use below format:
  *
  *     benchmarks/jmh:run -i 20 -wi 20 -f 4 -t 2 *.IOEmptyRaceBenchmark
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
class IOEmptyRaceBenchmark {
  @Param(Array("1000"))
  var size: Int = _

  @Benchmark
  def monixEmptyRace(): Int = {
    import monix.eval.Task

    def loop(i: Int): Task[Int] =
      if (i < size) Task.race(Task.never, Task.eval(i + 1)).flatMap(_ => loop(i + 1))
      else Task.pure(i)

    loop(0).runSyncUnsafe()
  }

  @Benchmark
  def monixBioEmptyRace(): Int = {
    import monix.bio.{BIO, UIO}

    def loop(i: Int): UIO[Int] =
      if (i < size) BIO.race(BIO.never, UIO.eval(i + 1)).flatMap(_ => loop(i + 1))
      else BIO.pure(i)

    loop(0).runSyncUnsafe()
  }

  @Benchmark
  def catsEmptyRace(): Int = {
    import cats.effect.IO

    def loop(i: Int): IO[Int] =
      if (i < size) IO.race(IO.never, IO.delay(i + 1)).flatMap(_ => loop(i + 1))
      else IO.pure(i)

    loop(0).unsafeRunSync()
  }

  @Benchmark
  def zioEmptyRace(): Int =  {
    import zio.{IO, UIO}

    def loop(i: Int): UIO[Int] =
      if (i < size) IO.never.raceAttempt(IO.effectTotal(i + 1)).flatMap(loop)
      else IO.succeed(i)

    zioUntracedRuntime.unsafeRun(loop(0))
  }
}

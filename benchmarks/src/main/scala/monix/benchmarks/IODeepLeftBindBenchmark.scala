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

import org.openjdk.jmh.annotations._

/** To run the benchmark from within SBT:
  *
  *     benchmarks/jmh:run .*.IODeepLeftBindBenchmark
  *     The above test will take default values as "10 iterations", "10 warm-up iterations",
  *     "2 forks", "1 thread".
  *
  *     Or to specify custom values use below format:
  *
  *     benchmarks/jmh:run -i 20 -wi 20 -f 4 -t 2 *.IODeepLeftBindBenchmark
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
class IODeepLeftBindBenchmark {
  @Param(Array("10000"))
  var depth: Int = _

  @Benchmark
  def monixDeepLeftBind(): Int = {
    import monix.eval.Task

    var i  = 0
    var io = Task.eval(i)
    while (i < depth) {
      io = io.flatMap(i => Task.eval(i))
      i += 1
    }

    io.runSyncUnsafe()
  }

  @Benchmark
  def monixBioDeepLeftBind(): Int = {
    import monix.bio.IO

    var i  = 0
    var io = IO.eval(i)
    while (i < depth) {
      io = io.flatMap(i => IO.eval(i))
      i += 1
    }

    io.runSyncUnsafe()
  }

  @Benchmark
  def zioDeepLeftBind(): Int =  {
    import zio.IO

    var i  = 0
    var io = IO.effectTotal(i)
    while (i < depth) {
      io = io.flatMap(i => IO.effectTotal(i))
      i += 1
    }

    zioUntracedRuntime.unsafeRun(io)
  }

  @Benchmark
  def catsDeepLeftBind(): Int = {
    import cats.effect.IO

    var i  = 0
    var io = IO(i)
    while (i < depth) {
      io = io.flatMap(i => IO(i))
      i += 1
    }

    io.unsafeRunSync
  }

}

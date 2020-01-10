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

package monix.bio

import cats.effect.laws.discipline.{ConcurrentEffectTests, ConcurrentTests}
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline.{
  BifunctorTests,
  CoflatMapTests,
  CommutativeApplicativeTests,
  ParallelTests,
  SemigroupKTests
}

object TypeClassLawsForTaskSuite
    extends BaseTypeClassLawsForTaskSuite()(
      BIO.defaultOptions.disableAutoCancelableRunLoops
    )

object TypeClassLawsForTaskAutoCancelableSuite
    extends BaseTypeClassLawsForTaskSuite()(
      BIO.defaultOptions.enableAutoCancelableRunLoops
    )

class BaseTypeClassLawsForTaskSuite(implicit opts: BIO.Options) extends BaseLawsSuite {

  checkAllAsync("CoflatMap[Task]") { implicit ec =>
    CoflatMapTests[Task].coflatMap[Int, Int, Int]
  }

  checkAllAsync("Concurrent[Task]") { implicit ec =>
    ConcurrentTests[BIO[Throwable, ?]].concurrent[Int, Int, Int]
  }

  checkAllAsync("ConcurrentEffect[Task]") { implicit ec =>
    ConcurrentEffectTests[Task].concurrentEffect[Int, Int, Int]
  }

  checkAllAsync("CommutativeApplicative[BIO.Par]") { implicit ec =>
    CommutativeApplicativeTests[BIO.Par[String, ?]].commutativeApplicative[Int, Int, Int]
  }

  checkAllAsync("Parallel[BIO, BIO.Par]") { implicit ec =>
    ParallelTests[Task, BIO.Par[Throwable, ?]].parallel[Int, Int]
  }

  checkAllAsync("Monoid[BIO[String, Int]]") { implicit ec =>
    MonoidTests[BIO[String, Int]].monoid
  }

  checkAllAsync("SemigroupK[Task[Int]]") { implicit ec =>
    SemigroupKTests[Task].semigroupK[Int]
  }

  checkAllAsync("Bifunctor[BIO[String, Int]]") { implicit ec =>
    BifunctorTests[BIO].bifunctor[String, String, String, Int, Int, Int]
  }
}

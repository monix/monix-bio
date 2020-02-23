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

import minitest.api.{AbstractTestSuite, Asserts, DefaultExecutionContext, Properties, TestSpec, Void}
import monix.execution.exceptions.UncaughtErrorException

import scala.concurrent.{ExecutionContext, Future}

trait SimpleBIOTestSuite extends AbstractTestSuite with Asserts {
  def test(name: String)(f: => Void): Unit =
    synchronized {
      if (isInitialized) throw initError()
      propertiesSeq = propertiesSeq :+ TestSpec.sync[Unit](name, _ => f)
    }

  def testAsync[E](name: String)(f: => Future[Either[E, Unit]]): Unit =
    synchronized {
      if (isInitialized) throw initError()
      propertiesSeq = propertiesSeq :+ TestSpec
        .async[Unit](name, _ => f.flatMap(_.fold(e => Future.failed(UncaughtErrorException.wrap(e)), _ => Future.unit)))
    }

  lazy val properties: Properties[_] =
    synchronized {
      if (!isInitialized) isInitialized = true
      Properties[Unit](() => (), _ => Void.UnitRef, () => (), () => (), propertiesSeq)
    }

  private[this] var propertiesSeq = Seq.empty[TestSpec[Unit, Unit]]
  private[this] var isInitialized = false
  private[this] implicit lazy val ec: ExecutionContext =
    DefaultExecutionContext

  private[this] def initError() =
    new AssertionError(
      "Cannot define new tests after SimpleTestSuite was initialized"
    )
}

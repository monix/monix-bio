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

import cats.effect.ExitCase

package object instances {
  @inline private[instances] final def exitCaseFlattenEither(exit: ExitCase[Either[Throwable, Throwable]]): ExitCase[Throwable] =
    exit match {
      case ExitCase.Error(e) => ExitCase.Error(e.fold(identity, identity))
      case ExitCase.Completed => ExitCase.Completed
      case ExitCase.Canceled => ExitCase.Canceled
    }
}

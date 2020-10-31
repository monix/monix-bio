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

package monix.bio.internal

import monix.bio.IO
import monix.bio.BiCallback
import monix.bio.internal.TracingPlatform.{isCachedStackTracing, isFullStackTracing}

/**
  * All Credits to https://github.com/typelevel/cats-effect and https://github.com/RaasAhsan
  */
private[bio] object TracedAsync {

  // Convenience function for internal Async calls that intend
  // to opt into tracing so the following code isn't repeated.
  def apply[E, A](
    k: (IO.Context[E], BiCallback[E, A]) => Unit,
    trampolineBefore: Boolean = false,
    trampolineAfter: Boolean = false,
    restoreLocals: Boolean = true,
    traceKey: AnyRef): IO[E, A] = {

    val trace = if (isCachedStackTracing) {
      TaskTracing.cached(traceKey.getClass)
    } else if (isFullStackTracing) {
      TaskTracing.uncached()
    } else {
      null
    }

    IO.Async(k, trampolineBefore, trampolineAfter, restoreLocals, trace)
  }

}

/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
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

package tastytest.zio.clock

import tastytest.zio.{ IO, UIO, ZIO }

trait Clock extends Serializable {
  val clock: Clock.Service[Any]
}

object Clock extends Serializable {
  trait Service[R] extends Serializable {
    val nanoTime: ZIO[R, Nothing, Long]
  }

  trait Live extends Clock {
    val clock: Service[Any] = new Service[Any] {
      val nanoTime: UIO[Long] = ???
    }
  }
  object Live extends Live
}

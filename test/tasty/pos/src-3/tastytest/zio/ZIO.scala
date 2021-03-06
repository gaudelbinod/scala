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

package tastytest.zio

sealed trait ZIO[-R, +E, +A] extends Serializable { self =>
  def flatMap[R1 <: R, E1 >: E, B](k: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] = ???
  def map[B](f: A => B): ZIO[R, E, B] = ???
  final def fold[B](failure: E => B, success: A => B)(implicit ev: CanFail[E]): URIO[R, B] = ???
}

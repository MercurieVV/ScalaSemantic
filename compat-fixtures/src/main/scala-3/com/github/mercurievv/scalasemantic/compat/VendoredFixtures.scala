/*
 * Copyright 2002-2024, EPFL
 * Copyright 2011-2024, Lightbend, Inc.
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
package com.github.mercurievv.scalasemantic.compat

// Enums
enum Color {
  case Red, Green, Blue
}

// Opaque types
object OpaqueTypes {
  opaque type Logarithm = Double
  object Logarithm {
    def apply(value: Double): Logarithm = value
    def safe(value: Double): Option[Logarithm] = if (value > 0) Some(value) else None
  }
}

// Parameterized traits
trait Friendly(val greeting: String) {
  def greet(): String = greeting
}
class FriendlyRobot extends Friendly("Hello from robot")

/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.crypto

import kamon.Kamon
import kamon.metric.MeasurementUnit

object Monitoring {

  object Metrics {
    val MessageSize = Kamon.histogram("messages.size", MeasurementUnit.information.bytes)
  }

  object Tags {
    val MessageDirection = "direction"

    object MessageDirections {
      val IN = "IN"
      val OUT = "OUT"
    }

  }

}

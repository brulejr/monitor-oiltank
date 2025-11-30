/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025 Jon Brule <brulejr@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.jrb.labs.monitor.oiltank.publishing

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HaDevice(
    val identifiers: List<String>,
    val name: String,
    val manufacturer: String? = null,
    val model: String? = null
)

/**
 * Minimal MQTT discovery config for a Home Assistant sensor entity.
 *
 * This matches the JSON shown in the Home Assistant docs:
 * https://www.home-assistant.io/integrations/sensor.mqtt/
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class HaMqttSensorConfig(
    val name: String,
    val unique_id: String,
    val state_topic: String,
    val json_attributes_topic: String? = null,
    val unit_of_measurement: String? = null,
    val device_class: String? = null,
    val state_class: String? = null,
    val icon: String? = null,
    val device: HaDevice? = null
)

/**
 * Attributes payload we’ll publish separately, so HA can expose them as
 * extra sensor attributes.
 *
 * Adjust the fields to match your TankLevel model as you see fit.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OilTankStateAttributes(
    val percentage: Double,
    val raw: Double? = null,
    val calibrated: Double? = null,
    val smoothed: Double? = null,
    val timestampIso: String? = null
)

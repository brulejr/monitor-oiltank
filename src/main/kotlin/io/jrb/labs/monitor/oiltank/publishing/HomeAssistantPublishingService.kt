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

import com.fasterxml.jackson.databind.ObjectMapper
import io.jrb.labs.commons.eventbus.EventBus
import io.jrb.labs.commons.eventbus.SystemEventBus
import io.jrb.labs.commons.service.ControllableService
import io.jrb.labs.monitor.oiltank.events.OilEvent
import io.jrb.labs.monitor.oiltank.events.OilEventBus
import io.jrb.labs.monitor.oiltank.processing.TankLevel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DiscoveryConversionService(
    private val datafill: OilTankMqttDatafill,
    private val eventBus: OilEventBus,
    private val objectMapper: ObjectMapper,
    private val mqttPublisher: MqttPublisher,
    systemEventBus: SystemEventBus
) : ControllableService(systemEventBus) {

    private val log = LoggerFactory.getLogger(DiscoveryConversionService::class.java)

    private var subscription: EventBus.Subscription? = null

    @Volatile
    private var discoveryPublished: Boolean = false

    override fun onStart() {
        if (!datafill.enabled) {
            log.info("MQTT discovery is disabled via configuration; not subscribing to OilEventBus")
            return
        }

        publishDiscoveryConfigIfNeeded()

        subscription = eventBus.subscribe<OilEvent.LevelCalculated> { message ->
            log.info("Oil level calculation: {}", message.level)
            handleLevelCalculated(message.level)
        }
    }

    override fun onStop() {
        subscription?.cancel()
    }

    private fun handleLevelCalculated(level: TankLevel) {
        if (!datafill.enabled) {
            return
        }

        // Make sure discovery exists (e.g. after HA restart)
        publishDiscoveryConfigIfNeeded()

        // Publish current level state (for gauge)
        publishState(level)

        // Publish attributes (raw/calibrated/smoothed/etc.)
        publishAttributes(level)
    }

    private fun publishDiscoveryConfigIfNeeded() {
        if (discoveryPublished) {
            return
        }

        val device = HaDevice(
            identifiers = listOf(datafill.deviceId),
            name = datafill.deviceName,
            manufacturer = datafill.deviceManufacturer,
            model = datafill.deviceModel
        )

        val config = HaMqttSensorConfig(
            name = datafill.sensorName,
            unique_id = datafill.uniqueId,
            state_topic = datafill.stateTopic,
            json_attributes_topic = datafill.attributesTopic,
            unit_of_measurement = "%",
            device_class = null,          // leave null, HA will still show a gauge card fine
            state_class = "measurement",  // time-series measurement
            icon = "mdi:gauge",
            device = device
        )

        val payload = objectMapper.writeValueAsString(config)

        log.info(
            "Publishing Home Assistant MQTT discovery config to topic [{}]: {}",
            datafill.discoveryTopic,
            payload
        )

        // Discovery messages are typically retained so HA picks them up after restart.
        mqttPublisher.publish(topic = datafill.discoveryTopic, payload = payload)

        discoveryPublished = true
    }

    private fun publishState(level: TankLevel) {
        val percentage = level.toPercentage()  // see extension below

        val payload = percentage.toString()

        log.info(
            "Publishing oil tank level state [{}%] to topic [{}]",
            percentage,
            datafill.stateTopic
        )

        // For sensor state, HA docs generally recommend NOT retaining the state payload.
        mqttPublisher.publish(topic = datafill.stateTopic, payload = payload)
    }

    private fun publishAttributes(level: TankLevel) {
        val attrs = level.toAttributes()

        val payload = objectMapper.writeValueAsString(attrs)

        log.debug(
            "Publishing oil tank attribute payload to topic [{}]: {}",
            datafill.attributesTopic,
            payload
        )

        mqttPublisher.publish(topic = datafill.attributesTopic, payload = payload)
    }

}

private fun TankLevel.toPercentage(): Double {
    return this.percent.toDouble()
}

private fun TankLevel.toAttributes(): OilTankStateAttributes {
    return OilTankStateAttributes(
        percentage = toPercentage(),
        raw = null,
        calibrated = null,
        smoothed = null,
        timestampIso = Instant.now().toString()
    )
}

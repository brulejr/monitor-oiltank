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

import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import io.jrb.labs.commons.eventbus.SystemEventBus
import io.jrb.labs.commons.service.ControllableService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MqttPublisher(
    datafill: MqttDatafill,
    systemEventBus: SystemEventBus
) : ControllableService(systemEventBus) {

    private val log = LoggerFactory.getLogger(MqttPublisher::class.java)

    private val _mqttClient: Mqtt3AsyncClient = com.hivemq.client.mqtt.MqttClient.builder()
        .useMqttVersion3()
        .identifier(datafill.clientId)
        .serverHost(datafill.host)
        .serverPort(datafill.port)
        .buildAsync()

    override fun onStart() {
        _mqttClient.connect()
    }

    override fun onStop() {
        _mqttClient.disconnect()
    }

    fun publish(topic: String, payload: String, qos: Int = 1, retain: Boolean = true) {
        if (isRunning()) {
            _mqttClient.publishWith()
            .topic(topic)
            .payload(payload.toByteArray())
                .qos(MqttQos.fromCode(qos) ?: MqttQos.AT_LEAST_ONCE)
                .retain(retain)
                .send()
                .whenComplete { _, t ->
                    if (t != null) {
                        log.error("Publishing message failed. ${t.message}")
                    }
                }
        }
    }

}
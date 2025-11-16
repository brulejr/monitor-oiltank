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

package io.jrb.labs.monitor.oiltank.mqtt

import io.jrb.labs.monitor.oiltank.config.MqttDatafill
import org.eclipse.paho.client.mqttv3.MqttClient
import org.springframework.stereotype.Service

@Service
class MqttPublisher(
    datafill: MqttDatafill
) {
    private val client = MqttClient(datafill.brokerUrl, MqttClient.generateClientId())

    init {
        client.connect()
    }

    private val levelTopic = datafill.topic.level
    private val alertTopic = datafill.topic.alert

    fun publishLevel(percent: Int) {
        client.publish(levelTopic, percent.toString().toByteArray(), 1, false)
    }

    fun publishAlert(message: String) {
        client.publish(alertTopic, message.toByteArray(), 1, false)
    }

}

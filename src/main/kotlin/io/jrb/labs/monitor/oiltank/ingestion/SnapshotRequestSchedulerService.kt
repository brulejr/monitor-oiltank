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

package io.jrb.labs.monitor.oiltank.ingestion

import io.jrb.labs.commons.eventbus.SystemEventBus
import io.jrb.labs.commons.service.ControllableService
import io.jrb.labs.monitor.oiltank.events.OilEvent
import io.jrb.labs.monitor.oiltank.events.OilEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class SnapshotRequestSchedulerService(
    private val eventBus: OilEventBus,
    systemEventBus: SystemEventBus
) : ControllableService(systemEventBus) {

    private val log = LoggerFactory.getLogger(SnapshotRequestSchedulerService::class.java)

    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStart() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onStop() {
        scope.cancel("SnapshotRequestSchedulerService stopped")
    }

    @Scheduled(fixedDelayString = "\${application.camera.snapshot-interval-ms:60000}")
    fun generateSnapshotRequest() {
        if (!isRunning()) {
            // Service is currently disabled via ControllableService / SystemEventBus
            return
        }

        val event = OilEvent.SnapshotRequested(reason = "scheduled")
        log.debug("Scheduling snapshot request event publish: {}", event)

        scope.launch {
            try {
                log.info("Publishing snapshot request event: {}", event)
                eventBus.publish(event)   // suspend fun
            } catch (ex: Exception) {
                log.error("Failed to publish snapshot request event", ex)
            }
        }
    }

}
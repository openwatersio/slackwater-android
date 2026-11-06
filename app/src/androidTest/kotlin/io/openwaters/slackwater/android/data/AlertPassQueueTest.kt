package io.openwaters.slackwater.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlertPassQueueTest {
    @Test fun observesSubsequentInputsAfterAStoreReadFails() = runBlocking {
        var attempts = 0
        val inputs = flow {
            if (attempts++ == 0) throw IOException("temporary read failure")
            emit(1)
            emit(2)
        }
        assertEquals(listOf(1, 2), inputs.retryAlertReads(backoffMillis = 1).toList())
    }

    @Test fun overlappingChangesProduceOneSerialFollowUpWithAllNewRules() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val started = Channel<Set<UUID>>(Channel.UNLIMITED)
            val finish = Channel<Unit>()
            val queue = AlertPassQueue(scope) {
                started.send(it)
                finish.receive()
            }
            queue.request()
            assertEquals(emptySet<UUID>(), withTimeout(5000) { started.receive() })
            val ids = List(3) { UUID.randomUUID() }
            ids.forEach(queue::request)
            repeat(5) { queue.request() }
            assertTrue(started.tryReceive().isFailure)
            finish.send(Unit)
            assertEquals(ids.toSet(), withTimeout(5000) { started.receive() })
            finish.send(Unit)
            queue.request()
            assertEquals(emptySet<UUID>(), withTimeout(5000) { started.receive() })
        } finally {
            scope.cancel()
        }
    }
}

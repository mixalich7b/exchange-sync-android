package net.mixalich7b.exchangesync.infrastructure.activesync

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSyncRequestPacerTest {
    @Test
    fun `first exchange is immediate and the next starts two seconds after completion`() =
        runTest {
            val dispatchTimes = mutableListOf<Long>()
            val pacer = pacer()

            pacer.exchange {
                dispatchTimes += testScheduler.currentTime
                delay(500)
            }
            pacer.exchange {
                dispatchTimes += testScheduler.currentTime
            }

            assertEquals(listOf(0L, 2_500L), dispatchTimes)
        }

    @Test
    fun `elapsed work or backoff satisfies the interval without another delay`() =
        runTest {
            val dispatchTimes = mutableListOf<Long>()
            val pacer = pacer()

            pacer.exchange { dispatchTimes += testScheduler.currentTime }
            delay(2_500)
            pacer.exchange { dispatchTimes += testScheduler.currentTime }

            assertEquals(listOf(0L, 2_500L), dispatchTimes)
        }

    @Test
    fun `transport failure establishes the next completion interval`() =
        runTest {
            val pacer = pacer()

            val failure = runCatching { pacer.exchange<Unit> { throw IOException("transport failed") } }.exceptionOrNull()
            var nextDispatchAt: Long? = null
            pacer.exchange { nextDispatchAt = testScheduler.currentTime }

            assertInstanceOf(IOException::class.java, failure)
            assertEquals(2_000L, nextDispatchAt)
        }

    @Test
    fun `cancellation during pacing sends no pending exchange`() =
        runTest {
            val pacer = pacer()
            pacer.exchange { }
            var dispatched = false

            val waiting = launch {
                pacer.exchange { dispatched = true }
            }
            runCurrent()
            waiting.cancelAndJoin()

            assertTrue(waiting.isCancelled)
            assertEquals(false, dispatched)
        }

    private fun kotlinx.coroutines.test.TestScope.pacer(): ActiveSyncRequestPacer =
        ActiveSyncRequestPacer(
            nanoTime = { testScheduler.currentTime * 1_000_000L },
            waitMillis = { millis -> delay(millis) },
        )
}

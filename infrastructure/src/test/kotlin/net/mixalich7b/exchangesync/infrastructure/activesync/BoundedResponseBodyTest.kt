package net.mixalich7b.exchangesync.infrastructure.activesync

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BoundedResponseBodyTest {
    @Test
    fun `OPTIONS response body is ignored even when it cannot be read`() {
        assertArrayEquals(byteArrayOf(), readActiveSyncResponseBody("OPTIONS", UnreadableBody()))
    }

    @Test
    fun `unknown-length response is read only through the protocol limit`() {
        val body = UnknownLengthBody(ByteArray(MAX_ACTIVE_SYNC_RESPONSE_BYTES + 1) { 7 })

        assertThrows(ActiveSyncResponseTooLargeException::class.java) {
            readBoundedActiveSyncBody(body)
        }
    }

    @Test
    fun `bounded response preserves its bytes`() {
        val expected = "bounded".encodeToByteArray()

        assertArrayEquals(expected, readBoundedActiveSyncBody(UnknownLengthBody(expected)))
    }

    private class UnknownLengthBody(bytes: ByteArray) : ResponseBody() {
        private val buffer = Buffer().write(bytes)

        override fun contentLength(): Long = -1

        override fun contentType(): MediaType? = null

        override fun source(): BufferedSource = buffer
    }

    private class UnreadableBody : ResponseBody() {
        override fun contentLength(): Long = error("OPTIONS body must not be inspected")

        override fun contentType(): MediaType? = null

        override fun source(): BufferedSource = error("OPTIONS body must not be read")
    }
}

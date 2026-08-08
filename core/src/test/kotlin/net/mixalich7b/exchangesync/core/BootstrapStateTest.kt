package net.mixalich7b.exchangesync.core

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class BootstrapStateTest {
    @Test
    fun `initial bootstrap state is unconfigured`() {
        assertSame(BootstrapState.Unconfigured, BootstrapState.initial())
    }
}

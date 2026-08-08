package net.mixalich7b.exchangesync.core

public sealed interface BootstrapState {
    public data object Unconfigured : BootstrapState

    public companion object {
        public fun initial(): BootstrapState = Unconfigured
    }
}

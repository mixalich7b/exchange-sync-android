package net.mixalich7b.exchangesync.core.connection

public interface ConnectionProfileRepository {
    public suspend fun load(): ConnectionProfile?

    public suspend fun replace(profile: ConnectionProfile)
}

public fun interface ConnectionVerifier {
    public suspend fun verify(profile: ConnectionProfile): ConnectionCheckResult
}

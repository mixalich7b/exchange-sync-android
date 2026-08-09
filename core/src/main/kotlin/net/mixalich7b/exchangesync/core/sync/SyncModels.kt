package net.mixalich7b.exchangesync.core.sync

import net.mixalich7b.exchangesync.core.connection.ConnectionProfile

public enum class ActiveSyncVersion(public val wireValue: String) {
    V14_0("14.0"),
    V14_1("14.1"),
    V16_0("16.0"),
    V16_1("16.1"),
    ;

    public companion object {
        public fun fromWireValue(value: String?): ActiveSyncVersion? =
            entries.firstOrNull { version -> version.wireValue == value }
    }
}

public enum class SyncPhase(
    public val code: String,
    public val isActive: Boolean,
    public val isCancellable: Boolean,
) {
    DISABLED("disabled", false, false),
    IDLE("idle", false, false),
    QUEUED("queued", true, true),
    DISCOVERING_PROTOCOL("discovering_protocol", true, true),
    DISCOVERING_FOLDERS("discovering_folders", true, true),
    DOWNLOADING("downloading", true, true),
    APPLYING("applying", true, true),
    CANCELLING("cancelling", false, false),
    BLOCKED("blocked", false, false),
    ;

    public companion object {
        public fun fromCode(code: String?): SyncPhase? = entries.firstOrNull { phase -> phase.code == code }
    }
}

public enum class SyncTrigger(public val code: String) {
    PROFILE_ACTIVATION("profile_activation"),
    ENABLE("enable"),
    MANUAL("manual"),
    PERIODIC("periodic"),
    CONTINUATION("continuation"),
    RETRY("retry"),
    ;

    public companion object {
        public fun fromCode(code: String?): SyncTrigger? = entries.firstOrNull { trigger -> trigger.code == code }
    }
}

public enum class SyncProblem(public val code: String) {
    CLIENT_CERTIFICATE("client_certificate"),
    TLS("tls"),
    ACCESS("access"),
    REDIRECT("redirect"),
    COMPATIBILITY("compatibility"),
    UNSUPPORTED_PROVISIONING("unsupported_provisioning"),
    PRIMARY_CALENDAR("primary_calendar"),
    REPEATED_INVALID_KEY("repeated_invalid_key"),
    PROTOCOL_DATA("protocol_data"),
    CALENDAR_PERMISSION("calendar_permission"),
    CALENDAR_PROVIDER("calendar_provider"),
    BACKGROUND_SCHEDULING("background_scheduling"),
    TRANSIENT_EXHAUSTED("transient_exhausted"),
    ;

    public companion object {
        public fun fromCode(code: String?): SyncProblem? = entries.firstOrNull { problem -> problem.code == code }
    }
}

public data class SyncFence(
    public val generation: Long,
    public val runToken: Long,
) {
    init {
        require(generation >= 0)
        require(runToken >= 0)
    }
}

public data class SyncCheckpoints(
    public val terminalCommandUrl: String? = null,
    public val protocolVersion: ActiveSyncVersion? = null,
    public val folderSyncKey: String? = null,
    public val primaryCalendarId: String? = null,
    public val collectionSyncKey: String? = null,
    public val windowSize: Int = DEFAULT_WINDOW_SIZE,
) {
    init {
        require(windowSize in 1..DEFAULT_WINDOW_SIZE)
        require(terminalCommandUrl?.isNotBlank() != false)
        require(folderSyncKey?.isNotBlank() != false)
        require(primaryCalendarId?.isNotBlank() != false)
        require(collectionSyncKey?.isNotBlank() != false)
    }

    public fun cleared(): SyncCheckpoints = EMPTY

    public companion object {
        public const val DEFAULT_WINDOW_SIZE: Int = 100
        public val EMPTY: SyncCheckpoints = SyncCheckpoints()
    }
}

public data class SyncState(
    public val enabled: Boolean,
    public val generation: Long,
    public val runToken: Long,
    public val fullSyncRequired: Boolean,
    public val invalidKeyRecoveryUsed: Boolean,
    public val deviceId: String?,
    public val checkpoints: SyncCheckpoints,
    public val phase: SyncPhase,
    public val currentTrigger: SyncTrigger?,
    public val followUpRequested: Boolean,
    public val consecutiveTransientAttempts: Int,
    public val lastSuccessfulEpochMillis: Long?,
    public val problem: SyncProblem?,
    public val notificationPermissionDenied: Boolean,
    public val calendarCleanupPending: Boolean,
) {
    init {
        require(generation >= 0)
        require(runToken >= 0)
        require(consecutiveTransientAttempts >= 0)
        require(lastSuccessfulEpochMillis == null || lastSuccessfulEpochMillis >= 0)
    }

    public val fence: SyncFence
        get() = SyncFence(generation, runToken)

    public companion object {
        public fun initial(): SyncState =
            SyncState(
                enabled = false,
                generation = 0,
                runToken = 0,
                fullSyncRequired = false,
                invalidKeyRecoveryUsed = false,
                deviceId = null,
                checkpoints = SyncCheckpoints.EMPTY,
                phase = SyncPhase.DISABLED,
                currentTrigger = null,
                followUpRequested = false,
                consecutiveTransientAttempts = 0,
                lastSuccessfulEpochMillis = null,
                problem = null,
                notificationPermissionDenied = false,
                calendarCleanupPending = false,
            )
    }
}

public data class SyncPageRequest(
    public val profile: ConnectionProfile,
    public val fence: SyncFence,
    public val deviceId: String,
    public val checkpoints: SyncCheckpoints,
    public val fullSyncRequired: Boolean,
) {
    init {
        require(deviceId.isNotBlank() && deviceId.all(Char::isLetterOrDigit))
    }
}

public interface CalendarChange

public data class RemoteCalendarPage(
    public val changes: List<CalendarChange>,
    public val nextCheckpoints: SyncCheckpoints,
    public val moreAvailable: Boolean,
)

public enum class SyncFailureKind {
    TRANSIENT,
    INVALID_KEY,
    WINDOW_TOO_LARGE,
    CRITICAL,
}

public sealed interface RemotePageOutcome {
    public data class Page(public val page: RemoteCalendarPage) : RemotePageOutcome

    public data class Failure(
        public val kind: SyncFailureKind,
        public val problem: SyncProblem?,
    ) : RemotePageOutcome
}

public sealed interface LocalPageOutcome {
    public data object Applied : LocalPageOutcome

    public data object Obsolete : LocalPageOutcome

    public data object FullResetRequired : LocalPageOutcome

    public data object TransactionTooLarge : LocalPageOutcome

    public data class Failed(public val problem: SyncProblem = SyncProblem.CALENDAR_PROVIDER) : LocalPageOutcome
}

public sealed interface SyncRunRequest {
    public val state: SyncState

    public data class Queued(override val state: SyncState) : SyncRunRequest

    public data class Coalesced(override val state: SyncState) : SyncRunRequest

    public data class Ignored(override val state: SyncState) : SyncRunRequest
}

public sealed interface SyncLifecycleOutcome {
    public data class Scheduled(public val generation: Long) : SyncLifecycleOutcome

    public data class PermissionRequired(public val generation: Long) : SyncLifecycleOutcome

    public data class Blocked(
        public val generation: Long,
        public val problem: SyncProblem,
    ) : SyncLifecycleOutcome

    public data object Ignored : SyncLifecycleOutcome
}

public sealed interface SyncSliceOutcome {
    public data object Completed : SyncSliceOutcome

    public data object Continued : SyncSliceOutcome

    public data object Retry : SyncSliceOutcome

    public data class Blocked(public val problem: SyncProblem) : SyncSliceOutcome

    public data object Obsolete : SyncSliceOutcome

    public data object Cancelled : SyncSliceOutcome

    public data object PermissionRequired : SyncSliceOutcome
}

public sealed interface SyncCancellationOutcome {
    public data object Cancelled : SyncCancellationOutcome

    public data object Ignored : SyncCancellationOutcome
}

public sealed interface SyncDisableOutcome {
    public data object Disabled : SyncDisableOutcome

    public data class CleanupPending(public val problem: SyncProblem) : SyncDisableOutcome

    public data object Ignored : SyncDisableOutcome
}

public data class SyncSliceLimits(
    public val maxPages: Int = 10,
    public val maxElapsedMillis: Long = 4 * 60 * 1_000L,
) {
    init {
        require(maxPages > 0)
        require(maxElapsedMillis > 0)
    }
}

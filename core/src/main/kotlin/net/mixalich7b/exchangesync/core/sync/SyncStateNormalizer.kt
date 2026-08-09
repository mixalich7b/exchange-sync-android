package net.mixalich7b.exchangesync.core.sync

import java.net.URI

public data class RawSyncCheckpoints(
    public val terminalCommandUrl: String? = null,
    public val protocolVersion: String? = null,
    public val folderSyncKey: String? = null,
    public val primaryCalendarId: String? = null,
    public val collectionSyncKey: String? = null,
    public val windowSize: Int? = null,
)

public data class RawSyncState(
    public val enabled: Boolean? = null,
    public val generation: Long? = null,
    public val runToken: Long? = null,
    public val fullSyncRequired: Boolean? = null,
    public val invalidKeyRecoveryUsed: Boolean? = null,
    public val deviceId: String? = null,
    public val phaseCode: String? = null,
    public val triggerCode: String? = null,
    public val followUpRequested: Boolean? = null,
    public val consecutiveTransientAttempts: Int? = null,
    public val lastSuccessfulEpochMillis: Long? = null,
    public val problemCode: String? = null,
    public val notificationPermissionDenied: Boolean? = null,
    public val calendarCleanupPending: Boolean? = null,
    public val checkpoints: RawSyncCheckpoints? = null,
)

public object SyncStateNormalizer {
    public fun normalize(
        raw: RawSyncState?,
        hasSavedProfile: Boolean,
        recoverInterruptedPhase: Boolean = true,
    ): SyncState {
        if (!hasSavedProfile || raw == null) return SyncState.initial()

        val enabled = raw.enabled == true
        val generation = raw.generation.nonNegativeOrZero()
        val runToken = raw.runToken.nonNegativeOrZero()
        val deviceId = raw.deviceId?.takeIf(::isValidDeviceId)

        if (!enabled) {
            val cleanupPending = raw.calendarCleanupPending == true
            val cleanupProblem =
                SyncProblem.fromCode(raw.problemCode)
                    ?.takeIf { problem ->
                        cleanupPending &&
                            (problem == SyncProblem.CALENDAR_PERMISSION || problem == SyncProblem.CALENDAR_PROVIDER)
                    }
            return SyncState.initial().copy(
                generation = generation,
                runToken = runToken,
                deviceId = deviceId,
                lastSuccessfulEpochMillis = raw.lastSuccessfulEpochMillis.nonNegativeOrNull(),
                problem = cleanupProblem,
                notificationPermissionDenied = raw.notificationPermissionDenied == true,
                calendarCleanupPending = cleanupPending,
            )
        }

        val decodedCheckpoints = decodeCheckpoints(raw.checkpoints)
        val checkpoints = decodedCheckpoints ?: SyncCheckpoints.EMPTY
        val persistedPhase = SyncPhase.fromCode(raw.phaseCode)
        var phase = normalizeEnabledPhase(persistedPhase, recoverInterruptedPhase)
        val problem = SyncProblem.fromCode(raw.problemCode)
        if (phase == SyncPhase.BLOCKED && problem == null) phase = SyncPhase.IDLE
        val trigger =
            SyncTrigger.fromCode(raw.triggerCode)
                ?.takeIf { phase.isActive }

        return SyncState(
            enabled = true,
            generation = generation,
            runToken = runToken,
            fullSyncRequired = raw.fullSyncRequired == true || decodedCheckpoints == null,
            invalidKeyRecoveryUsed = raw.invalidKeyRecoveryUsed == true && decodedCheckpoints != null,
            deviceId = deviceId,
            checkpoints = checkpoints,
            phase = phase,
            currentTrigger = trigger,
            followUpRequested = raw.followUpRequested == true && trigger != null,
            consecutiveTransientAttempts = raw.consecutiveTransientAttempts.validAttemptCount(),
            lastSuccessfulEpochMillis = raw.lastSuccessfulEpochMillis.nonNegativeOrNull(),
            problem = problem,
            notificationPermissionDenied = raw.notificationPermissionDenied == true,
            calendarCleanupPending = raw.calendarCleanupPending == true,
        )
    }

    private fun normalizeEnabledPhase(
        phase: SyncPhase?,
        recoverInterruptedPhase: Boolean,
    ): SyncPhase =
        when {
            phase == null || phase == SyncPhase.DISABLED -> SyncPhase.IDLE
            recoverInterruptedPhase && phase == SyncPhase.CANCELLING -> SyncPhase.IDLE
            recoverInterruptedPhase && phase.isActive -> SyncPhase.QUEUED
            else -> phase
        }

    private fun decodeCheckpoints(raw: RawSyncCheckpoints?): SyncCheckpoints? {
        if (raw == null) return SyncCheckpoints.EMPTY
        val endpoint = raw.terminalCommandUrl.nullIfBlank()
        val version = ActiveSyncVersion.fromWireValue(raw.protocolVersion)
        val folderKey = raw.folderSyncKey.nullIfBlank()
        val calendarId = raw.primaryCalendarId.nullIfBlank()
        val collectionKey = raw.collectionSyncKey.nullIfBlank()
        val window = raw.windowSize ?: SyncCheckpoints.DEFAULT_WINDOW_SIZE

        if (raw.terminalCommandUrl != null && endpoint == null) return null
        if (raw.protocolVersion != null && version == null) return null
        if ((endpoint == null) != (version == null)) return null
        if (endpoint != null && !isSafeHttpsEndpoint(endpoint)) return null
        if (raw.folderSyncKey != null && folderKey == null) return null
        if (raw.primaryCalendarId != null && calendarId == null) return null
        if (raw.collectionSyncKey != null && collectionKey == null) return null
        if (calendarId != null && folderKey == null) return null
        if (collectionKey != null && calendarId == null) return null
        if (window !in 1..SyncCheckpoints.DEFAULT_WINDOW_SIZE) return null

        return SyncCheckpoints(
            terminalCommandUrl = endpoint,
            protocolVersion = version,
            folderSyncKey = folderKey,
            primaryCalendarId = calendarId,
            collectionSyncKey = collectionKey,
            windowSize = window,
        )
    }

    private fun isSafeHttpsEndpoint(value: String): Boolean =
        runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null
        }.getOrDefault(false)

    private fun isValidDeviceId(value: String): Boolean =
        value.length in 1..32 && value.all { character -> character in 'A'..'Z' || character in '0'..'9' }

    private fun String?.nullIfBlank(): String? = this?.takeIf(String::isNotBlank)

    private fun Long?.nonNegativeOrZero(): Long = this?.takeIf { value -> value >= 0 } ?: 0

    private fun Long?.nonNegativeOrNull(): Long? = this?.takeIf { value -> value >= 0 }

    private fun Int?.validAttemptCount(): Int = this?.takeIf { value -> value in 0..5 } ?: 0
}

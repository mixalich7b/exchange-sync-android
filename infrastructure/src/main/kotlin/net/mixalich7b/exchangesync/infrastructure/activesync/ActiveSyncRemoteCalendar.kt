package net.mixalich7b.exchangesync.infrastructure.activesync

import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPage
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPort
import net.mixalich7b.exchangesync.core.sync.RemotePageOutcome
import net.mixalich7b.exchangesync.core.sync.SyncCheckpoints
import net.mixalich7b.exchangesync.core.sync.SyncFailureKind
import net.mixalich7b.exchangesync.core.sync.SyncFence
import net.mixalich7b.exchangesync.core.sync.SyncPageRequest
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import okhttp3.HttpUrl
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticComponent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticActiveSyncCommand
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCapacityKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCapacityOutcome
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCapacityProblem
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFailureSnapshot
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperationKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticSeverity
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage
import net.mixalich7b.exchangesync.infrastructure.diagnostics.SyncRequestMode
import net.mixalich7b.exchangesync.infrastructure.diagnostics.FolderPreparationOutcome
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlReadLimitKind

internal sealed interface ActiveSyncCapabilityOutcome {
    data class Success(
        val terminalEndpoint: HttpUrl,
        val version: ActiveSyncVersion,
        val supportedVersions: Set<ActiveSyncVersion> = setOf(version),
    ) : ActiveSyncCapabilityOutcome {
        init {
            require(version in supportedVersions)
        }
    }

    data class Failure(
        val kind: SyncFailureKind,
        val problem: SyncProblem?,
    ) : ActiveSyncCapabilityOutcome
}

internal fun interface ActiveSyncCapabilityGateway {
    suspend fun discover(profile: ConnectionProfile): ActiveSyncCapabilityOutcome

    suspend fun discover(
        profile: ConnectionProfile,
        operation: DiagnosticOperation,
    ): ActiveSyncCapabilityOutcome = discover(profile)
}

internal class ActiveSyncRemoteCalendar(
    private val capabilities: ActiveSyncCapabilityGateway,
    private val commands: ActiveSyncCommandGateway,
    private val sessions: ActiveSyncProfileSessionRegistry = ActiveSyncProfileSessionRegistry(),
    private val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
) : RemoteCalendarPort {
    override fun invalidatePreparedState(
        profile: ConnectionProfile,
        fence: SyncFence,
    ) {
        invalidatePreparedFolder(
            profile,
            diagnostics.operation(DiagnosticOperationKind.SYNCHRONIZATION, fence.generation, fence.runToken),
        )
    }

    override suspend fun fetchPage(request: SyncPageRequest): RemotePageOutcome =
        fetchPage(request) {}

    override suspend fun fetchPage(
        request: SyncPageRequest,
        reportPhase: suspend (SyncPhase) -> Unit,
    ): RemotePageOutcome {
        val operation =
            diagnostics.operation(
                DiagnosticOperationKind.SYNCHRONIZATION,
                request.fence.generation,
                request.fence.runToken,
            )
        return try {
            fetchProtocolPage(request, reportPhase, operation)
        } catch (status: ActiveSyncStatusException) {
            if (status.kind.invalidatesPreparedFolder()) {
                invalidatePreparedFolder(request.profile, operation)
            }
            emitProtocolFailure(
                operation = operation,
                stage = status.commandKind.diagnosticStage(),
                reason = "COMMAND_STATUS_${status.kind.name}",
                failureCategory = status.problem?.name ?: status.kind.name,
                command = status.commandKind,
                throwable = status,
            )
            RemotePageOutcome.Failure(status.kind, status.problem)
        } catch (failure: PrimaryCalendarSelectionException) {
            invalidatePreparedFolder(request.profile, operation)
            emitProtocolFailure(
                operation,
                DiagnosticStage.FOLDER_SYNC,
                "PRIMARY_CALENDAR",
                SyncProblem.PRIMARY_CALENDAR.name,
                ActiveSyncCommand.FOLDER_SYNC.wireValue,
                throwable = failure,
            )
            RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PRIMARY_CALENDAR)
        } catch (failure: ActiveSyncWbxmlReadLimitException) {
            if (failure.commandKind == ActiveSyncCommand.FOLDER_SYNC.wireValue) {
                invalidatePreparedFolder(request.profile, operation)
            }
            emitWbxmlCapacity(
                operation = operation,
                kind = failure.kind,
                command = failure.commandKind.toDiagnosticCommand(),
                windowSize = request.checkpoints.windowSize.takeIf {
                    failure.commandKind == ActiveSyncCommand.SYNC.wireValue
                },
                pageScaled = false,
            )
            RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PROTOCOL_DATA)
        } catch (failure: ActiveSyncProtocolDataException) {
            if (failure.commandKind == ActiveSyncCommand.FOLDER_SYNC.wireValue) {
                invalidatePreparedFolder(request.profile, operation)
            }
            emitProtocolFailure(
                operation = operation,
                stage = DiagnosticStage.EVENT_PARSE,
                reason = failure.reason.name,
                failureCategory = SyncProblem.PROTOCOL_DATA.name,
                command = failure.commandKind,
                serverId = failure.serverId,
                throwable = failure,
                calendarFailureSnapshot = failure.calendarFailureSnapshot,
            )
            RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PROTOCOL_DATA)
        } catch (failure: IllegalArgumentException) {
            emitProtocolFailure(
                operation,
                DiagnosticStage.EVENT_PARSE,
                "ILLEGAL_ARGUMENT",
                SyncProblem.PROTOCOL_DATA.name,
                throwable = failure,
            )
            RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PROTOCOL_DATA)
        }
    }

    private suspend fun fetchProtocolPage(
        request: SyncPageRequest,
        reportPhase: suspend (SyncPhase) -> Unit,
        operation: DiagnosticOperation,
    ): RemotePageOutcome {
        if (sessions.acquire(request.profile).liveCapability() == null) {
            reportPhase(SyncPhase.DISCOVERING_PROTOCOL)
        }
        val prepared =
            when (val result = prepareCapability(request, operation)) {
                is Preparation.Failure -> return result.outcome
                is Preparation.Success -> result.value
            }
        reportPhase(SyncPhase.DISCOVERING_FOLDERS)
        val folder =
            when (val result = prepareFolder(request, prepared, operation)) {
                is Preparation.Failure -> return result.outcome
                is Preparation.Success -> result.value
            }
        reportPhase(SyncPhase.DOWNLOADING)
        var collectionKey = request.checkpoints.collectionSyncKey ?: "0"
        var calendarEndpoint = folder.endpoint
        if (collectionKey == "0") {
            val priming =
                commands.execute(
                    profile = request.profile,
                    endpoint = calendarEndpoint,
                    command = ActiveSyncCommand.SYNC,
                    deviceId = request.deviceId,
                    version = prepared.version,
                    body =
                        CalendarSyncCodec.encodeRequest(
                            syncKey = "0",
                            collectionId = folder.primaryCalendarId,
                            windowSize = request.checkpoints.windowSize,
                            getChanges = false,
                            version = prepared.version,
                        ),
                    operation = operation,
                )
            when (priming) {
                is ActiveSyncCommandOutcome.Failure -> {
                    priming.capacityKind?.let { capacityKind ->
                        emitPageCapacity(operation, capacityKind, request.checkpoints.windowSize)
                    }
                    if (priming.kind.invalidatesPreparedFolder()) {
                        invalidatePreparedFolder(request.profile, operation)
                    }
                    return priming.toRemoteFailure()
                }
                is ActiveSyncCommandOutcome.Success -> {
                    emitSyncResponse(
                        operation = operation,
                        mode = SyncRequestMode.PRIMING,
                        windowSize = request.checkpoints.windowSize,
                        body = priming.body,
                    )
                    val primed = decodeCalendarResponse(priming.body, folder.primaryCalendarId)
                    emitDecodedPage(
                        operation = operation,
                        mode = SyncRequestMode.PRIMING,
                        previousKey = "0",
                        page = primed,
                    )
                    if (primed.commands.isNotEmpty() || primed.moreAvailable || primed.syncKey == "0") {
                        throw ActiveSyncProtocolDataException(
                            "Invalid Calendar Sync priming response",
                            commandKind = ActiveSyncCommand.SYNC.wireValue,
                        )
                    }
                    collectionKey = primed.syncKey
                    calendarEndpoint = priming.terminalEndpoint
                }
            }
        }
        val calendarOutcome =
            commands.execute(
                profile = request.profile,
                endpoint = calendarEndpoint,
                command = ActiveSyncCommand.SYNC,
                deviceId = request.deviceId,
                version = prepared.version,
                body =
                    CalendarSyncCodec.encodeRequest(
                        syncKey = collectionKey,
                        collectionId = folder.primaryCalendarId,
                        windowSize = request.checkpoints.windowSize,
                        getChanges = true,
                        version = prepared.version,
                    ),
                operation = operation,
            )
        return when (calendarOutcome) {
            is ActiveSyncCommandOutcome.Failure -> {
                calendarOutcome.capacityKind?.let { capacityKind ->
                    emitPageCapacity(operation, capacityKind, request.checkpoints.windowSize)
                }
                if (calendarOutcome.kind.invalidatesPreparedFolder()) {
                    invalidatePreparedFolder(request.profile, operation)
                }
                calendarOutcome.toRemoteFailure()
            }
            is ActiveSyncCommandOutcome.Success -> {
                val mode =
                    if (request.fullSyncRequired) {
                        SyncRequestMode.FULL
                    } else {
                        SyncRequestMode.INCREMENTAL
                    }
                emitSyncResponse(
                    operation = operation,
                    mode = mode,
                    windowSize = request.checkpoints.windowSize,
                    body = calendarOutcome.body,
                )
                val page =
                    if (calendarOutcome.body.isEmpty()) {
                        RawCalendarSyncPage(
                            syncKey = collectionKey,
                            commands = emptyList(),
                            moreAvailable = false,
                        )
                    } else {
                        try {
                            decodeCalendarResponse(calendarOutcome.body, folder.primaryCalendarId)
                        } catch (failure: ActiveSyncWbxmlReadLimitException) {
                            if (failure.kind.isPageScaled()) {
                                emitWbxmlCapacity(
                                    operation = operation,
                                    kind = failure.kind,
                                    command = DiagnosticActiveSyncCommand.SYNC,
                                    windowSize = request.checkpoints.windowSize,
                                    pageScaled = true,
                                )
                                return RemotePageOutcome.Failure(SyncFailureKind.WINDOW_TOO_LARGE, null)
                            }
                            throw failure
                        }
                    }
                emitDecodedPage(operation, mode, collectionKey, page)
                if (page.moreAvailable && page.syncKey == collectionKey) {
                    throw ActiveSyncProtocolDataException(
                        "Calendar SyncKey did not advance while more changes are available",
                        commandKind = ActiveSyncCommand.SYNC.wireValue,
                    )
                }
                RemotePageOutcome.Page(
                    RemoteCalendarPage(
                        changes =
                            page.commands.map { command ->
                                ActiveSyncCalendarApplicationParser.parse(command, request.profile.email, prepared.version)
                            },
                        nextCheckpoints =
                            SyncCheckpoints(
                                terminalCommandUrl = calendarOutcome.terminalEndpoint.toString(),
                                protocolVersion = prepared.version,
                                folderSyncKey = folder.folderSyncKey,
                                primaryCalendarId = folder.primaryCalendarId,
                                collectionSyncKey = page.syncKey,
                                windowSize = request.checkpoints.windowSize,
                            ),
                        moreAvailable = page.moreAvailable,
                    ),
                )
            }
        }
    }

    private fun emitSyncResponse(
        operation: DiagnosticOperation,
        mode: SyncRequestMode,
        windowSize: Int,
        body: ByteArray,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = DiagnosticSeverity.INFO,
                component = DiagnosticComponent.ACTIVE_SYNC,
                stage = DiagnosticStage.RESPONSE,
                operation = operation,
                method = "POST",
                command = ActiveSyncCommand.SYNC.wireValue,
                syncMode = mode,
                windowSize = windowSize,
                responseBytes = body.size,
                responseEmpty = body.isEmpty(),
                outcome = "success",
            ),
        )
    }

    private fun emitDecodedPage(
        operation: DiagnosticOperation,
        mode: SyncRequestMode,
        previousKey: String,
        page: RawCalendarSyncPage,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = DiagnosticSeverity.INFO,
                component = DiagnosticComponent.ACTIVE_SYNC,
                stage = DiagnosticStage.CALENDAR_SYNC,
                operation = operation,
                command = ActiveSyncCommand.SYNC.wireValue,
                syncMode = mode,
                commandCount = page.commands.size,
                addCount = page.commands.count { command -> command.kind == RawCalendarCommandKind.ADD },
                changeCount = page.commands.count { command -> command.kind == RawCalendarCommandKind.CHANGE },
                deleteCount =
                    page.commands.count { command ->
                        command.kind == RawCalendarCommandKind.DELETE ||
                            command.kind == RawCalendarCommandKind.SOFT_DELETE
                    },
                moreAvailable = page.moreAvailable,
                keyAdvanced = page.syncKey != previousKey,
                outcome = "decoded",
            ),
        )
    }

    private suspend fun prepareCapability(
        request: SyncPageRequest,
        operation: DiagnosticOperation,
    ): Preparation<PreparedCapability> {
        val session = sessions.acquire(request.profile)
        val savedVersion = request.checkpoints.protocolVersion
        val capability =
            session.liveCapability()
                ?: when (val outcome = capabilities.discover(request.profile, operation)) {
                    is ActiveSyncCapabilityOutcome.Success ->
                        ActiveSyncLiveCapability(
                            terminalEndpoint = outcome.terminalEndpoint,
                            version = outcome.version,
                            supportedVersions = outcome.supportedVersions,
                        )
                    is ActiveSyncCapabilityOutcome.Failure ->
                        return Preparation.Failure(RemotePageOutcome.Failure(outcome.kind, outcome.problem))
                }
        session.recordCapability(capability)
        if (savedVersion != null && savedVersion !in capability.supportedVersions) {
            invalidatePreparedFolder(request.profile, operation)
            return Preparation.Failure(
                RemotePageOutcome.Failure(SyncFailureKind.FULL_RESET_REQUIRED, null),
            )
        }
        val selectedVersion = savedVersion ?: capability.version
        val selected = capability.copy(version = selectedVersion)
        session.recordCapability(selected)
        return Preparation.Success(PreparedCapability(selected.terminalEndpoint, selected.version))
    }

    private suspend fun prepareFolder(
        request: SyncPageRequest,
        capability: PreparedCapability,
        operation: DiagnosticOperation,
    ): Preparation<PreparedFolder> {
        val session = sessions.acquire(request.profile)
        session.preparedFolder(request.fence, capability.version)?.let { prepared ->
            emitFolderPreparation(operation, FolderPreparationOutcome.REUSE)
            return Preparation.Success(
                PreparedFolder(
                    folderSyncKey = prepared.folderSyncKey,
                    primaryCalendarId = prepared.primaryCalendarId,
                    endpoint = prepared.terminalEndpoint,
                ),
            )
        }
        val refreshOutcome =
            if (session.hasPreparedFolder()) {
                FolderPreparationOutcome.REFRESH
            } else {
                FolderPreparationOutcome.COLD_REFRESH
            }
        val savedPrimary = request.checkpoints.primaryCalendarId
        val savedFolderKey = request.checkpoints.folderSyncKey
        val folderKey = savedFolderKey ?: "0"
        emitFolderPreparation(operation, refreshOutcome)
        val outcome =
            commands.execute(
                profile = request.profile,
                endpoint = capability.endpoint,
                command = ActiveSyncCommand.FOLDER_SYNC,
                deviceId = request.deviceId,
                version = capability.version,
                body = FolderSyncCodec.encodeRequest(folderKey),
                operation = operation,
            )
        return when (outcome) {
            is ActiveSyncCommandOutcome.Failure -> {
                if (outcome.kind.invalidatesPreparedFolder()) {
                    invalidatePreparedFolder(request.profile, operation)
                }
                Preparation.Failure(outcome.toRemoteFailure())
            }
            is ActiveSyncCommandOutcome.Success -> {
                val page = decodeFolderResponse(outcome.body)
                val state =
                    if (savedPrimary != null) {
                        reconcileRetainedPrimary(savedPrimary, folderKey, page)
                    } else {
                        FolderHierarchyReducer.apply(FolderHierarchyState("0", emptyMap()), page)
                    }
                val primary = PrimaryCalendarSelector.select(state.folders.values)
                val prepared =
                    ActiveSyncPreparedFolder(
                        fence = request.fence,
                        version = capability.version,
                        terminalEndpoint = outcome.terminalEndpoint,
                        folderSyncKey = page.syncKey,
                        primaryCalendarId = primary.serverId,
                    )
                session.recordPreparedFolder(prepared)
                Preparation.Success(
                    PreparedFolder(
                        prepared.folderSyncKey,
                        prepared.primaryCalendarId,
                        prepared.terminalEndpoint,
                    ),
                )
            }
        }
    }

    private fun reconcileRetainedPrimary(
        primaryCalendarId: String,
        folderSyncKey: String,
        page: FolderSyncPage,
    ): FolderHierarchyState {
        val folders =
            mutableMapOf(
                primaryCalendarId to ActiveSyncFolder(primaryCalendarId, "", "", DEFAULT_CALENDAR_TYPE),
            )
        page.changes.forEach { change ->
            when (change) {
                is FolderHierarchyChange.Add -> folders[change.folder.serverId] = change.folder
                is FolderHierarchyChange.Delete -> folders.remove(change.serverId)
                is FolderHierarchyChange.Update -> {
                    val existing = folders[change.serverId]
                    if (existing != null) {
                        folders[change.serverId] =
                            existing.copy(
                                parentId = change.parentId ?: existing.parentId,
                                displayName = change.displayName ?: existing.displayName,
                                type = change.type ?: existing.type,
                            )
                    } else if (change.type == DEFAULT_CALENDAR_TYPE) {
                        folders[change.serverId] =
                            ActiveSyncFolder(
                                serverId = change.serverId,
                                parentId = change.parentId.orEmpty(),
                                displayName = change.displayName.orEmpty(),
                                type = DEFAULT_CALENDAR_TYPE,
                            )
                    }
                }
            }
        }
        return FolderHierarchyState(page.syncKey.ifBlank { folderSyncKey }, folders)
    }

    private data class PreparedCapability(val endpoint: HttpUrl, val version: ActiveSyncVersion)

    private data class PreparedFolder(
        val folderSyncKey: String,
        val primaryCalendarId: String,
        val endpoint: HttpUrl,
    )

    private sealed interface Preparation<out T> {
        data class Success<T>(val value: T) : Preparation<T>

        data class Failure(val outcome: RemotePageOutcome.Failure) : Preparation<Nothing>
    }

    private fun ActiveSyncCommandOutcome.Failure.toRemoteFailure(): RemotePageOutcome.Failure =
        RemotePageOutcome.Failure(kind, problem)

    private fun decodeFolderResponse(body: ByteArray): FolderSyncPage =
        try {
            FolderSyncCodec.decodeResponse(body)
        } catch (status: ActiveSyncStatusException) {
            throw status.withCommand(ActiveSyncCommand.FOLDER_SYNC.wireValue)
        } catch (failure: ActiveSyncProtocolDataException) {
            throw failure.withContext(ActiveSyncCommand.FOLDER_SYNC.wireValue)
        } catch (failure: ActiveSyncWbxmlReadLimitException) {
            throw failure.withContext(ActiveSyncCommand.FOLDER_SYNC.wireValue)
        }

    private fun decodeCalendarResponse(
        body: ByteArray,
        primaryCalendarId: String,
    ): RawCalendarSyncPage =
        try {
            CalendarSyncCodec.decodeResponse(body, primaryCalendarId)
        } catch (status: ActiveSyncStatusException) {
            throw status.withCommand(ActiveSyncCommand.SYNC.wireValue)
        } catch (failure: ActiveSyncProtocolDataException) {
            throw failure.withContext(ActiveSyncCommand.SYNC.wireValue)
        } catch (failure: ActiveSyncWbxmlReadLimitException) {
            throw failure.withContext(ActiveSyncCommand.SYNC.wireValue)
        }

    private fun emitProtocolFailure(
        operation: DiagnosticOperation,
        stage: DiagnosticStage,
        reason: String,
        failureCategory: String,
        command: String? = null,
        serverId: String? = null,
        throwable: Throwable? = null,
        calendarFailureSnapshot: DiagnosticCalendarFailureSnapshot? = null,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                DiagnosticSeverity.WARN,
                DiagnosticComponent.ACTIVE_SYNC,
                stage,
                operation,
                command = command,
                reasonCode = reason,
                failureCategory = failureCategory,
                serverId = serverId,
                calendarFailureSnapshot = calendarFailureSnapshot,
                throwable = throwable,
            ),
        )
    }

    private fun String?.diagnosticStage(): DiagnosticStage =
        when (this) {
            ActiveSyncCommand.FOLDER_SYNC.wireValue -> DiagnosticStage.FOLDER_SYNC
            ActiveSyncCommand.SYNC.wireValue -> DiagnosticStage.CALENDAR_SYNC
            else -> DiagnosticStage.COMMAND
        }

    private fun WbxmlReadLimitKind.isPageScaled(): Boolean =
        this == WbxmlReadLimitKind.DOCUMENT_BYTES || this == WbxmlReadLimitKind.ELEMENT_COUNT

    private fun SyncFailureKind.invalidatesPreparedFolder(): Boolean =
        this == SyncFailureKind.INVALID_KEY || this == SyncFailureKind.FULL_RESET_REQUIRED

    private fun emitWbxmlCapacity(
        operation: DiagnosticOperation,
        kind: WbxmlReadLimitKind,
        command: DiagnosticActiveSyncCommand?,
        windowSize: Int?,
        pageScaled: Boolean,
    ) =
        emitCapacity(
            operation = operation,
            kind = kind.toDiagnosticCapacityKind(),
            command = command,
            windowSize = windowSize,
            pageScaled = pageScaled,
        )

    private fun emitPageCapacity(
        operation: DiagnosticOperation,
        kind: ActiveSyncPageCapacityKind,
        windowSize: Int,
    ) =
        emitCapacity(
            operation = operation,
            kind =
                when (kind) {
                    ActiveSyncPageCapacityKind.HTTP_RESPONSE_BYTES -> DiagnosticCapacityKind.HTTP_RESPONSE_BYTES
                },
            command = DiagnosticActiveSyncCommand.SYNC,
            windowSize = windowSize,
            pageScaled = true,
        )

    private fun emitCapacity(
        operation: DiagnosticOperation,
        kind: DiagnosticCapacityKind,
        command: DiagnosticActiveSyncCommand?,
        windowSize: Int?,
        pageScaled: Boolean,
    ) {
        val canReduce = pageScaled && windowSize != null && windowSize > 1
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = DiagnosticSeverity.WARN,
                component = DiagnosticComponent.ACTIVE_SYNC,
                stage = DiagnosticStage.WBXML,
                operation = operation,
                capacityKind = kind,
                capacityCommand = command,
                capacityOutcome =
                    when {
                        canReduce -> DiagnosticCapacityOutcome.WINDOW_REDUCTION
                        pageScaled -> DiagnosticCapacityOutcome.MINIMUM_WINDOW_BLOCK
                        else -> DiagnosticCapacityOutcome.TERMINAL
                    },
                capacityProblem = DiagnosticCapacityProblem.PROTOCOL_DATA,
                windowSize = windowSize,
                reducedWindowSize =
                    windowSize
                        ?.div(2)
                        ?.coerceAtLeast(1)
                        ?.takeIf { canReduce },
            ),
        )
    }

    private fun emitFolderPreparation(
        operation: DiagnosticOperation,
        outcome: FolderPreparationOutcome,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = DiagnosticSeverity.INFO,
                component = DiagnosticComponent.ACTIVE_SYNC,
                stage = DiagnosticStage.FOLDER_SYNC,
                operation = operation,
                folderPreparationOutcome = outcome,
            ),
        )
    }

    private fun invalidatePreparedFolder(
        profile: ConnectionProfile,
        operation: DiagnosticOperation,
    ) {
        sessions.acquire(profile).clearPreparedFolder()
        emitFolderPreparation(operation, FolderPreparationOutcome.INVALIDATED)
    }

    private fun WbxmlReadLimitKind.toDiagnosticCapacityKind(): DiagnosticCapacityKind =
        when (this) {
            WbxmlReadLimitKind.DOCUMENT_BYTES -> DiagnosticCapacityKind.WBXML_DOCUMENT_BYTES
            WbxmlReadLimitKind.ELEMENT_COUNT -> DiagnosticCapacityKind.WBXML_ELEMENT_COUNT
            WbxmlReadLimitKind.DEPTH -> DiagnosticCapacityKind.WBXML_DEPTH
            WbxmlReadLimitKind.INLINE_STRING_BYTES -> DiagnosticCapacityKind.WBXML_INLINE_STRING_BYTES
        }

    private fun String?.toDiagnosticCommand(): DiagnosticActiveSyncCommand? =
        when (this) {
            ActiveSyncCommand.FOLDER_SYNC.wireValue -> DiagnosticActiveSyncCommand.FOLDER_SYNC
            ActiveSyncCommand.SYNC.wireValue -> DiagnosticActiveSyncCommand.SYNC
            else -> null
        }

    private companion object {
        const val DEFAULT_CALENDAR_TYPE = 8
    }
}

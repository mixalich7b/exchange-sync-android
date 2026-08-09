package net.mixalich7b.exchangesync.infrastructure.activesync

import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPage
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPort
import net.mixalich7b.exchangesync.core.sync.RemotePageOutcome
import net.mixalich7b.exchangesync.core.sync.SyncCheckpoints
import net.mixalich7b.exchangesync.core.sync.SyncFailureKind
import net.mixalich7b.exchangesync.core.sync.SyncPageRequest
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal sealed interface ActiveSyncCapabilityOutcome {
    data class Success(
        val terminalEndpoint: HttpUrl,
        val version: ActiveSyncVersion,
    ) : ActiveSyncCapabilityOutcome

    data class Failure(
        val kind: SyncFailureKind,
        val problem: SyncProblem?,
    ) : ActiveSyncCapabilityOutcome
}

internal fun interface ActiveSyncCapabilityGateway {
    suspend fun discover(profile: ConnectionProfile): ActiveSyncCapabilityOutcome
}

internal class ActiveSyncRemoteCalendar(
    private val capabilities: ActiveSyncCapabilityGateway,
    private val commands: ActiveSyncCommandGateway,
) : RemoteCalendarPort {
    override suspend fun fetchPage(request: SyncPageRequest): RemotePageOutcome =
        fetchPage(request) {}

    override suspend fun fetchPage(
        request: SyncPageRequest,
        reportPhase: suspend (SyncPhase) -> Unit,
    ): RemotePageOutcome =
        try {
            fetchProtocolPage(request, reportPhase)
        } catch (status: ActiveSyncStatusException) {
            RemotePageOutcome.Failure(status.kind, status.problem)
        } catch (_: PrimaryCalendarSelectionException) {
            RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PRIMARY_CALENDAR)
        } catch (_: ActiveSyncProtocolDataException) {
            RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PROTOCOL_DATA)
        } catch (_: IllegalArgumentException) {
            RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PROTOCOL_DATA)
        }

    private suspend fun fetchProtocolPage(
        request: SyncPageRequest,
        reportPhase: suspend (SyncPhase) -> Unit,
    ): RemotePageOutcome {
        if (
            request.checkpoints.terminalCommandUrl?.toHttpUrlOrNull() == null ||
            request.checkpoints.protocolVersion == null
        ) {
            reportPhase(SyncPhase.DISCOVERING_PROTOCOL)
        }
        val prepared =
            when (val result = prepareCapability(request)) {
                is Preparation.Failure -> return result.outcome
                is Preparation.Success -> result.value
            }
        reportPhase(SyncPhase.DISCOVERING_FOLDERS)
        val folder =
            when (val result = prepareFolder(request, prepared)) {
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
                )
            when (priming) {
                is ActiveSyncCommandOutcome.Failure -> return priming.toRemoteFailure()
                is ActiveSyncCommandOutcome.Success -> {
                    val primed = CalendarSyncCodec.decodeResponse(priming.body, folder.primaryCalendarId)
                    if (primed.commands.isNotEmpty() || primed.moreAvailable || primed.syncKey == "0") {
                        throw ActiveSyncProtocolDataException("Invalid Calendar Sync priming response")
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
            )
        return when (calendarOutcome) {
            is ActiveSyncCommandOutcome.Failure -> calendarOutcome.toRemoteFailure()
            is ActiveSyncCommandOutcome.Success -> {
                val page =
                    if (calendarOutcome.body.isEmpty()) {
                        RawCalendarSyncPage(
                            syncKey = collectionKey,
                            commands = emptyList(),
                            moreAvailable = false,
                        )
                    } else {
                        CalendarSyncCodec.decodeResponse(calendarOutcome.body, folder.primaryCalendarId)
                    }
                if (page.moreAvailable && page.syncKey == collectionKey) {
                    throw ActiveSyncProtocolDataException("Calendar SyncKey did not advance while more changes are available")
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

    private suspend fun prepareCapability(request: SyncPageRequest): Preparation<PreparedCapability> {
        val savedUrl = request.checkpoints.terminalCommandUrl?.toHttpUrlOrNull()
        val savedVersion = request.checkpoints.protocolVersion
        if (savedUrl != null && savedVersion != null) {
            return Preparation.Success(PreparedCapability(savedUrl, savedVersion))
        }
        return when (val outcome = capabilities.discover(request.profile)) {
            is ActiveSyncCapabilityOutcome.Success ->
                Preparation.Success(PreparedCapability(outcome.terminalEndpoint, outcome.version))
            is ActiveSyncCapabilityOutcome.Failure ->
                Preparation.Failure(RemotePageOutcome.Failure(outcome.kind, outcome.problem))
        }
    }

    private suspend fun prepareFolder(
        request: SyncPageRequest,
        capability: PreparedCapability,
    ): Preparation<PreparedFolder> {
        val savedPrimary = request.checkpoints.primaryCalendarId
        val savedFolderKey = request.checkpoints.folderSyncKey
        val folderKey = savedFolderKey ?: "0"
        val outcome =
            commands.execute(
                profile = request.profile,
                endpoint = capability.endpoint,
                command = ActiveSyncCommand.FOLDER_SYNC,
                deviceId = request.deviceId,
                version = capability.version,
                body = FolderSyncCodec.encodeRequest(folderKey),
            )
        return when (outcome) {
            is ActiveSyncCommandOutcome.Failure -> {
                Preparation.Failure(outcome.toRemoteFailure())
            }
            is ActiveSyncCommandOutcome.Success -> {
                val page = FolderSyncCodec.decodeResponse(outcome.body)
                val state =
                    if (savedPrimary != null) {
                        reconcileRetainedPrimary(savedPrimary, folderKey, page)
                    } else {
                        FolderHierarchyReducer.apply(FolderHierarchyState("0", emptyMap()), page)
                    }
                val primary = PrimaryCalendarSelector.select(state.folders.values)
                Preparation.Success(PreparedFolder(page.syncKey, primary.serverId, outcome.terminalEndpoint))
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

    private companion object {
        const val DEFAULT_CALENDAR_TYPE = 8
    }
}

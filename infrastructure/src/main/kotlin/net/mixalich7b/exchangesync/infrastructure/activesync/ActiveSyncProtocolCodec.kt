package net.mixalich7b.exchangesync.infrastructure.activesync

import net.mixalich7b.exchangesync.core.sync.SyncFailureKind
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSyncBase
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.Calendar
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSync
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.FolderHierarchy
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlElement
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlReader
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlReadLimitException
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlReadLimitKind
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlTag
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlWriter

internal enum class ActiveSyncValidationReason {
    MALFORMED_WBXML,
    UNEXPECTED_ROOT,
    MISSING_REQUIRED_VALUE,
    EMPTY_VALUE,
    INVALID_STATUS,
    INVALID_NUMBER,
    COUNT_MISMATCH,
    UNSUPPORTED_COMMAND,
    UNKNOWN_FOLDER,
    COLLECTION_MISMATCH,
    MISSING_APPLICATION_DATA,
    INVALID_APPLICATION_DATA,
    MISSING_START,
    MISSING_END,
    INVALID_TIME_RANGE,
    INVALID_ALL_DAY,
    INVALID_RECURRENCE,
    INVALID_ATTENDEE,
    INVALID_MEETING_RESPONSE,
    INVALID_TIME_ZONE,
    INVALID_VALUE,
    NON_ADVANCING_SYNC_KEY,
    INVALID_PRIMING_RESPONSE,
    PROTOCOL_STRUCTURE,
}

internal class ActiveSyncProtocolDataException(
    message: String,
    val reason: ActiveSyncValidationReason = reasonFor(message),
    val commandKind: String? = null,
    val serverId: String? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    fun withContext(
        commandKind: String,
        serverId: String? = this.serverId,
    ): ActiveSyncProtocolDataException =
        ActiveSyncProtocolDataException(
            message = message.orEmpty(),
            reason = reason,
            commandKind = commandKind,
            serverId = serverId,
            cause = this,
        )

    private companion object {
        fun reasonFor(message: String): ActiveSyncValidationReason =
            when {
                "Malformed ActiveSync WBXML" in message -> ActiveSyncValidationReason.MALFORMED_WBXML
                "Unexpected ActiveSync response root" in message -> ActiveSyncValidationReason.UNEXPECTED_ROOT
                "Required ActiveSync value is missing" in message -> ActiveSyncValidationReason.MISSING_REQUIRED_VALUE
                "ActiveSync value is empty" in message -> ActiveSyncValidationReason.EMPTY_VALUE
                "command status" in message -> ActiveSyncValidationReason.INVALID_STATUS
                "change count" in message -> ActiveSyncValidationReason.COUNT_MISMATCH
                "Unsupported" in message -> ActiveSyncValidationReason.UNSUPPORTED_COMMAND
                "unknown folder" in message -> ActiveSyncValidationReason.UNKNOWN_FOLDER
                "collection is missing or ambiguous" in message -> ActiveSyncValidationReason.COLLECTION_MISMATCH
                "no application data" in message -> ActiveSyncValidationReason.MISSING_APPLICATION_DATA
                "application data" in message -> ActiveSyncValidationReason.INVALID_APPLICATION_DATA
                "start is missing" in message -> ActiveSyncValidationReason.MISSING_START
                "end is missing" in message -> ActiveSyncValidationReason.MISSING_END
                "time range" in message -> ActiveSyncValidationReason.INVALID_TIME_RANGE
                "All-day" in message -> ActiveSyncValidationReason.INVALID_ALL_DAY
                "recurrence" in message.lowercase() -> ActiveSyncValidationReason.INVALID_RECURRENCE
                "attendee" in message.lowercase() -> ActiveSyncValidationReason.INVALID_ATTENDEE
                "meeting response" in message.lowercase() -> ActiveSyncValidationReason.INVALID_MEETING_RESPONSE
                "time zone" in message.lowercase() -> ActiveSyncValidationReason.INVALID_TIME_ZONE
                "SyncKey did not advance" in message -> ActiveSyncValidationReason.NON_ADVANCING_SYNC_KEY
                "priming response" in message -> ActiveSyncValidationReason.INVALID_PRIMING_RESPONSE
                message.startsWith("Invalid ") -> ActiveSyncValidationReason.INVALID_VALUE
                else -> ActiveSyncValidationReason.PROTOCOL_STRUCTURE
            }
    }
}

internal class ActiveSyncWbxmlReadLimitException(
    val kind: WbxmlReadLimitKind,
    val commandKind: String? = null,
    cause: Throwable? = null,
) : IllegalArgumentException("ActiveSync WBXML read limit exceeded", cause) {
    fun withContext(commandKind: String): ActiveSyncWbxmlReadLimitException =
        ActiveSyncWbxmlReadLimitException(
            kind = kind,
            commandKind = commandKind,
            cause = this,
        )
}

internal class PrimaryCalendarSelectionException : IllegalArgumentException("Primary Calendar folder is ambiguous")

internal class ActiveSyncStatusException(
    val kind: SyncFailureKind,
    val problem: SyncProblem?,
    val commandKind: String? = null,
    cause: Throwable? = null,
) : IllegalArgumentException("ActiveSync command returned a failure status", cause) {
    fun withCommand(command: String): ActiveSyncStatusException =
        if (commandKind == command) this else ActiveSyncStatusException(kind, problem, command, this)
}

internal data class ActiveSyncFolder(
    val serverId: String,
    val parentId: String,
    val displayName: String,
    val type: Int,
)

internal sealed interface FolderHierarchyChange {
    data class Add(val folder: ActiveSyncFolder) : FolderHierarchyChange

    data class Update(
        val serverId: String,
        val parentId: String?,
        val displayName: String?,
        val type: Int?,
    ) : FolderHierarchyChange

    data class Delete(val serverId: String) : FolderHierarchyChange
}

internal data class FolderSyncPage(
    val syncKey: String,
    val changes: List<FolderHierarchyChange>,
)

internal data class FolderHierarchyState(
    val syncKey: String,
    val folders: Map<String, ActiveSyncFolder>,
)

internal object FolderSyncCodec {
    fun encodeRequest(syncKey: String): ByteArray {
        require(syncKey.isNotBlank())
        return WbxmlWriter().write(
            WbxmlElement(
                FolderHierarchy.FOLDER_SYNC,
                children = listOf(WbxmlElement(FolderHierarchy.SYNC_KEY, text = syncKey)),
            ),
        )
    }

    fun decodeResponse(body: ByteArray): FolderSyncPage {
        val root = readRoot(body, FolderHierarchy.FOLDER_SYNC)
        root.requiredStatus(FolderHierarchy.STATUS).validateFolderSyncStatus()
        val syncKey = root.requiredText(FolderHierarchy.SYNC_KEY)
        val changesElement = root.child(FolderHierarchy.CHANGES)
        val changes = changesElement?.children.orEmpty().filter { child -> child.tag in changeTags }.map(::parseChange)
        changesElement?.child(FolderHierarchy.COUNT)?.text?.parseNonNegative("folder change count")?.let { count ->
            if (count != changes.size) throw ActiveSyncProtocolDataException("Folder change count does not match")
        }
        return FolderSyncPage(syncKey, changes)
    }

    private fun parseChange(element: WbxmlElement): FolderHierarchyChange {
        val serverId = element.requiredText(FolderHierarchy.SERVER_ID)
        return when (element.tag) {
            FolderHierarchy.ADD ->
                FolderHierarchyChange.Add(
                    ActiveSyncFolder(
                        serverId = serverId,
                        parentId = element.requiredText(FolderHierarchy.PARENT_ID),
                        displayName = element.requiredText(FolderHierarchy.DISPLAY_NAME),
                        type = element.requiredText(FolderHierarchy.TYPE).parseNonNegative("folder type"),
                    ),
                )
            FolderHierarchy.UPDATE ->
                FolderHierarchyChange.Update(
                    serverId = serverId,
                    parentId = element.optionalNonBlankText(FolderHierarchy.PARENT_ID),
                    displayName = element.optionalNonBlankText(FolderHierarchy.DISPLAY_NAME),
                    type = element.optionalNonBlankText(FolderHierarchy.TYPE)?.parseNonNegative("folder type"),
                )
            FolderHierarchy.DELETE -> FolderHierarchyChange.Delete(serverId)
            else -> throw ActiveSyncProtocolDataException("Unsupported folder hierarchy change")
        }
    }

    private val changeTags = setOf(FolderHierarchy.ADD, FolderHierarchy.UPDATE, FolderHierarchy.DELETE)
}

internal object FolderHierarchyReducer {
    fun apply(state: FolderHierarchyState, page: FolderSyncPage): FolderHierarchyState {
        val folders = state.folders.toMutableMap()
        page.changes.forEach { change ->
            when (change) {
                is FolderHierarchyChange.Add -> folders[change.folder.serverId] = change.folder
                is FolderHierarchyChange.Update -> {
                    val existing = folders[change.serverId]
                        ?: throw ActiveSyncProtocolDataException("Folder update refers to an unknown folder")
                    folders[change.serverId] =
                        existing.copy(
                            parentId = change.parentId ?: existing.parentId,
                            displayName = change.displayName ?: existing.displayName,
                            type = change.type ?: existing.type,
                        )
                }
                is FolderHierarchyChange.Delete -> folders.remove(change.serverId)
            }
        }
        return FolderHierarchyState(page.syncKey, folders.toMap())
    }
}

internal object PrimaryCalendarSelector {
    private const val DEFAULT_CALENDAR_FOLDER_TYPE = 8

    fun select(folders: Collection<ActiveSyncFolder>): ActiveSyncFolder {
        val matches = folders.filter { folder -> folder.type == DEFAULT_CALENDAR_FOLDER_TYPE }
        if (matches.size != 1) throw PrimaryCalendarSelectionException()
        return matches.single()
    }
}

internal enum class RawCalendarCommandKind {
    ADD,
    CHANGE,
    DELETE,
    SOFT_DELETE,
}

internal data class RawCalendarCommand(
    val kind: RawCalendarCommandKind,
    val serverId: String,
    val applicationData: WbxmlElement?,
)

internal data class RawCalendarSyncPage(
    val syncKey: String,
    val commands: List<RawCalendarCommand>,
    val moreAvailable: Boolean,
)

internal object CalendarSyncCodec {
    fun encodeRequest(
        syncKey: String,
        collectionId: String,
        windowSize: Int,
        getChanges: Boolean,
        version: ActiveSyncVersion,
    ): ByteArray {
        require(syncKey.isNotBlank())
        require(collectionId.isNotBlank())
        require(windowSize in 1..100)
        val collectionChildren =
            mutableListOf(
                WbxmlElement(AirSync.SYNC_KEY, text = syncKey),
                WbxmlElement(AirSync.COLLECTION_ID, text = collectionId),
            )
        if (version == ActiveSyncVersion.V14_0 || version == ActiveSyncVersion.V14_1) {
            collectionChildren += WbxmlElement(AirSync.SUPPORTED, children = supportedCalendarProperties)
        }
        if (getChanges) {
            collectionChildren += WbxmlElement(AirSync.GET_CHANGES)
            collectionChildren += WbxmlElement(AirSync.WINDOW_SIZE, text = windowSize.toString())
            collectionChildren +=
                WbxmlElement(
                    AirSync.OPTIONS,
                    children =
                        listOf(
                            WbxmlElement(
                                AirSyncBase.BODY_PREFERENCE,
                                children =
                                    listOf(
                                        WbxmlElement(AirSyncBase.TYPE, text = "1"),
                                        WbxmlElement(AirSyncBase.TRUNCATION_SIZE, text = BODY_TRUNCATION_SIZE.toString()),
                                    ),
                            ),
                        ),
                )
        }
        val collection =
            WbxmlElement(
                AirSync.COLLECTION,
                children = collectionChildren,
            )
        return WbxmlWriter().write(
            WbxmlElement(
                AirSync.SYNC,
                children =
                    listOf(
                        WbxmlElement(AirSync.COLLECTIONS, children = listOf(collection)),
                    ),
            ),
        )
    }

    fun decodeResponse(body: ByteArray, expectedCollectionId: String): RawCalendarSyncPage {
        val root = readRoot(body, AirSync.SYNC)
        root.child(AirSync.STATUS)?.text?.parseStatus()?.validateSyncStatus()
        val collections = root.child(AirSync.COLLECTIONS)?.children(AirSync.COLLECTION).orEmpty()
        val matching = collections.filter { collection ->
            collection.child(AirSync.COLLECTION_ID)?.text == expectedCollectionId
        }
        if (matching.size != 1) throw ActiveSyncProtocolDataException("Calendar Sync collection is missing or ambiguous")
        val collection = matching.single()
        collection.requiredStatus(AirSync.STATUS).validateSyncStatus()
        val syncKey = collection.requiredText(AirSync.SYNC_KEY)
        val commands = collection.child(AirSync.COMMANDS)?.children.orEmpty().map(::parseCalendarCommand)
        return RawCalendarSyncPage(
            syncKey = syncKey,
            commands = commands,
            moreAvailable = collection.child(AirSync.MORE_AVAILABLE) != null,
        )
    }

    private fun parseCalendarCommand(element: WbxmlElement): RawCalendarCommand {
        val kind =
            when (element.tag) {
                AirSync.ADD -> RawCalendarCommandKind.ADD
                AirSync.CHANGE -> RawCalendarCommandKind.CHANGE
                AirSync.DELETE -> RawCalendarCommandKind.DELETE
                AirSync.SOFT_DELETE -> RawCalendarCommandKind.SOFT_DELETE
                else -> throw ActiveSyncProtocolDataException("Unsupported Calendar Sync command")
            }
        val applicationData = element.child(AirSync.APPLICATION_DATA)
        if (kind == RawCalendarCommandKind.ADD && applicationData == null) {
            throw ActiveSyncProtocolDataException("Calendar Add has no application data")
        }
        return RawCalendarCommand(
            kind = kind,
            serverId = element.requiredText(AirSync.SERVER_ID),
            applicationData = applicationData,
        )
    }

    private val supportedCalendarProperties =
        listOf(
            Calendar.TIMEZONE,
            Calendar.ALL_DAY_EVENT,
            Calendar.ATTENDEES,
            Calendar.BUSY_STATUS,
            Calendar.END_TIME,
            Calendar.EXCEPTIONS,
            Calendar.LOCATION,
            Calendar.MEETING_STATUS,
            Calendar.ORGANIZER_EMAIL,
            Calendar.ORGANIZER_NAME,
            Calendar.RECURRENCE,
            Calendar.REMINDER,
            Calendar.SENSITIVITY,
            Calendar.SUBJECT,
            Calendar.START_TIME,
            Calendar.UID,
            Calendar.RESPONSE_REQUESTED,
            Calendar.RESPONSE_TYPE,
            AirSyncBase.BODY,
        ).map { tag -> WbxmlElement(tag) }

    private const val BODY_TRUNCATION_SIZE = 32_768
}

private fun readRoot(body: ByteArray, expectedTag: WbxmlTag): WbxmlElement =
    try {
        WbxmlReader().read(body).also { root ->
            if (root.tag != expectedTag) throw ActiveSyncProtocolDataException("Unexpected ActiveSync response root")
        }
    } catch (error: WbxmlReadLimitException) {
        throw ActiveSyncWbxmlReadLimitException(error.kind, cause = error)
    } catch (error: ActiveSyncProtocolDataException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw ActiveSyncProtocolDataException(
            message = "Malformed ActiveSync WBXML response",
            reason = ActiveSyncValidationReason.MALFORMED_WBXML,
            cause = error,
        )
    }

private fun WbxmlElement.requiredStatus(tag: WbxmlTag): Int = requiredText(tag).parseStatus()

private fun WbxmlElement.requiredText(tag: WbxmlTag): String =
    child(tag)?.text?.takeIf(String::isNotBlank)
        ?: throw ActiveSyncProtocolDataException("Required ActiveSync value is missing")

private fun WbxmlElement.optionalNonBlankText(tag: WbxmlTag): String? {
    val child = child(tag) ?: return null
    return child.text?.takeIf(String::isNotBlank)
        ?: throw ActiveSyncProtocolDataException("ActiveSync value is empty")
}

private fun String.parseNonNegative(label: String): Int {
    val parsed = toIntOrNull() ?: throw ActiveSyncProtocolDataException("Invalid $label")
    if (parsed < 0) throw ActiveSyncProtocolDataException("Invalid $label")
    return parsed
}

private fun String.parseStatus(): Int =
    toIntOrNull()?.takeIf { status -> status > 0 }
        ?: throw ActiveSyncProtocolDataException("Invalid ActiveSync command status")

private fun Int.validateFolderSyncStatus() {
    when (this) {
        1 -> Unit
        6, 11 -> throw ActiveSyncStatusException(SyncFailureKind.TRANSIENT, null)
        9 -> throw ActiveSyncStatusException(SyncFailureKind.INVALID_KEY, null)
        else -> throw commonStatusFailure()
    }
}

private fun Int.validateSyncStatus() {
    when (this) {
        1 -> Unit
        3, 12 -> throw ActiveSyncStatusException(SyncFailureKind.INVALID_KEY, null)
        5, 16 -> throw ActiveSyncStatusException(SyncFailureKind.TRANSIENT, null)
        else -> throw commonStatusFailure()
    }
}

private fun Int.commonStatusFailure(): ActiveSyncStatusException =
    when (this) {
        110, 111, 114, 133 -> ActiveSyncStatusException(SyncFailureKind.TRANSIENT, null)
        112, in 125..131 -> ActiveSyncStatusException(SyncFailureKind.CRITICAL, SyncProblem.ACCESS)
        in 139..144 ->
            ActiveSyncStatusException(SyncFailureKind.CRITICAL, SyncProblem.UNSUPPORTED_PROVISIONING)
        else -> ActiveSyncStatusException(SyncFailureKind.CRITICAL, SyncProblem.PROTOCOL_DATA)
    }

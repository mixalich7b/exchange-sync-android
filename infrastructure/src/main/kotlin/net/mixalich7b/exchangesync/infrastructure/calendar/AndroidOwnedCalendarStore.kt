package net.mixalich7b.exchangesync.infrastructure.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.Events
import android.net.Uri

internal enum class CalendarDeleteTarget {
    COLLECTION,
}

internal data class OwnedCalendarDeleteRequest(
    val target: CalendarDeleteTarget,
    val callerIsSyncAdapter: Boolean,
    val accountNameParameter: String,
    val accountTypeParameter: String,
    val selection: String,
    val selectionArguments: List<String>,
)

internal fun interface OwnedCalendarDeleteOperation {
    fun execute(request: OwnedCalendarDeleteRequest): Int
}

internal class AndroidOwnedCalendarStore private constructor(
    private val resolver: ContentResolver?,
    private val deleteOperation: OwnedCalendarDeleteOperation,
) : OwnedCalendarStore {
    constructor(contentResolver: ContentResolver) : this(
        resolver = contentResolver,
        deleteOperation =
            OwnedCalendarDeleteOperation { request ->
                contentResolver.delete(
                    request.target.contentUri().withSyncAdapterParameters(request),
                    request.selection,
                    request.selectionArguments.toTypedArray(),
                )
            },
    )

    internal constructor(deleteOperation: OwnedCalendarDeleteOperation) : this(
        resolver = null,
        deleteOperation = deleteOperation,
    )

    override fun queryOwned(): List<OwnedCalendarRow> {
        val cursor =
            requireResolver().query(
                Calendars.CONTENT_URI,
                PROJECTION,
                OWNERSHIP_SELECTION,
                OWNERSHIP_ARGUMENTS,
                null,
            ) ?: throw OwnedCalendarProviderException("Calendar Provider query failed")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        OwnedCalendarRow(
                            id = it.getLong(it.getColumnIndexOrThrow(Calendars._ID)),
                            accountName = it.getString(it.getColumnIndexOrThrow(Calendars.ACCOUNT_NAME)),
                            accountType = it.getString(it.getColumnIndexOrThrow(Calendars.ACCOUNT_TYPE)),
                            internalName = it.getString(it.getColumnIndexOrThrow(Calendars.NAME)),
                            displayName = it.getString(it.getColumnIndexOrThrow(Calendars.CALENDAR_DISPLAY_NAME)),
                            ownerEmail = it.getString(it.getColumnIndexOrThrow(Calendars.OWNER_ACCOUNT)).orEmpty(),
                            color = it.getInt(it.getColumnIndexOrThrow(Calendars.CALENDAR_COLOR)),
                        ),
                    )
                }
            }
        }
    }

    override fun create(definition: OwnedCalendarDefinition): Long {
        val values =
            ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, definition.accountName)
                put(Calendars.ACCOUNT_TYPE, definition.accountType)
                put(Calendars.NAME, definition.internalName)
                put(Calendars.CALENDAR_DISPLAY_NAME, definition.displayName)
                put(Calendars.OWNER_ACCOUNT, definition.ownerEmail)
                put(Calendars.CALENDAR_COLOR, definition.color)
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
                put(Calendars.VISIBLE, definition.visible.asInt())
                put(Calendars.SYNC_EVENTS, definition.syncEvents.asInt())
                put(Calendars.CAN_MODIFY_TIME_ZONE, 0)
                put(Calendars.CAN_ORGANIZER_RESPOND, 0)
                put(Calendars.MAX_REMINDERS, 1)
                put(Calendars.ALLOWED_REMINDERS, Reminders.METHOD_ALERT.toString())
                put(
                    Calendars.ALLOWED_AVAILABILITY,
                    listOf(Events.AVAILABILITY_FREE, Events.AVAILABILITY_BUSY, Events.AVAILABILITY_TENTATIVE)
                        .joinToString(","),
                )
            }
        val inserted = requireResolver().insert(syncAdapterUri(Calendars.CONTENT_URI), values)
            ?: throw OwnedCalendarProviderException("Calendar Provider insert failed")
        return ContentUris.parseId(inserted)
    }

    override fun deleteOwned(calendarId: Long): Boolean =
        deleteOperation.execute(
            OwnedCalendarDeleteRequest(
                target = CalendarDeleteTarget.COLLECTION,
                callerIsSyncAdapter = true,
                accountNameParameter = OwnedCalendarIdentity.ACCOUNT_NAME,
                accountTypeParameter = OwnedCalendarIdentity.ACCOUNT_TYPE,
                selection = DELETE_SELECTION,
                selectionArguments =
                    listOf(
                        calendarId.toString(),
                        OwnedCalendarIdentity.ACCOUNT_NAME,
                        OwnedCalendarIdentity.ACCOUNT_TYPE,
                        OwnedCalendarIdentity.INTERNAL_NAME,
                    ),
            ),
        ) > 0

    private fun syncAdapterUri(uri: Uri): Uri =
        uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, OwnedCalendarIdentity.ACCOUNT_NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, OwnedCalendarIdentity.ACCOUNT_TYPE)
            .build()

    private fun requireResolver(): ContentResolver = checkNotNull(resolver)

    private fun Boolean.asInt(): Int = if (this) 1 else 0

    private companion object {
        val PROJECTION =
            arrayOf(
                Calendars._ID,
                Calendars.ACCOUNT_NAME,
                Calendars.ACCOUNT_TYPE,
                Calendars.NAME,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.OWNER_ACCOUNT,
                Calendars.CALENDAR_COLOR,
            )
        const val OWNERSHIP_SELECTION =
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=?"
        const val DELETE_SELECTION = "${Calendars._ID}=? AND $OWNERSHIP_SELECTION"
        val OWNERSHIP_ARGUMENTS =
            arrayOf(
                OwnedCalendarIdentity.ACCOUNT_NAME,
                OwnedCalendarIdentity.ACCOUNT_TYPE,
                OwnedCalendarIdentity.INTERNAL_NAME,
            )
    }
}

private fun CalendarDeleteTarget.contentUri(): Uri =
    when (this) {
        CalendarDeleteTarget.COLLECTION -> Calendars.CONTENT_URI
    }

private fun Uri.withSyncAdapterParameters(request: OwnedCalendarDeleteRequest): Uri =
    buildUpon()
        .appendQueryParameter(
            CalendarContract.CALLER_IS_SYNCADAPTER,
            request.callerIsSyncAdapter.toString(),
        )
        .appendQueryParameter(Calendars.ACCOUNT_NAME, request.accountNameParameter)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, request.accountTypeParameter)
        .build()

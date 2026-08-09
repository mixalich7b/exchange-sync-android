package net.mixalich7b.exchangesync.infrastructure.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.Events
import android.net.Uri

internal class AndroidOwnedCalendarStore(
    private val contentResolver: ContentResolver,
) : OwnedCalendarStore {
    override fun queryOwned(): List<OwnedCalendarRow> {
        val cursor =
            contentResolver.query(
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
        val inserted = contentResolver.insert(syncAdapterUri(Calendars.CONTENT_URI), values)
            ?: throw OwnedCalendarProviderException("Calendar Provider insert failed")
        return ContentUris.parseId(inserted)
    }

    override fun deleteOwned(calendarId: Long): Boolean =
        contentResolver.delete(
            syncAdapterUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId)),
            OWNERSHIP_SELECTION,
            OWNERSHIP_ARGUMENTS,
        ) > 0

    private fun syncAdapterUri(uri: Uri): Uri =
        uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, OwnedCalendarIdentity.ACCOUNT_NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, OwnedCalendarIdentity.ACCOUNT_TYPE)
            .build()

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
        val OWNERSHIP_ARGUMENTS =
            arrayOf(
                OwnedCalendarIdentity.ACCOUNT_NAME,
                OwnedCalendarIdentity.ACCOUNT_TYPE,
                OwnedCalendarIdentity.INTERNAL_NAME,
            )
    }
}

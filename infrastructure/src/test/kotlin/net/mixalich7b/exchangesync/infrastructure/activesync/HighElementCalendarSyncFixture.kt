package net.mixalich7b.exchangesync.infrastructure.activesync

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSync
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSyncBase
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.Calendar
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlElement
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlLimits
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlTag
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlWriter

internal object HighElementCalendarSyncFixture {
    const val COLLECTION_ID: String = "primary-calendar"
    const val RETURNED_SYNC_KEY: String = "collection-2"
    const val SERVER_ID: String = "high-fanout-series"
    const val PROFILE_EMAIL: String = "calendar@example.test"
    const val ATTENDEE_COUNT: Int = 201
    const val CHANGED_EXCEPTION_COUNT: Int = 10
    const val DELETED_EXCEPTION_COUNT: Int = 10
    const val TOTAL_EXCEPTION_COUNT: Int = CHANGED_EXCEPTION_COUNT + DELETED_EXCEPTION_COUNT
    const val NEW_MAX_ELEMENTS: Int = 256_000

    fun responseBytes(): ByteArray =
        WbxmlWriter(WbxmlLimits(maxElements = NEW_MAX_ELEMENTS)).write(responseElement())

    fun responseElement(): WbxmlElement =
        element(
            AirSync.SYNC,
            element(
                AirSync.COLLECTIONS,
                element(
                    AirSync.COLLECTION,
                    text(AirSync.SYNC_KEY, RETURNED_SYNC_KEY),
                    text(AirSync.COLLECTION_ID, COLLECTION_ID),
                    text(AirSync.STATUS, "1"),
                    element(
                        AirSync.COMMANDS,
                        element(
                            AirSync.ADD,
                            text(AirSync.SERVER_ID, SERVER_ID),
                            applicationData(),
                        ),
                    ),
                ),
            ),
        )

    fun mutation(): ActiveSyncCalendarMutation.Upsert =
        ActiveSyncCalendarApplicationParser.parse(
            command = RawCalendarCommand(RawCalendarCommandKind.ADD, SERVER_ID, applicationData()),
            profileEmail = PROFILE_EMAIL,
            version = ActiveSyncVersion.V16_1,
        ) as ActiveSyncCalendarMutation.Upsert

    fun elementCount(): Int = responseElement().elementCount()

    fun maximumDepth(): Int = responseElement().maximumDepth()

    fun maximumInlineStringBytes(): Int = responseElement().maximumInlineStringBytes()

    private fun applicationData(): WbxmlElement =
        element(
            AirSync.APPLICATION_DATA,
            text(Calendar.SUBJECT, "High fanout recurring meeting"),
            text(Calendar.START_TIME, "20260809T090000Z"),
            text(Calendar.END_TIME, "20260809T100000Z"),
            text(Calendar.ALL_DAY_EVENT, "0"),
            text(Calendar.ORGANIZER_EMAIL, "organizer@example.test"),
            text(Calendar.ORGANIZER_NAME, "Organizer"),
            text(Calendar.MEETING_STATUS, "3"),
            text(Calendar.RESPONSE_TYPE, "3"),
            element(
                Calendar.RECURRENCE,
                text(Calendar.TYPE, "0"),
                text(Calendar.INTERVAL, "1"),
            ),
            attendeeList(),
            element(
                Calendar.EXCEPTIONS,
                *(changedExceptions() + deletedExceptions()).toTypedArray(),
            ),
        )

    private fun changedExceptions(): List<WbxmlElement> =
        List(CHANGED_EXCEPTION_COUNT) { index ->
            val instance = exceptionInstant(index)
            element(
                Calendar.EXCEPTION,
                text(AirSyncBase.INSTANCE_ID, instance.toCompactUtc()),
                text(Calendar.SUBJECT, "Changed occurrence $index"),
                attendeeList(),
            )
        }

    private fun deletedExceptions(): List<WbxmlElement> =
        List(DELETED_EXCEPTION_COUNT) { index ->
            val instance = exceptionInstant(CHANGED_EXCEPTION_COUNT + index)
            element(
                Calendar.EXCEPTION,
                text(AirSyncBase.INSTANCE_ID, instance.toCompactUtc()),
                text(Calendar.DELETED, "1"),
                attendeeList(),
            )
        }

    private fun attendeeList(): WbxmlElement =
        element(
            Calendar.ATTENDEES,
            *List(ATTENDEE_COUNT) { index -> attendee(index) }.toTypedArray(),
        )

    private fun attendee(index: Int): WbxmlElement =
        element(
            Calendar.ATTENDEE,
            text(Calendar.EMAIL, if (index == 0) PROFILE_EMAIL else "guest-$index@example.test"),
            text(Calendar.NAME, if (index == 0) "Current User" else "Guest $index"),
            text(Calendar.ATTENDEE_STATUS, "3"),
            text(Calendar.ATTENDEE_TYPE, "1"),
        )

    private fun exceptionInstant(index: Int): Instant =
        Instant.parse("2026-08-10T09:00:00Z").plusSeconds(index * 86_400L)

    private fun Instant.toCompactUtc(): String = COMPACT_UTC.format(this)

    private fun WbxmlElement.elementCount(): Int = 1 + children.sumOf { child -> child.elementCount() }

    private fun WbxmlElement.maximumDepth(): Int =
        1 + (children.maxOfOrNull { child -> child.maximumDepth() } ?: 0)

    private fun WbxmlElement.maximumInlineStringBytes(): Int =
        maxOf(
            text?.toByteArray(Charsets.UTF_8)?.size ?: 0,
            children.maxOfOrNull { child -> child.maximumInlineStringBytes() } ?: 0,
        )

    private fun element(tag: WbxmlTag, vararg children: WbxmlElement): WbxmlElement =
        WbxmlElement(tag, children = children.toList())

    private fun text(tag: WbxmlTag, value: String): WbxmlElement = WbxmlElement(tag, text = value)

    private val COMPACT_UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
}

internal fun overDefaultElementCapacityResponse(): ByteArray =
    WbxmlWriter(WbxmlLimits(maxElements = HighElementCalendarSyncFixture.NEW_MAX_ELEMENTS + 1)).write(
        WbxmlElement(
            AirSync.SYNC,
            children =
                List(HighElementCalendarSyncFixture.NEW_MAX_ELEMENTS) {
                    WbxmlElement(AirSync.MORE_AVAILABLE)
                },
        ),
    )

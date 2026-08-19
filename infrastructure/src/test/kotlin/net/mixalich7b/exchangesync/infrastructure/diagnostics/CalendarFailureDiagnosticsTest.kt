package net.mixalich7b.exchangesync.infrastructure.diagnostics

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarFailureDiagnosticsTest {
    @Test
    fun `calendar snapshot renders field state and each typed value`() {
        val snapshot =
            calendarSnapshot(
                fields =
                    listOf(
                        field(
                            DiagnosticCalendarFieldSource.RESPONSE,
                            DiagnosticCalendarField.RESPONSE_TYPE,
                            DiagnosticFieldState.PRESENT,
                            DiagnosticFieldValue.EnumName("ACCEPTED"),
                        ),
                        field(
                            DiagnosticCalendarFieldSource.RESPONSE,
                            DiagnosticCalendarField.LOCATION,
                            DiagnosticFieldState.EMPTY,
                        ),
                        field(
                            DiagnosticCalendarFieldSource.EFFECTIVE,
                            DiagnosticCalendarField.START,
                            DiagnosticFieldState.PRESENT,
                            DiagnosticFieldValue.Timestamp(Instant.parse("2026-08-19T09:00:00Z")),
                        ),
                        field(
                            DiagnosticCalendarFieldSource.EFFECTIVE,
                            DiagnosticCalendarField.ALL_DAY,
                            DiagnosticFieldState.PRESENT,
                            DiagnosticFieldValue.BooleanValue(false),
                        ),
                        field(
                            DiagnosticCalendarFieldSource.EFFECTIVE,
                            DiagnosticCalendarField.REMINDER_MINUTES,
                            DiagnosticFieldState.PRESENT,
                            DiagnosticFieldValue.IntegerValue(15),
                        ),
                        field(
                            DiagnosticCalendarFieldSource.DERIVED,
                            DiagnosticCalendarField.ATTENDEE_COUNT,
                            DiagnosticFieldState.PRESENT,
                            DiagnosticFieldValue.Count(4),
                        ),
                        field(
                            DiagnosticCalendarFieldSource.DERIVED,
                            DiagnosticCalendarField.TIME_RELATIONSHIP,
                            DiagnosticFieldState.PRESENT,
                            DiagnosticFieldValue.Relationship(DiagnosticRelationship.EQUAL),
                        ),
                    ),
            )

        val record = formatRecords(event(snapshot)).single()

        listOf(
            "response.location.state=empty",
            "response.response_type.state=present",
            "response.response_type.value=accepted",
            "effective.start.value=2026-08-19T09:00:00Z",
            "effective.all_day.value=false",
            "effective.reminder_minutes.value=15",
            "derived.attendee_count.value=4",
            "derived.time_relationship.value=equal",
        ).forEach { expected -> assertTrue(record.contains(expected), record) }
    }

    @Test
    fun `calendar snapshot ordering and chunk ordinals are deterministic and bounded`() {
        val sources = DiagnosticCalendarFieldSource.entries
        val allowedFields =
            DiagnosticCalendarField.entries.filter { field ->
                field.policy == DiagnosticCalendarFieldPolicy.FULL_VALUE
            }
        val fields =
            sources.flatMap { source ->
                allowedFields.map { calendarField ->
                    field(
                        source,
                        calendarField,
                        DiagnosticFieldState.PRESENT,
                        DiagnosticFieldValue.Text("${source.name}-${calendarField.name}-" + "x".repeat(400)),
                    )
                }
            }

        val forward = formatRecords(event(calendarSnapshot(fields)))
        val reversed = formatRecords(event(calendarSnapshot(fields.reversed())))

        assertEquals(forward, reversed)
        assertTrue(forward.size > 1, forward.toString())
        forward.forEachIndexed { index, record ->
            assertTrue(record.length <= 3_000, "${record.length}: $record")
            assertTrue(record.contains("chunk=${index + 1}/${forward.size}"), record)
            assertTrue(record.contains("rule=event_time_range_invalid"), record)
            assertTrue(record.contains("operation=sync-17"), record)
        }
    }

    @Test
    fun `each text value is sanitized and bounded before records are chunked`() {
        val marker = "Room 7 " + "z".repeat(400)
        val record =
            formatRecords(
                event(
                    calendarSnapshot(
                        listOf(
                            field(
                                DiagnosticCalendarFieldSource.RESPONSE,
                                DiagnosticCalendarField.LOCATION,
                                DiagnosticFieldState.PRESENT,
                                DiagnosticFieldValue.Text(marker),
                            ),
                        ),
                    ),
                ),
            ).single()
        val sanitizedBounded = marker.replace(' ', '_').take(256)

        assertTrue(record.contains("response.location.value=$sanitizedBounded"), record)
        assertFalse(record.contains(marker.replace(' ', '_')), record)
        assertTrue(record.length <= 3_000, record)
    }

    @Test
    fun `calendar snapshot retains exception class graph without payload-sensitive messages`() {
        val failure =
            IllegalArgumentException(
                "title-secret-marker",
                IllegalStateException("body-secret-marker"),
            )
        val formatted =
            formatRecords(
                event(calendarSnapshot(emptyList())).copy(throwable = failure),
            ).joinToString("\n")

        assertTrue(formatted.contains("IllegalArgumentException"), formatted)
        assertTrue(formatted.contains("IllegalStateException"), formatted)
        assertFalse(formatted.contains("title-secret-marker"), formatted)
        assertFalse(formatted.contains("body-secret-marker"), formatted)
    }

    @Test
    fun `maximal throwable graph remains bounded without suppressing calendar fields`() {
        val throwables = List(12) { IllegalStateException("payload-secret-marker-$it") }
        throwables.zipWithNext().forEach { (parent, cause) -> parent.initCause(cause) }
        throwables.forEachIndexed { throwableIndex, throwable ->
            throwable.stackTrace =
                Array(4) { frameIndex ->
                    StackTraceElement(
                        "calendar.failure.Class${"c".repeat(400)}",
                        "method${"m".repeat(400)}",
                        "File${"f".repeat(400)}.kt",
                        throwableIndex * 10 + frameIndex,
                    )
                }
        }
        val records =
            formatRecords(
                event(
                    calendarSnapshot(
                        listOf(
                            text(DiagnosticCalendarField.LOCATION, "retained-location-marker"),
                        ),
                    ),
                ).copy(throwable = throwables.first()),
            )

        assertTrue(records.isNotEmpty())
        records.forEach { record -> assertTrue(record.length <= 3_000, "${record.length}: $record") }
        val formatted = records.joinToString("\n")
        assertTrue(formatted.contains("retained-location-marker"), formatted)
        assertTrue(formatted.contains("IllegalStateException"), formatted)
        assertTrue(formatted.contains("truncated"), formatted)
        assertFalse(formatted.contains("payload-secret-marker"), formatted)
    }

    @Test
    fun `wide repeated suppressed graph has an independent traversal bound`() {
        val root = IllegalStateException("root-secret-marker")
        val repeated = IllegalArgumentException("suppressed-secret-marker")
        repeat(5_000) { root.addSuppressed(repeated) }

        val records =
            formatRecords(
                event(
                    calendarSnapshot(
                        listOf(text(DiagnosticCalendarField.LOCATION, "wide-graph-location-marker")),
                    ),
                ).copy(throwable = root),
            )
        val formatted = records.joinToString("\n")

        assertTrue(records.isNotEmpty())
        records.forEach { record -> assertTrue(record.length <= 3_000, "${record.length}: $record") }
        assertTrue(formatted.contains("wide-graph-location-marker"), formatted)
        assertTrue(formatted.contains("truncated"), formatted)
        assertTrue(formatted.split("cycle").size - 1 <= 32, formatted)
        assertFalse(formatted.contains("root-secret-marker"), formatted)
        assertFalse(formatted.contains("suppressed-secret-marker"), formatted)
    }

    @Test
    fun `projection and formatting failures remain non fatal`() {
        var sinkCalls = 0
        val projectionDiagnostics =
            DeviceDiagnostics(
                DeviceDiagnosticSink {
                    sinkCalls += 1
                },
            )
        val formattingDiagnostics = DeviceDiagnostics(DeviceDiagnosticSink { error("formatter failed") })

        assertDoesNotThrow {
            projectionDiagnostics.emit { error("projection failed") }
        }
        assertEquals(0, sinkCalls)
        assertDoesNotThrow {
            formattingDiagnostics.emit(event(calendarSnapshot(emptyList())))
        }
    }

    @Test
    fun `calendar privacy boundary excludes narrative people and secret markers but retains allowed values`() {
        val records =
            formatRecords(
                event(
                    calendarSnapshot(
                        listOf(
                            structural(DiagnosticCalendarField.SUBJECT, "title-secret-marker"),
                            structural(DiagnosticCalendarField.BODY, "body-secret-marker"),
                            structural(DiagnosticCalendarField.ATTENDEES, "attendee-secret-marker@example.test"),
                            structural(DiagnosticCalendarField.ORGANIZER_EMAIL, "organizer-secret-marker@example.test"),
                            structural(DiagnosticCalendarField.ORGANIZER_NAME, "organizer-name-secret-marker"),
                            text(DiagnosticCalendarField.UID, "email-secret-marker@example.test"),
                            text(DiagnosticCalendarField.PROVIDER_SYNC_ID, "domain-secret\\login-secret-marker"),
                            text(DiagnosticCalendarField.PROVIDER_TIME_ZONE, "Authorization: header-secret-marker"),
                            text(
                                DiagnosticCalendarField.LOCATION,
                                "https://exchange.test/calendar?url-secret-marker=value",
                            ),
                            text(DiagnosticCalendarField.LOCATION, "allowed-location-marker Room 7"),
                            field(
                                DiagnosticCalendarFieldSource.EFFECTIVE,
                                DiagnosticCalendarField.START,
                                DiagnosticFieldState.PRESENT,
                                DiagnosticFieldValue.Timestamp(Instant.parse("2026-08-19T09:00:00Z")),
                            ),
                            text(
                                DiagnosticCalendarField.RECURRENCE_RULE,
                                "FREQ=WEEKLY;BYDAY=MO,WE;allowed-recurrence-marker",
                            ),
                        ),
                    ),
                ),
            )
        val formatted = records.joinToString("\n")

        listOf(
            "title-secret-marker",
            "body-secret-marker",
            "attendee-secret-marker",
            "organizer-secret-marker",
            "organizer-name-secret-marker",
            "email-secret-marker",
            "domain-secret",
            "login-secret-marker",
            "header-secret-marker",
            "url-secret-marker",
        ).forEach { excluded -> assertFalse(formatted.contains(excluded), excluded) }
        listOf(
            "response.subject.state=present",
            "response.body.state=present",
            "response.attendees.state=present",
            "allowed-location-marker_Room_7",
            "2026-08-19T09:00:00Z",
            "allowed-recurrence-marker",
        ).forEach { allowed -> assertTrue(formatted.contains(allowed), formatted) }
    }

    @Test
    fun `calendar string values redact every absolute uri and query component`() {
        val formatted =
            formatRecords(
                event(
                    calendarSnapshot(
                        listOf(
                            text(
                                DiagnosticCalendarField.LOCATION,
                                "allowed-location-marker " +
                                    "http://user-secret:password-secret@host.test/path" +
                                    "?http-query-secret=value#http-fragment-secret",
                            ),
                            text(
                                DiagnosticCalendarField.UID,
                                "content://calendar/events/7" +
                                    "?content-query-secret=value#content-fragment-secret",
                            ),
                            text(
                                DiagnosticCalendarField.PROVIDER_TIME_ZONE,
                                "custom+calendar://host/path" +
                                    "?custom-query-secret=value#custom-fragment-secret",
                            ),
                            text(DiagnosticCalendarField.RECURRENCE_RULE, "urn:private:urn-secret-marker"),
                            text(DiagnosticCalendarField.LOCATION, "file:/private/file-uri-secret-marker"),
                            text(DiagnosticCalendarField.UID, "sip:sip-uri-secret-marker@host.test"),
                            text(DiagnosticCalendarField.LOCATION, "x://single-scheme-secret-marker"),
                            text(
                                DiagnosticCalendarField.PROVIDER_TIME_ZONE,
                                "verylongcustomschemename:value-with-long-scheme-secret-marker",
                            ),
                        ),
                    ),
                ),
            ).joinToString("\n")

        listOf(
            "http://",
            "content://",
            "custom+calendar://",
            "urn:private",
            "file:/",
            "sip:",
            "x://",
            "verylongcustomschemename:",
            "user-secret",
            "password-secret",
            "query-secret",
            "fragment-secret",
            "file-uri-secret-marker",
            "sip-uri-secret-marker",
            "single-scheme-secret-marker",
            "long-scheme-secret-marker",
        ).forEach { excluded -> assertFalse(formatted.contains(excluded), formatted) }
        assertTrue(formatted.contains("allowed-location-marker"), formatted)
        assertTrue(formatted.contains("<redacted-uri>"), formatted)
    }

    @Test
    fun `provider snapshot preserves a fixed-offset timezone`() {
        val snapshot =
            DiagnosticProviderOperationSnapshot(
                globalOperationIndex = 0,
                subBatchOperationIndex = 0,
                operationKind = DiagnosticProviderOperationKind.EVENT_INSERT,
                target = DiagnosticProviderTarget.EVENT,
                calendarId = 73,
                columns =
                    listOf(
                        providerColumn(DiagnosticProviderColumn.EVENT_TIME_ZONE, "GMT+03:00"),
                        providerColumn(DiagnosticProviderColumn.EVENT_END_TIME_ZONE, "GMT-18:00"),
                    ),
            )

        val formatted =
            formatRecords(
                DeviceDiagnosticEvent(
                    severity = DiagnosticSeverity.ERROR,
                    component = DiagnosticComponent.CALENDAR,
                    stage = DiagnosticStage.PROVIDER_BATCH,
                    providerOperationSnapshot = snapshot,
                ),
            ).single()

        assertTrue(formatted.contains("column.eventTimezone.value=GMT+03:00"), formatted)
        assertTrue(formatted.contains("column.eventEndTimezone.value=GMT-18:00"), formatted)
    }

    @Test
    fun `provider snapshot exposes allowed event values but only structure for excluded columns`() {
        val snapshot =
            DiagnosticProviderOperationSnapshot(
                globalOperationIndex = 51,
                subBatchOperationIndex = 1,
                operationKind = DiagnosticProviderOperationKind.EVENT_UPDATE,
                target = DiagnosticProviderTarget.EVENT,
                calendarId = 73,
                reference = DiagnosticProviderReference.Existing(91),
                columns =
                    listOf(
                        providerColumn(DiagnosticProviderColumn.TITLE, "provider-title-secret-marker"),
                        providerColumn(DiagnosticProviderColumn.DESCRIPTION, "provider-body-secret-marker"),
                        providerColumn(DiagnosticProviderColumn.ORGANIZER_EMAIL, "provider-organizer-secret-marker"),
                        providerColumn(DiagnosticProviderColumn.ATTENDEE_EMAIL, "provider-attendee-secret-marker"),
                        providerColumn(DiagnosticProviderColumn.LOCATION, "provider-allowed-location"),
                        providerColumn(DiagnosticProviderColumn.START, 1_777_000_000_000L),
                        providerColumn(DiagnosticProviderColumn.RECURRENCE_RULE, "FREQ=DAILY;INTERVAL=2"),
                    ),
            )
        val formatted =
            formatRecords(
                DeviceDiagnosticEvent(
                    severity = DiagnosticSeverity.ERROR,
                    component = DiagnosticComponent.CALENDAR,
                    stage = DiagnosticStage.PROVIDER_BATCH,
                    operation = DiagnosticOperation("sync-17", DiagnosticOperationKind.SYNCHRONIZATION, 3, 9),
                    providerCallOutcome = DiagnosticProviderCallOutcome.UNKNOWN,
                    providerOperationSnapshot = snapshot,
                ),
            ).joinToString("\n")

        listOf(
            "provider-title-secret-marker",
            "provider-body-secret-marker",
            "provider-organizer-secret-marker",
            "provider-attendee-secret-marker",
        ).forEach { excluded -> assertFalse(formatted.contains(excluded), excluded) }
        listOf(
            "global_operation_index=51",
            "sub_batch_operation_index=1",
            "reference_kind=existing",
            "reference_value=91",
            "column.title.state=present",
            "column.description.state=present",
            "column.eventLocation.value=provider-allowed-location",
            "column.dtstart.value=1777000000000",
            "column.rrule.value=FREQ=DAILY;INTERVAL=2",
        ).forEach { allowed -> assertTrue(formatted.contains(allowed), formatted) }
    }

    private fun calendarSnapshot(fields: List<DiagnosticCalendarFieldEntry>) =
        DiagnosticCalendarFailureSnapshot(
            commandKind = DiagnosticCalendarCommandKind.CHANGE,
            serverId = "event-42",
            rule = DiagnosticCalendarRule.EVENT_TIME_RANGE_INVALID,
            path = DiagnosticCalendarPath.Event,
            fields = fields,
        )

    private fun event(snapshot: DiagnosticCalendarFailureSnapshot) =
        DeviceDiagnosticEvent(
            severity = DiagnosticSeverity.ERROR,
            component = DiagnosticComponent.CALENDAR,
            stage = DiagnosticStage.EVENT_MAP,
            operation = DiagnosticOperation("sync-17", DiagnosticOperationKind.SYNCHRONIZATION, 3, 9),
            calendarFailureSnapshot = snapshot,
        )

    private fun field(
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
        state: DiagnosticFieldState,
        value: DiagnosticFieldValue? = null,
    ) = DiagnosticCalendarFieldEntry(source, field, state, value)

    private fun structural(
        field: DiagnosticCalendarField,
        marker: String,
    ) =
        field(
            DiagnosticCalendarFieldSource.RESPONSE,
            field,
            DiagnosticFieldState.PRESENT,
            DiagnosticFieldValue.Text(marker),
        )

    private fun text(
        field: DiagnosticCalendarField,
        value: String,
    ) =
        field(
            DiagnosticCalendarFieldSource.RESPONSE,
            field,
            DiagnosticFieldState.PRESENT,
            DiagnosticFieldValue.Text(value),
        )

    private fun providerColumn(
        column: DiagnosticProviderColumn,
        value: String,
    ) = DiagnosticProviderColumnEntry(column, DiagnosticFieldState.PRESENT, DiagnosticFieldValue.Text(value))

    private fun providerColumn(
        column: DiagnosticProviderColumn,
        value: Long,
    ) = DiagnosticProviderColumnEntry(column, DiagnosticFieldState.PRESENT, DiagnosticFieldValue.IntegerValue(value))

    private fun formatRecords(event: DeviceDiagnosticEvent): List<String> =
        DeviceDiagnosticFormatter.formatRecords(event)
}

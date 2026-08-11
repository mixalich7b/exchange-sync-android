package net.mixalich7b.exchangesync.infrastructure.activesync

import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSync
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.Calendar
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.FolderHierarchy
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlElement
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlFormatException
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlReader
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlReadLimitKind
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlWriter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActiveSyncProtocolCodecTest {
    @Test
    fun `FolderSync zero request is the canonical minimal WBXML command`() {
        assertArrayEquals(
            bytes(0x03, 0x01, 0x6A, 0x00, 0x00, 0x07, 0x56, 0x52, 0x03, 0x30, 0x00, 0x01, 0x01),
            FolderSyncCodec.encodeRequest("0"),
        )
    }

    @Test
    fun `folder hierarchy pages apply incremental add update and delete changes`() {
        val initial =
            FolderHierarchyState(
                syncKey = "7",
                folders =
                    mapOf(
                        "calendar-primary" to ActiveSyncFolder("calendar-primary", "0", "Calendar", 8),
                        "calendar-secondary" to ActiveSyncFolder("calendar-secondary", "0", "Team", 13),
                    ),
            )
        val response =
            wbxml(
                element(
                    FolderHierarchy.FOLDER_SYNC,
                    text(FolderHierarchy.STATUS, "1"),
                    text(FolderHierarchy.SYNC_KEY, "8"),
                    element(
                        FolderHierarchy.CHANGES,
                        text(FolderHierarchy.COUNT, "3"),
                        element(
                            FolderHierarchy.UPDATE,
                            text(FolderHierarchy.SERVER_ID, "calendar-primary"),
                            text(FolderHierarchy.DISPLAY_NAME, "Main calendar"),
                        ),
                        element(
                            FolderHierarchy.ADD,
                            text(FolderHierarchy.SERVER_ID, "calendar-new"),
                            text(FolderHierarchy.PARENT_ID, "0"),
                            text(FolderHierarchy.DISPLAY_NAME, "Archive"),
                            text(FolderHierarchy.TYPE, "13"),
                        ),
                        element(
                            FolderHierarchy.DELETE,
                            text(FolderHierarchy.SERVER_ID, "calendar-secondary"),
                        ),
                    ),
                ),
            )

        val page = FolderSyncCodec.decodeResponse(response)
        val updated = FolderHierarchyReducer.apply(initial, page)

        assertEquals("8", updated.syncKey)
        assertEquals("Main calendar", updated.folders.getValue("calendar-primary").displayName)
        assertEquals(8, updated.folders.getValue("calendar-primary").type)
        assertEquals("Archive", updated.folders.getValue("calendar-new").displayName)
        assertFalse("calendar-secondary" in updated.folders)
    }

    @Test
    fun `duplicate singleton synchronization keys are rejected as malformed WBXML`() {
        val response =
            wbxml(
                element(
                    FolderHierarchy.FOLDER_SYNC,
                    text(FolderHierarchy.STATUS, "1"),
                    text(FolderHierarchy.SYNC_KEY, "7"),
                    text(FolderHierarchy.SYNC_KEY, "8"),
                ),
            )

        assertThrows(WbxmlFormatException::class.java) {
            FolderSyncCodec.decodeResponse(response)
        }
    }

    @Test
    fun `primary calendar selection requires exactly one default Calendar folder`() {
        val primary = ActiveSyncFolder("primary-id", "0", "Calendar", 8)
        val secondary = ActiveSyncFolder("secondary-id", "0", "Other calendar", 13)

        assertEquals("primary-id", PrimaryCalendarSelector.select(listOf(primary, secondary)).serverId)
        assertThrows(PrimaryCalendarSelectionException::class.java) {
            PrimaryCalendarSelector.select(listOf(secondary))
        }
        assertThrows(PrimaryCalendarSelectionException::class.java) {
            PrimaryCalendarSelector.select(listOf(primary, primary.copy(serverId = "duplicate-primary")))
        }
    }

    @Test
    fun `initial calendar Sync primes key zero without GetChanges`() {
        val expected =
            bytes(
                0x03, 0x01, 0x6A, 0x00,
                0x45,
                0x5C,
                0x4F,
                0x4B, 0x03, 0x30, 0x00, 0x01,
                0x52, 0x03, 0x63, 0x61, 0x6C, 0x65, 0x6E, 0x64, 0x61, 0x72, 0x2D, 0x31, 0x00, 0x01,
                0x01,
                0x01,
                0x01,
            )

        val encoded =
            CalendarSyncCodec.encodeRequest(
                syncKey = "0",
                collectionId = "calendar-1",
                windowSize = 100,
                getChanges = false,
                version = net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion.V16_1,
            )

        assertArrayEquals(expected, encoded)
    }

    @Test
    fun `nonzero calendar Sync requests unfiltered changes with window one hundred`() {
        val encoded =
            CalendarSyncCodec.encodeRequest(
                syncKey = "primed-key",
                collectionId = "calendar-1",
                windowSize = 100,
                getChanges = true,
                version = net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion.V16_1,
            )
        val collection = WbxmlReader().read(encoded).child(AirSync.COLLECTIONS)?.child(AirSync.COLLECTION)

        assertEquals("primed-key", collection?.child(AirSync.SYNC_KEY)?.text)
        assertEquals("100", collection?.child(AirSync.WINDOW_SIZE)?.text)
        assertEquals(WbxmlElement(AirSync.GET_CHANGES), collection?.child(AirSync.GET_CHANGES))
        assertFalse(collection?.children.orEmpty().any { element -> element.tag == AirSync.FILTER_TYPE })
    }

    @Test
    fun `calendar Sync response exposes commands next key and MoreAvailable pagination`() {
        val response =
            wbxml(
                element(
                    AirSync.SYNC,
                    element(
                        AirSync.COLLECTIONS,
                        element(
                            AirSync.COLLECTION,
                            text(AirSync.SYNC_KEY, "next-key"),
                            text(AirSync.COLLECTION_ID, "calendar-1"),
                            text(AirSync.STATUS, "1"),
                            element(
                                AirSync.COMMANDS,
                                element(
                                    AirSync.ADD,
                                    text(AirSync.SERVER_ID, "server-event-1"),
                                    element(
                                        AirSync.APPLICATION_DATA,
                                        text(Calendar.SUBJECT, "Pending meeting"),
                                    ),
                                ),
                            ),
                            WbxmlElement(AirSync.MORE_AVAILABLE),
                        ),
                    ),
                ),
            )

        val page = CalendarSyncCodec.decodeResponse(response, expectedCollectionId = "calendar-1")

        assertEquals("next-key", page.syncKey)
        assertTrue(page.moreAvailable)
        assertEquals(
            RawCalendarCommand(
                kind = RawCalendarCommandKind.ADD,
                serverId = "server-event-1",
                applicationData = page.commands.single().applicationData,
            ),
            page.commands.single(),
        )
        assertEquals("Pending meeting", page.commands.single().applicationData?.child(Calendar.SUBJECT)?.text)
    }

    @Test
    fun `calendar Sync response without MoreAvailable is a terminal page`() {
        val response =
            wbxml(
                element(
                    AirSync.SYNC,
                    element(
                        AirSync.COLLECTIONS,
                        element(
                            AirSync.COLLECTION,
                            text(AirSync.SYNC_KEY, "terminal-key"),
                            text(AirSync.COLLECTION_ID, "calendar-1"),
                            text(AirSync.STATUS, "1"),
                        ),
                    ),
                ),
            )

        assertFalse(CalendarSyncCodec.decodeResponse(response, "calendar-1").moreAvailable)
    }

    @Test
    fun `calendar decoder preserves a typed WBXML document capacity outcome`() {
        val oversizedDocument = ByteArray(2 * 1024 * 1024 + 1)

        val failure =
            assertThrows(ActiveSyncWbxmlReadLimitException::class.java) {
                CalendarSyncCodec.decodeResponse(oversizedDocument, "calendar-1")
            }

        assertEquals(WbxmlReadLimitKind.DOCUMENT_BYTES, failure.kind)
    }

    private fun wbxml(root: WbxmlElement): ByteArray = WbxmlWriter().write(root)

    private fun element(tag: net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlTag, vararg children: WbxmlElement) =
        WbxmlElement(tag, children = children.toList())

    private fun text(tag: net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlTag, value: String) =
        WbxmlElement(tag, text = value)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }
}

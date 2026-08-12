package net.mixalich7b.exchangesync.infrastructure.activesync.wbxml

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WbxmlCodecTest {
    @Test
    fun `canonical ActiveSync header and empty element decode and encode byte for byte`() {
        val fixture = bytes(0x03, 0x01, 0x6A, 0x00, 0x05)

        val root = WbxmlReader().read(fixture)

        assertEquals(ActiveSyncWbxmlTokens.AirSync.SYNC, root.tag)
        assertNull(root.text)
        assertEquals(emptyList<WbxmlElement>(), root.children)
        assertArrayEquals(fixture, WbxmlWriter().write(root))
    }

    @Test
    fun `multi-byte integers preserve literal boundary values`() {
        val fixtures =
            listOf(
                0 to bytes(0x00),
                127 to bytes(0x7F),
                128 to bytes(0x81, 0x00),
                16_383 to bytes(0xFF, 0x7F),
                16_384 to bytes(0x81, 0x80, 0x00),
                268_435_455 to bytes(0xFF, 0xFF, 0xFF, 0x7F),
            )

        fixtures.forEach { (value, encoded) ->
            assertArrayEquals(encoded, WbxmlMbUInt.encode(value), value.toString())
            assertEquals(WbxmlMbUInt.Decoded(value, encoded.size), WbxmlMbUInt.decode(encoded), value.toString())
        }
    }

    @Test
    fun `inline strings and nested content decode from a literal fixture`() {
        val fixture =
            bytes(
                0x03,
                0x01,
                0x6A,
                0x00,
                0x45,
                0x4B,
                0x03,
                0x31,
                0x32,
                0x33,
                0x00,
                0x01,
                0x01,
            )

        val root = WbxmlReader().read(fixture)

        assertEquals("123", root.child(ActiveSyncWbxmlTokens.AirSync.SYNC_KEY)?.text)
        assertArrayEquals(fixture, WbxmlWriter().write(root))
    }

    @Test
    fun `code-page switches retain namespace identity and switch back for siblings`() {
        val fixture =
            bytes(
                0x03,
                0x01,
                0x6A,
                0x00,
                0x45,
                0x00,
                0x04,
                0x66,
                0x03,
                0x4D,
                0x65,
                0x65,
                0x74,
                0x69,
                0x6E,
                0x67,
                0x00,
                0x01,
                0x00,
                0x00,
                0x4B,
                0x03,
                0x37,
                0x00,
                0x01,
                0x01,
            )

        val root = WbxmlReader().read(fixture)

        assertEquals("Meeting", root.child(ActiveSyncWbxmlTokens.Calendar.SUBJECT)?.text)
        assertEquals("7", root.child(ActiveSyncWbxmlTokens.AirSync.SYNC_KEY)?.text)
        assertArrayEquals(fixture, WbxmlWriter().write(root))
    }

    @Test
    fun `well-formed unknown element subtree is skipped without hiding its known sibling`() {
        val fixture =
            bytes(
                0x03,
                0x01,
                0x6A,
                0x00,
                0x45,
                0x7F,
                0x03,
                0x69,
                0x67,
                0x6E,
                0x6F,
                0x72,
                0x65,
                0x64,
                0x00,
                0x01,
                0x4B,
                0x03,
                0x38,
                0x00,
                0x01,
                0x01,
            )

        val root = WbxmlReader().read(fixture)

        assertEquals(listOf(ActiveSyncWbxmlTokens.AirSync.SYNC_KEY), root.children.map(WbxmlElement::tag))
        assertEquals("8", root.children.single().text)
    }

    @Test
    fun `token tables use the protocol values for every required code page`() {
        assertEquals(WbxmlTag(0, 0x05, "Sync"), ActiveSyncWbxmlTokens.AirSync.SYNC)
        assertEquals(WbxmlTag(7, 0x16, "FolderSync"), ActiveSyncWbxmlTokens.FolderHierarchy.FOLDER_SYNC)
        assertEquals(WbxmlTag(4, 0x36, "ResponseType"), ActiveSyncWbxmlTokens.Calendar.RESPONSE_TYPE)
        assertEquals(WbxmlTag(17, 0x20, "Location"), ActiveSyncWbxmlTokens.AirSyncBase.LOCATION)
    }

    @Test
    fun `document and element reader capacity accept the exact boundary and reject the next unit`() {
        val document = bytes(0x03, 0x01, 0x6A, 0x00, 0x05)
        val twoElements =
            WbxmlWriter(WbxmlLimits(maxElements = 2)).write(
                WbxmlElement(
                    ActiveSyncWbxmlTokens.AirSync.SYNC,
                    children = listOf(WbxmlElement(ActiveSyncWbxmlTokens.AirSync.MORE_AVAILABLE)),
                ),
            )

        assertEquals(ActiveSyncWbxmlTokens.AirSync.SYNC, WbxmlReader(WbxmlLimits(maxDocumentBytes = 5)).read(document).tag)
        assertEquals(1, WbxmlReader(WbxmlLimits(maxElements = 2)).read(twoElements).children.size)
        val documentFailure =
            assertThrows(WbxmlReadLimitException::class.java) {
                WbxmlReader(WbxmlLimits(maxDocumentBytes = 4)).read(document)
            }
        val elementFailure =
            assertThrows(WbxmlReadLimitException::class.java) {
                WbxmlReader(WbxmlLimits(maxElements = 1)).read(twoElements)
            }

        assertEquals(WbxmlReadLimitKind.DOCUMENT_BYTES, documentFailure.kind)
        assertEquals(WbxmlReadLimitKind.ELEMENT_COUNT, elementFailure.kind)
    }

    @Test
    fun `depth and inline reader capacity remain distinct non-page-scaled limits`() {
        val tooDeep =
            bytes(
                0x03,
                0x01,
                0x6A,
                0x00,
                0x45,
                0x4F,
                0x4E,
                0x03,
                0x31,
                0x00,
                0x01,
                0x01,
                0x01,
            )
        val tooLongString = bytes(0x03, 0x01, 0x6A, 0x00, 0x45, 0x03, 0x31, 0x32, 0x33, 0x34, 0x00, 0x01)

        val depthFailure =
            assertThrows(WbxmlReadLimitException::class.java) {
                WbxmlReader(WbxmlLimits(maxDepth = 2)).read(tooDeep)
            }
        val inlineFailure =
            assertThrows(WbxmlReadLimitException::class.java) {
                WbxmlReader(WbxmlLimits(maxInlineStringBytes = 3)).read(tooLongString)
            }

        assertEquals(WbxmlReadLimitKind.DEPTH, depthFailure.kind)
        assertEquals(WbxmlReadLimitKind.INLINE_STRING_BYTES, inlineFailure.kind)
    }

    @Test
    fun `malformed syntax unsupported encoding and tokens remain format errors`() {
        val reader = WbxmlReader()

        assertThrows(WbxmlFormatException::class.java) { reader.read(bytes(0x02, 0x01, 0x6A, 0x00, 0x05)) }
        assertThrows(WbxmlFormatException::class.java) { reader.read(bytes(0x03, 0x01, 0x6A, 0x01)) }
        assertThrows(WbxmlFormatException::class.java) { reader.read(bytes(0x03, 0x01, 0x6A, 0x00, 0x45, 0x03, 0x41)) }
        assertThrows(WbxmlFormatException::class.java) { reader.read(bytes(0x03, 0x01, 0x6A, 0x00, 0x02)) }
        assertThrows(WbxmlFormatException::class.java) { reader.read(bytes(0x03, 0x01, 0x6A, 0x00, 0x85)) }
        assertThrows(WbxmlFormatException::class.java) {
            WbxmlMbUInt.decode(bytes(0x81, 0x80, 0x80, 0x80, 0x00))
        }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }
}

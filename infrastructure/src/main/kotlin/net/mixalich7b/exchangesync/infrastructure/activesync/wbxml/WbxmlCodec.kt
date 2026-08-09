package net.mixalich7b.exchangesync.infrastructure.activesync.wbxml

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class WbxmlFormatException(message: String) : IllegalArgumentException(message)

data class WbxmlLimits(
    val maxDocumentBytes: Int = 2 * 1024 * 1024,
    val maxDepth: Int = 32,
    val maxElements: Int = 20_000,
    val maxInlineStringBytes: Int = 256 * 1024,
) {
    init {
        require(maxDocumentBytes > 0)
        require(maxDepth > 0)
        require(maxElements > 0)
        require(maxInlineStringBytes > 0)
    }
}

data class WbxmlElement(
    val tag: WbxmlTag,
    val text: String? = null,
    val children: List<WbxmlElement> = emptyList(),
) {
    init {
        require(text == null || children.isEmpty()) { "WBXML mixed content is not supported" }
    }

    fun child(tag: WbxmlTag): WbxmlElement? {
        val matching = children.filter { child -> child.tag == tag }
        if (matching.size > 1) throw WbxmlFormatException("Duplicate WBXML singleton element: ${tag.name}")
        return matching.singleOrNull()
    }

    fun children(tag: WbxmlTag): List<WbxmlElement> = children.filter { child -> child.tag == tag }
}

object WbxmlMbUInt {
    data class Decoded(
        val value: Int,
        val bytesRead: Int,
    )

    fun encode(value: Int): ByteArray {
        require(value in 0..MAX_VALUE)
        if (value == 0) return byteArrayOf(0)
        val chunks = ArrayDeque<Int>()
        var remaining = value
        while (remaining > 0) {
            chunks.addFirst(remaining and 0x7F)
            remaining = remaining ushr 7
        }
        val encodedSize = chunks.size
        return ByteArray(encodedSize) { index ->
            val chunk = chunks.removeFirst()
            (chunk or if (index < encodedSize - 1) 0x80 else 0x00).toByte()
        }
    }

    fun decode(bytes: ByteArray): Decoded = decode(bytes, 0)

    internal fun decode(
        bytes: ByteArray,
        offset: Int,
    ): Decoded {
        var value = 0
        var index = offset
        var count = 0
        while (index < bytes.size && count < MAX_BYTES) {
            val next = bytes[index].toInt() and 0xFF
            value = (value shl 7) or (next and 0x7F)
            index += 1
            count += 1
            if (next and 0x80 == 0) return Decoded(value, count)
        }
        throw WbxmlFormatException("Malformed multi-byte integer")
    }

    private const val MAX_BYTES = 4
    private const val MAX_VALUE = 0x0FFF_FFFF
}

class WbxmlReader(
    private val limits: WbxmlLimits = WbxmlLimits(),
) {
    fun read(bytes: ByteArray): WbxmlElement {
        if (bytes.size > limits.maxDocumentBytes) throw WbxmlFormatException("WBXML document is too large")
        val cursor = Cursor(bytes)
        if (cursor.readByte() != VERSION_1_3) throw WbxmlFormatException("Unsupported WBXML version")
        if (cursor.readMbUInt() != ACTIVE_SYNC_PUBLIC_ID) {
            throw WbxmlFormatException("Unsupported WBXML public identifier")
        }
        if (cursor.readMbUInt() != UTF_8_MIB_ENUM) throw WbxmlFormatException("WBXML must use UTF-8")
        if (cursor.readMbUInt() != 0) throw WbxmlFormatException("ActiveSync WBXML string table must be empty")

        val root = cursor.readElement(depth = 1, capture = true)
            ?: throw WbxmlFormatException("Unknown WBXML root element")
        if (!cursor.isAtEnd()) throw WbxmlFormatException("Trailing WBXML content")
        return root
    }

    private inner class Cursor(
        private val bytes: ByteArray,
    ) {
        private var index: Int = 0
        private var codePage: Int = 0
        private var elementCount: Int = 0

        fun isAtEnd(): Boolean = index == bytes.size

        fun readMbUInt(): Int {
            val decoded = WbxmlMbUInt.decode(bytes, index)
            index += decoded.bytesRead
            return decoded.value
        }

        fun readByte(): Int {
            if (index >= bytes.size) throw WbxmlFormatException("Unexpected end of WBXML")
            return bytes[index++].toInt() and 0xFF
        }

        fun readElement(
            depth: Int,
            capture: Boolean,
        ): WbxmlElement? {
            if (depth > limits.maxDepth) throw WbxmlFormatException("WBXML nesting is too deep")
            consumePageSwitches()
            val rawToken = readByte()
            if (rawToken and ATTRIBUTE_BIT != 0) throw WbxmlFormatException("WBXML attributes are unsupported")
            val token = rawToken and TOKEN_MASK
            if (token < MIN_TAG_TOKEN) throw WbxmlFormatException("Unsupported WBXML global token")
            elementCount += 1
            if (elementCount > limits.maxElements) throw WbxmlFormatException("Too many WBXML elements")

            val tag = ActiveSyncWbxmlTokens.find(codePage, token)
            val shouldCapture = capture && tag != null
            if (rawToken and CONTENT_BIT == 0) return if (shouldCapture) WbxmlElement(checkNotNull(tag)) else null

            val children = mutableListOf<WbxmlElement>()
            val text = StringBuilder()
            var sawText = false
            while (true) {
                if (index >= bytes.size) throw WbxmlFormatException("Unterminated WBXML element")
                when (peekByte()) {
                    END -> {
                        index += 1
                        break
                    }
                    SWITCH_PAGE -> consumePageSwitches()
                    STR_I -> {
                        index += 1
                        val value = readInlineString()
                        if (shouldCapture) {
                            text.append(value)
                            sawText = true
                        }
                    }
                    else -> {
                        val child = readElement(depth + 1, shouldCapture)
                        if (child != null) children += child
                    }
                }
            }

            if (!shouldCapture) return null
            if (sawText && children.isNotEmpty()) throw WbxmlFormatException("Mixed WBXML content is unsupported")
            return WbxmlElement(
                tag = checkNotNull(tag),
                text = text.toString().takeIf { sawText },
                children = children,
            )
        }

        private fun consumePageSwitches() {
            while (index < bytes.size && peekByte() == SWITCH_PAGE) {
                index += 1
                codePage = readByte()
            }
        }

        private fun readInlineString(): String {
            val start = index
            while (index < bytes.size && bytes[index].toInt() != 0) {
                if (index - start >= limits.maxInlineStringBytes) {
                    throw WbxmlFormatException("WBXML inline string is too long")
                }
                index += 1
            }
            if (index >= bytes.size) throw WbxmlFormatException("Unterminated WBXML inline string")
            val encoded = bytes.copyOfRange(start, index)
            index += 1
            return try {
                checkNotNull(UTF8_DECODER.get()).reset().decode(ByteBuffer.wrap(encoded)).toString()
            } catch (_: Exception) {
                throw WbxmlFormatException("Malformed UTF-8 inline string")
            }
        }

        private fun peekByte(): Int = bytes[index].toInt() and 0xFF
    }

    private companion object {
        const val VERSION_1_3 = 0x03
        const val ACTIVE_SYNC_PUBLIC_ID = 0x01
        const val UTF_8_MIB_ENUM = 0x6A
        const val SWITCH_PAGE = 0x00
        const val END = 0x01
        const val STR_I = 0x03
        const val MIN_TAG_TOKEN = 0x05
        const val CONTENT_BIT = 0x40
        const val ATTRIBUTE_BIT = 0x80
        const val TOKEN_MASK = 0x3F
        val UTF8_DECODER: ThreadLocal<java.nio.charset.CharsetDecoder> =
            ThreadLocal.withInitial {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            }
    }
}

class WbxmlWriter(
    private val limits: WbxmlLimits = WbxmlLimits(),
) {
    fun write(root: WbxmlElement): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(VERSION_1_3)
        output.write(ACTIVE_SYNC_PUBLIC_ID)
        output.write(UTF_8_MIB_ENUM)
        output.write(0)
        val state = WriteState(output)
        state.writeElement(root, depth = 1)
        val result = output.toByteArray()
        if (result.size > limits.maxDocumentBytes) throw WbxmlFormatException("WBXML document is too large")
        return result
    }

    private inner class WriteState(
        private val output: ByteArrayOutputStream,
    ) {
        private var codePage: Int = 0
        private var elementCount: Int = 0

        fun writeElement(
            element: WbxmlElement,
            depth: Int,
        ) {
            if (!ActiveSyncWbxmlTokens.contains(element.tag)) throw WbxmlFormatException("Unknown WBXML tag")
            if (depth > limits.maxDepth) throw WbxmlFormatException("WBXML nesting is too deep")
            elementCount += 1
            if (elementCount > limits.maxElements) throw WbxmlFormatException("Too many WBXML elements")
            switchTo(element.tag.codePage)

            val hasContent = element.text != null || element.children.isNotEmpty()
            output.write(element.tag.token or if (hasContent) CONTENT_BIT else 0)
            element.text?.let(::writeInlineString)
            element.children.forEach { child -> writeElement(child, depth + 1) }
            if (hasContent) output.write(END)
        }

        private fun switchTo(nextCodePage: Int) {
            if (codePage == nextCodePage) return
            output.write(SWITCH_PAGE)
            output.write(nextCodePage)
            codePage = nextCodePage
        }

        private fun writeInlineString(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            if (encoded.size > limits.maxInlineStringBytes) {
                throw WbxmlFormatException("WBXML inline string is too long")
            }
            output.write(STR_I)
            output.write(encoded)
            output.write(0)
        }
    }

    private companion object {
        const val VERSION_1_3 = 0x03
        const val ACTIVE_SYNC_PUBLIC_ID = 0x01
        const val UTF_8_MIB_ENUM = 0x6A
        const val SWITCH_PAGE = 0x00
        const val END = 0x01
        const val STR_I = 0x03
        const val CONTENT_BIT = 0x40
    }
}

package org.bimz.reforge.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class ParsedAxmlResult(
    val xmlText: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val compileSdkVersion: Int?,
    val applicationLabel: String,
    val debuggable: Boolean,
    val allowBackup: Boolean,
    val permissions: List<String>,
    val rawElements: List<AxmlElementNode>
)

data class AxmlElementNode(
    val tagName: String,
    val attributes: Map<String, String>,
    val depth: Int
)

/**
 * Decoder for Android Binary XML (AXML) chunks (RES_XML_TYPE 0x0003).
 */
object AxmlBinaryParser {

    private const val CHUNK_XML_TREE = 0x0003
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_NAMESPACE = 0x0100
    private const val CHUNK_END_NAMESPACE = 0x0101
    private const val CHUNK_START_TAG = 0x0102
    private const val CHUNK_END_TAG = 0x0103
    private const val CHUNK_TEXT = 0x0104

    private const val TYPE_STRING = 0x03
    private const val TYPE_FIRST_INT = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val TYPE_REFERENCE = 0x01

    fun parse(bytes: ByteArray): ParsedAxmlResult {
        if (bytes.size < 8) {
            return emptyFallback("AXML buffer too short (${bytes.size} bytes)")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rootType = buf.getShort(0).toInt() and 0xFFFF
        if (rootType != CHUNK_XML_TREE) {
            val text = String(bytes, StandardCharsets.UTF_8)
            if (text.trimStart().startsWith("<")) {
                return ParsedAxmlResult(
                    xmlText = text,
                    packageName = extractXmlAttr(text, "package"),
                    versionName = extractXmlAttr(text, "android:versionName"),
                    versionCode = extractXmlAttr(text, "android:versionCode").toLongOrNull() ?: 0L,
                    minSdkVersion = extractXmlAttr(text, "android:minSdkVersion").toIntOrNull() ?: 0,
                    targetSdkVersion = extractXmlAttr(text, "android:targetSdkVersion").toIntOrNull() ?: 0,
                    compileSdkVersion = extractXmlAttr(text, "android:compileSdkVersion").toIntOrNull(),
                    applicationLabel = extractXmlAttr(text, "android:label"),
                    debuggable = extractXmlAttr(text, "android:debuggable") == "true",
                    allowBackup = extractXmlAttr(text, "android:allowBackup") != "false",
                    permissions = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"")
                        .findAll(text).map { it.groupValues[1] }.toList(),
                    rawElements = emptyList()
                )
            }
            return emptyFallback("Not a valid AXML binary header (0x${rootType.toString(16)})")
        }

        var strings: List<String> = emptyList()
        val namespaces = mutableMapOf<String, String>()
        val xmlOut = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        val elements = mutableListOf<AxmlElementNode>()
        var depth = 0

        var packageName = ""
        var versionName = ""
        var versionCode = 0L
        var minSdk = 0
        var targetSdk = 0
        var compileSdk: Int? = null
        var appLabel = ""
        var debuggable = false
        var allowBackup = true
        val permissions = mutableListOf<String>()

        var offset = 8
        while (offset + 8 <= bytes.size) {
            val chunkType = buf.getShort(offset).toInt() and 0xFFFF
            val headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < 8 || offset + chunkSize > bytes.size) break

            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    strings = parseStringPool(bytes, offset, headerSize, chunkSize)
                }
                CHUNK_RESOURCE_MAP -> Unit
                CHUNK_START_NAMESPACE -> {
                    if (offset + 24 <= bytes.size) {
                        val prefixIdx = buf.getInt(offset + 16)
                        val uriIdx = buf.getInt(offset + 20)
                        val prefix = strings.getOrNull(prefixIdx) ?: "ns"
                        val uri = strings.getOrNull(uriIdx) ?: ""
                        namespaces[uri] = prefix
                    }
                }
                CHUNK_END_NAMESPACE -> Unit
                CHUNK_START_TAG -> {
                    if (offset + 36 <= bytes.size) {
                        val nsIdx = buf.getInt(offset + 16)
                        val nameIdx = buf.getInt(offset + 20)
                        val attrStart = buf.getShort(offset + 24).toInt() and 0xFFFF
                        val attrSize = buf.getShort(offset + 26).toInt() and 0xFFFF
                        val attrCount = buf.getShort(offset + 28).toInt() and 0xFFFF

                        val tagName = strings.getOrNull(nameIdx) ?: "unknown"
                        val nsUri = strings.getOrNull(nsIdx)
                        val qualifiedTag = if (!nsUri.isNullOrEmpty() && namespaces.containsKey(nsUri)) {
                            "${namespaces[nsUri]}:$tagName"
                        } else {
                            tagName
                        }

                        val indent = "  ".repeat(depth)
                        xmlOut.append("$indent<$qualifiedTag")
                        if (depth == 0) {
                            for ((uri, prefix) in namespaces) {
                                xmlOut.append(" xmlns:$prefix=\"$uri\"")
                            }
                        }

                        val attrMap = linkedMapOf<String, String>()
                        val attrsBase = offset + 16 + attrStart
                        for (i in 0 until attrCount) {
                            val aOff = attrsBase + i * attrSize
                            if (aOff + 20 > offset + chunkSize) break
                            val aNsIdx = buf.getInt(aOff)
                            val aNameIdx = buf.getInt(aOff + 4)
                            val aRawValIdx = buf.getInt(aOff + 8)
                            val aValType = (buf.getInt(aOff + 12) ushr 24) and 0xFF
                            val aValData = buf.getInt(aOff + 16)

                            val rawAttrName = strings.getOrNull(aNameIdx) ?: "attr_$i"
                            val aNsUri = strings.getOrNull(aNsIdx)
                            val attrKey = if (!aNsUri.isNullOrEmpty() && namespaces.containsKey(aNsUri)) {
                                "${namespaces[aNsUri]}:$rawAttrName"
                            } else {
                                rawAttrName
                            }

                            val attrVal = decodeTypedValue(strings, aRawValIdx, aValType, aValData)
                            attrMap[attrKey] = attrVal
                            xmlOut.append(" $attrKey=\"${escapeXml(attrVal)}\"")
                        }
                        xmlOut.append(">\n")
                        elements.add(AxmlElementNode(qualifiedTag, attrMap, depth))

                        when (tagName) {
                            "manifest" -> {
                                packageName = attrMap["package"] ?: packageName
                                versionName = attrMap["android:versionName"] ?: attrMap["versionName"] ?: versionName
                                versionCode = (attrMap["android:versionCode"] ?: attrMap["versionCode"])?.toLongOrNull() ?: versionCode
                                compileSdk = (attrMap["android:compileSdkVersion"] ?: attrMap["compileSdkVersion"])?.toIntOrNull()
                            }
                            "uses-sdk" -> {
                                minSdk = (attrMap["android:minSdkVersion"] ?: attrMap["minSdkVersion"])?.toIntOrNull() ?: minSdk
                                targetSdk = (attrMap["android:targetSdkVersion"] ?: attrMap["targetSdkVersion"])?.toIntOrNull() ?: targetSdk
                            }
                            "application" -> {
                                appLabel = attrMap["android:label"] ?: attrMap["label"] ?: appLabel
                                debuggable = (attrMap["android:debuggable"] ?: attrMap["debuggable"]) == "true"
                                allowBackup = (attrMap["android:allowBackup"] ?: attrMap["allowBackup"]) != "false"
                            }
                            "uses-permission", "uses-permission-sdk-23" -> {
                                val perm = attrMap["android:name"] ?: attrMap["name"]
                                if (!perm.isNullOrBlank()) permissions.add(perm)
                            }
                        }
                        depth++
                    }
                }
                CHUNK_END_TAG -> {
                    if (offset + 24 <= bytes.size) {
                        depth = maxOf(0, depth - 1)
                        val nameIdx = buf.getInt(offset + 20)
                        val tagName = strings.getOrNull(nameIdx) ?: "unknown"
                        val indent = "  ".repeat(depth)
                        xmlOut.append("$indent</$tagName>\n")
                    }
                }
                CHUNK_TEXT -> {
                    if (offset + 24 <= bytes.size) {
                        val textIdx = buf.getInt(offset + 16)
                        val text = strings.getOrNull(textIdx)
                        if (!text.isNullOrBlank()) {
                            xmlOut.append("  ".repeat(depth)).append(escapeXml(text)).append("\n")
                        }
                    }
                }
            }
            offset += chunkSize
        }

        return ParsedAxmlResult(
            xmlText = xmlOut.toString(),
            packageName = packageName.ifBlank { "unknown.package" },
            versionName = versionName.ifBlank { "unlisted" },
            versionCode = versionCode,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
            compileSdkVersion = compileSdk,
            applicationLabel = appLabel.ifBlank { packageName },
            debuggable = debuggable,
            allowBackup = allowBackup,
            permissions = permissions,
            rawElements = elements
        )
    }

    private fun parseStringPool(bytes: ByteArray, offset: Int, headerSize: Int, chunkSize: Int): List<String> {
        if (offset + 28 > bytes.size) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringCount = buf.getInt(offset + 8)
        val flags = buf.getInt(offset + 16)
        val stringsStart = buf.getInt(offset + 20)
        val isUtf8 = (flags and (1 shl 8)) != 0
        if (stringCount <= 0 || stringCount > 200_000) return emptyList()

        val offsetsBase = offset + headerSize
        val dataBase = offset + stringsStart
        val result = ArrayList<String>(stringCount)

        for (i in 0 until stringCount) {
            val idxPos = offsetsBase + i * 4
            if (idxPos + 4 > offset + chunkSize) break
            val relOff = buf.getInt(idxPos)
            val strPos = dataBase + relOff
            if (strPos < 0 || strPos >= offset + chunkSize) {
                result.add("")
                continue
            }
            result.add(if (isUtf8) readUtf8PoolString(bytes, strPos, offset + chunkSize) else readUtf16PoolString(bytes, strPos, offset + chunkSize))
        }
        return result
    }

    private fun readUtf8PoolString(bytes: ByteArray, pos: Int, limit: Int): String {
        var cursor = pos
        if (cursor >= limit) return ""
        val firstLen = bytes[cursor++].toInt() and 0xFF
        if ((firstLen and 0x80) != 0 && cursor < limit) cursor++
        if (cursor >= limit) return ""
        var byteLen = bytes[cursor++].toInt() and 0xFF
        if ((byteLen and 0x80) != 0 && cursor < limit) {
            byteLen = ((byteLen and 0x7F) shl 8) or (bytes[cursor++].toInt() and 0xFF)
        }
        val safeLen = minOf(byteLen, maxOf(0, limit - cursor))
        return String(bytes, cursor, safeLen, StandardCharsets.UTF_8)
    }

    private fun readUtf16PoolString(bytes: ByteArray, pos: Int, limit: Int): String {
        if (pos + 2 > limit) return ""
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = pos
        var charLen = buf.getShort(cursor).toInt() and 0xFFFF
        cursor += 2
        if ((charLen and 0x8000) != 0 && cursor + 2 <= limit) {
            charLen = ((charLen and 0x7FFF) shl 16) or (buf.getShort(cursor).toInt() and 0xFFFF)
            cursor += 2
        }
        val byteLen = minOf(charLen * 2, maxOf(0, limit - cursor))
        return String(bytes, cursor, byteLen, StandardCharsets.UTF_16LE)
    }

    private fun decodeTypedValue(strings: List<String>, rawValIdx: Int, valType: Int, valData: Int): String {
        if (rawValIdx >= 0 && rawValIdx < strings.size) {
            return strings[rawValIdx]
        }
        return when (valType) {
            TYPE_STRING -> strings.getOrNull(valData) ?: ""
            TYPE_INT_BOOLEAN -> if (valData != 0) "true" else "false"
            TYPE_INT_HEX -> "0x${valData.toUInt().toString(16)}"
            TYPE_REFERENCE -> "@0x${valData.toUInt().toString(16).padStart(8, '0')}"
            in TYPE_FIRST_INT..0x1F -> valData.toString()
            else -> "0x${valData.toUInt().toString(16)}"
        }
    }

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun extractXmlAttr(xml: String, attr: String): String =
        Regex("$attr=\"([^\"]*)\"").find(xml)?.groupValues?.get(1).orEmpty()

    private fun emptyFallback(reason: String) = ParsedAxmlResult(
        xmlText = "<!-- $reason -->",
        packageName = "unknown",
        versionName = "unknown",
        versionCode = 0L,
        minSdkVersion = 0,
        targetSdkVersion = 0,
        compileSdkVersion = null,
        applicationLabel = "unknown",
        debuggable = false,
        allowBackup = true,
        permissions = emptyList(),
        rawElements = emptyList()
    )
}

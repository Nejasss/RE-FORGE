package org.bimz.reforge.resources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.apk.AxmlBinaryParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

data class ResourceEntryNode(
    val path: String,
    val group: String,
    val sizeBytes: Long,
    val isBinaryXml: Boolean
)

class ResourceInspectorEngine {

    suspend fun listResources(apkOrZip: File): Result<List<ResourceEntryNode>> = withContext(Dispatchers.IO) {
        runCatching {
            val nodes = mutableListOf<ResourceEntryNode>()
            ZipFile(apkOrZip).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val name = e.name
                    if (name == "AndroidManifest.xml" ||
                        name == "resources.arsc" ||
                        name.startsWith("res/") ||
                        name.startsWith("assets/")
                    ) {
                        val group = when {
                            name == "AndroidManifest.xml" -> "Manifest"
                            name == "resources.arsc" -> "Resource Table"
                            name.startsWith("res/layout") -> "Layouts"
                            name.startsWith("res/values") -> "Values"
                            name.startsWith("res/drawable") || name.startsWith("res/mipmap") -> "Drawables"
                            name.startsWith("res/xml") -> "XML Configs"
                            name.startsWith("assets/") -> "Assets"
                            else -> "Other Resources"
                        }
                        nodes.add(
                            ResourceEntryNode(
                                path = name,
                                group = group,
                                sizeBytes = e.size,
                                isBinaryXml = name.endsWith(".xml")
                            )
                        )
                    }
                }
            }
            nodes.sortedBy { it.path }
        }
    }

    suspend fun readResourceContent(apkOrZip: File, entryPath: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ZipFile(apkOrZip).use { zip ->
                val entry = zip.getEntry(entryPath)
                    ?: throw IllegalArgumentException("Resource entry not found: $entryPath")
                if (entry.size > 2_000_000L) {
                    throw IllegalStateException("Resource exceeds 2MB preview safety limit (${entry.size} bytes)")
                }
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (entryPath.endsWith(".xml")) {
                    AxmlBinaryParser.parse(bytes).xmlText
                } else if (entryPath.endsWith(".json") || entryPath.endsWith(".txt") || entryPath.endsWith(".properties")) {
                    String(bytes, StandardCharsets.UTF_8)
                } else {
                    "Binary resource (${bytes.size} bytes) - inspect raw bytes in Hex Viewer."
                }
            }
        }
    }

    fun validateXmlSyntax(xmlContent: String): Result<Boolean> = runCatching {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xmlContent))
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            // Walk entire XML document to verify well-formedness
        }
        true
    }
}

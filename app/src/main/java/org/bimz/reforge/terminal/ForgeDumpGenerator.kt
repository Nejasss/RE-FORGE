package org.bimz.reforge.terminal

import android.content.Context
import org.bimz.reforge.engines.Radare2Bridge
import org.bimz.reforge.storage.SavedFileManager
import org.bimz.reforge.storage.SavedForgeFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ForgeDumpGenerator {

    enum class DumpMode(val key: String, val label: String) {
        EXPORTS("exports", "Exports"),
        IMPORTS("imports", "Imports"),
        FUNCTIONS("functions", "Functions"),
        STRINGS("strings", "Strings"),
        SECTIONS("sections", "Sections"),
        FULL("full", "Full Dump");

        companion object {
            fun fromToken(token: String): DumpMode? = when (token.lowercase(Locale.US)) {
                "1", "exp", "export", "exports" -> EXPORTS
                "2", "imp", "import", "imports" -> IMPORTS
                "3", "fun", "func", "function", "functions" -> FUNCTIONS
                "4", "str", "string", "strings" -> STRINGS
                "5", "sec", "section", "sections" -> SECTIONS
                "6", "all", "full" -> FULL
                else -> null
            }
        }
    }

    enum class DumpFormat(val ext: String) {
        TXT("txt"),
        MD("md"),
        JSON("json");

        companion object {
            fun fromToken(token: String): DumpFormat? = when (token.lowercase(Locale.US)) {
                "1", "txt", "text", "il2cpp" -> TXT
                "2", "md", "markdown" -> MD
                "3", "json" -> JSON
                else -> null
            }
        }
    }

    private data class DumpData(
        val info: JSONObject,
        val exports: JSONArray,
        val imports: JSONArray,
        val functions: JSONArray,
        val strings: JSONArray,
        val sections: JSONArray
    )

    private data class DumpMeta(
        val file: String,
        val path: String,
        val arch: String,
        val bits: String,
        val format: String,
        val os: String,
        val sizeHuman: String,
        val sizeRaw: Long,
        val md5: String,
        val sections: Int,
        val exports: Int,
        val imports: Int,
        val functions: Int,
        val strings: Int,
        val generated: String
    )

    private const val SPLIT_MARK = "__R2DUMP_SPLIT__"

    private val BANNER = """
 ██████╗ ██████╗ ██████╗ ██╗   ██╗███╗   ███╗██████╗
 ██╔══██╗╚════██╗██╔══██╗██║   ██║████╗ ████║██╔══██╗
 ██████╔╝ █████╔╝██║  ██║██║   ██║██╔████╔██║██████╔╝
 ██╔══██╗██╔═══╝ ██║  ██║██║   ██║██║╚██╔╝██║██╔═══╝
 ██║  ██║███████╗██████╔╝╚██████╔╝██║ ╚═╝ ██║██║
 ╚═╝  ╚═╝╚══════╝╚═════╝  ╚═════╝ ╚═╝     ╚═╝╚═╝
""".trimIndent()

    fun parseArgs(rawArg: String): Pair<DumpMode, DumpFormat>? {
        var mode = DumpMode.FULL
        var format = DumpFormat.TXT
        val tokens = rawArg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (tok in tokens) {
            val m = DumpMode.fromToken(tok)
            val f = DumpFormat.fromToken(tok)
            when {
                m != null -> mode = m
                f != null -> format = f
                else -> return null
            }
        }
        return mode to format
    }

    fun runDump(
        context: Context,
        targetFile: File,
        mode: DumpMode,
        format: DumpFormat,
        onStatus: (String) -> Unit
    ): Result<Pair<SavedForgeFile, String>> = runCatching {
        val bridge = Radare2Bridge(context)
        val r2Bin = bridge.resolveBinary()
            ?: throw IllegalStateException("Radare2 binary not found.")

        onStatus("[dump] Opening & analyzing ${targetFile.name} (aaa)...")
        val data = collectBinaryData(context, r2Bin, targetFile, onStatus)

        onStatus("[dump] Building ${mode.label} (${format.ext.uppercase(Locale.US)})...")
        val meta = buildMeta(targetFile, data)
        val body = when (format) {
            DumpFormat.JSON -> buildJsonContent(mode, data, meta)
            DumpFormat.MD -> buildHeader(meta, mode, format) + buildMarkdownContent(mode, data)
            DumpFormat.TXT -> buildHeader(meta, mode, format) + buildTxtContent(mode, data)
        }

        val safeBase = targetFile.nameWithoutExtension.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outName = "${safeBase}_${mode.key}_${stamp}.${format.ext}"
        val saved = SavedFileManager(context).saveTextDump(outName, body)

        val summary = buildString {
            appendLine("✓ Dump saved to Download/RE FORGE/${saved.name}")
            appendLine("  Target    : ${meta.file} (${meta.arch} / ${meta.bits}-bit, ${meta.format})")
            appendLine("  Mode      : ${mode.label} | Format: ${format.ext.uppercase(Locale.US)} (${humanSize(saved.sizeBytes)})")
            appendLine("  MD5       : ${meta.md5}")
            append("  Counts    : ${meta.sections} sections | ${meta.exports} exports | ${meta.imports} imports | ${meta.functions} funcs | ${meta.strings} strings")
        }
        saved to summary
    }

    private fun collectBinaryData(context: Context, r2Bin: File, targetFile: File, onStatus: (String) -> Unit): DumpData {
        val batchCmd = listOf(
            "aaa",
            "ij", "?e $SPLIT_MARK",
            "iEj", "?e $SPLIT_MARK",
            "iij", "?e $SPLIT_MARK",
            "aflj", "?e $SPLIT_MARK",
            "izj", "?e $SPLIT_MARK",
            "iSj", "q"
        ).joinToString("; ")

        val pb = ProcessBuilder(
            r2Bin.absolutePath,
            "-N",
            "-e", "scr.color=0",
            "-e", "scr.utf8=0",
            "-e", "bin.relocs.apply=true",
            "-e", "bin.demangle=true",
            "-q",
            "-c", batchCmd,
            targetFile.absolutePath
        )
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        val libDir = r2Bin.parentFile?.absolutePath.orEmpty()
        val prevLd = env["LD_LIBRARY_PATH"].orEmpty()
        env["LD_LIBRARY_PATH"] = if (prevLd.isBlank()) libDir else "$libDir:$prevLd"
        env["HOME"] = File(context.filesDir, "r2home").apply { mkdirs() }.absolutePath
        env["TMPDIR"] = File(context.cacheDir, "r2tmp").apply { mkdirs() }.absolutePath
        env["R2_NOPLUGINS"] = "1"

        val proc = pb.start()
        val rawOut = StringBuilder()
        var lastTick = System.currentTimeMillis()
        BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                rawOut.append(line).append('\n')
                val now = System.currentTimeMillis()
                if (now - lastTick >= 650L) {
                    onStatus("[dump] Analyzing & collecting symbols (${rawOut.length / 1024} KB read)...")
                    lastTick = now
                }
            }
        }
        proc.waitFor()

        val chunks = rawOut.toString().split(SPLIT_MARK)
        return DumpData(
            info = parseJsonObject(chunks.getOrNull(0).orEmpty()),
            exports = parseJsonArray(chunks.getOrNull(1).orEmpty()),
            imports = parseJsonArray(chunks.getOrNull(2).orEmpty()),
            functions = parseJsonArray(chunks.getOrNull(3).orEmpty()),
            strings = parseJsonArray(chunks.getOrNull(4).orEmpty()),
            sections = parseJsonArray(chunks.getOrNull(5).orEmpty())
        )
    }

    private fun parseJsonObject(raw: String): JSONObject {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return JSONObject()
        return runCatching { JSONObject(raw.substring(s, e + 1)) }.getOrElse { JSONObject() }
    }

    private fun parseJsonArray(raw: String): JSONArray {
        val s = raw.indexOf('[')
        val e = raw.lastIndexOf(']')
        if (s < 0 || e <= s) return JSONArray()
        return runCatching { JSONArray(raw.substring(s, e + 1)) }.getOrElse { JSONArray() }
    }

    private fun buildMeta(file: File, data: DumpData): DumpMeta {
        val binfo = data.info.optJSONObject("bin") ?: JSONObject()
        val core = data.info.optJSONObject("core") ?: JSONObject()
        val fsize = file.length()
        val bitsVal = binfo.optInt("bits", 0)
        return DumpMeta(
            file = file.name,
            path = file.absolutePath,
            arch = binfo.optString("arch", "unknown").ifBlank { "unknown" },
            bits = if (bitsVal > 0) bitsVal.toString() else "?",
            format = core.optString("format", "unknown").ifBlank { "unknown" },
            os = binfo.optString("os", "unknown").ifBlank { "unknown" },
            sizeHuman = humanSize(fsize),
            sizeRaw = fsize,
            md5 = md5File(file),
            sections = data.sections.length(),
            exports = data.exports.length(),
            imports = data.imports.length(),
            functions = data.functions.length(),
            strings = data.strings.length(),
            generated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        )
    }

    private fun buildHeader(meta: DumpMeta, mode: DumpMode, format: DumpFormat): String {
        val sep = "=".repeat(68)
        return buildString {
            appendLine(sep)
            appendLine(BANNER)
            appendLine("              *** dump by Bimz ***")
            appendLine(sep)
            appendLine("  File       : ${meta.file}")
            appendLine("  Path       : ${meta.path}")
            appendLine("  Arch       : ${meta.arch} / ${meta.bits}-bit")
            appendLine("  Format     : ${meta.format}")
            appendLine("  OS         : ${meta.os}")
            appendLine("  Size       : ${meta.sizeHuman}")
            appendLine("  MD5        : ${meta.md5}")
            appendLine("  Sections   : ${meta.sections}")
            appendLine("  Exports    : ${meta.exports}")
            appendLine("  Imports    : ${meta.imports}")
            appendLine("  Functions  : ${meta.functions}")
            appendLine("  Strings    : ${meta.strings}")
            appendLine("  Generated  : ${meta.generated}")
            appendLine("  Mode       : ${mode.label}")
            appendLine("  Format     : ${format.ext.uppercase(Locale.US)}")
            appendLine(sep)
            appendLine()
        }
    }

    private fun resolveDemangled(obj: JSONObject): Pair<String, String> {
        val raw = obj.optString("name", "?")
        val dem = obj.optString("demname", "")
            .ifBlank { obj.optString("realname", "") }
            .ifBlank { raw }
        return raw to dem
    }

    private fun buildTxtContent(mode: DumpMode, data: DumpData): String {
        val modes = if (mode == DumpMode.FULL) {
            listOf(DumpMode.EXPORTS, DumpMode.IMPORTS, DumpMode.FUNCTIONS, DumpMode.STRINGS, DumpMode.SECTIONS)
        } else {
            listOf(mode)
        }
        return modes.joinToString("\n\n") { m ->
            when (m) {
                DumpMode.EXPORTS -> fmtTxtExports(data.exports)
                DumpMode.IMPORTS -> fmtTxtImports(data.imports)
                DumpMode.FUNCTIONS -> fmtTxtFunctions(data.functions)
                DumpMode.STRINGS -> fmtTxtStrings(data.strings)
                DumpMode.SECTIONS -> fmtTxtSections(data.sections)
                DumpMode.FULL -> ""
            }
        }
    }

    private fun fmtTxtExports(arr: JSONArray): String = buildString {
        appendLine("// ═══════════════════ EXPORTS ═══════════════════")
        appendLine()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val off = hexAddr(e.optLong("vaddr", 0L))
            val (raw, dem) = resolveDemangled(e)
            val typ = e.optString("type", "")
            appendLine("[$off] ($typ) $dem")
            if (dem != raw) {
                appendLine("         // raw: $raw")
            }
        }
    }.trimEnd()

    private fun fmtTxtImports(arr: JSONArray): String = buildString {
        appendLine("// ═══════════════════ IMPORTS ═══════════════════")
        appendLine()
        for (i in 0 until arr.length()) {
            val imp = arr.optJSONObject(i) ?: continue
            val (_, dem) = resolveDemangled(imp)
            val lib = imp.optString("libname", "")
            append("  $dem")
            if (lib.isNotBlank()) append("  // from: $lib")
            appendLine()
        }
    }.trimEnd()

    private fun fmtTxtFunctions(arr: JSONArray): String = buildString {
        appendLine("// ═══════════════════ FUNCTIONS ═══════════════════")
        appendLine()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val off = hexAddr(f.optLong("offset", 0L))
            val (_, dem) = resolveDemangled(f)
            val size = f.optLong("size", 0L)
            appendLine("[$off] $dem  // size: $size")
        }
    }.trimEnd()

    private fun fmtTxtStrings(arr: JSONArray): String = buildString {
        appendLine("// ═══════════════════ STRINGS ═════════════════════")
        appendLine()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val off = hexAddr(s.optLong("vaddr", 0L))
            val valStr = s.optString("string", "")
            val typ = s.optString("type", "")
            appendLine("[$off] ($typ) \"$valStr\"")
        }
    }.trimEnd()

    private fun fmtTxtSections(arr: JSONArray): String = buildString {
        appendLine("// ═══════════════════ SECTIONS ════════════════════")
        appendLine()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val name = s.optString("name", "?")
            val vaddr = hexAddr(s.optLong("vaddr", 0L))
            val size = humanSize(s.optLong("vsize", 0L))
            val perms = s.optString("perm", "---")
            appendLine(String.format(Locale.US, "  %-20s %s  size:%s  perm:%s", name, vaddr, size, perms))
        }
    }.trimEnd()

    private fun buildMarkdownContent(mode: DumpMode, data: DumpData): String {
        val modes = if (mode == DumpMode.FULL) {
            listOf(DumpMode.EXPORTS, DumpMode.IMPORTS, DumpMode.FUNCTIONS, DumpMode.STRINGS, DumpMode.SECTIONS)
        } else {
            listOf(mode)
        }
        return modes.joinToString("\n\n") { m ->
            when (m) {
                DumpMode.EXPORTS -> fmtMdExports(data.exports)
                DumpMode.IMPORTS -> fmtMdImports(data.imports)
                DumpMode.FUNCTIONS -> fmtMdFunctions(data.functions)
                DumpMode.STRINGS -> fmtMdStrings(data.strings)
                DumpMode.SECTIONS -> fmtMdSections(data.sections)
                DumpMode.FULL -> ""
            }
        }
    }

    private fun fmtMdExports(arr: JSONArray): String = buildString {
        appendLine("## Exports")
        appendLine()
        appendLine("| Offset | Type | Demangled | Raw Name |")
        appendLine("|--------|------|-----------|----------|")
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val off = hexAddr(e.optLong("vaddr", 0L))
            val (raw, dem) = resolveDemangled(e)
            val typ = e.optString("type", "")
            appendLine("| `$off` | $typ | `$dem` | `$raw` |")
        }
    }.trimEnd()

    private fun fmtMdImports(arr: JSONArray): String = buildString {
        appendLine("## Imports")
        appendLine()
        appendLine("| Name | Demangled | Library |")
        appendLine("|------|-----------|---------|")
        for (i in 0 until arr.length()) {
            val imp = arr.optJSONObject(i) ?: continue
            val (raw, dem) = resolveDemangled(imp)
            val lib = imp.optString("libname", "-").ifBlank { "-" }
            appendLine("| `$raw` | `$dem` | $lib |")
        }
    }.trimEnd()

    private fun fmtMdFunctions(arr: JSONArray): String = buildString {
        appendLine("## Functions")
        appendLine()
        appendLine("| Offset | Name | Size |")
        appendLine("|--------|------|------|")
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val off = hexAddr(f.optLong("offset", 0L))
            val (_, dem) = resolveDemangled(f)
            val size = f.optLong("size", 0L)
            appendLine("| `$off` | `$dem` | $size |")
        }
    }.trimEnd()

    private fun fmtMdStrings(arr: JSONArray): String = buildString {
        appendLine("## Strings")
        appendLine()
        appendLine("| Offset | Type | Value |")
        appendLine("|--------|------|-------|")
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val off = hexAddr(s.optLong("vaddr", 0L))
            val valEsc = s.optString("string", "").replace("|", "\\|").replace("\n", "\\n")
            val typ = s.optString("type", "")
            appendLine("| `$off` | $typ | `$valEsc` |")
        }
    }.trimEnd()

    private fun fmtMdSections(arr: JSONArray): String = buildString {
        appendLine("## Sections")
        appendLine()
        appendLine("| Name | VAddr | Size | Perm |")
        appendLine("|------|-------|------|------|")
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val name = s.optString("name", "?")
            val vaddr = hexAddr(s.optLong("vaddr", 0L))
            val size = humanSize(s.optLong("vsize", 0L))
            val perms = s.optString("perm", "---")
            appendLine("| `$name` | `$vaddr` | $size | $perms |")
        }
    }.trimEnd()

    private fun buildJsonContent(mode: DumpMode, data: DumpData, meta: DumpMeta): String {
        fun enrichDemangled(arr: JSONArray): JSONArray {
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val orig = arr.optJSONObject(i) ?: continue
                val copy = JSONObject(orig.toString())
                val (_, dem) = resolveDemangled(orig)
                copy.put("demangled", dem)
                out.put(copy)
            }
            return out
        }

        val metaObj = JSONObject().apply {
            put("file", meta.file)
            put("path", meta.path)
            put("arch", meta.arch)
            put("bits", meta.bits)
            put("format", meta.format)
            put("os", meta.os)
            put("size", meta.sizeHuman)
            put("size_raw", meta.sizeRaw)
            put("checksum", meta.md5)
            put("sections", meta.sections)
            put("exports", meta.exports)
            put("imports", meta.imports)
            put("functions", meta.functions)
            put("strings", meta.strings)
            put("generated", meta.generated)
        }

        val root = JSONObject().apply {
            put("meta", metaObj)
            when (mode) {
                DumpMode.FULL -> {
                    put("exports", enrichDemangled(data.exports))
                    put("imports", enrichDemangled(data.imports))
                    put("functions", enrichDemangled(data.functions))
                    put("strings", data.strings)
                    put("sections", data.sections)
                }
                DumpMode.EXPORTS -> put("exports", enrichDemangled(data.exports))
                DumpMode.IMPORTS -> put("imports", enrichDemangled(data.imports))
                DumpMode.FUNCTIONS -> put("functions", enrichDemangled(data.functions))
                DumpMode.STRINGS -> put("strings", data.strings)
                DumpMode.SECTIONS -> put("sections", data.sections)
            }
        }
        return root.toString(2)
    }

    private fun hexAddr(value: Long): String = "0x" + java.lang.Long.toHexString(value)

    private fun humanSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        for (u in units) {
            if (v < 1024.0) return String.format(Locale.US, "%.1f %s", v, u)
            v /= 1024.0
        }
        return String.format(Locale.US, "%.1f PB", v)
    }

    private fun md5File(file: File): String = runCatching {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(16384)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }.getOrDefault("unknown")
}

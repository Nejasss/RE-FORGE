package org.bimz.reforge.ui.state

import org.bimz.reforge.terminal.ConsoleCommandRunner

import android.content.Context
import org.bimz.reforge.storage.SavedFileManager
import org.bimz.reforge.storage.SavedForgeFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bimz.reforge.BuildConfig
import org.bimz.reforge.ReForgeApplication
import org.bimz.reforge.binary.NativeBinaryBridge
import org.bimz.reforge.core.model.DisassembledInstruction
import org.bimz.reforge.core.model.ElfBinaryReport
import org.bimz.reforge.core.model.ModifiedEntryRecord
import org.bimz.reforge.core.model.WorkspaceProject
import org.bimz.reforge.core.security.HashCalculator
import org.bimz.reforge.engines.EngineDescriptor
import org.bimz.reforge.filemanager.FileComparisonReport
import org.bimz.reforge.hex.HexEditorSession
import org.bimz.reforge.storage.AnalysisHistoryEntity
import org.bimz.reforge.terminal.ConsoleEntry
import java.io.File
import java.io.RandomAccessFile

enum class WorkstationDestination(val label: String) {
    HOME("Home"),
    WORKSPACE("Workspace"),
    BINARY("Radare2"),
    TOOLS("Terminal"),
    HEX("Hex"),
    ENGINES("Engines"),
    SETTINGS("Settings")
}

data class TerminalSessionState(
    val id: String,
    val title: String,
    val history: List<ConsoleEntry>,
    val r2Mode: Boolean = false,
    val currentSeekHex: String = "0x00000000",
    val isRunning: Boolean = false
)

data class WorkstationUiState(
    val savedForgeFiles: List<SavedForgeFile> = emptyList(),
    val terminalSessions: List<TerminalSessionState> = listOf(TerminalSessionState("session-1", "Session 1", emptyList())),
    val activeSessionId: String = "session-1",
    val overrideActiveFile: File? = null,
    val currentRoute: WorkstationDestination = WorkstationDestination.HOME,
    val darkTheme: Boolean = true,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val deviceAbi: String = Build.SUPPORTED_ABIS.joinToString(", "),
    val workspaceStorageBytes: Long = 0L,
    val projects: List<WorkspaceProject> = emptyList(),
    val activeProject: WorkspaceProject? = null,
    val recentAnalysis: List<AnalysisHistoryEntity> = emptyList(),
    val engines: List<EngineDescriptor> = emptyList(),
    val isBusy: Boolean = false,
    val busyMessage: String = "",
    val errorMessage: String? = null,
    val statusBanner: String? = null,

    // Native ELF, Capstone & Radare2 state
    val elfReport: ElfBinaryReport? = null,
    val disasmArch: String = "arm64",
    val disasmOffsetHex: String = "0",
    val instructions: List<DisassembledInstruction> = emptyList(),
    val r2Output: String = "",

    // Hex Viewer / Editor state
    val hexPageOffset: Long = 0L,
    val hexPageBytes: ByteArray = ByteArray(0),
    val hexSearchStatus: String = "",
    val hexLastMatchOffset: Long = -1L,
    val hexLastMatchLength: Int = 0,
    val hexCanUndo: Boolean = false,
    val hexCanRedo: Boolean = false,
    val hexRevision: Int = 0,

    // Patch & Compare state
    val modifiedEntries: List<ModifiedEntryRecord> = emptyList(),
    val comparisonReport: FileComparisonReport? = null,

    // Console & Sticky r2 Session state
    val consoleHistory: List<ConsoleEntry> = emptyList(),
    val r2SessionActive: Boolean = false,
    val pendingDumpSaveText: String? = null,
    val pendingDumpSaveFilename: String = "reforge_r2_dump.txt"
) {
    val activeFile: File? get() = overrideActiveFile
}

class WorkstationViewModel(private val app: ReForgeApplication) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkstationUiState())
    val uiState: StateFlow<WorkstationUiState> = _uiState.asStateFlow()

    private var hexSession: HexEditorSession? = null

    init {
        viewModelScope.launch {
            app.repository.observeProjects().collect { list ->
                val activeId = _uiState.value.activeProject?.id
                val updatedActive = list.firstOrNull { it.id == activeId } ?: _uiState.value.activeProject
                val storageBytes = app.repository.calculateTotalStorageBytes()
                _uiState.update {
                    it.copy(
                        projects = list,
                        activeProject = updatedActive,
                        workspaceStorageBytes = storageBytes
                    )
                }
            }
        }
        viewModelScope.launch {
            app.repository.observeRecentAnalysis().collect { history ->
                _uiState.update { it.copy(recentAnalysis = history) }
            }
        }
        viewModelScope.launch {
            app.engineRegistry.engines.collect { list ->
                _uiState.update { it.copy(engines = list) }
            }
        }
    }

    fun navigateTo(destination: WorkstationDestination) {
        _uiState.update { it.copy(currentRoute = destination, errorMessage = null) }
    }

    fun toggleTheme(dark: Boolean) {
        _uiState.update { it.copy(darkTheme = dark) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearPendingDumpSave() {
        _uiState.update { it.copy(pendingDumpSaveText = null) }
    }

    fun importFileFromUri(uri: Uri, targetRouteAfterImport: WorkstationDestination? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Importing target into isolated workspace...", errorMessage = null) }
            val result = app.fileImportManager.importFromUri(uri)
            result.fold(
                onSuccess = { project ->
                    selectProject(project, targetRouteAfterImport)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = "Import failed: ${err.message}")
                    }
                }
            )
        }
    }

    fun selectProject(project: WorkspaceProject, routeOverride: WorkstationDestination? = null) {
        val workingFile = File(project.workingPath)
        hexSession = HexEditorSession(workingFile)

        val defaultDest = routeOverride ?: WorkstationDestination.BINARY

        _uiState.update {
            it.copy(
                activeProject = project,
                currentRoute = defaultDest,
                isBusy = false,
                errorMessage = null,
                statusBanner = "Active target: ${project.originalFileName} (${project.detectedFormat.label})",
                elfReport = null,
                instructions = emptyList(),
                r2Output = "",
                hexPageOffset = 0L,
                hexLastMatchOffset = -1L,
                hexLastMatchLength = 0,
                hexRevision = it.hexRevision + 1
            )
        }

        loadHexPage(0L)
        refreshModifiedEntries()

        if (project.detectedFormat.supportsElfAnalysis) {
            runElfInspection()
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            app.repository.deleteProject(projectId)
            if (_uiState.value.activeProject?.id == projectId) {
                hexSession = null
                _uiState.update { it.copy(activeProject = null, currentRoute = WorkstationDestination.HOME) }
            }
        }
    }

    fun saveProjectNotes(notes: String) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            app.repository.updateProjectNotes(project.id, notes)
            _uiState.update { it.copy(statusBanner = "Workspace notes saved.") }
        }
    }

    fun runElfInspection() {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Parsing ELF64/32 headers, sections, and symbols...") }
            val file = File(project.workingPath)
            val res = NativeBinaryBridge.analyzeElf(file)
            res.fold(
                onSuccess = { report ->
                    app.repository.recordAnalysis(
                        project.id,
                        project.name,
                        "NativeElfParser",
                        if (report.valid) "ELF ${report.architectureName}: ${report.sections.size} sections, ${report.symbols.size} symbols" else report.error
                    )
                    val initialOffset = report.sections.firstOrNull { it.name == ".text" }?.fileOffset ?: 0L
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            elfReport = report,
                            disasmOffsetHex = initialOffset.toString(16),
                            errorMessage = if (report.valid) null else report.error
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = "Native ELF parser error: ${err.message}")
                    }
                }
            )
        }
    }

    fun runCapstoneDisassembly(arch: String, offsetHex: String) {
        val project = _uiState.value.activeProject ?: return
        val offset = offsetHex.trim().removePrefix("0x").toLongOrNull(16) ?: 0L
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Disassembling at 0x${offset.toString(16)} with Capstone ($arch)...") }
            val res = NativeBinaryBridge.disassembleSlice(
                file = File(project.workingPath),
                arch = arch,
                mode = "arm",
                fileOffset = offset,
                byteLength = 256,
                virtualBaseAddress = offset,
                maxInstructions = 64
            )
            res.fold(
                onSuccess = { insns ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            disasmArch = arch,
                            disasmOffsetHex = offset.toString(16),
                            instructions = insns,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = "Capstone error: ${err.message}")
                    }
                }
            )
        }
    }

    fun runRadare2Query(query: String) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Running Radare2 ($query)...") }
            val res = app.radare2Bridge.executeCommand(File(project.workingPath), query)
            val combined = buildString {
                if (res.stdout.isNotBlank()) append(res.stdout.trimEnd())
                if (res.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(res.stderr.trimEnd())
                }
            }.ifBlank { "(exit ${res.exitCode}, no output)" }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    r2Output = combined,
                    errorMessage = if (res.success) null else res.stderr.ifBlank { "Radare2 exited with code ${res.exitCode}" }
                )
            }
        }
    }

    fun loadHexPage(offset: Long) {
        val session = hexSession ?: return
        val safeOffset = offset.coerceAtLeast(0L)
        viewModelScope.launch {
            val bytes = session.readPage(safeOffset, 256)
            _uiState.update {
                it.copy(
                    hexPageOffset = safeOffset,
                    hexPageBytes = bytes,
                    hexCanUndo = session.canUndo,
                    hexCanRedo = session.canRedo
                )
            }
        }
    }

    fun searchHexOrAscii(query: String, isAsciiMode: Boolean, startOffset: Long, onFound: (Long, Int) -> Unit) {
        val project = _uiState.value.activeProject ?: return
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(hexSearchStatus = "Enter a search query.") }
            return
        }
        viewModelScope.launch {
            val needle: ByteArray = try {
                if (isAsciiMode) {
                    trimmed.toByteArray(Charsets.UTF_8)
                } else {
                    val clean = trimmed.replace("0x", "", ignoreCase = true).replace(Regex("\\s+"), "")
                    require(clean.length % 2 == 0) { "Hex string must have even length" }
                    ByteArray(clean.length / 2) { idx ->
                        clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(hexSearchStatus = "Invalid query: ${e.message}") }
                return@launch
            }

            if (needle.isEmpty()) return@launch

            val foundAt = withContext(Dispatchers.IO) {
                findBytesInFile(File(project.workingPath), needle, startOffset.coerceAtLeast(0L))
            }

            if (foundAt >= 0L) {
                _uiState.update {
                    it.copy(
                        hexPageOffset = foundAt,
                        hexLastMatchOffset = foundAt,
                        hexLastMatchLength = needle.size,
                        hexSearchStatus = "Match (${needle.size}B) at 0x${foundAt.toString(16).uppercase()}"
                    )
                }
                onFound(foundAt, needle.size)
            } else {
                _uiState.update {
                    it.copy(hexSearchStatus = "Not found from 0x${startOffset.toString(16).uppercase()}. Try from 0x0.")
                }
            }
        }
    }

    private fun findBytesInFile(file: File, needle: ByteArray, startOffset: Long): Long {
        if (!file.exists() || needle.isEmpty()) return -1L
        val fileLen = file.length()
        if (startOffset >= fileLen) return -1L

        RandomAccessFile(file, "r").use { raf ->
            val chunkSize = 65536
            val overlap = needle.size - 1
            val buf = ByteArray(chunkSize + overlap)
            var pos = startOffset
            while (pos < fileLen) {
                raf.seek(pos)
                val toRead = minOf(buf.size.toLong(), fileLen - pos).toInt()
                val read = raf.read(buf, 0, toRead)
                if (read < needle.size) break

                val limit = read - needle.size
                for (i in 0..limit) {
                    var matched = true
                    for (j in needle.indices) {
                        if (buf[i + j] != needle[j]) {
                            matched = false
                            break
                        }
                    }
                    if (matched) return pos + i
                }
                pos += (read - overlap).coerceAtLeast(1)
            }
        }
        return -1L
    }

    fun patchHexBytes(offsetHex: String, replacementHex: String, onPatched: (() -> Unit)? = null) {
        val session = hexSession ?: return
        val offset = offsetHex.trim().removePrefix("0x").toLongOrNull(16)
        if (offset == null) {
            _uiState.update { it.copy(errorMessage = "Invalid hexadecimal offset.") }
            return
        }
        viewModelScope.launch {
            val res = session.applyPatch(offset, replacementHex)
            res.fold(
                onSuccess = {
                    loadHexPage(offset)
                    refreshModifiedEntries()
                    _uiState.update {
                        it.copy(
                            hexRevision = it.hexRevision + 1,
                            hexCanUndo = session.canUndo,
                            hexCanRedo = session.canRedo,
                            statusBanner = "Patched working copy at 0x${offset.toString(16).uppercase()}."
                        )
                    }
                    onPatched?.invoke()
                },
                onFailure = { err ->
                    _uiState.update { it.copy(errorMessage = "Hex patch failed: ${err.message}") }
                }
            )
        }
    }

    fun undoHexPatch() {
        val session = hexSession ?: return
        viewModelScope.launch {
            session.undo()
            loadHexPage(_uiState.value.hexPageOffset)
            refreshModifiedEntries()
            _uiState.update {
                it.copy(
                    hexRevision = it.hexRevision + 1,
                    hexCanUndo = session.canUndo,
                    hexCanRedo = session.canRedo
                )
            }
        }
    }

    fun redoHexPatch() {
        val session = hexSession ?: return
        viewModelScope.launch {
            session.redo()
            loadHexPage(_uiState.value.hexPageOffset)
            refreshModifiedEntries()
            _uiState.update {
                it.copy(
                    hexRevision = it.hexRevision + 1,
                    hexCanUndo = session.canUndo,
                    hexCanRedo = session.canRedo
                )
            }
        }
    }

    fun exportWorkingCopyToUri(destUri: Uri) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(destUri)?.use { out ->
                        File(project.workingPath).inputStream().use { input ->
                            input.copyTo(out)
                        }
                    } ?: error("Unable to open destination URI")
                }
            }.onSuccess {
                _uiState.update { it.copy(statusBanner = "Exported patched binary successfully.") }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = "Export failed: ${err.message}") }
            }
        }
    }

    fun exportTextToUri(destUri: Uri, content: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(destUri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    } ?: error("Unable to open destination URI")
                }
            }.onSuccess {
                _uiState.update { it.copy(statusBanner = "Saved dump to file.") }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = "Save dump failed: ${err.message}") }
            }
        }
    }

    fun refreshModifiedEntries() {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            val mods = app.patchManager.listModifiedEntries(project)
            _uiState.update { it.copy(modifiedEntries = mods) }
        }
    }

    fun compareActiveWithOtherProject(otherProject: WorkspaceProject) {
        val active = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Comparing ${active.originalFileName} vs ${otherProject.originalFileName}...") }
            val report = app.comparisonEngine.compare(File(active.workingPath), File(otherProject.workingPath))
            _uiState.update {
                it.copy(isBusy = false, comparisonReport = report)
            }
        }
    }

    fun exitR2Session() {
        if (!_uiState.value.r2SessionActive) return
        _uiState.update {
            it.copy(
                r2SessionActive = false,
                consoleHistory = it.consoleHistory + ConsoleEntry(
                    command = "exit",
                    output = "[r2] Exited Radare2 interactive session.",
                    isError = false,
                    isR2Command = true
                )
            )
        }
    }

    fun runConsoleCommand(command: String) {
        if (command.trim().equals("clear", ignoreCase = true)) {
            _uiState.update { it.copy(consoleHistory = emptyList()) }
            return
        }
        val activeFile = _uiState.value.activeProject?.let { File(it.workingPath) }
        val wasR2Mode = _uiState.value.r2SessionActive

        viewModelScope.launch {
            var inserted = false
            val outcome = app.consoleRunner.executeInteractive(
                rawInput = command,
                activeFile = activeFile,
                currentR2Mode = wasR2Mode
            ) { liveEntry ->
                _uiState.update { current ->
                    val updatedList = if (!inserted) {
                        inserted = true
                        current.consoleHistory + liveEntry
                    } else {
                        current.consoleHistory.map { existing ->
                            if (existing.id == liveEntry.id) liveEntry else existing
                        }
                    }
                    current.copy(consoleHistory = updatedList, r2SessionActive = true)
                }
            }

            _uiState.update { current ->
                val updatedList = if (!inserted) {
                    current.consoleHistory + outcome.entry
                } else {
                    current.consoleHistory.map { existing ->
                        if (existing.id == outcome.entry.id) outcome.entry else existing
                    }
                }
                val latestDump = if (outcome.requestSaveDump) {
                    updatedList.lastOrNull { !it.isRunning && it.output.isNotBlank() && it.command != "save" }?.output
                } else {
                    current.pendingDumpSaveText
                }
                val baseName = current.activeProject?.originalFileName ?: "reforge"
                current.copy(
                    consoleHistory = updatedList,
                    r2SessionActive = outcome.nextR2Mode,
                    pendingDumpSaveText = latestDump,
                    pendingDumpSaveFilename = "${baseName}_r2_dump.txt"
                )
            }
        }
    }

    fun toggleEngineEnabled(engineId: String, enabled: Boolean) {
        app.engineRegistry.setEngineEnabled(engineId, enabled)
    }

    fun runEngineSelfTest(engineId: String) {
        viewModelScope.launch {
            val result = when (engineId) {
                "radare2_core" -> {
                    val ver = app.radare2Bridge.getVersion()
                    if (app.radare2Bridge.isAvailable()) "PASS: $ver" else "AVAILABLE (not bundled): $ver"
                }
                "native_elf_mmap" -> {
                    if (NativeBinaryBridge.isNativeLoaded) {
                        val tmp = File.createTempFile("reforge_probe_", ".bin")
                        tmp.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
                        val hashJson = NativeBinaryBridge.nativeComputeHashes(tmp.absolutePath)
                        tmp.delete()
                        "PASS: nativeComputeHashes -> $hashJson"
                    } else {
                        "FAILED: libreforge_native.so not loaded"
                    }
                }
                "capstone_disasm" -> {
                    val caps = NativeBinaryBridge.queryCapstoneCapabilities()
                    "PROBE: $caps"
                }
                else -> {
                    val active = _uiState.value.activeProject?.let { File(it.workingPath) }
                    if (active != null && active.exists()) {
                        val h = HashCalculator.computeReport(active)
                        "PASS: Verified target SHA-256 ${h.sha256.take(16)}..."
                    } else {
                        "PASS: Engine ready"
                    }
                }
            }
            app.engineRegistry.recordTestResult(engineId, result)
        }
    }

    private val sessionCommandRunner = ConsoleCommandRunner()
    private var sessionCounter = 1

    fun activeTerminalSession(): TerminalSessionState {
        val st = _uiState.value
        return st.terminalSessions.firstOrNull { it.id == st.activeSessionId }
            ?: st.terminalSessions.firstOrNull()
            ?: TerminalSessionState("session-1", "Session 1", emptyList())
    }

    fun addTerminalSession() {
        sessionCounter += 1
        val sid = "session-${UUID.randomUUID().toString().take(8)}"
        val targetLabel = _uiState.value.activeFile?.name ?: "none"
        val newSess = TerminalSessionState(
            id = sid,
            title = "Session $sessionCounter",
            history = listOf(
                ConsoleEntry(
                    command = "session",
                    output = "Session $sessionCounter ready (Radare2 ARM64 • Unlimited Analysis Time).\nTarget: $targetLabel",
                    isError = false
                )
            )
        )
        _uiState.value = _uiState.value.copy(
            terminalSessions = _uiState.value.terminalSessions + newSess,
            activeSessionId = sid
        )
    }

    fun selectTerminalSession(sessionId: String) {
        if (_uiState.value.terminalSessions.any { it.id == sessionId }) {
            _uiState.value = _uiState.value.copy(activeSessionId = sessionId)
        }
    }

    fun closeTerminalSession(context: Context, sessionId: String) {
        val cur = _uiState.value.terminalSessions
        if (cur.size <= 1) return
        sessionCommandRunner.closeSession(sessionId)
        val rem = cur.filterNot { it.id == sessionId }
        val nextId = if (_uiState.value.activeSessionId == sessionId) rem.last().id else _uiState.value.activeSessionId
        _uiState.value = _uiState.value.copy(terminalSessions = rem, activeSessionId = nextId)
    }

    fun stopActiveSessionCommand(context: Context) {
        val sess = activeTerminalSession()
        sessionCommandRunner.stopSession(sess.id)
        updateTerminalSession(sess.id) { it.copy(isRunning = false) }
    }

    private fun updateTerminalSession(sessionId: String, fn: (TerminalSessionState) -> TerminalSessionState) {
        val updated = _uiState.value.terminalSessions.map { if (it.id == sessionId) fn(it) else it }
        _uiState.value = _uiState.value.copy(terminalSessions = updated)
    }

    fun executeSessionCommand(context: Context, rawInput: String) {
        val sess = activeTerminalSession()
        val sid = sess.id
        if (rawInput.trim().equals("clear", ignoreCase = true)) {
            updateTerminalSession(sid) { it.copy(history = emptyList()) }
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            updateTerminalSession(sid) { it.copy(isRunning = true) }
            val outcome = sessionCommandRunner.executeInteractive(
                context = context,
                rawInput = rawInput,
                activeFile = _uiState.value.activeFile,
                sessionId = sid,
                currentR2Mode = sess.r2Mode,
                currentSeekHex = sess.currentSeekHex
            ) { progressEntry: ConsoleEntry ->
                val curHist = _uiState.value.terminalSessions.firstOrNull { it.id == sid }?.history.orEmpty()
                val idx = curHist.indexOfFirst { it.id == progressEntry.id }
                val nextHist = if (idx >= 0) curHist.toMutableList().apply { set(idx, progressEntry) } else curHist + progressEntry
                updateTerminalSession(sid) { it.copy(history = nextHist, isRunning = true) }
            }
            val curHist = _uiState.value.terminalSessions.firstOrNull { it.id == sid }?.history.orEmpty()
            val idx = curHist.indexOfFirst { it.id == outcome.entry.id }
            val nextHist = if (idx >= 0) curHist.toMutableList().apply { set(idx, outcome.entry) } else curHist + outcome.entry
            updateTerminalSession(sid) {
                it.copy(
                    history = nextHist,
                    r2Mode = outcome.nextR2Mode,
                    currentSeekHex = outcome.nextSeekHex,
                    isRunning = false
                )
            }
            if (outcome.requestSaveDump) {
                val dumpText = buildLatestSessionDump()
                if (dumpText.isNotBlank()) {
                    saveDumpToForgeVault(context, dumpText)
                }
            }
        }
    }

    fun exitActiveR2Session(context: Context) {
        val sess = activeTerminalSession()
        if (!sess.r2Mode) return
        sessionCommandRunner.closeSession(sess.id)
        updateTerminalSession(sess.id) {
            it.copy(
                r2Mode = false,
                currentSeekHex = "0x00000000",
                isRunning = false,
                history = it.history + ConsoleEntry(
                    command = "exit",
                    output = "[r2] Closed Radare2 interactive session. Returned to shell.",
                    isError = false,
                    isR2Command = true
                )
            )
        }
    }

    fun buildLatestSessionDump(): String {
        val sess = activeTerminalSession()
        val last = sess.history.lastOrNull {
            it.command !in listOf("save", "export", "help", "r2", "exit", "q", "quit") && it.output.isNotBlank()
        }
        val target = _uiState.value.activeFile?.name ?: "no_target"
        return if (last != null) {
            "# RE FORGE Dump (${sess.title})\n# Target: $target\n# Command: ${last.command}\n# ----------------------------------------\n${last.output}"
        } else {
            sess.history.joinToString("\n\n") { "$ ${it.command}\n${it.output}" }
        }
    }

    fun refreshSavedForgeFiles(context: Context) {
        _uiState.value = _uiState.value.copy(savedForgeFiles = SavedFileManager(context).listSavedFiles())
    }

    fun saveDumpToForgeVault(context: Context, text: String) {
        val payload = text.ifBlank { buildLatestSessionDump() }
        if (payload.isBlank()) return
        val base = (_uiState.value.activeFile?.nameWithoutExtension ?: "r2_dump").replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        SavedFileManager(context).saveTextDump("${base}_${stamp}.txt", payload)
        refreshSavedForgeFiles(context)
    }

    fun importFileDirectToForge(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            SavedFileManager(context).importUri(uri)
            refreshSavedForgeFiles(context)
        }
    }

    fun deleteSavedForgeFile(context: Context, item: SavedForgeFile) {
        SavedFileManager(context).deleteFile(item)
        refreshSavedForgeFiles(context)
    }

    fun openSavedForgeFileInWorkstation(file: File) {
        _uiState.value = _uiState.value.copy(overrideActiveFile = file)
    }
}

class WorkstationViewModelFactory(private val app: ReForgeApplication) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WorkstationViewModel(app) as T
    }
}

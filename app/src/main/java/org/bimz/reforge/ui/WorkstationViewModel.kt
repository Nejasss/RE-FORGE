package org.bimz.reforge.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bimz.reforge.ReForgeApplication
import org.bimz.reforge.binary.ElfAnalysisReport
import org.bimz.reforge.binary.NativeBinaryBridge
import org.bimz.reforge.core.model.BinaryFileSummary
import org.bimz.reforge.core.model.HexPatchRecord
import org.bimz.reforge.core.security.HashCalculator
import org.bimz.reforge.core.workspace.WorkspaceStorageManager
import org.bimz.reforge.hex.HexBufferManager
import org.bimz.reforge.storage.SavedFileManager
import org.bimz.reforge.storage.SavedForgeFile
import org.bimz.reforge.terminal.ConsoleEntry
import org.bimz.reforge.terminal.EngineConsoleRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TerminalSessionState(
    val id: String,
    val title: String,
    val history: List<ConsoleEntry>,
    val r2Mode: Boolean = false,
    val currentSeekHex: String = "0x00000000",
    val isRunning: Boolean = false
)

data class WorkstationUiState(
    val activeFile: File? = null,
    val activeSummary: BinaryFileSummary? = null,
    val recentFiles: List<File> = emptyList(),
    val savedForgeFiles: List<SavedForgeFile> = emptyList(),
    val elfReport: ElfAnalysisReport? = null,
    val hexPageOffset: Long = 0L,
    val hexPageBytes: ByteArray = ByteArray(0),
    val hexPatches: List<HexPatchRecord> = emptyList(),
    val terminalSessions: List<TerminalSessionState> = emptyList(),
    val activeSessionId: String = "",
    val statusBanner: String? = null,
    val isBusy: Boolean = false
) {
    val activeSession: TerminalSessionState
        get() = terminalSessions.firstOrNull { it.id == activeSessionId }
            ?: terminalSessions.firstOrNull()
            ?: TerminalSessionState(
                id = "session-1",
                title = "Session 1",
                history = emptyList()
            )
}

class WorkstationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ReForgeApplication
    val engineRegistry = app.engineRegistry
    val radare2Bridge = app.radare2Bridge
    val workspaceManager = WorkspaceStorageManager(application)
    val savedFileManager = SavedFileManager(application)
    val hexBufferManager = HexBufferManager()
    val consoleRunner = EngineConsoleRunner(engineRegistry, radare2Bridge)

    private var sessionCounter = 1

    private val _uiState = MutableStateFlow(
        WorkstationUiState(
            recentFiles = workspaceManager.listWorkspaceFiles(),
            savedForgeFiles = savedFileManager.listSavedFiles(),
            terminalSessions = listOf(createInitialSession("Session 1", null)),
            activeSessionId = "session-1"
        )
    )
    val uiState: StateFlow<WorkstationUiState> = _uiState.asStateFlow()

    private fun createInitialSession(title: String, targetFile: File?, forcedId: String? = null): TerminalSessionState {
        val sid = forcedId ?: "session-${UUID.randomUUID().toString().take(8)}"
        val targetLabel = targetFile?.name ?: "none (open target from Home or RE FORGE)"
        return TerminalSessionState(
            id = sid,
            title = title,
            history = listOf(
                ConsoleEntry(
                    command = "session",
                    output = "$title ready (Radare2 ARM64 • Unlimited Analysis Time).\nTarget: $targetLabel\nTip: Type 'r2' to lock into persistent Radare2 mode, then run 'aaa', 'afl', 'pdf @ main', etc.",
                    isError = false
                )
            )
        )
    }

    fun addTerminalSession() {
        sessionCounter += 1
        val newSession = createInitialSession("Session $sessionCounter", _uiState.value.activeFile)
        _uiState.value = _uiState.value.copy(
            terminalSessions = _uiState.value.terminalSessions + newSession,
            activeSessionId = newSession.id
        )
    }

    fun selectTerminalSession(sessionId: String) {
        if (_uiState.value.terminalSessions.any { it.id == sessionId }) {
            _uiState.value = _uiState.value.copy(activeSessionId = sessionId)
        }
    }

    fun closeTerminalSession(sessionId: String) {
        val current = _uiState.value.terminalSessions
        if (current.size <= 1) return
        consoleRunner.closeSession(sessionId)
        val remaining = current.filterNot { it.id == sessionId }
        val nextActive = if (_uiState.value.activeSessionId == sessionId) {
            remaining.last().id
        } else {
            _uiState.value.activeSessionId
        }
        _uiState.value = _uiState.value.copy(
            terminalSessions = remaining,
            activeSessionId = nextActive
        )
    }

    fun stopActiveSessionCommand() {
        val sid = _uiState.value.activeSession.id
        consoleRunner.stopSession(sid)
        updateSession(sid) { s ->
            s.copy(isRunning = false)
        }
        _uiState.value = _uiState.value.copy(statusBanner = "Stopped active command in ${_uiState.value.activeSession.title}.")
    }

    private fun updateSession(sessionId: String, transform: (TerminalSessionState) -> TerminalSessionState) {
        val updated = _uiState.value.terminalSessions.map { session ->
            if (session.id == sessionId) transform(session) else session
        }
        _uiState.value = _uiState.value.copy(terminalSessions = updated)
    }

    fun refreshRecentFiles() {
        _uiState.value = _uiState.value.copy(
            recentFiles = workspaceManager.listWorkspaceFiles(),
            savedForgeFiles = savedFileManager.listSavedFiles()
        )
    }

    fun importFileFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isBusy = true, statusBanner = "Importing target binary into sandbox...")
            try {
                val imported = workspaceManager.importUriToWorkspace(uri)
                if (imported == null) {
                    _uiState.value = _uiState.value.copy(isBusy = false, statusBanner = "Failed to import selected file.")
                    return@launch
                }
                loadWorkspaceFileInternal(imported)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isBusy = false, statusBanner = "Import error: ${e.message}")
            }
        }
    }

    fun importFileDirectToForge(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved = savedFileManager.importUri(uri)
                _uiState.value = _uiState.value.copy(
                    savedForgeFiles = savedFileManager.listSavedFiles(),
                    statusBanner = if (saved != null) "Imported '${saved.name}' into RE FORGE." else "Failed to import file."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(statusBanner = "Import error: ${e.message}")
            }
        }
    }

    fun openExistingFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isBusy = true)
            loadWorkspaceFileInternal(file)
        }
    }

    private fun loadWorkspaceFileInternal(file: File) {
        val summary = HashCalculator.computeReport(file)
        hexBufferManager.openFile(file)
        val initialBytes = hexBufferManager.readPage(0L, 512)
        val elf = if (engineRegistry.isEngineEnabled("native_elf_mmap")) {
            NativeBinaryBridge.analyzeElf(file).getOrNull()
        } else null

        val updatedSessions = _uiState.value.terminalSessions.map { s ->
            consoleRunner.closeSession(s.id)
            s.copy(
                r2Mode = false,
                currentSeekHex = "0x00000000",
                history = s.history + ConsoleEntry(
                    command = "target",
                    output = "Loaded target: ${file.name} (${file.length()} bytes) [${summary.signatureLabel}]",
                    isError = false
                )
            )
        }

        _uiState.value = _uiState.value.copy(
            activeFile = file,
            activeSummary = summary,
            recentFiles = workspaceManager.listWorkspaceFiles(),
            savedForgeFiles = savedFileManager.listSavedFiles(),
            elfReport = elf,
            hexPageOffset = 0L,
            hexPageBytes = initialBytes,
            hexPatches = emptyList(),
            terminalSessions = updatedSessions,
            isBusy = false,
            statusBanner = "Loaded ${file.name}"
        )
    }

    fun loadHexPage(offset: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val safeOffset = offset.coerceAtLeast(0L)
            val bytes = hexBufferManager.readPage(safeOffset, 512)
            _uiState.value = _uiState.value.copy(
                hexPageOffset = safeOffset,
                hexPageBytes = bytes
            )
        }
    }

    fun patchHexByte(offset: Long, newValue: Byte) {
        hexBufferManager.stageBytePatch(offset, newValue)
        val currentOffset = _uiState.value.hexPageOffset
        _uiState.value = _uiState.value.copy(
            hexPageBytes = hexBufferManager.readPage(currentOffset, 512),
            hexPatches = hexBufferManager.getStagedPatches()
        )
    }

    fun revertHexPatches() {
        hexBufferManager.clearPatches()
        val currentOffset = _uiState.value.hexPageOffset
        _uiState.value = _uiState.value.copy(
            hexPageBytes = hexBufferManager.readPage(currentOffset, 512),
            hexPatches = emptyList(),
            statusBanner = "Staged patches cleared."
        )
    }

    fun savePatchedToForgeFolder(): SavedForgeFile? {
        val active = _uiState.value.activeFile ?: return null
        val stampedBytes = hexBufferManager.exportPatchedBytes()
        val baseName = active.nameWithoutExtension
        val ext = active.extension.ifEmpty { "bin" }
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val outName = "${baseName}_patched_${stamp}.$ext"
        val saved = savedFileManager.saveBinaryBytes(outName, stampedBytes)
        _uiState.value = _uiState.value.copy(
            savedForgeFiles = savedFileManager.listSavedFiles(),
            statusBanner = "Saved patched binary to RE FORGE/${saved.name}"
        )
        return saved
    }

    fun exportPatchedBinary(destUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = hexBufferManager.writePatchedCopy(getApplication(), destUri)
            _uiState.value = _uiState.value.copy(
                statusBanner = if (ok) "Exported patched binary successfully." else "Failed to export patched binary."
            )
        }
    }

    fun runConsoleCommand(command: String, onSaveDumpCallback: ((String) -> Unit)? = null) {
        val trimmed = command.trim()
        val session = _uiState.value.activeSession
        val sessionId = session.id

        if (trimmed.equals("clear", ignoreCase = true)) {
            updateSession(sessionId) { s -> s.copy(history = emptyList()) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            updateSession(sessionId) { s -> s.copy(isRunning = true) }
            val outcome = consoleRunner.executeInteractive(
                rawInput = command,
                activeFile = _uiState.value.activeFile,
                sessionId = sessionId,
                currentR2Mode = session.r2Mode,
                currentSeekHex = session.currentSeekHex
            ) { progressEntry ->
                val curHistory = _uiState.value.terminalSessions
                    .firstOrNull { it.id == sessionId }?.history.orEmpty()
                val existingIdx = curHistory.indexOfFirst { it.id == progressEntry.id }
                val updatedHistory = if (existingIdx >= 0) {
                    curHistory.toMutableList().apply { set(existingIdx, progressEntry) }
                } else {
                    curHistory + progressEntry
                }
                updateSession(sessionId) { s -> s.copy(history = updatedHistory, isRunning = true) }
            }

            val curHistory = _uiState.value.terminalSessions
                .firstOrNull { it.id == sessionId }?.history.orEmpty()
            val finalIdx = curHistory.indexOfFirst { it.id == outcome.entry.id }
            val updatedHistory = if (finalIdx >= 0) {
                curHistory.toMutableList().apply { set(finalIdx, outcome.entry) }
            } else {
                curHistory + outcome.entry
            }

            updateSession(sessionId) { s ->
                s.copy(
                    history = updatedHistory,
                    r2Mode = outcome.nextR2Mode,
                    currentSeekHex = outcome.nextSeekHex,
                    isRunning = false
                )
            }

            if (outcome.requestSaveDump) {
                val dumpText = buildLatestDumpText()
                if (dumpText.isNotBlank()) {
                    saveDumpDirectToForge(dumpText)
                    onSaveDumpCallback?.invoke(dumpText)
                }
            }
        }
    }

    fun exitR2Mode() {
        val session = _uiState.value.activeSession
        if (!session.r2Mode) return
        consoleRunner.closeSession(session.id)
        updateSession(session.id) { s ->
            s.copy(
                r2Mode = false,
                currentSeekHex = "0x00000000",
                isRunning = false,
                history = s.history + ConsoleEntry(
                    command = "exit",
                    output = "[r2] Closed Radare2 interactive session. Returned to workstation shell.",
                    isError = false,
                    isR2Command = true
                )
            )
        }
    }

    fun buildLatestDumpText(): String {
        val session = _uiState.value.activeSession
        val lastMeaningful = session.history.lastOrNull {
            it.command !in listOf("save", "export", "help", "r2", "exit", "q", "quit") && it.output.isNotBlank()
        }
        val targetName = _uiState.value.activeFile?.name ?: "no_target"
        return if (lastMeaningful != null) {
            buildString {
                appendLine("# RE FORGE Dump (${session.title})")
                appendLine("# Target: $targetName")
                appendLine("# Command: ${lastMeaningful.command}")
                appendLine("# ----------------------------------------")
                appendLine(lastMeaningful.output)
            }
        } else {
            session.history.joinToString("\n\n") { "$ ${it.command}\n${it.output}" }
        }
    }

    fun saveDumpDirectToForge(text: String, customPrefix: String? = null): SavedForgeFile {
        val targetBase = (customPrefix ?: _uiState.value.activeFile?.nameWithoutExtension ?: "r2_dump")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${targetBase}_${stamp}.txt"
        val saved = savedFileManager.saveTextDump(fileName, text)
        _uiState.value = _uiState.value.copy(
            savedForgeFiles = savedFileManager.listSavedFiles(),
            statusBanner = "Saved to RE FORGE/${saved.name}"
        )
        return saved
    }

    fun saveDumpToUri(uri: Uri, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
                _uiState.value = _uiState.value.copy(statusBanner = "Dump saved to device storage.")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(statusBanner = "Save dump error: ${e.message}")
            }
        }
    }

    fun deleteSavedForgeFile(item: SavedForgeFile) {
        savedFileManager.deleteFile(item)
        _uiState.value = _uiState.value.copy(
            savedForgeFiles = savedFileManager.listSavedFiles(),
            statusBanner = "Deleted '${item.name}' from RE FORGE."
        )
    }

    fun deleteWorkspaceFile(file: File) {
        workspaceManager.deleteWorkspaceFile(file)
        val remaining = workspaceManager.listWorkspaceFiles()
        val nextActive = if (_uiState.value.activeFile?.absolutePath == file.absolutePath) null else _uiState.value.activeFile
        _uiState.value = _uiState.value.copy(
            recentFiles = remaining,
            activeFile = nextActive,
            statusBanner = "Removed ${file.name}"
        )
    }

    fun dismissBanner() {
        _uiState.value = _uiState.value.copy(statusBanner = null)
    }
}

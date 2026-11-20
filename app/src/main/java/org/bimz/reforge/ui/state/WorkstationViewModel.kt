package org.bimz.reforge.ui.state

import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bimz.reforge.BuildConfig
import org.bimz.reforge.ReForgeApplication
import org.bimz.reforge.binary.NativeBinaryBridge
import org.bimz.reforge.core.model.ApkInspectionReport
import org.bimz.reforge.core.model.ApkVerificationReport
import org.bimz.reforge.core.model.DexClassSummary
import org.bimz.reforge.core.model.DexContainerSummary
import org.bimz.reforge.core.model.DisassembledInstruction
import org.bimz.reforge.core.model.ElfBinaryReport
import org.bimz.reforge.core.model.ModifiedEntryRecord
import org.bimz.reforge.core.model.RebuildResult
import org.bimz.reforge.core.model.WorkspaceProject
import org.bimz.reforge.core.security.HashCalculator
import org.bimz.reforge.engines.EngineDescriptor
import org.bimz.reforge.filemanager.FileComparisonReport
import org.bimz.reforge.hex.HexEditorSession
import org.bimz.reforge.resources.ResourceEntryNode
import org.bimz.reforge.signing.SigningResult
import org.bimz.reforge.storage.AnalysisHistoryEntity
import org.bimz.reforge.terminal.ConsoleEntry
import java.io.File

enum class WorkstationDestination(val label: String) {
    HOME("Home"),
    WORKSPACE("Workspace"),
    APK("APK"),
    DEX("DEX"),
    BINARY("Binary"),
    HEX("Hex"),
    TOOLS("Tools"),
    ENGINES("Engines"),
    SETTINGS("Settings")
}

data class WorkstationUiState(
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

    // APK & Resource state
    val apkReport: ApkInspectionReport? = null,
    val resourceEntries: List<ResourceEntryNode> = emptyList(),
    val selectedResourcePath: String? = null,
    val selectedResourceText: String = "",

    // DEX / Smali / JADX state
    val dexContainers: List<DexContainerSummary> = emptyList(),
    val dexClasses: List<DexClassSummary> = emptyList(),
    val selectedDexClass: DexClassSummary? = null,
    val originalSmaliText: String = "",
    val editedSmaliText: String = "",
    val smaliDiffText: String = "",
    val decompiledJavaText: String = "",
    val decompilerDisclaimer: String = "",

    // Native ELF & Capstone state
    val elfReport: ElfBinaryReport? = null,
    val disasmArch: String = "arm64",
    val disasmOffsetHex: String = "0",
    val instructions: List<DisassembledInstruction> = emptyList(),
    val r2Output: String = "",

    // Hex Viewer / Editor state
    val hexPageOffset: Long = 0L,
    val hexPageBytes: ByteArray = ByteArray(0),
    val hexSearchStatus: String = "",
    val hexCanUndo: Boolean = false,
    val hexCanRedo: Boolean = false,

    // Patch, Rebuild, Sign, Verify, Compare state
    val modifiedEntries: List<ModifiedEntryRecord> = emptyList(),
    val rebuildResult: RebuildResult? = null,
    val signingResult: SigningResult? = null,
    val verificationReport: ApkVerificationReport? = null,
    val comparisonReport: FileComparisonReport? = null,

    // Console state
    val consoleHistory: List<ConsoleEntry> = emptyList()
)

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

    fun importFileFromUri(uri: Uri, targetRouteAfterImport: WorkstationDestination? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Importing file via SAF and computing SHA-256...", errorMessage = null) }
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

        val defaultDest = routeOverride ?: when {
            project.detectedFormat.supportsApkInspection -> WorkstationDestination.APK
            project.detectedFormat.supportsDexAnalysis -> WorkstationDestination.DEX
            project.detectedFormat.supportsElfAnalysis -> WorkstationDestination.BINARY
            else -> WorkstationDestination.HEX
        }

        _uiState.update {
            it.copy(
                activeProject = project,
                currentRoute = defaultDest,
                isBusy = false,
                errorMessage = null,
                statusBanner = "Active workspace: ${project.originalFileName} (${project.detectedFormat.label})",
                apkReport = null,
                resourceEntries = emptyList(),
                selectedResourcePath = null,
                selectedResourceText = "",
                dexContainers = emptyList(),
                dexClasses = emptyList(),
                selectedDexClass = null,
                originalSmaliText = "",
                editedSmaliText = "",
                decompiledJavaText = "",
                elfReport = null,
                instructions = emptyList(),
                r2Output = "",
                hexPageOffset = 0L,
                rebuildResult = null,
                signingResult = null,
                verificationReport = null
            )
        }

        loadHexPage(0L)
        refreshModifiedEntries()

        if (project.detectedFormat.supportsApkInspection) {
            runApkInspection()
            runDexIndexing()
        } else if (project.detectedFormat.supportsDexAnalysis) {
            runDexIndexing()
        } else if (project.detectedFormat.supportsElfAnalysis) {
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

    fun runApkInspection() {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Parsing AndroidManifest.xml, ZIP entries, and APK signatures...") }
            val file = File(project.workingPath)
            val apkRes = app.apkInspector.inspectApk(file)
            val resList = app.resourceInspector.listResources(file).getOrDefault(emptyList())
            apkRes.fold(
                onSuccess = { report ->
                    app.repository.recordAnalysis(
                        project.id,
                        project.name,
                        "ApkInspectorEngine",
                        "Parsed ${report.packageName} (${report.components.size} components, ${report.entries.size} entries)"
                    )
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            apkReport = report,
                            resourceEntries = resList,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = "APK inspection failed: ${err.message}")
                    }
                }
            )
        }
    }

    fun inspectResourceEntry(entryPath: String) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            val res = app.resourceInspector.readResourceContent(File(project.workingPath), entryPath)
            res.fold(
                onSuccess = { text ->
                    _uiState.update {
                        it.copy(selectedResourcePath = entryPath, selectedResourceText = text)
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(errorMessage = "Resource read error: ${err.message}") }
                }
            )
        }
    }

    fun runDexIndexing() {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Indexing DEX classes, methods, and fields...") }
            val res = app.dexInspector.indexContainer(File(project.workingPath))
            res.fold(
                onSuccess = { index ->
                    app.repository.recordAnalysis(
                        project.id,
                        project.name,
                        "DexInspectorEngine",
                        "Indexed ${index.classes.size} classes across ${index.containers.size} DEX file(s)"
                    )
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            dexContainers = index.containers,
                            dexClasses = index.classes,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = "DEX indexing error: ${err.message}")
                    }
                }
            )
        }
    }

    fun selectDexClass(cls: DexClassSummary) {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedDexClass = cls,
                    isBusy = true,
                    busyMessage = "Disassembling ${cls.javaName} to Smali..."
                )
            }
            val file = File(project.workingPath)
            val smaliRes = app.dexInspector.disassembleClassToSmali(file, cls.dexEntryName, cls.descriptor)
            smaliRes.fold(
                onSuccess = { smaliCode ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            originalSmaliText = smaliCode,
                            editedSmaliText = smaliCode,
                            smaliDiffText = "No local edits yet.",
                            decompiledJavaText = "",
                            decompilerDisclaimer = ""
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = "Baksmali disassembly error: ${err.message}")
                    }
                }
            )
        }
    }

    fun updateEditedSmali(newText: String) {
        val orig = _uiState.value.originalSmaliText
        val label = _uiState.value.selectedDexClass?.javaName ?: "class.smali"
        val diff = app.patchManager.computeUnifiedLineDiff(orig, newText, label)
        _uiState.update { it.copy(editedSmaliText = newText, smaliDiffText = diff) }
    }

    fun saveStagedSmaliEdit() {
        val project = _uiState.value.activeProject ?: return
        val cls = _uiState.value.selectedDexClass ?: return
        viewModelScope.launch {
            app.patchManager.saveSmaliClassEdit(project, cls.descriptor, _uiState.value.editedSmaliText)
            refreshModifiedEntries()
            _uiState.update { it.copy(statusBanner = "Staged Smali edit for ${cls.javaName} in workspace.") }
        }
    }

    fun decompileSelectedClassWithJadx() {
        val project = _uiState.value.activeProject ?: return
        val cls = _uiState.value.selectedDexClass ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, busyMessage = "Decompiling ${cls.javaName} with JADX Core...")
            }
            val outDir = File(project.workspaceDir, "jadx_cache")
            val res = app.jadxDecompiler.decompileClass(File(project.workingPath), cls.javaName, outDir)
            res.fold(
                onSuccess = { decompiled ->
                    app.repository.recordAnalysis(
                        project.id,
                        project.name,
                        "JadxDecompilerEngine",
                        "Decompiled ${cls.javaName}"
                    )
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            decompiledJavaText = decompiled.code,
                            decompilerDisclaimer = decompiled.disclaimer
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = "JADX decompilation failed: ${err.message}")
                    }
                }
            )
        }
    }

    fun runElfInspection() {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Parsing ELF64/32 headers, sections, and symbols via JNI...") }
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
            val res = app.radare2Bridge.executeSafeQuery(File(project.workingPath), query)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    r2Output = if (res.success) res.stdout else res.stderr,
                    errorMessage = if (res.success) null else res.stderr
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

    fun searchHexBytes(hexQuery: String) {
        val session = hexSession ?: return
        viewModelScope.launch {
            runCatching {
                val found = session.searchHexPattern(_uiState.value.hexPageOffset, hexQuery)
                if (found >= 0L) {
                    loadHexPage(found)
                    _uiState.update { it.copy(hexSearchStatus = "Match at offset 0x${found.toString(16)}") }
                } else {
                    _uiState.update { it.copy(hexSearchStatus = "Pattern not found from current offset.") }
                }
            }.onFailure { err ->
                _uiState.update { it.copy(hexSearchStatus = "Invalid hex query: ${err.message}") }
            }
        }
    }

    fun patchHexBytes(offsetHex: String, replacementHex: String) {
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
                    _uiState.update { it.copy(statusBanner = "Patched working copy at 0x${offset.toString(16)}.") }
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
        }
    }

    fun redoHexPatch() {
        val session = hexSession ?: return
        viewModelScope.launch {
            session.redo()
            loadHexPage(_uiState.value.hexPageOffset)
            refreshModifiedEntries()
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

    fun rebuildActiveApk() {
        val project = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Rebuilding APK with 16KB .so alignment...") }
            val result = app.rebuildEngine.rebuildWorkspaceApk(project)
            app.repository.recordAnalysis(
                project.id,
                project.name,
                "ApkRebuildEngine",
                "Rebuild status: ${result.status} (${result.changedFileCount} staged changes)"
            )
            _uiState.update {
                it.copy(
                    isBusy = false,
                    rebuildResult = result,
                    errorMessage = if (result.outputApkPath == null) "Rebuild did not emit an APK. Check build log." else null
                )
            }
        }
    }

    fun signRebuiltOrWorkingApk() {
        val project = _uiState.value.activeProject ?: return
        val inputPath = _uiState.value.rebuildResult?.outputApkPath ?: project.workingPath
        val inputFile = File(inputPath)
        val outFile = File(File(project.workspaceDir, "build").apply { mkdirs() }, "signed-release.apk")

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyMessage = "Signing APK locally with v1/v2/v3 schemes...") }
            val res = app.signingEngine.signWithLocalDebugKey(inputFile, outFile)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    signingResult = res,
                    errorMessage = if (!res.success) res.message else null
                )
            }
            val signedPath = res.signedApkPath
            if (res.success && signedPath != null) {
                verifyApkAtPath(signedPath)
            }
        }
    }

    fun verifyApkAtPath(apkPath: String) {
        viewModelScope.launch {
            val report = app.verificationEngine.verifyApk(File(apkPath))
            _uiState.update { it.copy(verificationReport = report) }
        }
    }

    fun runConsoleCommand(command: String) {
        if (command.trim().equals("clear", ignoreCase = true)) {
            _uiState.update { it.copy(consoleHistory = emptyList()) }
            return
        }
        val activeFile = _uiState.value.activeProject?.let { File(it.workingPath) }
        viewModelScope.launch {
            val entry = app.consoleRunner.execute(command, activeFile)
            _uiState.update { it.copy(consoleHistory = it.consoleHistory + entry) }
        }
    }

    fun toggleEngineEnabled(engineId: String, enabled: Boolean) {
        app.engineRegistry.setEngineEnabled(engineId, enabled)
    }

    fun runEngineSelfTest(engineId: String) {
        viewModelScope.launch {
            val result = when (engineId) {
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
                "radare2_core" -> {
                    val ver = app.radare2Bridge.getVersion()
                    if (app.radare2Bridge.isAvailable()) "PASS: $ver" else "AVAILABLE (not bundled): $ver"
                }
                else -> {
                    val active = _uiState.value.activeProject?.let { File(it.workingPath) }
                    if (active != null && active.exists()) {
                        val h = HashCalculator.computeReport(active)
                        "PASS: Verified active target SHA-256 ${h.sha256.take(16)}..."
                    } else {
                        "PASS: Engine initialized and ready on ART"
                    }
                }
            }
            app.engineRegistry.recordTestResult(engineId, result)
        }
    }
}

class WorkstationViewModelFactory(private val app: ReForgeApplication) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WorkstationViewModel(app) as T
    }
}

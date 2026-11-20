package org.bimz.reforge

import android.app.Application
import org.bimz.reforge.apk.ApkInspectorEngine
import org.bimz.reforge.binary.NativeBinaryBridge
import org.bimz.reforge.dex.DexInspectorEngine
import org.bimz.reforge.dex.JadxDecompilerEngine
import org.bimz.reforge.engines.EngineRegistry
import org.bimz.reforge.engines.Radare2Bridge
import org.bimz.reforge.filemanager.FileComparisonEngine
import org.bimz.reforge.filemanager.FileImportManager
import org.bimz.reforge.patch.PatchWorkspaceManager
import org.bimz.reforge.rebuild.ApkRebuildEngine
import org.bimz.reforge.resources.ResourceInspectorEngine
import org.bimz.reforge.signing.ApkSigningEngine
import org.bimz.reforge.signing.ApkVerificationEngine
import org.bimz.reforge.storage.WorkspaceDatabase
import org.bimz.reforge.storage.WorkspaceRepository
import org.bimz.reforge.terminal.EngineConsoleRunner

class ReForgeApplication : Application() {

    lateinit var repository: WorkspaceRepository
        private set
    lateinit var fileImportManager: FileImportManager
        private set
    lateinit var engineRegistry: EngineRegistry
        private set
    lateinit var radare2Bridge: Radare2Bridge
        private set
    lateinit var apkInspector: ApkInspectorEngine
        private set
    lateinit var dexInspector: DexInspectorEngine
        private set
    lateinit var jadxDecompiler: JadxDecompilerEngine
        private set
    lateinit var resourceInspector: ResourceInspectorEngine
        private set
    lateinit var patchManager: PatchWorkspaceManager
        private set
    lateinit var rebuildEngine: ApkRebuildEngine
        private set
    lateinit var signingEngine: ApkSigningEngine
        private set
    lateinit var verificationEngine: ApkVerificationEngine
        private set
    lateinit var comparisonEngine: FileComparisonEngine
        private set
    lateinit var consoleRunner: EngineConsoleRunner
        private set

    override fun onCreate() {
        super.onCreate()
        val db = WorkspaceDatabase.getInstance(this)
        repository = WorkspaceRepository(this, db.workspaceDao())
        fileImportManager = FileImportManager(this, repository)
        radare2Bridge = Radare2Bridge(this)
        val capstoneInfo = NativeBinaryBridge.queryCapstoneCapabilities()
        engineRegistry = EngineRegistry(
            context = this,
            nativeLoaded = NativeBinaryBridge.isNativeLoaded,
            capstoneReportJson = capstoneInfo,
            radare2Bridge = radare2Bridge
        )
        apkInspector = ApkInspectorEngine()
        dexInspector = DexInspectorEngine()
        jadxDecompiler = JadxDecompilerEngine()
        resourceInspector = ResourceInspectorEngine()
        patchManager = PatchWorkspaceManager()
        rebuildEngine = ApkRebuildEngine(dexInspector, patchManager, resourceInspector)
        signingEngine = ApkSigningEngine(this)
        verificationEngine = ApkVerificationEngine()
        comparisonEngine = FileComparisonEngine()
        consoleRunner = EngineConsoleRunner(engineRegistry, radare2Bridge)
    }
}

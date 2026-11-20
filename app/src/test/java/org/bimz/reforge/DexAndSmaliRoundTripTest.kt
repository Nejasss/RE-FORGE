package org.bimz.reforge

import kotlinx.coroutines.runBlocking
import org.bimz.reforge.dex.DexInspectorEngine
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DexAndSmaliRoundTripTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun assemblesSmaliAndDisassemblesBack() = runBlocking {
        val smaliDir = tempFolder.newFolder("smali")
        val smaliFile = File(smaliDir, "HelloTest.smali")
        smaliFile.writeText(
            """
            .class public Lorg/bimz/test/HelloTest;
            .super Ljava/lang/Object;

            .method public static answer()I
                .locals 1
                const/16 v0, 0x2a
                return v0
            .end method
            """.trimIndent()
        )

        val engine = DexInspectorEngine()
        val outDex = File(tempFolder.root, "classes.dex")
        val asmRes = engine.assembleSmaliDirectoryToDex(smaliDir, outDex, apiLevel = 26)
        assertTrue("Assemble failed: ${asmRes.exceptionOrNull()?.stackTraceToString()}", asmRes.isSuccess)
        assertTrue("Output DEX missing or empty", outDex.exists() && outDex.length() > 0L)

        val indexRes = engine.indexContainer(outDex)
        assertTrue("Index failed: ${indexRes.exceptionOrNull()?.stackTraceToString()}", indexRes.isSuccess)
        val classes = indexRes.getOrThrow().classes
        assertTrue("Class not found in indexed classes: $classes", classes.any { it.javaName == "org.bimz.test.HelloTest" })

        val disasmRes = engine.disassembleClassToSmali(outDex, "classes.dex", "Lorg/bimz/test/HelloTest;")
        assertTrue("Disassemble failed: ${disasmRes.exceptionOrNull()?.stackTraceToString()}", disasmRes.isSuccess)
        val smaliOut = disasmRes.getOrThrow()
        assertTrue("Unexpected smali output:\n$smaliOut", smaliOut.contains("const/16 v0, 0x2a"))
    }
}

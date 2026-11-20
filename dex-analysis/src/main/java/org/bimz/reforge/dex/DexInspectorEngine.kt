package org.bimz.reforge.dex

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bimz.reforge.core.model.DexClassSummary
import org.bimz.reforge.core.model.DexContainerSummary
import org.bimz.reforge.core.model.DexFieldSummary
import org.bimz.reforge.core.model.DexMethodSummary
import java.io.File
import java.io.StringWriter

data class DexIndexResult(
    val containers: List<DexContainerSummary>,
    val classes: List<DexClassSummary>
)

class DexInspectorEngine {

    suspend fun indexContainer(targetFile: File, maxClasses: Int = 15_000): Result<DexIndexResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val opcodes = Opcodes.getDefault()
                val container: MultiDexContainer<out DexFile> =
                    DexFileFactory.loadDexContainer(targetFile, opcodes)
                val entryNames = container.dexEntryNames.sorted()
                val summaries = mutableListOf<DexContainerSummary>()
                val classes = mutableListOf<DexClassSummary>()

                for (entryName in entryNames) {
                    val dexEntry = container.getEntry(entryName) ?: continue
                    val dexFile = dexEntry.dexFile
                    var methodTotal = 0
                    var fieldTotal = 0

                    for (classDef in dexFile.classes) {
                        val methods = classDef.methods.map { m ->
                            DexMethodSummary(
                                name = m.name,
                                parameters = m.parameterTypes.map { descriptorToJava(it.toString()) },
                                returnType = descriptorToJava(m.returnType),
                                accessFlags = m.accessFlags,
                                instructionCount = m.implementation?.instructions?.count() ?: 0,
                                isDirect = m in classDef.directMethods
                            )
                        }
                        val fields = classDef.fields.map { f ->
                            DexFieldSummary(
                                name = f.name,
                                type = descriptorToJava(f.type),
                                accessFlags = f.accessFlags,
                                isStatic = f in classDef.staticFields
                            )
                        }
                        methodTotal += methods.size
                        fieldTotal += fields.size

                        if (classes.size < maxClasses) {
                            val javaName = descriptorToJava(classDef.type)
                            val pkg = javaName.substringBeforeLast('.', "(default)")
                            classes.add(
                                DexClassSummary(
                                    dexEntryName = entryName,
                                    descriptor = classDef.type,
                                    javaName = javaName,
                                    packageName = pkg,
                                    superClass = classDef.superclass?.let { descriptorToJava(it) },
                                    interfaces = classDef.interfaces.map { descriptorToJava(it) },
                                    accessFlags = classDef.accessFlags,
                                    sourceFile = classDef.sourceFile,
                                    methods = methods,
                                    fields = fields
                                )
                            )
                        }
                    }

                    summaries.add(
                        DexContainerSummary(
                            dexFileName = entryName,
                            dexVersion = "API ${dexFile.opcodes.api}",
                            apiLevel = dexFile.opcodes.api,
                            classCount = dexFile.classes.size,
                            stringCount = 0,
                            methodCount = methodTotal,
                            fieldCount = fieldTotal
                        )
                    )
                }

                DexIndexResult(
                    containers = summaries,
                    classes = classes.sortedBy { it.javaName }
                )
            }
        }

    suspend fun disassembleClassToSmali(
        targetFile: File,
        dexEntryName: String,
        classDescriptor: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val container = DexFileFactory.loadDexContainer(targetFile, Opcodes.getDefault())
            val dexEntry = container.getEntry(dexEntryName)
                ?: container.dexEntryNames.singleOrNull()?.let { container.getEntry(it) }
                ?: throw IllegalArgumentException("DEX entry not found: $dexEntryName")
            val classDef: ClassDef = dexEntry.dexFile.classes.firstOrNull { it.type == classDescriptor }
                ?: throw IllegalArgumentException("Class descriptor not found: $classDescriptor")

            val options = BaksmaliOptions()
            val stringWriter = StringWriter()
            val writer = BaksmaliWriter(stringWriter)
            val classDefinition = ClassDefinition(options, classDef)
            classDefinition.writeTo(writer)
            writer.flush()
            stringWriter.toString()
        }
    }

    suspend fun assembleSmaliDirectoryToDex(smaliDir: File, outputDex: File, apiLevel: Int = 26): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val options = SmaliOptions().apply {
                    outputDexFile = outputDex.absolutePath
                    this.apiLevel = apiLevel
                    jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                }
                val smaliFiles = smaliDir.walkTopDown()
                    .filter { it.isFile && it.extension == "smali" }
                    .map { it.absolutePath }
                    .toList()
                if (smaliFiles.isEmpty()) {
                    throw IllegalStateException("No .smali files found in ${smaliDir.absolutePath}")
                }
                val success = Smali.assemble(options, smaliFiles)
                if (!success || !outputDex.exists()) {
                    throw IllegalStateException("Smali assembly failed with syntax or register verification errors")
                }
                outputDex
            }
        }

    companion object {
        fun descriptorToJava(descriptor: String): String {
            var arrayDims = 0
            var idx = 0
            while (idx < descriptor.length && descriptor[idx] == '[') {
                arrayDims++
                idx++
            }
            val core = when (val c = descriptor.getOrNull(idx)) {
                'V' -> "void"
                'Z' -> "boolean"
                'B' -> "byte"
                'S' -> "short"
                'C' -> "char"
                'I' -> "int"
                'J' -> "long"
                'F' -> "float"
                'D' -> "double"
                'L' -> descriptor.substring(idx + 1).removeSuffix(";").replace('/', '.')
                else -> descriptor
            }
            return core + "[]".repeat(arrayDims)
        }
    }
}

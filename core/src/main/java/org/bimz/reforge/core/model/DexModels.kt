package org.bimz.reforge.core.model

data class DexContainerSummary(
    val dexFileName: String,
    val dexVersion: String,
    val apiLevel: Int,
    val classCount: Int,
    val stringCount: Int,
    val methodCount: Int,
    val fieldCount: Int
)

data class DexMethodSummary(
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val accessFlags: Int,
    val instructionCount: Int,
    val isDirect: Boolean
)

data class DexFieldSummary(
    val name: String,
    val type: String,
    val accessFlags: Int,
    val isStatic: Boolean
)

data class DexClassSummary(
    val dexEntryName: String,
    val descriptor: String,
    val javaName: String,
    val packageName: String,
    val superClass: String?,
    val interfaces: List<String>,
    val accessFlags: Int,
    val sourceFile: String?,
    val methods: List<DexMethodSummary>,
    val fields: List<DexFieldSummary>
)

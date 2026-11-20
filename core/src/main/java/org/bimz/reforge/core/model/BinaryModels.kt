package org.bimz.reforge.core.model

data class ElfSegmentModel(
    val index: Int,
    val type: Int,
    val typeName: String,
    val flags: Int,
    val fileOffset: Long,
    val virtualAddress: Long,
    val fileSize: Long,
    val memSize: Long,
    val alignment: Long
)

data class ElfSectionModel(
    val index: Int,
    val name: String,
    val type: Int,
    val flags: Long,
    val virtualAddress: Long,
    val fileOffset: Long,
    val size: Long,
    val alignment: Long
)

data class ElfSymbolModel(
    val name: String,
    val value: Long,
    val size: Long,
    val bind: Int,
    val type: Int,
    val isDynamic: Boolean,
    val isImport: Boolean
)

data class BinaryStringModel(
    val offset: Long,
    val section: String,
    val value: String
)

data class ElfBinaryReport(
    val valid: Boolean,
    val error: String,
    val bitWidth: Int,
    val littleEndian: Boolean,
    val objectType: Int,
    val machine: Int,
    val architectureName: String,
    val entryPoint: Long,
    val segments: List<ElfSegmentModel>,
    val sections: List<ElfSectionModel>,
    val symbols: List<ElfSymbolModel>,
    val dependencies: List<String>,
    val strings: List<BinaryStringModel>
)

data class DisassembledInstruction(
    val address: Long,
    val size: Int,
    val bytes: String,
    val mnemonic: String,
    val opStr: String
)

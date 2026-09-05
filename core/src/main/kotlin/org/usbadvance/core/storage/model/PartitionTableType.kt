package org.usbadvance.core.storage.model

/**
 * Esquemas de tabela de partição suportados.
 */
enum class PartitionTableType(
    val displayName: String,
    val description: String,
    val maxDiskSizeBytes: Long
) {
    MBR(
        displayName = "MBR (Master Boot Record)",
        description = "Padrão clássico. Suporta até 2 TB e até 4 partições primárias. Maior compatibilidade com equipamentos legados.",
        maxDiskSizeBytes = 2L * 1024 * 1024 * 1024 * 1024 // 2 TB
    ),
    GPT(
        displayName = "GPT (GUID Partition Table)",
        description = "Padrão moderno UEFI. Suporta discos maiores que 2 TB, até 128 partições e possui cabeçalhos redundantes com CRC32.",
        maxDiskSizeBytes = Long.MAX_VALUE
    ),
    RAW_SUPERFLOPPY(
        displayName = "Sem Partição (Superfloppy / Raw)",
        description = "O sistema de arquivos é gravado diretamente a partir do LBA 0 sem tabela MBR ou GPT.",
        maxDiskSizeBytes = Long.MAX_VALUE
    )
}

package org.usbadvance.core.storage.model

/**
 * Representação geométrica e dimensional de uma unidade de armazenamento físico.
 */
data class DiskGeometry(
    val sectorSize: Int, // Geralmente 512 ou 4096 bytes
    val totalSectors: Long, // Número total de Logical Block Addresses (LBA)
    val capacityBytes: Long = totalSectors * sectorSize
) {
    init {
        require(sectorSize > 0 && (sectorSize and (sectorSize - 1)) == 0) {
            "O tamanho do setor deve ser uma potência de 2 (ex: 512, 4096). Atual: $sectorSize"
        }
        require(totalSectors >= 0) {
            "O número total de setores não pode ser negativo. Atual: $totalSectors"
        }
    }

    /**
     * Retorna a capacidade formatada em formato legível (KB, MB, GB, TB).
     */
    fun getFormattedCapacity(): String {
        val bytes = capacityBytes
        if (bytes <= 0) return "Tocar p/ Conectar"
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.2f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}

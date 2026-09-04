package org.usbadvance.core.partition.align

/**
 * Calculador de alinhamento de partição otimizado para memórias Flash NAND (pendrives e SSDs).
 *
 * Dispositivos Flash possuem blocos de apagamento (erase blocks) de 1 MiB a 4 MiB.
 * Iniciar uma partição desalinhada causa penalidade de escrita e amplificação de desgaste.
 * Este alinhador garante que toda partição inicie em um múltiplo exato de 1 MiB (LBA 2048 para setores de 512B).
 */
object PartitionAligner {
    const val DEFAULT_ALIGNMENT_BYTES: Long = 1024 * 1024 // 1 MiB (1.048.576 bytes)

    /**
     * Calcula o primeiro LBA alinhado a 1 MiB para o setor informado.
     * Para setores de 512 bytes: 1048576 / 512 = 2048 setores.
     * Para setores de 4096 bytes: 1048576 / 4096 = 256 setores.
     */
    fun getFirstAlignedLba(sectorSize: Int): Long {
        require(sectorSize > 0) { "Tamanho do setor inválido: $sectorSize" }
        return DEFAULT_ALIGNMENT_BYTES / sectorSize
    }

    /**
     * Alinha um LBA arbitrário para cima ao múltiplo de 1 MiB mais próximo.
     */
    fun alignUp(lba: Long, sectorSize: Int): Long {
        val sectorsPerMib = DEFAULT_ALIGNMENT_BYTES / sectorSize
        val remainder = lba % sectorsPerMib
        return if (remainder == 0L) lba else lba + (sectorsPerMib - remainder)
    }

    /**
     * Alinha um LBA arbitrário para baixo ao múltiplo de 1 MiB mais próximo.
     */
    fun alignDown(lba: Long, sectorSize: Int): Long {
        val sectorsPerMib = DEFAULT_ALIGNMENT_BYTES / sectorSize
        return lba - (lba % sectorsPerMib)
    }
}

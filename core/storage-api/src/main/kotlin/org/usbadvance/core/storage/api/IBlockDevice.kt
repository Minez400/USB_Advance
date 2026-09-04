package org.usbadvance.core.storage.api

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Abstração de baixo nível para um dispositivo de bloco de armazenamento direto (setores LBA).
 * Pode ser implementada tanto via USB Mass Storage (SCSI sobre BOT em userspace)
 * quanto via nós /dev/block/sdX em dispositivos com root.
 */
interface IBlockDevice : Closeable {
    /**
     * Tamanho em bytes de cada setor físico/lógico (geralmente 512 ou 4096).
     */
    val sectorSize: Int

    /**
     * Quantidade total de blocos lógicos endereçáveis (LBAs).
     */
    val totalSectors: Long

    /**
     * Capacidade total em bytes (totalSectors * sectorSize).
     */
    val capacityBytes: Long get() = totalSectors * sectorSize

    /**
     * Lê [count] setores a partir do endereço [lba] e grava no buffer de destino [destination].
     * O buffer de destino deve possuir capacidade de ao menos count * sectorSize.
     */
    suspend fun readSectors(lba: Long, count: Int, destination: ByteBuffer)

    /**
     * Grava [count] setores a partir do endereço [lba] consumindo dados de [source].
     * O buffer de origem deve possuir ao menos count * sectorSize bytes restantes.
     */
    suspend fun writeSectors(lba: Long, count: Int, source: ByteBuffer)

    /**
     * Zera ou descarta (TRIM / SCSI UNMAP) uma faixa contígua de setores.
     */
    suspend fun eraseSectors(lba: Long, count: Int)

    /**
     * Envia comando de descarga de cache volátil (SCSI SYNCHRONIZE CACHE / flush de kernel).
     * Garante que todas as escritas anteriores foram persistidas na memória Flash NAND física.
     */
    suspend fun sync()

    /**
     * Verifica se o dispositivo possui chave física ou flag de proteção contra gravação (Write-Protect).
     */
    suspend fun isWriteProtected(): Boolean

    /**
     * Descarrega caches e estaciona o dispositivo para ejeção segura.
     */
    suspend fun eject(): Boolean = false
}

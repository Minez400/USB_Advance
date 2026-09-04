package org.usbadvance.core.usb.bot

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manipulador da máquina de estados do protocolo USB Mass Storage Bulk-Only Transport (BOT).
 */
class BotProtocolHandler(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint
) {
    private val tagGenerator = AtomicInteger(1)
    private val defaultTimeoutMs = 10000 // 10 segundos

    /**
     * Executa uma transação SCSI completa sobre BOT:
     * 1. Fase de Comando: Envia o CBW (31 bytes) pelo endpoint Bulk OUT.
     * 2. Fase de Dados (opcional): Transfere dados de/para o dispositivo.
     * 3. Fase de Status: Recebe o CSW (13 bytes) pelo endpoint Bulk IN e valida o resultado.
     */
    @Synchronized
    fun executeCommand(
        cdb: ByteArray,
        dataBuffer: ByteBuffer?,
        directionIn: Boolean
    ): CommandStatusWrapper {
        val tag = tagGenerator.getAndIncrement()
        val transferLength = dataBuffer?.remaining() ?: 0

        val cbw = CommandBlockWrapper(
            tag = tag,
            dataTransferLength = transferLength,
            directionIn = directionIn,
            cdb = cdb
        )

        // 1. Envia CBW
        val cbwBytes = cbw.serialize()
        val cbwSent = connection.bulkTransfer(outEndpoint, cbwBytes, cbwBytes.size, defaultTimeoutMs)
        if (cbwSent != CommandBlockWrapper.CBW_LENGTH) {
            throw IOException("Falha ao enviar Command Block Wrapper (CBW). Bytes enviados: $cbwSent")
        }

        // 2. Transfere dados (se houver)
        if (dataBuffer != null && transferLength > 0) {
            val transferArray = ByteArray(transferLength)
            if (!directionIn) {
                // Host to Device (Data OUT)
                val currentPos = dataBuffer.position()
                dataBuffer.get(transferArray)
                dataBuffer.position(currentPos)

                val sent = connection.bulkTransfer(outEndpoint, transferArray, transferLength, defaultTimeoutMs)
                if (sent < 0) {
                    throw IOException("Erro durante envio de dados SCSI no endpoint Bulk OUT: $sent")
                }
            } else {
                // Device to Host (Data IN)
                val received = connection.bulkTransfer(inEndpoint, transferArray, transferLength, defaultTimeoutMs)
                if (received < 0) {
                    throw IOException("Erro durante recepção de dados SCSI no endpoint Bulk IN: $received")
                }
                dataBuffer.put(transferArray, 0, received)
            }
        }

        // 3. Recebe CSW
        val cswBuffer = ByteArray(CommandStatusWrapper.CSW_LENGTH)
        val cswReceived = connection.bulkTransfer(inEndpoint, cswBuffer, cswBuffer.size, defaultTimeoutMs)
        if (cswReceived != CommandStatusWrapper.CSW_LENGTH) {
            throw IOException("Falha ao receber Command Status Wrapper (CSW). Bytes lidos: $cswReceived")
        }

        val csw = CommandStatusWrapper.parse(cswBuffer)
        if (csw.tag != tag) {
            throw IOException("Tag de transação inconsistente no CSW. Esperado: $tag, Recebido: ${csw.tag}")
        }

        return csw
    }

    /**
     * Executa a recuperação de erro Reset Recovery padrão USB Mass Storage BOT.
     */
    fun resetRecovery() {
        // Envia comando de controle Bulk-Only Mass Storage Reset (Classe de controle 0x21, Request 0xFF)
        connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, defaultTimeoutMs)
        // Limpa a condição de parada (HALT) nos endpoints
        // connection.clearHalt(inEndpoint); connection.clearHalt(outEndpoint);
    }
}

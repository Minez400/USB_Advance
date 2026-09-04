# Especificações de Protocolos: USB BOT e SCSI

## 1. USB Mass Storage Bulk-Only Transport (BOT)
A especificação USB Mass Storage Class Bulk-Only Transport (BOT) rege a troca de pacotes entre o Host (Android) e a controladora do pendrive utilizando dois endpoints Bulk (IN e OUT):

### 1.1 Command Block Wrapper (CBW) - 31 Bytes
* **Bytes 0-3**: Assinatura `0x43425355` ("USBC")
* **Bytes 4-7**: Tag única da transação (32-bit LE)
* **Bytes 8-11**: Quantidade de bytes de dados a transferir (`DataTransferLength`)
* **Byte 12**: Flags (`0x80` = Data IN / Leitura, `0x00` = Data OUT / Escrita)
* **Byte 13**: LUN (Logical Unit Number, padrão 0)
* **Byte 14**: Comprimento do comando SCSI (1 a 16 bytes)
* **Bytes 15-30**: Bloco de comando SCSI (CDB)

### 1.2 Command Status Wrapper (CSW) - 13 Bytes
* **Bytes 0-3**: Assinatura `0x53425355` ("USBS")
* **Bytes 4-7**: Tag da transação (deve coincidir com a do CBW)
* **Bytes 8-11**: Diferença de dados não transferidos (`DataResidue`)
* **Byte 12**: Código de status (`0x00` = Pass, `0x01` = Fail, `0x02` = Phase Error)

---

## 2. Comandos SCSI Implementados
* **`INQUIRY (0x12)`**: Recupera fabricante, produto e versão.
* **`READ CAPACITY 10 (0x25)`**: Obtém o total de LBAs e tamanho do setor (até 2 TB).
* **`READ CAPACITY 16 (0x9E)`**: Suporte a discos maiores que 2 TB.
* **`MODE SENSE 6 (0x1A)`**: Lê cabeçalho de parâmetros para checar a flag Write-Protect (WP).
* **`READ 10 (0x28)`**: Leitura de blocos lógicos especificados.
* **`WRITE 10 (0x2A)`**: Gravação de blocos lógicos especificados.
* **`SYNCHRONIZE CACHE 10 (0x35)`**: Descarga de cache volátil para a memória Flash física.

---

## 3. Alinhamento Flash de 1 MiB (LBA 2048)
Tanto no esquema MBR quanto no GPT, a primeira partição é posicionada rigorosamente no LBA 2048:
$$\text{Offset em bytes} = 2048 \times 512 = 1.048.576\text{ bytes } (1\text{ MiB})$$
Isso sincroniza os clusters com os limites físicos dos blocos de apagamento NAND, minimizando ciclos de re-escrita e ampliando a durabilidade da mídia.

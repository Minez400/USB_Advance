# Protocol Specifications: USB BOT and SCSI

## 1. USB Mass Storage Bulk-Only Transport (BOT)
The USB Mass Storage Class Bulk-Only Transport (BOT 1.0) specification defines packets exchanged between the Host (Android) and the storage controller using two Bulk endpoints (IN and OUT):

### 1.1 Command Block Wrapper (CBW) - 31 Bytes
* **Bytes 0-3**: Signature `0x43425355` ("USBC")
* **Bytes 4-7**: Unique 32-bit Little Endian transaction tag
* **Bytes 8-11**: Data transfer length in bytes (`DataTransferLength`)
* **Byte 12**: Flags (`0x80` = Data IN / Read, `0x00` = Data OUT / Write)
* **Byte 13**: LUN (Logical Unit Number, default 0)
* **Byte 14**: SCSI Command descriptor block length (1 to 16 bytes)
* **Bytes 15-30**: SCSI Command Descriptor Block (CDB)

### 1.2 Command Status Wrapper (CSW) - 13 Bytes
* **Bytes 0-3**: Signature `0x53425355` ("USBS")
* **Bytes 4-7**: Transaction tag (must match CBW tag)
* **Bytes 8-11**: Difference in untransferred data (`DataResidue`)
* **Byte 12**: Status code (`0x00` = Command Passed, `0x01` = Command Failed, `0x02` = Phase Error)

---

## 2. Implemented SCSI Commands
* **`INQUIRY (0x12)`**: Retrieves vendor, product, and firmware revision strings.
* **`READ CAPACITY 10 (0x25)`**: Retrieves total sector count and sector size (for drives up to 2 TB).
* **`READ CAPACITY 16 (0x9E)`**: 64-bit LBA addressing for drives exceeding 2 TB.
* **`TEST UNIT READY (0x00)`**: Verifies media readiness and clears pending power-on unit attention.
* **`REQUEST SENSE (0x03)`**: Retrieves detailed sense keys, ASC, and ASCQ error parameters.
* **`READ 10 (0x28)`**: Reads specified logical blocks.
* **`WRITE 10 (0x2A)`**: Writes specified logical blocks.
* **`SYNCHRONIZE CACHE 10 (0x35)`**: Flushes volatile controller cache to non-volatile flash memory.
* **`START STOP UNIT (0x1B)`**: Safely unloads and stops media before device disconnection.

---

## 3. 1 MiB Flash Alignment (LBA 2048)
In both MBR and GPT partitioning schemes, the first partition is strictly positioned at LBA 2048:
$$\text{Byte offset} = 2048 \times 512 = 1,048,576\text{ bytes } (1\text{ MiB})$$
This aligns cluster boundaries with the physical NAND erase blocks of modern flash controllers, maximizing read/write throughput and minimizing flash wear.

# Arquitetura do Sistema - USB Advance

## 1. Visão Geral
O **USB Advance** foi concebido sob os princípios de Clean Architecture e separação de responsabilidades. O objetivo central é fornecer capacidades de particionamento e formatação de discos USB OTG sem necessidade de privilégios de superusuário (Root), mantendo paralelamente um backend com suporte a Root para cartões MicroSD internos e nós `/dev/block/`.

---

## 2. Diagrama de Módulos e Dependências

```
               +--------------------------------------+
               |                 :app                 |
               +-------------------+------------------+
                                   |
            +----------------------+----------------------+
            |                                             |
   +--------v-------------+                      +--------v------------+
   | :feature:device-list |                      | :feature:formatter  |
   +--------+-------------+                      +--------+------------+
            |                                             |
            +----------------------+----------------------+
                                   |
                       +-----------v------------+
                       |   :core:storage-api    | <--- Contratos, Modelos e SPI
                       +-----------+------------+
                                   |
         +-------------------------+-------------------------+
         |                         |                         |
+--------v--------+       +--------v--------+       +--------v--------+
|    :core:usb    |       | :core:partition |       |   :core:root    |
| (BOT & SCSI)    |       |   (MBR & GPT)   |       | (libsu backend) |
+--------+--------+       +--------+--------+       +-----------------+
         |                         |
         +------------+------------+
                      |
             +--------v---------+
             |  :core:fs-native | <--- Motores C++20 (FAT16/32, exFAT, ext4)
             +------------------+
```

---

## 3. Padrão SPI (Service Provider Interface)

A interface central de extensão é `FilesystemProvider`:

```kotlin
interface FilesystemProvider {
    val id: String
    val filesystemType: FilesystemType
    val displayName: String
    val supportedClusterSizes: List<Int>
    fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult
    suspend fun format(
        blockDevice: IBlockDevice,
        partition: IPartition,
        options: FormatOptions,
        progressCallback: FormatProgressCallback
    ): FormatResult
}
```

Cada formato é um provedor isolado:
* `Fat32FilesystemProvider`: Formata FAT32 para compatibilidade máxima.
* `ExFatFilesystemProvider`: Formata exFAT para suportar arquivos > 4 GB.
* `Ext4FilesystemProvider`: Formata ext4 para sistemas Linux com suporte a extents.
* `Fat16FilesystemProvider`: Formata FAT16 para mídias industriais e legadas.

Para adicionar novos formatos no futuro (como F2FS, NTFS ou Btrfs), basta criar uma nova implementação de `FilesystemProvider` e registrá-la em `FilesystemRegistry.register()`, sem necessidade de alterar a interface gráfica ou a camada de transporte USB.

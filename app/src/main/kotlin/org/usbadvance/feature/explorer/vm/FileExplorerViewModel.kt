package org.usbadvance.feature.explorer.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.usbadvance.core.transfer.TransferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.usbadvance.core.partition.PartitionManager
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.transfer.TransferItem
import org.usbadvance.core.transfer.TransferManager
import org.usbadvance.core.vfs.VfsFactory
import org.usbadvance.core.vfs.api.IVirtualFileSystem
import org.usbadvance.core.vfs.model.ContiguityInfo
import org.usbadvance.core.vfs.model.FsEntry
import org.usbadvance.core.vfs.stream.LocalMediaStreamServer
import java.io.OutputStream

import org.usbadvance.core.storage.api.IPartition

data class BreadcrumbItem(
    val path: String,
    val name: String
)

data class FileExplorerUiState(
    val isMounting: Boolean = false,
    val isLoadingDirectory: Boolean = false,
    val currentPath: String = "/",
    val breadcrumbs: List<BreadcrumbItem> = listOf(BreadcrumbItem("/", "Root")),
    val entries: List<FsEntry> = emptyList(),
    val volumeLabel: String = "USB Storage",
    val filesystemType: FilesystemType = FilesystemType.EXFAT,
    val totalSpaceBytes: Long = 0L,
    val freeSpaceBytes: Long = 0L,
    val isReadOnly: Boolean = false,
    val selectedEntry: FsEntry? = null,
    val contiguityInfo: ContiguityInfo? = null,
    val isCheckingContiguity: Boolean = false,
    val streamUrl: String? = null,
    val activeTransfer: TransferItem? = null,
    val errorMessage: String? = null,
    val transientMessage: String? = null,
    val partitions: List<IPartition> = emptyList(),
    val currentPartitionIndex: Int = 0
)

class FileExplorerViewModel(
    private val transferManager: TransferManager = TransferManager()
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileExplorerUiState())
    val uiState: StateFlow<FileExplorerUiState> = _uiState.asStateFlow()

    private var activeVfs: IVirtualFileSystem? = null
    private var activeBlockDevice: IBlockDevice? = null
    private var streamServer: LocalMediaStreamServer? = null
    private var currentStorageDevice: IStorageDevice? = null
    private var mountedDeviceId: String? = null

    /** Serializes every operation that modifies the filesystem (FAT/bitmap/directory writes). */
    private val writeMutex = Mutex()
    private var loadJob: Job? = null
    private var contiguityJob: Job? = null

    /** Short-lived feedback message (shown as a Snackbar) that does not hide the file list. */
    fun consumeTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun notify(message: String) {
        _uiState.update { it.copy(transientMessage = message) }
    }

    /**
     * Mounts [device] unless it is already mounted (e.g. screen re-entered after the
     * document picker). Use [force] for explicit retries or partition switches.
     */
    fun mountDevice(device: IStorageDevice, partitionIndex: Int? = null, force: Boolean = false) {
        if (!force && partitionIndex == null && mountedDeviceId == device.id && activeVfs != null) return
        if (_uiState.value.activeTransfer?.status == TransferStatus.IN_PROGRESS) {
            notify("Aguarde a transferência terminar antes de remontar a unidade.")
            return
        }
        currentStorageDevice = device
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isMounting = true, errorMessage = null) }
            writeMutex.withLock {
            try {
                // Ensure previous devices/servers are closed before opening new session
                runCatching { streamServer?.close() }
                runCatching { activeVfs?.close() }
                runCatching { activeBlockDevice?.close() }
                streamServer = null
                activeVfs = null
                activeBlockDevice = null
                mountedDeviceId = null

                val blockDevice = device.openBlockDevice()
                activeBlockDevice = blockDevice

                // Resolve partitions
                val partitions = if (device.partitions.isNotEmpty()) {
                    device.partitions
                } else {
                    PartitionManager.scanPartitions(blockDevice)
                }

                // Prepare candidates to mount
                val candidatesToTry: List<Pair<Int, IPartition?>> = if (partitionIndex != null) {
                    listOf(partitionIndex to partitions.getOrNull(partitionIndex))
                } else if (partitions.isNotEmpty()) {
                    // Try data partitions first (exFAT, FAT32, NTFS, or largest partition), then others
                    val prioritized = partitions.withIndex().sortedWith(
                        compareByDescending<IndexedValue<IPartition>> {
                            it.value.filesystem in setOf(FilesystemType.EXFAT, FilesystemType.FAT32, FilesystemType.NTFS, FilesystemType.EXT4)
                        }.thenByDescending { it.value.sizeBytes }
                    )
                    prioritized.map { it.index to it.value } + listOf(0 to null) // Superfloppy fallback
                } else {
                    listOf(0 to null) // Single raw partition / Superfloppy at LBA 0
                }

                var mountedVfs: IVirtualFileSystem? = null
                var mountedIndex = 0
                var lastError: Throwable? = null

                for ((idx, part) in candidatesToTry) {
                    val startLba = part?.startLba ?: 0L
                    val sectorCount = part?.sectorCount ?: blockDevice.totalSectors

                    val result = VfsFactory.mount(blockDevice, startLba, sectorCount)
                    if (result.isSuccess) {
                        mountedVfs = result.getOrNull()
                        mountedIndex = idx
                        break
                    } else {
                        lastError = result.exceptionOrNull()
                    }
                }

                if (mountedVfs == null) {
                    _uiState.update {
                        it.copy(
                            isMounting = false,
                            errorMessage = lastError?.message ?: "Não foi possível montar a partição USB. Verifique o formato do sistema de arquivos (exFAT, FAT32, NTFS)."
                        )
                    }
                    return@withLock
                }

                val vfs = mountedVfs
                activeVfs = vfs
                mountedDeviceId = device.id

                _uiState.update {
                    it.copy(
                        isMounting = false,
                        volumeLabel = vfs.volumeLabel,
                        totalSpaceBytes = vfs.totalSpaceBytes,
                        freeSpaceBytes = vfs.freeSpaceBytes,
                        filesystemType = vfs.filesystemType,
                        isReadOnly = vfs.isReadOnly,
                        partitions = partitions,
                        currentPartitionIndex = mountedIndex,
                        currentPath = "/",
                        entries = emptyList(),
                        errorMessage = null
                    )
                }

                // Start HTTP streaming daemon (optional for media playback)
                try {
                    val server = LocalMediaStreamServer(vfs)
                    server.start()
                    streamServer = server
                } catch (se: Throwable) {
                    android.util.Log.w("FileExplorerVM", "Local media streaming server unavailable: ${se.message}")
                }
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        isMounting = false,
                        errorMessage = "Erro ao acessar unidade USB: ${e.localizedMessage ?: e.message}"
                    )
                }
                return@withLock
            }
            }
            // Load root directory (outside the lock so it is not blocked by itself)
            if (activeVfs != null) loadDirectory("/")
        }
    }

    fun switchPartition(index: Int) {
        val dev = currentStorageDevice ?: return
        mountDevice(dev, index, force = true)
    }

    fun retryMount(device: IStorageDevice) = mountDevice(device, force = true)

    fun loadDirectory(path: String) {
        val vfs = activeVfs ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingDirectory = true, errorMessage = null) }
            try {
                val rawEntries = writeMutex.withLock { vfs.listEntries(path) }

                // Sort: folders first, then alphabetical
                val sorted = rawEntries.sortedWith(
                    compareByDescending<FsEntry> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )

                val breadcrumbs = buildBreadcrumbs(path)

                _uiState.update {
                    it.copy(
                        isLoadingDirectory = false,
                        currentPath = path,
                        breadcrumbs = breadcrumbs,
                        entries = sorted,
                        freeSpaceBytes = runCatching { vfs.freeSpaceBytes }.getOrDefault(it.freeSpaceBytes),
                        contiguityInfo = null
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoadingDirectory = false,
                        errorMessage = "Erro ao ler a pasta: ${e.message}"
                    )
                }
            }
        }
    }

    /** Returns true if navigation was handled (i.e. we were not at the root). */
    fun navigateUp(): Boolean {
        val current = _uiState.value.currentPath
        if (current == "/" || current.isEmpty()) return false

        val parent = current.substringBeforeLast('/', "")
        val target = if (parent.isEmpty()) "/" else parent
        loadDirectory(target)
        return true
    }

    fun selectEntry(entry: FsEntry?) {
        contiguityJob?.cancel()
        _uiState.update { it.copy(selectedEntry = entry, contiguityInfo = null, isCheckingContiguity = false) }
        if (entry != null && !entry.isDirectory) {
            checkContiguity(entry)
        }
    }

    fun checkContiguity(entry: FsEntry) {
        val vfs = activeVfs ?: return
        contiguityJob?.cancel()
        contiguityJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isCheckingContiguity = true) }
            try {
                val info = writeMutex.withLock { vfs.checkContiguity(entry.fullPath) }
                _uiState.update {
                    if (it.selectedEntry?.fullPath == entry.fullPath)
                        it.copy(isCheckingContiguity = false, contiguityInfo = info)
                    else it.copy(isCheckingContiguity = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(isCheckingContiguity = false) }
            }
        }
    }

    fun getStreamUrl(entry: FsEntry): String? {
        val server = streamServer ?: return null
        if (!server.isRunning) return null
        return server.getStreamUrl(entry.fullPath)
    }

    /** Imports several files sequentially (parallel writes would corrupt the allocation table). */
    fun importFilesFromUris(uris: List<android.net.Uri>, contentResolver: android.content.ContentResolver) {
        if (uris.isEmpty()) return
        val vfs = activeVfs ?: return
        if (vfs.isReadOnly) {
            notify("A unidade USB está montada em modo somente leitura.")
            return
        }
        val targetDir = _uiState.value.currentPath
        viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            for (uri in uris) {
                if (importSingle(vfs, uri, contentResolver, targetDir)) ok++
            }
            _uiState.update { it.copy(activeTransfer = null) }
            if (_uiState.value.currentPath == targetDir) loadDirectory(targetDir)
            if (ok > 0) {
                notify(
                    if (ok == uris.size) "$ok arquivo(s) copiado(s) para o USB."
                    else "$ok de ${uris.size} arquivo(s) copiado(s)."
                )
            }
        }
    }

    fun importFileFromUri(uri: android.net.Uri, contentResolver: android.content.ContentResolver) =
        importFilesFromUris(listOf(uri), contentResolver)

    private suspend fun importSingle(
        vfs: IVirtualFileSystem,
        uri: android.net.Uri,
        contentResolver: android.content.ContentResolver,
        currentDir: String
    ): Boolean {
        return try {
            var fileName = "arquivo_usb"
            var totalBytes = 0L

            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex != -1) {
                        fileName = it.getString(nameIndex) ?: fileName
                    }
                    if (sizeIndex != -1 && !it.isNull(sizeIndex)) {
                        totalBytes = it.getLong(sizeIndex)
                    }
                }
            }

            // Characters forbidden on FAT/exFAT/NTFS
            fileName = fileName.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trimEnd('.')
                .ifEmpty { "arquivo_usb" }

            if (totalBytes > 0 && totalBytes > vfs.freeSpaceBytes) {
                notify("Espaço insuficiente no USB para '$fileName'.")
                return false
            }
            if (totalBytes >= 4L * 1024 * 1024 * 1024 && vfs.filesystemType == FilesystemType.FAT32) {
                notify("'$fileName' tem 4 GB ou mais — o FAT32 não suporta. Use exFAT ou divida o arquivo.")
                return false
            }

            val inputStream = contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Não foi possível acessar o arquivo selecionado")

            inputStream.use { input ->
                writeMutex.withLock {
                    transferManager.importFile(
                        vfs = vfs,
                        destinationDir = currentDir,
                        fileName = fileName,
                        inputStream = input,
                        totalBytes = totalBytes
                    ) { item ->
                        _uiState.update { it.copy(activeTransfer = item) }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            notify("Erro ao copiar arquivo para USB: ${e.message}")
            false
        }
    }

    fun exportFileToUri(entry: FsEntry, destinationUri: android.net.Uri, contentResolver: android.content.ContentResolver) {
        val vfs = activeVfs ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outputStream = contentResolver.openOutputStream(destinationUri)
                    ?: throw java.io.IOException("Não foi possível abrir o local de destino no celular")

                val success = outputStream.use { out ->
                    writeMutex.withLock {
                        transferManager.exportFile(
                            vfs = vfs,
                            sourceVfsPath = entry.fullPath,
                            outputStream = out
                        ) { item ->
                            _uiState.update { it.copy(activeTransfer = item) }
                        }
                    }
                }

                if (success) {
                    notify("'${entry.name}' salvo no celular.")
                    kotlinx.coroutines.delay(1200)
                }
                _uiState.update { it.copy(activeTransfer = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(activeTransfer = null) }
                notify("Erro ao salvar arquivo no celular: ${e.message}")
            }
        }
    }

    fun cancelActiveTransfer() {
        val active = _uiState.value.activeTransfer ?: return
        transferManager.cancelTransfer(active.id)
    }

    fun createDirectory(folderName: String) {
        val vfs = activeVfs ?: return
        val trimmed = folderName.trim()
        if (trimmed.isEmpty()) return
        if (Regex("[\\\\/:*?\"<>|]").containsMatchIn(trimmed) || trimmed == "." || trimmed == "..") {
            notify("Nome de pasta inválido. Evite os caracteres \\ / : * ? \" < > |")
            return
        }
        if (vfs.isReadOnly) {
            notify("A unidade USB está montada em modo somente leitura.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = _uiState.value.currentPath
                if (_uiState.value.entries.any { it.name.equals(trimmed, ignoreCase = true) }) {
                    notify("Já existe um item chamado '$trimmed'.")
                    return@launch
                }
                val targetPath = if (current == "/" || current.isEmpty()) "/$trimmed" else "$current/$trimmed"
                val success = writeMutex.withLock { vfs.createDirectory(targetPath) }
                if (success) {
                    loadDirectory(current)
                } else {
                    notify("Não foi possível criar a pasta '$trimmed'.")
                }
            } catch (e: Throwable) {
                notify("Erro ao criar pasta: ${e.message}")
            }
        }
    }

    fun deleteEntry(entry: FsEntry) {
        val vfs = activeVfs ?: return
        if (vfs.isReadOnly) {
            notify("A unidade USB está montada em modo somente leitura.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = _uiState.value.currentPath
                val success = writeMutex.withLock { vfs.deleteEntry(entry.fullPath) }
                if (success) {
                    _uiState.update { it.copy(selectedEntry = null) }
                    loadDirectory(current)
                    notify("'${entry.name}' excluído.")
                } else {
                    notify("Não foi possível excluir '${entry.name}'. Verifique se a pasta está vazia.")
                }
            } catch (e: Throwable) {
                notify("Erro ao excluir: ${e.message}")
            }
        }
    }

    fun exportFile(entry: FsEntry, outputStream: OutputStream) {
        val vfs = activeVfs ?: return
        viewModelScope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                transferManager.exportFile(vfs, entry.fullPath, outputStream) { updatedItem ->
                    _uiState.update { it.copy(activeTransfer = updatedItem) }
                }
            }
        }
    }

    private fun buildBreadcrumbs(path: String): List<BreadcrumbItem> {
        if (path == "/" || path.isEmpty()) {
            return listOf(BreadcrumbItem("/", "Root"))
        }

        val items = mutableListOf(BreadcrumbItem("/", "Root"))
        val segments = path.split("/").filter { it.isNotEmpty() }
        var accumulated = ""

        for (seg in segments) {
            accumulated += "/$seg"
            items.add(BreadcrumbItem(accumulated, seg))
        }

        return items
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { streamServer?.close() }
        runCatching { activeVfs?.close() }
        runCatching { activeBlockDevice?.close() }
    }
}

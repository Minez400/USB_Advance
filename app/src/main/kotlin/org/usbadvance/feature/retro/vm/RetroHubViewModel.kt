package org.usbadvance.feature.retro.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.usbadvance.core.partition.PartitionManager
import org.usbadvance.core.retro.ps2.UlCfgManager
import org.usbadvance.core.retro.ps2.UlGameEntry
import org.usbadvance.core.storage.api.IPartition
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.vfs.VfsFactory
import org.usbadvance.core.vfs.api.IVirtualFileSystem
import org.usbadvance.core.vfs.model.ContiguityInfo
import org.usbadvance.core.vfs.model.FsEntry

sealed class RetroGameItem {
    abstract val title: String
    abstract val sizeBytes: Long

    data class UlGame(
        val entry: UlGameEntry
    ) : RetroGameItem() {
        override val title: String get() = entry.title
        override val sizeBytes: Long get() = entry.sizeBytes
    }

    data class IsoGame(
        val fsEntry: FsEntry,
        val contiguity: ContiguityInfo?
    ) : RetroGameItem() {
        override val title: String get() = fsEntry.name.removeSuffix(".${fsEntry.extension}")
        override val sizeBytes: Long get() = fsEntry.sizeBytes
    }
}

data class RetroHubUiState(
    val isLoading: Boolean = false,
    val games: List<RetroGameItem> = emptyList(),
    val totalGamesCount: Int = 0,
    val ulGamesCount: Int = 0,
    val isoGamesCount: Int = 0,
    val fragmentedCount: Int = 0,
    val selectedGame: RetroGameItem? = null,
    val errorMessage: String? = null
)

class RetroHubViewModel(
    private val ulCfgManager: UlCfgManager = UlCfgManager()
) : ViewModel() {

    private val _uiState = MutableStateFlow(RetroHubUiState())
    val uiState: StateFlow<RetroHubUiState> = _uiState.asStateFlow()

    private var activeVfs: IVirtualFileSystem? = null

    fun loadGames(device: IStorageDevice) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val blockDevice = device.openBlockDevice()
                val partitions = if (device.partitions.isNotEmpty()) {
                    device.partitions
                } else {
                    PartitionManager.scanPartitions(blockDevice)
                }

                val candidatesToTry: List<Pair<Int, IPartition?>> = if (partitions.isNotEmpty()) {
                    val prioritized = partitions.withIndex().sortedWith(
                        compareByDescending<IndexedValue<IPartition>> {
                            it.value.filesystem in setOf(FilesystemType.EXFAT, FilesystemType.FAT32, FilesystemType.NTFS, FilesystemType.EXT4)
                        }.thenByDescending { it.value.sizeBytes }
                    )
                    prioritized.map { it.index to it.value } + listOf(0 to null)
                } else {
                    listOf(0 to null)
                }

                var mountedVfs: IVirtualFileSystem? = null
                for ((_, part) in candidatesToTry) {
                    val startLba = part?.startLba ?: 0L
                    val sectorCount = part?.sectorCount ?: blockDevice.totalSectors
                    val mountResult = VfsFactory.mount(blockDevice, startLba, sectorCount)
                    if (mountResult.isSuccess) {
                        mountedVfs = mountResult.getOrNull()
                        break
                    }
                }

                if (mountedVfs == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Não foi possível montar a unidade exFAT/FAT32/NTFS para buscar jogos."
                        )
                    }
                    return@launch
                }
                val vfs = mountedVfs
                activeVfs = vfs

                val items = mutableListOf<RetroGameItem>()

                // 1. Scan ul.cfg catalog (USBUtil format)
                val ulGames = ulCfgManager.readCatalogFromVfs(vfs)
                for (ul in ulGames) {
                    items.add(RetroGameItem.UlGame(ul))
                }

                // 2. Scan /DVD and /CD folders for direct ISOs
                val dvdEntries = try {
                    vfs.listEntries("/DVD").filter { it.isDiscImage }
                } catch (_: Exception) {
                    emptyList()
                }

                val cdEntries = try {
                    vfs.listEntries("/CD").filter { it.isDiscImage }
                } catch (_: Exception) {
                    emptyList()
                }

                var fragmented = 0
                val allIsos = dvdEntries + cdEntries
                for (iso in allIsos) {
                    val contiguity = try {
                        vfs.checkContiguity(iso.fullPath)
                    } catch (_: Exception) {
                        null
                    }

                    if (contiguity != null && !contiguity.isContiguous) {
                        fragmented++
                    }

                    items.add(RetroGameItem.IsoGame(iso, contiguity))
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        games = items,
                        totalGamesCount = items.size,
                        ulGamesCount = ulGames.size,
                        isoGamesCount = allIsos.size,
                        fragmentedCount = fragmented
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Erro ao buscar jogos: ${e.message}"
                    )
                }
            }
        }
    }

    fun selectGame(game: RetroGameItem?) {
        _uiState.update { it.copy(selectedGame = game) }
    }

    override fun onCleared() {
        super.onCleared()
        activeVfs?.close()
    }
}

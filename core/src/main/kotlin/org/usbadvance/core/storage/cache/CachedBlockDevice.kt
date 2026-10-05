package org.usbadvance.core.storage.cache

import org.usbadvance.core.storage.api.IBlockDevice
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import kotlin.math.min

/**
 * High-performance LRU Block Sector Cache wrapping an [IBlockDevice].
 * Minimizes costly USB OTG SCSI transactions by caching frequently accessed
 * filesystem metadata structures (VBR, FAT tables, directory clusters, bitmaps).
 */
class CachedBlockDevice(
    private val delegate: IBlockDevice,
    private val maxCachedSectors: Int = 4096 // 4096 * 512B = 2 MiB default cache
) : IBlockDevice by delegate {

    private val cacheLock = Any()

    // Fixed-size LRU Map: LBA -> ByteArray of size sectorSize
    private val sectorCache = object : LinkedHashMap<Long, ByteArray>(maxCachedSectors, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean {
            return size > maxCachedSectors
        }
    }

    var cacheHits: Long = 0L
        private set
    var cacheMisses: Long = 0L
        private set

    override suspend fun readSectors(lba: Long, count: Int, destination: ByteBuffer) {
        if (count <= 0) return

        val sectorSize = delegate.sectorSize
        var allCached = true

        synchronized(cacheLock) {
            for (i in 0 until count) {
                if (!sectorCache.containsKey(lba + i)) {
                    allCached = false
                    break
                }
            }
        }

        if (allCached) {
            // Full Cache Hit
            synchronized(cacheLock) {
                cacheHits += count
                for (i in 0 until count) {
                    val data = sectorCache[lba + i]!!
                    destination.put(data)
                }
            }
            return
        }

        // Cache Miss: read from physical hardware
        val startPos = destination.position()
        delegate.readSectors(lba, count, destination)
        val endPos = destination.position()

        // Populate cache from read buffer
        val totalBytes = count * sectorSize
        if (endPos - startPos >= totalBytes) {
            val duplicate = destination.duplicate()
            duplicate.position(startPos)
            duplicate.limit(startPos + totalBytes)

            val rawData = ByteArray(totalBytes)
            duplicate.get(rawData)

            synchronized(cacheLock) {
                cacheMisses += count
                for (i in 0 until count) {
                    val sectorBytes = rawData.copyOfRange(i * sectorSize, (i + 1) * sectorSize)
                    sectorCache[lba + i] = sectorBytes
                }
            }
        }
    }

    override suspend fun writeSectors(lba: Long, count: Int, source: ByteBuffer) {
        // Invalidate dirty range in cache
        synchronized(cacheLock) {
            for (i in 0 until count) {
                sectorCache.remove(lba + i)
            }
        }
        delegate.writeSectors(lba, count, source)
    }

    override suspend fun eraseSectors(lba: Long, count: Int) {
        synchronized(cacheLock) {
            for (i in 0 until count) {
                sectorCache.remove(lba + i)
            }
        }
        delegate.eraseSectors(lba, count)
    }

    override suspend fun sync() {
        delegate.sync()
    }

    fun clearCache() {
        synchronized(cacheLock) {
            sectorCache.clear()
        }
    }

    override fun close() {
        clearCache()
        delegate.close()
    }
}

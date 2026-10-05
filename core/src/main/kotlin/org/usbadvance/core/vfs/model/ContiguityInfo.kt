package org.usbadvance.core.vfs.model

/**
 * Detailed contiguity breakdown for a file on disk.
 * Crucial for PlayStation 2 (Open PS2 Loader - OPL), Nintendo Wii, and legacy loaders
 * that require disc image files (.iso) to be 100% contiguous (0 fragments) to run.
 */
data class ContiguityInfo(
    val fileName: String,
    val totalClusters: Long,
    val fragmentCount: Int,
    val fragments: List<ClusterExtent> = emptyList()
) {
    /**
     * An ISO is 100% contiguous if and only if all its clusters form a single continuous run (1 fragment).
     */
    val isContiguous: Boolean
        get() = fragmentCount <= 1
}

/**
 * Continuous sequence of clusters on storage media.
 */
data class ClusterExtent(
    val startCluster: Long,
    val clusterCount: Long,
    val physicalStartLba: Long
)

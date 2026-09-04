package org.usbadvance.core.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android Volume Daemon (vold) communication controller for safe block device unmounting.
 */
class VoldController {

    suspend fun unmountVolume(volumeId: String): Boolean = withContext(Dispatchers.IO) {
        val result = Shell.cmd("sm unmount $volumeId").exec()
        if (result.isSuccess) return@withContext true

        // Fallback to direct vdc daemon command
        val vdcResult = Shell.cmd("vdc volume unmount $volumeId").exec()
        return@withContext vdcResult.isSuccess
    }

    suspend fun unmountMountPoint(mountPoint: String): Boolean = withContext(Dispatchers.IO) {
        val result = Shell.cmd("umount -f $mountPoint").exec()
        return@withContext result.isSuccess
    }
}

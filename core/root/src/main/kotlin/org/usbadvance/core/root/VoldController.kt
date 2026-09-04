package org.usbadvance.core.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controlador de comunicação com o Volume Daemon (vold) do Android para desmontagem segura.
 */
class VoldController {

    suspend fun unmountVolume(volumeId: String): Boolean = withContext(Dispatchers.IO) {
        val result = Shell.cmd("sm unmount $volumeId").exec()
        if (result.isSuccess) return@withContext true

        // Fallback para vdc
        val vdcResult = Shell.cmd("vdc volume unmount $volumeId").exec()
        return@withContext vdcResult.isSuccess
    }

    suspend fun unmountMountPoint(mountPoint: String): Boolean = withContext(Dispatchers.IO) {
        val result = Shell.cmd("umount -f $mountPoint").exec()
        return@withContext result.isSuccess
    }
}

package org.usbadvance.core.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utilitário de detecção de privilégios de superusuário (Root) via libsu.
 */
object RootDetector {

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext Shell.isAppGrantedRoot() == true
        } catch (e: Exception) {
            return@withContext false
        }
    }

    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell()
            return@withContext Shell.isAppGrantedRoot() == true
        } catch (e: Exception) {
            return@withContext false
        }
    }
}

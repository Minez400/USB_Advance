package org.usbadvance

import android.app.Application
import org.usbadvance.core.fs.nativebridge.ExFatFilesystemProvider
import org.usbadvance.core.fs.nativebridge.Ext4FilesystemProvider
import org.usbadvance.core.fs.nativebridge.Fat16FilesystemProvider
import org.usbadvance.core.fs.nativebridge.Fat32FilesystemProvider
import org.usbadvance.core.storage.provider.FilesystemRegistry

class UsbAdvanceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registra provedores de sistemas de arquivos no barramento central
        FilesystemRegistry.register(Fat32FilesystemProvider())
        FilesystemRegistry.register(ExFatFilesystemProvider())
        FilesystemRegistry.register(Ext4FilesystemProvider())
        FilesystemRegistry.register(Fat16FilesystemProvider())
    }
}

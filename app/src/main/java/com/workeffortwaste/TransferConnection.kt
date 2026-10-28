package com.workeffortwaste

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

/** Retains only the application-context binding across activity recreation, not the USB worker. */
class TransferConnection(application: Application) : AndroidViewModel(application) {
    var model by mutableStateOf<BackupModel?>(null)
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            model = (service as TransferService.LocalBinder).model
            model?.refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            model = null
        }
    }
    private val bound = application.bindService(
        Intent(application, TransferService::class.java), connection, Context.BIND_AUTO_CREATE,
    )

    override fun onCleared() {
        if (bound) getApplication<Application>().unbindService(connection)
        model = null
    }
}

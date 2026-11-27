package com.mapeador

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Binder client for [EngineUserService]. This is pure Android IPC plumbing
 * (binding to a Shizuku user service, building/reading [Parcel]s) — it holds
 * no mapper logic at all, which now lives entirely in the native engine.
 */
object ShizukuBridge {
    private const val TAG = "MapeadorShizuku"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindTimeoutToken = Any()

    @Volatile private var remote: IBinder? = null
    private var connection: ServiceConnection? = null
    private val pendingCallbacks = mutableListOf<(Boolean, String) -> Unit>()
    @Volatile var lastError: String = ""
        private set

    val isBound: Boolean get() = remote?.isBinderAlive == true

    fun bind(context: Context, callback: (Boolean, String) -> Unit) {
        if (!Shizuku.pingBinder()) {
            lastError = "Shizuku não está em execução"
            callback(false, lastError)
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            lastError = "B13 MAUSE PRO ainda não foi autorizado no Shizuku"
            callback(false, lastError)
            return
        }
        remote?.takeIf { it.isBinderAlive }?.let { callback(true, ""); return }
        remote = null
        if (connection != null) {
            pendingCallbacks += callback
            return
        }
        pendingCallbacks += callback

        val args = Shizuku.UserServiceArgs(ComponentName(context.applicationContext, EngineUserService::class.java))
            .daemon(false)
            .processNameSuffix("engine")
            .tag("mapeador-engine")
            .version(1)

        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote = binder
                mainHandler.post {
                    mainHandler.removeCallbacksAndMessages(bindTimeoutToken)
                    val callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    if (binder == null) {
                        lastError = "O serviço Shizuku não respondeu"
                        callbacks.forEach { it(false, lastError) }
                    } else {
                        lastError = ""
                        callbacks.forEach { it(true, "") }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
                connection = null
            }
        }
        connection = serviceConnection
        try {
            Shizuku.bindUserService(args, serviceConnection)
        } catch (error: Throwable) {
            connection = null
            lastError = error.message ?: "Não foi possível iniciar o serviço"
            Log.e(TAG, "bindUserService falhou", error)
            val callbacks = pendingCallbacks.toList()
            pendingCallbacks.clear()
            callbacks.forEach { it(false, lastError) }
            return
        }
        mainHandler.postDelayed({
            if (remote == null) {
                val callbacks = pendingCallbacks.toList()
                pendingCallbacks.clear()
                connection = null
                lastError = "Tempo esgotado ao iniciar o serviço Shizuku"
                callbacks.forEach { it(false, lastError) }
            }
        }, bindTimeoutToken, 8000L)
    }

    private fun transact(code: Int, fill: (Parcel) -> Unit = {}, read: (Parcel) -> Unit = {}): Boolean {
        val binder = remote?.takeIf { it.isBinderAlive } ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(EngineUserService.DESCRIPTOR)
            fill(data)
            binder.transact(code, data, reply, 0)
            read(reply)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Transação $code falhou", error)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun start(packageName: String, screenWidth: Int, screenHeight: Int, keymapJson: String): Boolean =
        transact(EngineUserService.TRANSACTION_START, fill = {
            it.writeString(packageName)
            it.writeInt(screenWidth)
            it.writeInt(screenHeight)
            it.writeString(keymapJson)
        })

    fun stop(): Boolean = transact(EngineUserService.TRANSACTION_STOP)

    fun updateKeymap(keymapJson: String): Boolean =
        transact(EngineUserService.TRANSACTION_UPDATE_KEYMAP, fill = { it.writeString(keymapJson) })

    fun setForeground(foreground: Boolean): Boolean =
        transact(EngineUserService.TRANSACTION_SET_FOREGROUND, fill = { it.writeInt(if (foreground) 1 else 0) })

    fun setEditorMode(enabled: Boolean): Boolean =
        transact(EngineUserService.TRANSACTION_SET_EDITOR_MODE, fill = { it.writeInt(if (enabled) 1 else 0) })

    fun diagnostics(): String {
        var result = "{}"
        transact(EngineUserService.TRANSACTION_DIAGNOSTICS, read = { result = it.readString().orEmpty() })
        return result
    }

    /** [x, y, aimMode(0/1)] in screen pixels, or null if the service is not bound. */
    fun cursorPosition(): FloatArray? {
        var result: FloatArray? = null
        transact(EngineUserService.TRANSACTION_CURSOR, read = { result = it.createFloatArray() })
        return result
    }
}

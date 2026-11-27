package com.mapeador

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Runs inside the separate process Shizuku starts with the shell identity.
 * It owns the single [NativeEngine] instance and exposes it through a small
 * hand-rolled Binder protocol (no AIDL compiler needed). [ShizukuBridge] on
 * the app side is the only caller.
 *
 * Shizuku instantiates this class with a public no-arg constructor.
 */
class EngineUserService : Binder() {
    private val engine = NativeEngine().apply { create() }

    companion object {
        private const val TAG = "MapeadorEngineService"
        const val DESCRIPTOR = "com.mapeador.IMapeadorEngine"
        const val TRANSACTION_START = FIRST_CALL_TRANSACTION
        const val TRANSACTION_STOP = FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_UPDATE_KEYMAP = FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_SET_FOREGROUND = FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_SET_EDITOR_MODE = FIRST_CALL_TRANSACTION + 4
        const val TRANSACTION_DIAGNOSTICS = FIRST_CALL_TRANSACTION + 5
        const val TRANSACTION_CURSOR = FIRST_CALL_TRANSACTION + 6
        const val TRANSACTION_DESTROY = FIRST_CALL_TRANSACTION + 7
        const val TRANSACTION_SET_KEY_CAPTURE = FIRST_CALL_TRANSACTION + 8
        const val TRANSACTION_CONSUME_CAPTURED_KEY = FIRST_CALL_TRANSACTION + 9
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(DESCRIPTOR)
            return true
        }
        return try {
            data.enforceInterface(DESCRIPTOR)
            when (code) {
                TRANSACTION_START -> {
                    val packageName = data.readString().orEmpty()
                    val width = data.readInt()
                    val height = data.readInt()
                    val keymapJson = data.readString().orEmpty()
                    engine.updateKeymap(keymapJson)
                    engine.start(packageName, width, height)
                    reply?.writeInt(1)
                    true
                }
                TRANSACTION_STOP -> {
                    engine.stop()
                    reply?.writeInt(1)
                    true
                }
                TRANSACTION_UPDATE_KEYMAP -> {
                    engine.updateKeymap(data.readString().orEmpty())
                    reply?.writeInt(1)
                    true
                }
                TRANSACTION_SET_FOREGROUND -> {
                    engine.setForeground(data.readInt() != 0)
                    reply?.writeInt(1)
                    true
                }
                TRANSACTION_SET_EDITOR_MODE -> {
                    engine.setEditorMode(data.readInt() != 0)
                    reply?.writeInt(1)
                    true
                }
                TRANSACTION_DIAGNOSTICS -> {
                    reply?.writeString(engine.diagnostics())
                    true
                }
                TRANSACTION_CURSOR -> {
                    val position = engine.cursorPosition()
                    reply?.writeFloatArray(position)
                    true
                }
                TRANSACTION_SET_KEY_CAPTURE -> {
                    engine.setKeyCapture(data.readInt() != 0)
                    reply?.writeInt(1)
                    true
                }
                TRANSACTION_CONSUME_CAPTURED_KEY -> {
                    reply?.writeString(engine.consumeCapturedKey())
                    true
                }
                TRANSACTION_DESTROY -> {
                    engine.stop()
                    engine.destroy()
                    reply?.writeInt(1)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Falha ao processar transação $code", error)
            false
        }
    }
}

package com.mapeador

/**
 * Thin JNI wrapper around the C++ engine (`app/src/main/cpp`). This class owns
 * no logic of its own — every method here just forwards into native code.
 * All of the mapper's actual behaviour (reading /dev/input, deciding what each
 * key/mouse movement means, injecting touches) lives in the native engine.
 *
 * Only ever used from inside [EngineUserService], which runs in the Shizuku
 * (shell-identity) process.
 */
class NativeEngine {
    private var handle: Long = 0L

    companion object {
        init {
            System.loadLibrary("mapeador_engine")
        }
    }

    fun create() {
        if (handle == 0L) handle = nativeCreate()
    }

    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    fun updateKeymap(keymapJson: String) {
        if (handle != 0L) nativeUpdateKeymap(handle, keymapJson)
    }

    fun start(packageName: String, screenWidth: Int, screenHeight: Int) {
        if (handle != 0L) nativeStart(handle, packageName, screenWidth, screenHeight)
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    fun setForeground(foreground: Boolean) {
        if (handle != 0L) nativeSetForeground(handle, foreground)
    }

    fun setEditorMode(enabled: Boolean) {
        if (handle != 0L) nativeSetEditorMode(handle, enabled)
    }

    fun diagnostics(): String = if (handle != 0L) nativeDiagnostics(handle) else "{}"

    /** Enables a one-shot capture: the next physical key/mouse-button pressed is reported back. */
    fun setKeyCapture(enabled: Boolean) {
        if (handle != 0L) nativeSetKeyCapture(handle, enabled)
    }

    /** Empty string if nothing was captured yet. */
    fun consumeCapturedKey(): String = if (handle != 0L) nativeConsumeCapturedKey(handle) else ""

    /** [x, y, aimMode(0/1)] in screen pixels. */
    fun cursorPosition(): FloatArray =
        if (handle != 0L) nativeCursorPosition(handle) else floatArrayOf(0f, 0f, 0f)

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeUpdateKeymap(handle: Long, keymapJson: String)
    private external fun nativeStart(handle: Long, packageName: String, screenWidth: Int, screenHeight: Int)
    private external fun nativeStop(handle: Long)
    private external fun nativeSetForeground(handle: Long, foreground: Boolean)
    private external fun nativeSetEditorMode(handle: Long, enabled: Boolean)
    private external fun nativeDiagnostics(handle: Long): String
    private external fun nativeSetKeyCapture(handle: Long, enabled: Boolean)
    private external fun nativeConsumeCapturedKey(handle: Long): String
    private external fun nativeCursorPosition(handle: Long): FloatArray
}

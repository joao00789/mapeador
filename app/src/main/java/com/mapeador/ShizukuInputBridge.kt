package com.mapeador

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.Log
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/** Small Binder bridge: the user service runs as Shizuku's shell/root identity. */
object ShizukuInputBridge {
    private const val TAG = "NexoPlayShizuku"
    private const val DESCRIPTOR = "com.mapeador.IShizukuInput"
    private const val TRANSACTION_START = IBinder.FIRST_CALL_TRANSACTION
    private const val TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 1
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var remote: IBinder? = null
    private var connection: ServiceConnection? = null
    private val pendingCallbacks = mutableListOf<(Boolean, String) -> Unit>()

    fun bind(context: Context, callback: (Boolean, String) -> Unit) {
        if (!Shizuku.pingBinder()) {
            callback(false, "Shizuku não está iniciado")
            return
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            callback(false, "NexoPlay ainda não foi autorizado no Shizuku")
            return
        }
        remote?.let {
            callback(true, serverIdentity())
            return
        }
        if (connection != null) {
            pendingCallbacks += callback
            return
        }
        pendingCallbacks += callback
        val serviceArgs = Shizuku.UserServiceArgs(
            ComponentName(context, ShizukuInputUserService::class.java)
        ).daemon(false).tag("nexoplay-input").version(1)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote = binder
                connection = this
                mainHandler.post {
                    val callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    callbacks.forEach {
                        if (binder == null) it(false, "O serviço Shizuku não respondeu")
                        else it(true, serverIdentity())
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
            Shizuku.bindUserService(serviceArgs, serviceConnection)
        } catch (error: RuntimeException) {
            connection = null
            val message = error.message ?: "Não foi possível iniciar o serviço Shizuku"
            pendingCallbacks.toList().also { pendingCallbacks.clear() }.forEach { it(false, message) }
        }
    }

    fun startMapping(context: Context, gamePackage: String, mappingJson: String) {
        bind(context) { ready, message ->
            if (!ready) {
                Log.e(TAG, "Keymap não iniciado: $message")
                return@bind
            }
            val width = context.resources.displayMetrics.widthPixels
            val height = context.resources.displayMetrics.heightPixels
            transact(TRANSACTION_START) { parcel ->
                parcel.writeString(gamePackage)
                parcel.writeString(mappingJson)
                parcel.writeInt(width)
                parcel.writeInt(height)
            }
        }
    }

    fun stopMapping() {
        transact(TRANSACTION_STOP) { }
    }

    private fun serverIdentity(): String = when (Shizuku.getUid()) {
        0 -> "serviço Shizuku ativo como root"
        2000 -> "serviço Shizuku ativo como ADB shell"
        else -> "serviço Shizuku ativo (UID ${Shizuku.getUid()})"
    }

    private fun transact(code: Int, write: (Parcel) -> Unit): Boolean {
        val binder = remote ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao chamar o serviço Shizuku", error)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

/** Runs outside the app UID, so it can observe /dev/input and inject Android touch events. */
class ShizukuInputUserService : Binder() {
    private val tag = "NexoPlayShizuku"
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var eventProcess: java.lang.Process? = null
    @Volatile private var targetPackage = ""
    @Volatile private var width = 1
    @Volatile private var height = 1
    @Volatile private var mappings = emptyMap<String, InputMapping>()
    @Volatile private var foreground = false

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            reply?.writeString(DESCRIPTOR)
            return true
        }
        data.enforceInterface(DESCRIPTOR)
        when (code) {
            TRANSACTION_START -> {
                val game = data.readString().orEmpty()
                val json = data.readString().orEmpty()
                val screenWidth = data.readInt().coerceAtLeast(1)
                val screenHeight = data.readInt().coerceAtLeast(1)
                start(game, json, screenWidth, screenHeight)
            }
            TRANSACTION_STOP -> stop()
            else -> return super.onTransact(code, data, reply, flags)
        }
        reply?.writeNoException()
        return true
    }

    private fun start(game: String, json: String, screenWidth: Int, screenHeight: Int) {
        stop()
        targetPackage = game
        width = screenWidth
        height = screenHeight
        mappings = parseMappings(json)
        if (mappings.isEmpty()) return
        running = true
        val runGeneration = ++generation
        Thread({ watchForeground(runGeneration) }, "NexoPlay-Foreground").apply { isDaemon = true }.start()
        Thread({ watchInput(runGeneration) }, "NexoPlay-USB-Input").apply { isDaemon = true }.start()
    }

    private fun stop() {
        running = false
        generation++
        eventProcess?.destroy()
        eventProcess = null
        foreground = false
    }

    private fun watchInput(runGeneration: Int) {
        var process: java.lang.Process? = null
        try {
            val startedProcess = ProcessBuilder("/system/bin/getevent", "-lt")
                .redirectErrorStream(true)
                .start()
            if (!isRunning(runGeneration)) {
                startedProcess.destroy()
                return
            }
            process = startedProcess
            eventProcess = startedProcess
            BufferedReader(InputStreamReader(startedProcess.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("permission denied", ignoreCase = true) || line.contains("could not open", ignoreCase = true)) {
                        Log.e(tag, "Shizuku não consegue acessar a entrada física: $line")
                        return@forEach
                    }
                    if (!isRunning(runGeneration)) return@forEach
                    val match = EVENT_REGEX.find(line) ?: return@forEach
                    if (match.groupValues[2] != "DOWN" || !foreground) return@forEach
                    val keyName = normalizeLinuxKey(match.groupValues[1])
                    val mapping = mappings[keyName] ?: return@forEach
                    dispatch(mapping)
                }
            }
            val exitCode = startedProcess.waitFor()
            if (exitCode != 0 && isRunning(runGeneration)) {
                Log.e(tag, "getevent terminou com código $exitCode; verifique o acesso aos dispositivos de entrada")
            }
        } catch (error: Exception) {
            Log.e(tag, "Não foi possível ler os eventos USB via Shizuku", error)
        } finally {
            if (generation == runGeneration) running = false
            if (eventProcess === process) eventProcess = null
        }
    }

    private fun watchForeground(runGeneration: Int) {
        while (isRunning(runGeneration)) {
            val active = try {
                val process = ProcessBuilder("/system/bin/dumpsys", "activity", "activities")
                    .redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                val activeLine = text.lineSequence().firstOrNull {
                    it.contains("topResumedActivity") || it.contains("mResumedActivity")
                }.orEmpty()
                activeLine.contains("$targetPackage/")
            } catch (error: Exception) {
                Log.w(tag, "Não foi possível conferir o jogo ativo", error)
                false
            }
            if (isRunning(runGeneration)) foreground = active
            try { Thread.sleep(700) } catch (_: InterruptedException) { return }
        }
    }

    private fun isRunning(runGeneration: Int): Boolean = running && generation == runGeneration

    private fun dispatch(mapping: InputMapping) {
        val x = (mapping.x * width).toInt().coerceIn(0, width - 1)
        val y = (mapping.y * height).toInt().coerceIn(0, height - 1)
        val command = when (mapping.type) {
            "TOUCH", "MOUSE_PICK" -> arrayOf("/system/bin/input", "tap", "$x", "$y")
            "SWIPE" -> {
                val endX = if (x + 96 < width) x + 96 else (x - 96).coerceAtLeast(0)
                arrayOf("/system/bin/input", "swipe", "$x", "$y", "$endX", "$y", "220")
            }
            else -> return
        }
        try {
            ProcessBuilder(*command).redirectErrorStream(true).start().waitFor()
        } catch (error: Exception) {
            Log.w(tag, "Falha ao enviar toque para o jogo", error)
        }
    }

    private fun parseMappings(json: String): Map<String, InputMapping> = try {
        val array = JSONArray(json)
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val type = item.optString("type")
                if (type !in SUPPORTED_TYPES) continue
                val key = normalizeSavedKey(item.optString("key"))
                if (key.isBlank() || key == "TECLA?" || key == "TOQUE") continue
                put(key, InputMapping(
                    type,
                    item.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                    item.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                ))
            }
        }
    } catch (error: Exception) {
        Log.e(tag, "Mapeamento inválido recebido pelo serviço Shizuku", error)
        emptyMap()
    }

    private fun normalizeLinuxKey(value: String): String {
        val normalized = value.uppercase()
        if (normalized.startsWith("BTN_")) {
            return when (normalized.removePrefix("BTN_")) {
                "LEFT" -> "MOUSE_LEFT"
                "RIGHT" -> "MOUSE_RIGHT"
                "MIDDLE" -> "MOUSE_MIDDLE"
                "SIDE" -> "MOUSE_BUTTON"
                else -> normalized.removePrefix("BTN_")
            }
        }
        val key = normalized.removePrefix("KEY_")
        return when (key) {
            "LEFT", "RIGHT", "UP", "DOWN" -> "DPAD_$key"
            "ESC" -> "ESCAPE"
            else -> key
        }
    }

    private fun normalizeSavedKey(value: String): String = when (value.uppercase().removePrefix("KEYCODE_")) {
        "MOUSE_LEFT", "BUTTON_1" -> "MOUSE_LEFT"
        "MOUSE_RIGHT", "BUTTON_2" -> "MOUSE_RIGHT"
        "MOUSE_MIDDLE", "BUTTON_3" -> "MOUSE_MIDDLE"
        "ESC" -> "ESCAPE"
        else -> value.uppercase().removePrefix("KEYCODE_").removePrefix("KEY_")
    }

    private data class InputMapping(val type: String, val x: Float, val y: Float)

    private companion object {
        const val DESCRIPTOR = "com.mapeador.IShizukuInput"
        const val TRANSACTION_START = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 1
        val SUPPORTED_TYPES = setOf("TOUCH", "SWIPE", "MOUSE_PICK")
        val EVENT_REGEX = Regex(".*EV_KEY\\s+(\\S+)\\s+(DOWN|UP).*", RegexOption.IGNORE_CASE)
    }
}

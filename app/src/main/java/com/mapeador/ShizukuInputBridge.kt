package com.mapeador

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.widget.Toast
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
            callback(false, "B13 MAUSE PRO ainda não foi autorizado no Shizuku")
            return
        }
        remote?.takeIf { it.isBinderAlive }?.let {
            callback(true, serverIdentity())
            return
        }
        remote = null
        if (connection != null) {
            pendingCallbacks += callback
            return
        }
        pendingCallbacks += callback
        val serviceArgs = Shizuku.UserServiceArgs(
            ComponentName(context, ShizukuInputUserService::class.java)
        ).daemon(false).tag("nexoplay-input").version(2)
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

    /**
     * Starts the key engine. [width] and [height] are the real display size in pixels in the
     * current rotation; the saved positions are fractions of that size.
     */
    fun startMapping(context: Context, gamePackage: String, mappingJson: String, width: Int, height: Int) {
        val appContext = context.applicationContext
        bind(appContext) { ready, message ->
            if (!ready) {
                Log.e(TAG, "Keymap não iniciado: $message")
                toast(appContext, "Keymap parado: $message")
                return@bind
            }
            val status = transact(
                TRANSACTION_START,
                { parcel ->
                    parcel.writeString(gamePackage)
                    parcel.writeString(mappingJson)
                    parcel.writeInt(width)
                    parcel.writeInt(height)
                },
                { reply -> reply.readString().orEmpty() }
            )
            if (status == null) toast(appContext, "Keymap parado: o serviço Shizuku não respondeu")
            else if (status.isNotBlank()) toast(appContext, status)
        }
    }

    fun stopMapping() {
        transact(TRANSACTION_STOP, { }, { })
    }

    private fun toast(context: Context, message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    private fun serverIdentity(): String = when (Shizuku.getUid()) {
        0 -> "serviço Shizuku ativo como root"
        2000 -> "serviço Shizuku ativo como ADB shell"
        else -> "serviço Shizuku ativo (UID ${Shizuku.getUid()})"
    }

    private fun <T> transact(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T? {
        val binder = remote?.takeIf { it.isBinderAlive } ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao chamar o serviço Shizuku", error)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

/**
 * Injects real multi-touch gestures (down / move / up) through the Android input manager.
 * It only works inside the Shizuku process, which has the shell identity (INJECT_EVENTS).
 */
private class TouchInjector {
    private class Finger(val id: Int, var x: Float, var y: Float)

    private val lock = Any()
    private val fingers = ArrayList<Finger>()
    private var downTime = 0L
    private var target: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null

    val available: Boolean get() = injectMethod != null

    init {
        resolve()
    }

    private fun resolve() {
        val eventClass = InputEvent::class.java
        val intType = Int::class.javaPrimitiveType
        for (name in listOf("android.hardware.input.InputManager", "android.hardware.input.InputManagerGlobal")) {
            try {
                val cls = Class.forName(name)
                val getInstance = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }
                val instance = getInstance.invoke(null) ?: continue
                val inject = cls.getMethod("injectInputEvent", eventClass, intType)
                inject.isAccessible = true
                target = instance
                injectMethod = inject
                Log.i(TAG, "Injeção de toques via $name")
                return
            } catch (error: Throwable) {
                Log.w(TAG, "Não deu para usar $name", error)
            }
        }
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getDeclaredMethod("getService", String::class.java)
                .invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            if (service != null) {
                val inject = service.javaClass.getMethod("injectInputEvent", eventClass, intType)
                inject.isAccessible = true
                target = service
                injectMethod = inject
                Log.i(TAG, "Injeção de toques via IInputManager")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Nenhum caminho de injeção de toques disponível", error)
        }
    }

    /** Puts a new finger on the screen and returns its id, or -1 when it could not be added. */
    fun down(x: Float, y: Float): Int = synchronized(lock) {
        val id = (0 until MAX_FINGERS).firstOrNull { candidate -> fingers.none { it.id == candidate } }
            ?: return@synchronized -1
        val first = fingers.isEmpty()
        fingers.add(Finger(id, x, y))
        if (first) {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN)
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN or ((fingers.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
        id
    }

    fun move(id: Int, x: Float, y: Float) {
        synchronized(lock) {
            val finger = fingers.firstOrNull { it.id == id }
            if (finger != null) {
                finger.x = x
                finger.y = y
                send(MotionEvent.ACTION_MOVE)
            }
        }
    }

    fun up(id: Int) {
        synchronized(lock) {
            val index = fingers.indexOfFirst { it.id == id }
            if (index >= 0) {
                if (fingers.size == 1) {
                    send(MotionEvent.ACTION_UP)
                } else {
                    send(MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                }
                fingers.removeAt(index)
            }
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            while (fingers.isNotEmpty()) up(fingers.last().id)
        }
    }

    private fun send(action: Int) {
        val method = injectMethod ?: return
        val count = fingers.size
        val properties = Array(count) { index ->
            MotionEvent.PointerProperties().apply {
                id = fingers[index].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(count) { index ->
            MotionEvent.PointerCoords().apply {
                x = fingers[index].x
                y = fingers[index].y
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, count,
            properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            method.invoke(target, event, 0)
        } catch (error: Throwable) {
            Log.w(TAG, "Falha ao injetar o toque", error)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TAG = "NexoPlayShizuku"
        const val MAX_FINGERS = 10
    }
}

/** Runs outside the app UID, so it can read /dev/input and inject Android touch events. */
class ShizukuInputUserService : Binder() {
    private val tag = "NexoPlayShizuku"
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var targetPackage = ""
    @Volatile private var width = 1
    @Volatile private var height = 1
    @Volatile private var foreground = true
    private var bindings = emptyMap<String, List<Binding>>()
    private var injector: TouchInjector? = null
    private val devices = ConcurrentHashMap<String, FileInputStream>()
    private val swipeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NexoPlay-Swipe").apply { isDaemon = true }
    }
    private val legacyExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NexoPlay-LegacyTap").apply { isDaemon = true }
    }

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
                val status = try {
                    start(game, json, screenWidth, screenHeight)
                } catch (error: Throwable) {
                    Log.e(tag, "Falha ao iniciar o keymap", error)
                    "Keymap com erro: ${error.message ?: error.javaClass.simpleName}"
                }
                reply?.writeNoException()
                reply?.writeString(status)
                return true
            }
            TRANSACTION_STOP -> stop()
            else -> return super.onTransact(code, data, reply, flags)
        }
        reply?.writeNoException()
        return true
    }

    /** Returns the message to show to the user (empty = nothing to say). */
    private fun start(game: String, json: String, screenWidth: Int, screenHeight: Int): String {
        stop()
        targetPackage = game
        width = screenWidth
        height = screenHeight
        bindings = parseMappings(json)
        if (bindings.isEmpty()) return ""

        val touch = TouchInjector()
        injector = touch
        running = true
        foreground = true
        val runGeneration = ++generation
        Thread({ watchForeground(runGeneration) }, "NexoPlay-Foreground").apply { isDaemon = true }.start()

        val denied = intArrayOf(0)
        val opened = scanDevices(runGeneration, denied)
        Thread({ watchHotplug(runGeneration) }, "NexoPlay-Hotplug").apply { isDaemon = true }.start()

        return when {
            opened == 0 && denied[0] > 0 ->
                "Sem acesso ao teclado/mouse: o Shizuku não tem permissão para ler /dev/input. " +
                    "Reinicie o Shizuku (ADB) e tente de novo."
            opened == 0 -> "Keymap ativo, mas nenhum teclado ou mouse foi encontrado. Conecte o USB."
            !touch.available ->
                "Keymap ativo em modo reduzido: só toques simples (este Android bloqueou a injeção avançada)."
            else -> "Keymap ativo • $opened dispositivo(s) de entrada"
        }
    }

    private fun stop() {
        running = false
        generation++
        devices.values.forEach { stream ->
            try { stream.close() } catch (_: Exception) { }
        }
        devices.clear()
        synchronized(lock) {
            bindings.values.flatten().forEach { it.control.reset() }
            injector?.releaseAll()
        }
        injector = null
    }

    private fun isRunning(runGeneration: Int): Boolean = running && generation == runGeneration

    // ---------------------------------------------------------------- input devices

    /** Opens every readable /dev/input/event* node that is not being read yet. */
    private fun scanDevices(runGeneration: Int, denied: IntArray): Int {
        val nodes = File("/dev/input").listFiles { file -> file.name.startsWith("event") }.orEmpty()
        for (node in nodes) {
            val path = node.absolutePath
            if (devices.containsKey(path)) continue
            try {
                val stream = FileInputStream(node)
                devices[path] = stream
                Thread({ readDevice(path, stream, runGeneration) }, "NexoPlay-${node.name}")
                    .apply { isDaemon = true }.start()
            } catch (error: Exception) {
                if (error.message?.contains("Permission denied", ignoreCase = true) == true) denied[0]++
                else Log.w(tag, "Não foi possível abrir $path", error)
            }
        }
        return devices.size
    }

    private fun watchHotplug(runGeneration: Int) {
        val ignored = intArrayOf(0)
        while (isRunning(runGeneration)) {
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
            if (isRunning(runGeneration)) scanDevices(runGeneration, ignored)
        }
    }

    /** Reads struct input_event records: timeval (2 longs), type u16, code u16, value s32. */
    private fun readDevice(path: String, stream: FileInputStream, runGeneration: Int) {
        val is64 = android.os.Process.is64Bit()
        val size = if (is64) 24 else 16
        val offset = if (is64) 16 else 8
        val buffer = ByteArray(size)
        val view = ByteBuffer.wrap(buffer).order(ByteOrder.nativeOrder())
        try {
            while (isRunning(runGeneration)) {
                var filled = 0
                while (filled < size) {
                    val count = stream.read(buffer, filled, size - filled)
                    if (count < 0) return
                    filled += count
                }
                val type = view.getShort(offset).toInt() and 0xFFFF
                val code = view.getShort(offset + 2).toInt() and 0xFFFF
                val value = view.getInt(offset + 4)
                if (type == EV_KEY && (value == 0 || value == 1)) {
                    val name = LINUX_KEYS[code] ?: continue
                    onKey(name, value == 1)
                }
            }
        } catch (error: Exception) {
            if (isRunning(runGeneration)) Log.i(tag, "Leitura de $path encerrada: ${error.message}")
        } finally {
            if (devices[path] === stream) devices.remove(path)
            try { stream.close() } catch (_: Exception) { }
        }
    }

    // ---------------------------------------------------------------- foreground game

    private fun watchForeground(runGeneration: Int) {
        while (isRunning(runGeneration)) {
            val active = try {
                val process = ProcessBuilder("/system/bin/dumpsys", "activity", "activities")
                    .redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                val resumed = text.lineSequence().filter { it.contains("ResumedActivity") }.toList()
                // If this Android prints something unexpected, do not block the keys.
                resumed.isEmpty() || resumed.any { it.contains("$targetPackage/") }
            } catch (error: Exception) {
                Log.w(tag, "Não foi possível conferir o jogo ativo", error)
                true
            }
            if (isRunning(runGeneration)) {
                if (foreground && !active) {
                    synchronized(lock) {
                        bindings.values.flatten().forEach { it.control.reset() }
                        injector?.releaseAll()
                    }
                }
                foreground = active
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
    }

    // ---------------------------------------------------------------- key handling

    private fun onKey(key: String, down: Boolean) {
        val list = bindings[key] ?: return
        if (!foreground && down) return
        synchronized(lock) {
            val touch = injector ?: return
            list.forEach { binding ->
                val control = binding.control
                when (control.type) {
                    "TOUCH", "MOUSE_PICK" -> if (down) pressTouch(touch, control) else releaseTouch(touch, control)
                    "SWIPE" -> if (down) swipe(touch, control)
                    "JOYSTICK" -> moveJoystick(touch, control, binding.direction, down)
                }
            }
        }
    }

    private fun pixelX(control: Control) = (control.x * width).coerceIn(0f, (width - 1).toFloat())
    private fun pixelY(control: Control) = (control.y * height).coerceIn(0f, (height - 1).toFloat())

    private fun pressTouch(touch: TouchInjector, control: Control) {
        if (control.finger >= 0) return
        if (!touch.available) {
            legacyTap(pixelX(control), pixelY(control))
            return
        }
        control.finger = touch.down(pixelX(control), pixelY(control))
    }

    private fun releaseTouch(touch: TouchInjector, control: Control) {
        if (control.finger < 0) return
        touch.up(control.finger)
        control.finger = -1
    }

    private fun swipe(touch: TouchInjector, control: Control) {
        val startX = pixelX(control)
        val startY = pixelY(control)
        val endX = if (startX + SWIPE_DISTANCE < width) startX + SWIPE_DISTANCE
        else (startX - SWIPE_DISTANCE).coerceAtLeast(0f)
        if (!touch.available) {
            legacyExecutor.execute {
                runInput("swipe", "${startX.toInt()}", "${startY.toInt()}", "${endX.toInt()}", "${startY.toInt()}", "220")
            }
            return
        }
        swipeExecutor.execute {
            val id = touch.down(startX, startY)
            if (id < 0) return@execute
            try {
                val steps = 8
                for (step in 1..steps) {
                    Thread.sleep(25)
                    touch.move(id, startX + (endX - startX) * step / steps, startY)
                }
            } catch (_: InterruptedException) {
            } finally {
                touch.up(id)
            }
        }
    }

    private fun moveJoystick(touch: TouchInjector, control: Control, direction: Int, down: Boolean) {
        if (!touch.available) return
        control.held.remove(direction)
        if (down) control.held.add(direction)

        val vertical = axis(control, UP, DOWN)
        val horizontal = axis(control, LEFT, RIGHT)
        if (vertical == 0 && horizontal == 0) {
            if (control.finger >= 0) touch.up(control.finger)
            control.finger = -1
            return
        }
        val length = Math.sqrt((vertical * vertical + horizontal * horizontal).toDouble()).toFloat()
        val centerX = pixelX(control)
        val centerY = pixelY(control)
        val targetX = (centerX + horizontal / length * control.range).coerceIn(0f, (width - 1).toFloat())
        val targetY = (centerY + vertical / length * control.range).coerceIn(0f, (height - 1).toFloat())
        if (control.finger < 0) control.finger = touch.down(centerX, centerY)
        if (control.finger >= 0) touch.move(control.finger, targetX, targetY)
    }

    /** -1, 0 or +1 along one axis; opposite keys cancel out unless "stopOnOpposite" is off. */
    private fun axis(control: Control, negative: Int, positive: Int): Int {
        val negativeIndex = control.held.indexOf(negative)
        val positiveIndex = control.held.indexOf(positive)
        return when {
            negativeIndex < 0 && positiveIndex < 0 -> 0
            negativeIndex < 0 -> 1
            positiveIndex < 0 -> -1
            !control.stopOnOpposite -> if (positiveIndex > negativeIndex) 1 else -1
            else -> 0
        }
    }

    private fun legacyTap(x: Float, y: Float) {
        legacyExecutor.execute { runInput("tap", "${x.toInt()}", "${y.toInt()}") }
    }

    private fun runInput(vararg arguments: String) {
        try {
            ProcessBuilder("/system/bin/input", *arguments).redirectErrorStream(true).start().waitFor()
        } catch (error: Exception) {
            Log.w(tag, "Falha ao enviar toque para o jogo", error)
        }
    }

    // ---------------------------------------------------------------- mapping parsing

    private fun parseMappings(json: String): Map<String, List<Binding>> = try {
        val array = JSONArray(json)
        val result = HashMap<String, MutableList<Binding>>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val type = item.optString("type")
            if (type !in SUPPORTED_TYPES) continue
            val control = Control(
                type,
                item.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                item.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                item.optDouble("rangePx", 0.0).toFloat().takeIf { it > 1f } ?: DEFAULT_JOYSTICK_RANGE,
                item.optBoolean("stopOnOpposite", true)
            )
            val rawKey = item.optString("key")
            if (type == "JOYSTICK") {
                val keys = if (rawKey.equals("SETAS", ignoreCase = true)) {
                    listOf("DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT")
                } else {
                    listOf("W", "S", "A", "D")
                }
                keys.forEachIndexed { direction, key ->
                    result.getOrPut(key) { ArrayList() }.add(Binding(control, direction))
                }
            } else {
                val key = normalizeSavedKey(rawKey)
                if (key.isBlank() || key == "TECLA?" || key == "TOQUE") continue
                result.getOrPut(key) { ArrayList() }.add(Binding(control, -1))
            }
        }
        result
    } catch (error: Exception) {
        Log.e(tag, "Mapeamento inválido recebido pelo serviço Shizuku", error)
        emptyMap()
    }

    private fun normalizeSavedKey(value: String): String =
        when (val key = value.trim().uppercase().removePrefix("KEYCODE_")) {
            "BUTTON_1", "MOUSE_LEFT" -> "MOUSE_LEFT"
            "BUTTON_2", "MOUSE_RIGHT" -> "MOUSE_RIGHT"
            "BUTTON_3", "MOUSE_MIDDLE" -> "MOUSE_MIDDLE"
            "ESC" -> "ESCAPE"
            "SPACEBAR" -> "SPACE"
            else -> key
        }

    private class Control(
        val type: String,
        val x: Float,
        val y: Float,
        val range: Float,
        val stopOnOpposite: Boolean
    ) {
        var finger = -1
        val held = ArrayList<Int>()

        fun reset() {
            finger = -1
            held.clear()
        }
    }

    /** [direction] is UP/DOWN/LEFT/RIGHT for joysticks and -1 for the other controls. */
    private class Binding(val control: Control, val direction: Int)

    private companion object {
        const val DESCRIPTOR = "com.mapeador.IShizukuInput"
        const val TRANSACTION_START = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 1
        const val EV_KEY = 1
        const val UP = 0
        const val DOWN = 1
        const val LEFT = 2
        const val RIGHT = 3
        const val SWIPE_DISTANCE = 96f
        const val DEFAULT_JOYSTICK_RANGE = 120f
        val SUPPORTED_TYPES = setOf("TOUCH", "SWIPE", "MOUSE_PICK", "JOYSTICK")

        /**
         * Linux input codes (input-event-codes.h) -> the Android key names the editor saves
         * (KeyEvent.keyCodeToString without the KEYCODE_ prefix).
         */
        val LINUX_KEYS: Map<Int, String> = buildMap {
            put(1, "ESCAPE")
            "1234567890".forEachIndexed { index, char -> put(2 + index, char.toString()) }
            put(12, "MINUS")
            put(13, "EQUALS")
            put(14, "DEL")
            put(15, "TAB")
            "QWERTYUIOP".forEachIndexed { index, char -> put(16 + index, char.toString()) }
            put(26, "LEFT_BRACKET")
            put(27, "RIGHT_BRACKET")
            put(28, "ENTER")
            put(29, "CTRL_LEFT")
            "ASDFGHJKL".forEachIndexed { index, char -> put(30 + index, char.toString()) }
            put(39, "SEMICOLON")
            put(40, "APOSTROPHE")
            put(41, "GRAVE")
            put(42, "SHIFT_LEFT")
            put(43, "BACKSLASH")
            "ZXCVBNM".forEachIndexed { index, char -> put(44 + index, char.toString()) }
            put(51, "COMMA")
            put(52, "PERIOD")
            put(53, "SLASH")
            put(54, "SHIFT_RIGHT")
            put(55, "NUMPAD_MULTIPLY")
            put(56, "ALT_LEFT")
            put(57, "SPACE")
            put(58, "CAPS_LOCK")
            for (index in 0..9) put(59 + index, "F${index + 1}")
            put(69, "NUM_LOCK")
            put(70, "SCROLL_LOCK")
            put(71, "NUMPAD_7")
            put(72, "NUMPAD_8")
            put(73, "NUMPAD_9")
            put(74, "NUMPAD_SUBTRACT")
            put(75, "NUMPAD_4")
            put(76, "NUMPAD_5")
            put(77, "NUMPAD_6")
            put(78, "NUMPAD_ADD")
            put(79, "NUMPAD_1")
            put(80, "NUMPAD_2")
            put(81, "NUMPAD_3")
            put(82, "NUMPAD_0")
            put(83, "NUMPAD_DOT")
            put(87, "F11")
            put(88, "F12")
            put(96, "NUMPAD_ENTER")
            put(97, "CTRL_RIGHT")
            put(98, "NUMPAD_DIVIDE")
            put(100, "ALT_RIGHT")
            put(102, "MOVE_HOME")
            put(103, "DPAD_UP")
            put(104, "PAGE_UP")
            put(105, "DPAD_LEFT")
            put(106, "DPAD_RIGHT")
            put(107, "MOVE_END")
            put(108, "DPAD_DOWN")
            put(109, "PAGE_DOWN")
            put(110, "INSERT")
            put(111, "FORWARD_DEL")
            put(125, "META_LEFT")
            put(126, "META_RIGHT")
            put(272, "MOUSE_LEFT")
            put(273, "MOUSE_RIGHT")
            put(274, "MOUSE_MIDDLE")
            put(275, "BACK")
            put(276, "FORWARD")
        }
    }
}

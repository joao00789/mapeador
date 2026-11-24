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
    private const val TRANSACTION_STATE = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val TRANSACTION_STATUS = IBinder.FIRST_CALL_TRANSACTION + 3
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
        ).daemon(false).tag("nexoplay-input").version(4)
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

    /** [full] also makes the Shizuku side forget the game (used when the floating panel is closed). */
    fun stopMapping(full: Boolean = false) {
        transact(TRANSACTION_STOP, { parcel -> parcel.writeInt(if (full) 1 else 0) }, { })
    }

    /** Live state of the engine: is the game in front, and where is the app's own mouse cursor. */
    class EngineState(
        /** 1 = game in front, 0 = not in front, 2 = could not tell, -1 = not watching yet. */
        val foreground: Int,
        val cursorVisible: Boolean,
        val cursorX: Int,
        val cursorY: Int
    )

    fun queryState(): EngineState? = transact(
        TRANSACTION_STATE,
        { },
        { reply ->
            val foreground = reply.readInt()
            val cursorVisible = reply.readInt() == 1
            val cursorX = reply.readInt()
            val cursorY = reply.readInt()
            EngineState(foreground, cursorVisible, cursorX, cursorY)
        }
    )

    /** Multi-line text for the in-game diagnostics card. */
    fun diagnostics(): String? = transact(TRANSACTION_STATUS, { }, { reply -> reply.readString().orEmpty() })

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
    private var setDisplayId: java.lang.reflect.Method? = null
    @Volatile private var sent = 0
    @Volatile private var failed = 0
    @Volatile private var lastError = ""

    val available: Boolean get() = injectMethod != null

    init {
        resolve()
        try {
            setDisplayId = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        } catch (_: Throwable) {
        }
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
            setDisplayId?.let { setter ->
                try { setter.invoke(event, 0) } catch (_: Throwable) { }
            }
            val result = method.invoke(target, event, 0)
            sent++
            if (result == false) {
                failed++
                lastError = "o Android recusou o toque"
            }
        } catch (error: Throwable) {
            failed++
            lastError = (error.cause ?: error).let { it.message ?: it.javaClass.simpleName }
            Log.w(TAG, "Falha ao injetar o toque", error)
        } finally {
            event.recycle()
        }
    }

    /** One line for the diagnostics card. */
    fun summary(): String = when {
        !available -> "indisponível neste Android"
        sent == 0 -> "pronta (nenhum toque enviado ainda)"
        failed == 0 -> "ok ($sent eventos enviados)"
        else -> "$failed falhas em $sent eventos • último erro: $lastError"
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
    @Volatile private var mouseLookActive = true
    @Volatile private var watchedPackage = ""
    /** 1 = game in front, 0 = not in front, 2 = could not tell, -1 = not watching. */
    @Volatile private var foregroundState = -1
    private var watcherStarted = false
    @Volatile private var mouseManaged = false
    @Volatile private var grabMice = false
    private val grabbedPaths = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var grabNote = ""
    private var lookControl: Control? = null
    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f
    private var cursorFinger = -1
    @Volatile private var lastKeyText = ""
    @Volatile private var lastKeyAt = 0L
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
            TRANSACTION_STOP -> {
                val full = data.readInt() == 1
                stop()
                if (full) {
                    watchedPackage = ""
                    foregroundState = -1
                }
            }
            TRANSACTION_STATE -> {
                reply?.writeNoException()
                reply?.writeInt(foregroundState)
                reply?.writeInt(if (cursorVisible()) 1 else 0)
                reply?.writeInt(cursorX.toInt())
                reply?.writeInt(cursorY.toInt())
                return true
            }
            TRANSACTION_STATUS -> {
                reply?.writeNoException()
                reply?.writeString(buildStatus())
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        reply?.writeNoException()
        return true
    }

    /** Returns the message to show to the user (empty = nothing to say). */
    private fun start(game: String, json: String, screenWidth: Int, screenHeight: Int): String {
        stop()
        targetPackage = game
        watchedPackage = game
        width = screenWidth
        height = screenHeight
        bindings = parseMappings(json)
        lookControl = bindings.values.flatten().map { it.control }.firstOrNull { it.type == "MOUSE_LOOK" }
        mouseManaged = lookControl != null || bindings.keys.any { it.startsWith("MOUSE_") }
        mouseLookActive = true
        cursorX = width / 2f
        cursorY = height / 2f
        if (foregroundState < 0) foregroundState = 2
        foreground = foregroundState != 0
        // The watcher also drives the show/hide of the floating panel, so it runs even
        // when there is no key mapped yet.
        ensureForegroundWatcher()
        if (bindings.isEmpty()) return ""

        val touch = TouchInjector()
        injector = touch
        running = true
        val runGeneration = ++generation
        grabMice = mouseManaged && foregroundState == 1

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
            else ->
                "Keymap ativo • $opened dispositivo(s) de entrada" +
                    if (lookControl != null) " • Mouse FPS pronto (a tecla configurada alterna mira/cursor)" else ""
        }
    }

    private fun stop() {
        running = false
        generation++
        grabMice = false
        grabbedPaths.clear()
        devices.values.forEach { stream ->
            try { stream.close() } catch (_: Exception) { }
        }
        devices.clear()
        synchronized(lock) {
            bindings.values.flatten().forEach { it.control.reset() }
            cursorFinger = -1
            injector?.releaseAll()
        }
        injector = null
        lookControl = null
        mouseManaged = false
    }

    private fun isRunning(runGeneration: Int): Boolean = running && generation == runGeneration

    // ---------------------------------------------------------------- input devices

    /** Opens every readable /dev/input/event* node that is not being read yet. */
    @Synchronized
    private fun scanDevices(runGeneration: Int, denied: IntArray): Int {
        val nodes = File("/dev/input").listFiles { file -> file.name.startsWith("event") }.orEmpty()
        val mice = if (grabMice) mouseEventNames() else emptySet()
        for (node in nodes) {
            val path = node.absolutePath
            if (devices.containsKey(path)) continue
            try {
                val stream = FileInputStream(node)
                // A grabbed mouse stops moving Android's own pointer: the app draws its own.
                if (grabMice && node.name in mice && grab(stream)) grabbedPaths.add(path)
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

    /** EVIOCGRAB through the hidden libcore ioctl: the kernel gives this reader the mouse alone. */
    private fun grab(stream: FileInputStream): Boolean = try {
        val libcore = Class.forName("libcore.io.Libcore")
        val os = libcore.getField("os").get(null)
        val ioctl = os.javaClass.getMethod("ioctlInt", java.io.FileDescriptor::class.java, Int::class.javaPrimitiveType)
        ioctl.isAccessible = true
        ioctl.invoke(os, stream.fd, EVIOCGRAB)
        grabNote = ""
        true
    } catch (error: Throwable) {
        grabNote = (error.cause ?: error).let { it.message ?: it.javaClass.simpleName }
        Log.w(tag, "Não foi possível capturar o mouse", error)
        false
    }

    /** event nodes (e.g. "event6") whose device reports relative movement = mice and trackballs. */
    private fun mouseEventNames(): Set<String> {
        val result = HashSet<String>()
        try {
            var handlers = ""
            var events = 0L
            fun flush() {
                if ((events and 4L) != 0L) {
                    handlers.split(' ').filter { it.startsWith("event") }.forEach { result.add(it) }
                }
            }
            File("/proc/bus/input/devices").forEachLine { line ->
                when {
                    line.startsWith("I:") -> {
                        flush()
                        handlers = ""
                        events = 0L
                    }
                    line.startsWith("H: Handlers=") -> handlers = line.substringAfter("=")
                    line.startsWith("B: EV=") -> events = line.substringAfter("=").trim().toLongOrNull(16) ?: 0L
                }
            }
            flush()
        } catch (error: Exception) {
            Log.w(tag, "Não foi possível listar os mouses", error)
        }
        return result
    }

    /**
     * Grabs the mouse only while the game is confirmed in front, and gives it back to Android
     * the moment the player leaves the game (closing the file releases the grab).
     */
    private fun refreshMouseGrab() {
        val want = running && mouseManaged && foregroundState == 1
        if (want == grabMice) return
        grabMice = want
        val mice = mouseEventNames()
        for ((path, stream) in devices.entries.toList()) {
            if (File(path).name !in mice) continue
            devices.remove(path)
            grabbedPaths.remove(path)
            try { stream.close() } catch (_: Exception) { }
        }
        if (running) scanDevices(generation, intArrayOf(0))
    }

    /** Reads struct input_event records: timeval (2 longs), type u16, code u16, value s32. */
    private fun readDevice(path: String, stream: FileInputStream, runGeneration: Int) {
        val is64 = android.os.Process.is64Bit()
        val size = if (is64) 24 else 16
        val offset = if (is64) 16 else 8
        val buffer = ByteArray(size)
        val view = ByteBuffer.wrap(buffer).order(ByteOrder.nativeOrder())
        var relativeX = 0
        var relativeY = 0
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
                when (type) {
                    EV_KEY -> if (value == 0 || value == 1) {
                        val name = LINUX_KEYS[code]
                        if (name != null) onKey(name, value == 1)
                    }
                    EV_REL -> when (code) {
                        REL_X -> relativeX += value
                        REL_Y -> relativeY += value
                    }
                    EV_SYN -> if (code == SYN_REPORT) {
                        if (relativeX != 0 || relativeY != 0) onMouseMove(relativeX, relativeY)
                        relativeX = 0
                        relativeY = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (isRunning(runGeneration)) Log.i(tag, "Leitura de $path encerrada: ${error.message}")
        } finally {
            if (devices[path] === stream) {
                devices.remove(path)
                grabbedPaths.remove(path)
            }
            try { stream.close() } catch (_: Exception) { }
        }
    }

    // ---------------------------------------------------------------- foreground game

    private fun ensureForegroundWatcher() {
        if (watcherStarted) return
        watcherStarted = true
        Thread({ watchForeground() }, "NexoPlay-Foreground").apply { isDaemon = true }.start()
    }

    /** One long-lived thread: is the game in front? 1 = yes, 0 = no, 2 = could not tell. */
    private fun watchForeground() {
        while (true) {
            val game = watchedPackage
            if (game.isEmpty()) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return }
                continue
            }
            val state = try {
                val process = ProcessBuilder("/system/bin/dumpsys", "activity", "activities")
                    .redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                val resumed = text.lineSequence().filter { it.contains("ResumedActivity") }.toList()
                when {
                    resumed.isEmpty() -> 2
                    resumed.any { it.contains("$game/") } -> 1
                    else -> 0
                }
            } catch (error: Exception) {
                Log.w(tag, "Não foi possível conferir o jogo ativo", error)
                2
            }
            if (game == watchedPackage) applyForeground(state)
            try { Thread.sleep(if (state == 0) 1200 else 900) } catch (_: InterruptedException) { return }
        }
    }

    private fun applyForeground(state: Int) {
        val wasInFront = foreground
        foregroundState = state
        val inFront = state != 0
        foreground = inFront
        if (wasInFront && !inFront) {
            // Left the game: drop every virtual finger so nothing stays pressed.
            synchronized(lock) {
                bindings.values.flatten().forEach { it.control.reset() }
                cursorFinger = -1
                injector?.releaseAll()
            }
        }
        refreshMouseGrab()
    }

    // ---------------------------------------------------------------- mouse FPS and own cursor

    private fun onMouseMove(deltaX: Int, deltaY: Int) {
        if (!foreground || !mouseManaged || (deltaX == 0 && deltaY == 0)) return
        synchronized(lock) {
            val touch = injector ?: return
            if (!touch.available) return
            val look = lookControl ?: return
            routeMouseMove(touch, look, deltaX, deltaY)
        }
    }

    private fun routeMouseMove(touch: TouchInjector, look: Control, deltaX: Int, deltaY: Int) {
        if (mouseLookActive) {
            moveLook(touch, look, deltaX, deltaY)
        } else if (grabbedPaths.isNotEmpty()) {
            moveCursor(touch, deltaX, deltaY)
        }
    }

    /** Camera: the virtual finger drags from the look anchor; at the edge it restarts at the anchor. */
    private fun moveLook(touch: TouchInjector, control: Control, deltaX: Int, deltaY: Int) {
        val anchorX = pixelX(control)
        val anchorY = pixelY(control)
        if (control.finger < 0) {
            control.finger = touch.down(anchorX, anchorY)
            control.lookX = anchorX
            control.lookY = anchorY
        }
        if (control.finger < 0) return
        control.lookX += deltaX * control.sensitivityX * MOUSE_LOOK_SCALE
        control.lookY += deltaY * control.sensitivityY * MOUSE_LOOK_SCALE
        val limit = minOf(width, height) * LOOK_RADIUS
        if (Math.abs(control.lookX - anchorX) > limit || Math.abs(control.lookY - anchorY) > limit) {
            touch.up(control.finger)
            control.finger = touch.down(anchorX, anchorY)
            control.lookX = anchorX
            control.lookY = anchorY
            return
        }
        touch.move(control.finger, control.lookX, control.lookY)
    }

    private fun moveCursor(touch: TouchInjector, deltaX: Int, deltaY: Int) {
        cursorX = (cursorX + deltaX * CURSOR_SPEED).coerceIn(0f, (width - 1).toFloat())
        cursorY = (cursorY + deltaY * CURSOR_SPEED).coerceIn(0f, (height - 1).toFloat())
        if (cursorFinger >= 0) touch.move(cursorFinger, cursorX, cursorY)
    }

    private fun toggleMouseMode(touch: TouchInjector, control: Control) {
        mouseLookActive = !mouseLookActive
        if (control.finger >= 0) touch.up(control.finger)
        control.finger = -1
        if (cursorFinger >= 0) touch.up(cursorFinger)
        cursorFinger = -1
        Log.i(tag, if (mouseLookActive) "Mouse FPS: mira ativa" else "Mouse FPS: cursor próprio")
    }

    /** True while the mouse is grabbed and the player chose the app's own cursor. */
    private fun cursorModeActive(): Boolean =
        running && foreground && lookControl != null && !mouseLookActive && grabbedPaths.isNotEmpty()

    private fun cursorVisible(): Boolean = cursorModeActive()

    /** In cursor mode the left button taps (and drags) wherever the app's own cursor is. */
    private fun handleCursorClick(down: Boolean): Boolean {
        val touch = injector ?: return false
        if (!touch.available) return false
        if (!down) {
            if (cursorFinger < 0) return false
            touch.up(cursorFinger)
            cursorFinger = -1
            return true
        }
        if (!cursorModeActive()) return false
        if (cursorFinger < 0) cursorFinger = touch.down(cursorX, cursorY)
        return true
    }

    private fun buildStatus(): String {
        val lines = ArrayList<String>()
        lines += "Shizuku: serviço rodando como UID ${android.os.Process.myUid()}"
        lines += "Jogo: ${targetPackage.ifEmpty { "—" }} • na frente: " + when (foregroundState) {
            1 -> "sim"
            0 -> "não"
            2 -> "não deu para saber"
            else -> "ainda não verificado"
        }
        val names = devices.keys.map { File(it).name }.sorted()
        lines += "Entrada lida: " + if (names.isEmpty()) "nenhum dispositivo" else names.joinToString()
        lines += "Mouse capturado (cursor próprio): " + when {
            grabbedPaths.isNotEmpty() -> "sim"
            !mouseManaged -> "não (nenhum controle de mouse no keymap)"
            foregroundState != 1 -> "aguardando o jogo ficar na frente"
            else -> "não (${grabNote.ifEmpty { "nenhum mouse encontrado" }})"
        }
        lines += "Toques: " + (injector?.summary() ?: "motor parado")
        lines += "Teclas mapeadas: ${bindings.size}"
        lines += if (lastKeyAt == 0L) {
            "Última tecla: nenhuma recebida ainda"
        } else {
            "Última tecla: $lastKeyText (há ${(SystemClock.elapsedRealtime() - lastKeyAt) / 1000}s)"
        }
        return lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- key handling

    private fun onKey(key: String, down: Boolean) {
        lastKeyText = "$key ${if (down) "↓" else "↑"}"
        lastKeyAt = SystemClock.elapsedRealtime()
        if (key == "MOUSE_LEFT") {
            val handled = synchronized(lock) { handleCursorClick(down) }
            if (handled) return
        }
        val list = bindings[key] ?: return
        if (!foreground && down) return
        synchronized(lock) {
            val touch = injector ?: return
            list.forEach { binding ->
                val control = binding.control
                when (control.type) {
                    "MOUSE_LOOK" -> if (down) toggleMouseMode(touch, control)
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
                item.optBoolean("stopOnOpposite", true),
                item.optDouble("sensitivityX", 1.0).toFloat().coerceIn(0.1f, 4f),
                item.optDouble("sensitivityY", 1.0).toFloat().coerceIn(0.1f, 4f)
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
                val savedKey = normalizeSavedKey(rawKey)
                val key = if (type == "MOUSE_LOOK" && (savedKey.isBlank() || savedKey == "TECLA?")) "ALT_LEFT" else savedKey
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
        val stopOnOpposite: Boolean,
        val sensitivityX: Float,
        val sensitivityY: Float
    ) {
        var finger = -1
        var lookX = 0f
        var lookY = 0f
        val held = ArrayList<Int>()

        fun reset() {
            finger = -1
            lookX = 0f
            lookY = 0f
            held.clear()
        }
    }

    /** [direction] is UP/DOWN/LEFT/RIGHT for joysticks and -1 for the other controls. */
    private class Binding(val control: Control, val direction: Int)

    private companion object {
        const val DESCRIPTOR = "com.mapeador.IShizukuInput"
        const val TRANSACTION_START = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_STATE = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_STATUS = IBinder.FIRST_CALL_TRANSACTION + 3
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_REL = 2
        const val SYN_REPORT = 0
        const val REL_X = 0
        const val REL_Y = 1
        const val MOUSE_LOOK_SCALE = 1.0f
        /** How far (fraction of the short screen side) the camera drag goes before it restarts. */
        const val LOOK_RADIUS = 0.2f
        const val CURSOR_SPEED = 1.6f
        const val EVIOCGRAB = 0x40044590
        const val UP = 0
        const val DOWN = 1
        const val LEFT = 2
        const val RIGHT = 3
        const val SWIPE_DISTANCE = 96f
        const val DEFAULT_JOYSTICK_RANGE = 120f
        val SUPPORTED_TYPES = setOf("TOUCH", "SWIPE", "MOUSE_PICK", "JOYSTICK", "MOUSE_LOOK")

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

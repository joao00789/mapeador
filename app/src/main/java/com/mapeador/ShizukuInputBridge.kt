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
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.FileDescriptor
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Small Binder bridge: the user service runs as Shizuku's shell/root identity. */
object ShizukuInputBridge {
    private const val TAG = "NexoPlayShizuku"
    private const val DESCRIPTOR = "com.mapeador.IShizukuInput"
    private const val TRANSACTION_START = IBinder.FIRST_CALL_TRANSACTION
    private const val TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val TRANSACTION_STATE = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val TRANSACTION_STATUS = IBinder.FIRST_CALL_TRANSACTION + 3
    private const val TRANSACTION_MOUSE_MODE = IBinder.FIRST_CALL_TRANSACTION + 4
    private val BIND_TIMEOUT_TOKEN = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var remote: IBinder? = null
    private var connection: ServiceConnection? = null
    private val pendingCallbacks = mutableListOf<(Boolean, String) -> Unit>()

    @Volatile private var lastError = ""
    private var lastToastText = ""
    private var lastToastAt = 0L

    fun bind(context: Context, callback: (Boolean, String) -> Unit) {
        if (!Shizuku.pingBinder()) {
            lastError = "Shizuku não está iniciado"
            callback(false, lastError)
            return
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lastError = "B13 MAUSE PRO ainda não foi autorizado no Shizuku"
            callback(false, lastError)
            return
        }
        remote?.takeIf { it.isBinderAlive }?.let {
            callback(true, serverIdentity())
            return
        }
        remote = null
        if (connection != null) {
            // A connection is already being opened; the timeout below fails it if it never arrives.
            pendingCallbacks += callback
            return
        }
        pendingCallbacks += callback
        // processNameSuffix é obrigatório: sem ele o Shizuku lança
        // "process name suffix must not be null" e o keymap nunca inicia.
        val serviceArgs = Shizuku.UserServiceArgs(
            ComponentName(context.applicationContext, ShizukuInputUserService::class.java)
        ).daemon(false)
            .processNameSuffix("input")
            .tag("nexoplay-input")
            .version(14)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote = binder
                connection = this
                mainHandler.post {
                    mainHandler.removeCallbacksAndMessages(BIND_TIMEOUT_TOKEN)
                    val callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    callbacks.forEach {
                        if (binder == null) {
                            lastError = "O serviço Shizuku não respondeu"
                            it(false, lastError)
                        } else {
                            lastError = ""
                            it(true, serverIdentity())
                        }
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
        } catch (error: Throwable) {
            connection = null
            Log.e(TAG, "Falha no bindUserService", error)
            lastError = error.message ?: "Não foi possível iniciar o serviço Shizuku"
            pendingCallbacks.toList().also { pendingCallbacks.clear() }.forEach { it(false, lastError) }
            return
        }
        // If the Shizuku side never starts the service (it crashed while starting, for example)
        // nothing would ever answer: give up after a while so the next attempt can try again.
        mainHandler.postAtTime({
            if (remote == null && connection === serviceConnection) {
                lastError = "O serviço dentro do Shizuku não iniciou em 8 s " +
                    "(reinicie o Shizuku e abra o jogo de novo)"
                try {
                    Shizuku.unbindUserService(serviceArgs, serviceConnection, true)
                } catch (error: Throwable) {
                    Log.w(TAG, "Não foi possível desfazer o bind", error)
                }
                connection = null
                pendingCallbacks.toList().also { pendingCallbacks.clear() }.forEach { it(false, lastError) }
            }
        }, BIND_TIMEOUT_TOKEN, SystemClock.uptimeMillis() + 8000)
    }

    /** Explains, from the app side, why the engine does not answer (used by the diagnostics card). */
    fun connectionReport(): String {
        val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val granted = running && try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        return listOf(
            "O serviço do Shizuku não respondeu:",
            "• Shizuku iniciado: " + if (running) "sim" else "NÃO",
            "• Este app autorizado no Shizuku: " + if (granted) "sim" else "NÃO",
            "• Serviço conectado: " + if (remote?.isBinderAlive == true) "sim" else "NÃO",
            "• Último erro: " + lastError.ifEmpty { "nenhum" }
        ).joinToString("\n")
    }

    /**
     * Starts the key engine. [width] and [height] are the real display size in pixels in the
     * current rotation; the saved positions are fractions of that size.
     */
    fun startMapping(
        context: Context,
        gamePackage: String,
        mappingJson: String,
        width: Int,
        height: Int,
        cursorSocketName: String,
        emergencyKey: String,
        captureKeyboard: Boolean,
        rightClickMode: String
    ) {
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
                    parcel.writeString(cursorSocketName)
                    parcel.writeString(emergencyKey)
                    parcel.writeInt(if (captureKeyboard) 1 else 0)
                    parcel.writeString(rightClickMode)
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

    fun setMouseLookActive(active: Boolean) {
        transact(TRANSACTION_MOUSE_MODE, { parcel -> parcel.writeInt(if (active) 1 else 0) }, { })
    }

    /** Live state of the engine: is the game in front, and where is the app's own mouse cursor. */
    class EngineState(
        /** 1 = game in front, 0 = not in front, 2 = could not tell, -1 = not watching yet. */
        val foreground: Int,
        val cursorVisible: Boolean,
        val cursorX: Int,
        val cursorY: Int,
        val mouseLookActive: Boolean
    )

    fun queryState(): EngineState? = transact(
        TRANSACTION_STATE,
        { },
        { reply ->
            val foreground = reply.readInt()
            val cursorVisible = reply.readInt() == 1
            val cursorX = reply.readInt()
            val cursorY = reply.readInt()
            val mouseLookActive = reply.readInt() == 1
            EngineState(foreground, cursorVisible, cursorX, cursorY, mouseLookActive)
        }
    )

    /** Multi-line text for the in-game diagnostics card. */
    fun diagnostics(): String? = transact(TRANSACTION_STATUS, { }, { reply -> reply.readString().orEmpty() })

    private fun toast(context: Context, message: String) {
        mainHandler.post {
            // The panel retries by itself; do not repeat the same message over and over.
            val now = SystemClock.elapsedRealtime()
            if (message == lastToastText && now - lastToastAt < 30_000) return@post
            lastToastText = message
            lastToastAt = now
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
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
    private val keyDownTimes = HashMap<Int, Long>()
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
            if (!send(MotionEvent.ACTION_DOWN)) {
                fingers.removeAt(fingers.lastIndex)
                return@synchronized -1
            }
        } else {
            if (!send(MotionEvent.ACTION_POINTER_DOWN or ((fingers.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))) {
                fingers.removeAt(fingers.lastIndex)
                return@synchronized -1
            }
        }
        id
    }

    fun move(id: Int, x: Float, y: Float) {
        synchronized(lock) {
            val finger = fingers.firstOrNull { it.id == id }
            if (finger != null) {
                finger.x = x
                finger.y = y
                // send() keeps every other active finger down, including a held fire button.
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

    fun injectKey(key: String, down: Boolean) {
        val code = KeyEvent.keyCodeFromString("KEYCODE_$key")
        if (code == KeyEvent.KEYCODE_UNKNOWN) return
        injectKeyCode(code, down)
    }

    private fun injectKeyCode(code: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        val downTime = synchronized(lock) {
            if (down) keyDownTimes.getOrPut(code) { now }
            else keyDownTimes.remove(code) ?: now
        }
        val event = KeyEvent(
            downTime,
            now,
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code,
            0,
            0,
            0,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        injectEvent(event)
    }

    fun injectKeyRepeat(key: String) {
        val code = KeyEvent.keyCodeFromString("KEYCODE_$key")
        if (code == KeyEvent.KEYCODE_UNKNOWN) return
        val downTime = synchronized(lock) { keyDownTimes[code] } ?: return
        injectEvent(
            KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                code,
                1,
                0,
                0,
                0,
                KeyEvent.FLAG_LONG_PRESS,
                InputDevice.SOURCE_KEYBOARD
            )
        )
    }

    fun injectScroll(vertical: Float, horizontal: Float, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0f
            size = 0f
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
        }
        val event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_SCROLL,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )
        injectEvent(event)
    }

    fun tap(x: Float, y: Float) {
        val id = down(x, y)
        if (id >= 0) up(id)
    }

    fun cancel(id: Int) {
        synchronized(lock) {
            val index = fingers.indexOfFirst { it.id == id }
            if (index < 0) return
            if (fingers.size == 1) {
                if (!send(MotionEvent.ACTION_CANCEL)) send(MotionEvent.ACTION_UP)
                fingers.removeAt(index)
            } else {
                if (!send(MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))) {
                    return
                }
                fingers.removeAt(index)
            }
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            while (fingers.isNotEmpty()) up(fingers.last().id)
            keyDownTimes.keys.toList().forEach { injectKeyCode(it, false) }
        }
    }

    private fun send(action: Int): Boolean {
        val method = injectMethod ?: return false
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
        return try {
            setDisplayId?.let { setter ->
                try { setter.invoke(event, 0) } catch (_: Throwable) { }
            }
            val result = method.invoke(target, event, 0)
            sent++
            if (result == false) {
                failed++
                lastError = "o Android recusou o toque"
                false
            } else {
                true
            }
        } catch (error: Throwable) {
            failed++
            lastError = (error.cause ?: error).let { it.message ?: it.javaClass.simpleName }
            Log.w(TAG, "Falha ao injetar o toque", error)
            false
        } finally {
            event.recycle()
        }
    }

    private fun injectEvent(event: InputEvent): Boolean {
        val method = injectMethod ?: return false
        return try {
            setDisplayId?.let { setter ->
                try { setter.invoke(event, 0) } catch (_: Throwable) { }
            }
            val result = method.invoke(target, event, 0)
            sent++
            if (result == false) {
                failed++
                lastError = "o Android recusou o evento"
                false
            } else true
        } catch (error: Throwable) {
            failed++
            lastError = (error.cause ?: error).let { it.message ?: it.javaClass.simpleName }
            Log.w(TAG, "Falha ao injetar evento de entrada", error)
            false
        } finally {
            (event as? MotionEvent)?.recycle()
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
    @Volatile private var emergencyKey = "F12"
    private var injector: TouchInjector? = null
    // Start in camera mode when configured; its shortcut suspends the aim for cursor use.
    @Volatile private var mouseLookActive = false
    @Volatile private var watchedPackage = ""
    /** 1 = game in front, 0 = not in front, 2 = could not tell, -1 = not watching. */
    @Volatile private var foregroundState = -1
    private var watcherStarted = false
    @Volatile private var taskStackListenerStatus = "não registrado"
    private var taskStackListenerBinder: IBinder? = null
    private val foregroundCheckQueued = AtomicBoolean(false)
    private val foregroundExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NexoPlay-ForegroundEvents").apply { isDaemon = true }
    }
    @Volatile private var mouseManaged = false
    @Volatile private var grabMice = false
    @Volatile private var captureKeyboard = false
    @Volatile private var rightClickMode = "BACK"
    @Volatile private var grabKeyboard = false
    @Volatile private var chatMode = false
    @Volatile private var chatEnterReleasePending = false
    private val grabbedPaths = ConcurrentHashMap.newKeySet<String>()
    private val knownMousePaths = ConcurrentHashMap.newKeySet<String>()
    private val mouseMotionPaths = ConcurrentHashMap.newKeySet<String>()
    private val grabbedKeyboardPaths = ConcurrentHashMap.newKeySet<String>()
    private val knownKeyboardPaths = ConcurrentHashMap.newKeySet<String>()
    private val capturableKeyboardPaths = ConcurrentHashMap.newKeySet<String>()
    private val mouseNodeGroups = ConcurrentHashMap<String, String>()
    private val mouseGrabFailures = ConcurrentHashMap<String, String>()
    private val keyboardGrabFailures = ConcurrentHashMap<String, String>()
    private val pressedKeySources = HashMap<String, MutableSet<String>>()
    @Volatile private var grabNote = ""
    @Volatile private var mouseCandidateSummary = "não identificado"
    private var lookControl: Control? = null
    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f
    @Volatile private var cursorSpeed = 1.6f
    @Volatile private var lastKeyText = ""
    @Volatile private var lastKeyAt = 0L
    @Volatile private var lastMouseDeltaX = 0
    @Volatile private var lastMouseDeltaY = 0
    @Volatile private var lastMouseMoveAt = 0L
    private var cursorFinger = -1
    private var cursorRightFinger = -1
    private val cursorUpdates = java.util.concurrent.LinkedBlockingDeque<CursorUpdate>(1)
    private val cursorPublishLock = Any()
    private var pendingCursorPublish: CursorUpdate? = null
    private var cursorPublishScheduled = false
    private var lastCursorPublishAt = 0L
    @Volatile private var cursorSocket: android.net.LocalSocket? = null
    private val devices = ConcurrentHashMap<String, FileInputStream>()
    private val swipeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NexoPlay-Swipe").apply { isDaemon = true }
    }
    private val legacyExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NexoPlay-LegacyTap").apply { isDaemon = true }
    }
    private val responseExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NexoPlay-KeyResponse").apply { isDaemon = true }
    }
    private val mouseMotionExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NexoPlay-MouseMotion").apply { isDaemon = true }
    }
    private var pendingMouseDeltaX = 0.0
    private var pendingMouseDeltaY = 0.0
    private var wheelRemainderVertical = 0f
    private var wheelRemainderHorizontal = 0f
    private var mouseMotionFlushScheduled = false
    private var smoothedLookDeltaX = 0f
    private var smoothedLookDeltaY = 0f
    private var lastLookMotionAt = 0L

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
                val cursorSocketName = data.readString().orEmpty()
                val emergencyKey = data.readString().orEmpty()
                val captureKeyboard = data.readInt() == 1
                val rightClickMode = data.readString().orEmpty()
                val status = try {
                    start(
                        game,
                        json,
                        screenWidth,
                        screenHeight,
                        cursorSocketName,
                        emergencyKey,
                        captureKeyboard,
                        rightClickMode
                    )
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
                reply?.writeInt(if (mouseLookActive) 1 else 0)
                return true
            }
            TRANSACTION_STATUS -> {
                reply?.writeNoException()
                reply?.writeString(buildStatus())
                return true
            }
            TRANSACTION_MOUSE_MODE -> {
                setMouseLookActive(data.readInt() == 1)
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        reply?.writeNoException()
        return true
    }

    /** Returns the message to show to the user (empty = nothing to say). */
    private fun start(
        game: String,
        json: String,
        screenWidth: Int,
        screenHeight: Int,
        cursorSocketName: String,
        configuredEmergencyKey: String,
        captureKeyboard: Boolean,
        rightClickMode: String
    ): String {
        stop()
        targetPackage = game
        watchedPackage = game
        width = screenWidth
        height = screenHeight
        cursorSpeed = CURSOR_SPEED
        bindings = parseMappings(json)
        emergencyKey = configuredEmergencyKey.ifBlank { "F12" }
        this.captureKeyboard = captureKeyboard
        this.rightClickMode = rightClickMode
        chatMode = false
        chatEnterReleasePending = false
        lookControl = bindings.values.flatten().map { it.control }.firstOrNull { it.type == "MOUSE_LOOK" }
        mouseManaged = emergencyKey.startsWith("MOUSE_") || bindings.any { (key, keyBindings) ->
            key.startsWith("MOUSE_") || keyBindings.any {
                it.control.type == "MOUSE_LOOK" || it.control.type == "MOUSE_DRAG"
            }
        }
        mouseLookActive = lookControl != null
        cursorX = width / 2f
        cursorY = height / 2f
        cursorFinger = -1
        cursorRightFinger = -1
        if (foregroundState < 0) foregroundState = 2
        foreground = foregroundState == 1
        // The watcher also drives the show/hide of the floating panel, so it runs even
        // when there is no key mapped yet.
        ensureForegroundWatcher()

        val touch = TouchInjector()
        injector = touch
        running = true
        val runGeneration = ++generation
        grabMice = mouseManaged && foregroundState == 1
        grabKeyboard = captureKeyboard && foregroundState == 1
        startCursorUpdates(cursorSocketName, runGeneration)
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
                (if (bindings.isEmpty()) "Mouse do app ativo" else "Keymap ativo") +
                    " • $opened dispositivo(s) de entrada" +
                    if (mouseManaged) " • cursor próprio: ${mouseGrabStatus()}" else ""
        }
    }

    private fun stop() {
        running = false
        generation++
        cursorUpdates.clear()
        try { cursorSocket?.close() } catch (_: Exception) { }
        cursorSocket = null
        grabMice = false
        grabbedPaths.clear()
        grabbedKeyboardPaths.clear()
        knownMousePaths.clear()
        knownKeyboardPaths.clear()
        capturableKeyboardPaths.clear()
        mouseMotionPaths.clear()
        mouseNodeGroups.clear()
        mouseGrabFailures.clear()
        keyboardGrabFailures.clear()
        grabKeyboard = false
        chatMode = false
        chatEnterReleasePending = false
        devices.values.forEach { stream ->
            try { stream.close() } catch (_: Exception) { }
        }
        devices.clear()
        synchronized(lock) {
            bindings.values.flatten().forEach { it.control.reset() }
            pressedKeySources.clear()
            injector?.releaseAll()
            cursorFinger = -1
            cursorRightFinger = -1
            pendingMouseDeltaX = 0.0
            pendingMouseDeltaY = 0.0
            mouseMotionFlushScheduled = false
            smoothedLookDeltaX = 0f
            smoothedLookDeltaY = 0f
            lastLookMotionAt = 0L
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
        val presentPaths = nodes.mapTo(HashSet()) { it.absolutePath }
        knownMousePaths.retainAll(presentPaths)
        mouseMotionPaths.retainAll(presentPaths)
        mouseNodeGroups.keys.retainAll(presentPaths)
        grabbedPaths.retainAll(presentPaths)
        knownKeyboardPaths.retainAll(presentPaths)
        capturableKeyboardPaths.retainAll(presentPaths)
        grabbedKeyboardPaths.retainAll(presentPaths)
        mouseGrabFailures.keys.removeIf { it !in presentPaths }
        keyboardGrabFailures.keys.removeIf { it !in presentPaths }
        for (node in nodes) {
            val path = node.absolutePath
            if (devices.containsKey(path)) continue
            try {
                val stream = FileInputStream(node)
                if (mouseManaged || captureKeyboard) {
                    var capabilityError: String? = null
                    val capabilities = try {
                        NativeMouseGrab.capabilities(stream.fd)
                    } catch (error: Throwable) {
                        capabilityError = "não foi possível ler capacidades: " +
                            ((error.cause ?: error).message ?: error.javaClass.simpleName)
                        -1
                    }
                    if (capabilities < 0) {
                        mouseGrabFailures[path] = capabilityError
                            ?: "leitura de capacidades falhou (errno=${-capabilities})"
                    } else {
                        mouseGrabFailures.remove(path)
                        if ((capabilities and NativeMouseGrab.MOUSE_DEVICE) != 0) {
                            knownMousePaths.add(path)
                            mouseNodeGroups[path] = try {
                                NativeMouseGrab.identity(stream.fd).orEmpty().ifEmpty { "path:$path" }
                            } catch (error: Throwable) {
                                Log.w(tag, "Não foi possível identificar o dispositivo em $path", error)
                                "path:$path"
                            }
                            if ((capabilities and NativeMouseGrab.MOUSE_MOTION) != 0) {
                                mouseMotionPaths.add(path)
                            }
                            if (grabMice) {
                                setMouseGrab(path, stream, true)
                            }
                        }
                        if ((capabilities and NativeMouseGrab.KEYBOARD_DEVICE) != 0) {
                            knownKeyboardPaths.add(path)
                            if ((capabilities and NativeMouseGrab.MOUSE_DEVICE) == 0 || mouseManaged) {
                                capturableKeyboardPaths.add(path)
                                if (grabKeyboard) setKeyboardGrab(path, stream, true)
                            } else {
                                keyboardGrabFailures[path] =
                                    "captura desativada: teclado e mouse compartilham este nó"
                            }
                        }
                    }
                }
                devices[path] = stream
                Thread({ readDevice(path, stream, runGeneration) }, "NexoPlay-${node.name}")
                    .apply { isDaemon = true }.start()
            } catch (error: Exception) {
                if (error.message?.contains("Permission denied", ignoreCase = true) == true) denied[0]++
                else Log.w(tag, "Não foi possível abrir $path", error)
            }
        }
        mouseCandidateSummary = when {
            knownMousePaths.isEmpty() -> "nenhum nó evdev com botão ou movimento relativo identificado"
            else -> knownMousePaths.sorted().joinToString { path ->
                File(path).name +
                    if (path in mouseMotionPaths) " (REL_X/Y, ${mouseNodeGroups[path]})"
                    else " (botões, ${mouseNodeGroups[path]})"
            }
        }
        return devices.size
    }

    private fun watchHotplug(runGeneration: Int) {
        val ignored = intArrayOf(0)
        while (isRunning(runGeneration)) {
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
            if (isRunning(runGeneration)) {
                scanDevices(runGeneration, ignored)
                retryMouseCapture()
                retryKeyboardCapture()
            }
        }
    }

    private fun retryMouseCapture() {
        if (!running || !grabMice) return
        knownMousePaths.toList().forEach { path ->
            if (path in grabbedPaths) return@forEach
            val stream = devices[path] ?: return@forEach
            if (setMouseGrab(path, stream, true)) {
                Log.i(tag, "Captura do mouse recuperada para ${File(path).name}")
            }
        }
    }

    private fun retryKeyboardCapture() {
        if (!running || !grabKeyboard) return
        capturableKeyboardPaths.toList().forEach { path ->
            if (path in grabbedKeyboardPaths) return@forEach
            val stream = devices[path] ?: return@forEach
            if (setKeyboardGrab(path, stream, true)) {
                Log.i(tag, "Captura do teclado recuperada para ${File(path).name}")
            }
        }
    }

    private fun setKeyboardGrab(path: String, stream: FileInputStream, enabled: Boolean): Boolean {
        if (enabled && path in grabbedPaths && grabMice) {
            grabbedKeyboardPaths.add(path)
            return true
        }
        if (!enabled && path in grabbedPaths && grabMice) {
            grabbedKeyboardPaths.remove(path)
            return true
        }
        val errno = try {
            NativeMouseGrab.grab(stream.fd, enabled)
        } catch (error: Throwable) {
            grabNote = (error.cause ?: error).message ?: error.javaClass.simpleName
            -1
        }
        if (errno == 0) {
            if (enabled) grabbedKeyboardPaths.add(path) else grabbedKeyboardPaths.remove(path)
            keyboardGrabFailures.remove(path)
            return true
        }
        if (enabled) {
            keyboardGrabFailures[path] = if (errno < 0) grabNote.ifEmpty { "erro ioctl $errno" }
                else "ioctl EVIOCGRAB falhou (errno=$errno)"
        } else {
            Log.e(tag, "Não foi possível liberar captura do teclado em ${File(path).name} (errno=$errno)")
        }
        return false
    }

    private fun setMouseGrab(path: String, stream: FileInputStream, enabled: Boolean): Boolean {
        if (enabled && path in grabbedKeyboardPaths && grabKeyboard) {
            grabbedPaths.add(path)
            return true
        }
        if (!enabled && path in grabbedKeyboardPaths && grabKeyboard) {
            grabbedPaths.remove(path)
            return true
        }
        val errno = try {
            NativeMouseGrab.grab(stream.fd, enabled)
        } catch (error: Throwable) {
            grabNote = (error.cause ?: error).message ?: error.javaClass.simpleName
            -1
        }
        if (errno == 0) {
            if (enabled) grabbedPaths.add(path) else grabbedPaths.remove(path)
            mouseGrabFailures.remove(path)
            return true
        }
        if (enabled) {
            mouseGrabFailures[path] = if (errno < 0) grabNote.ifEmpty { "erro ioctl $errno" }
                else "ioctl EVIOCGRAB falhou (errno=$errno)"
            grabNote = mouseGrabFailures[path].orEmpty()
        } else {
            Log.e(tag, "Não foi possível liberar a captura de ${File(path).name} (errno=$errno)")
        }
        return false
    }

    private fun refreshMouseGrab() {
        val want = running && mouseManaged && foregroundState == 1
        if (want == grabMice) return
        grabMice = want
        for (path in knownMousePaths.toList()) {
            val stream = devices[path] ?: continue
            setMouseGrab(path, stream, want)
        }
    }

    private fun refreshKeyboardGrab() {
        val want = running && captureKeyboard && !chatMode && foregroundState == 1
        if (want == grabKeyboard) return
        grabKeyboard = want
        for (path in capturableKeyboardPaths.toList()) {
            val stream = devices[path] ?: continue
            setKeyboardGrab(path, stream, want)
        }
    }

    private fun mouseGrabStatus(): String {
        val capturedGroups = capturedMouseGroups()
        return when {
            !mouseManaged -> "sem controles de mouse no keymap"
            capturedGroups.isNotEmpty() ->
                "${capturedGroups.size} dispositivo(s) com movimento capturado"
            grabbedPaths.isNotEmpty() -> "assumido parcialmente (${grabbedPaths.size}/${knownMousePaths.size} nó(s))"
            knownMousePaths.isNotEmpty() -> "mouse detectado, mas não capturado; mira usa os eventos REL_X/REL_Y recebidos"
            mouseGrabFailures.isNotEmpty() -> "falha: ${mouseGrabFailures.values.first()}"
            else -> "aguardando nó do mouse ($mouseCandidateSummary)"
        }
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
        var wheelVertical = 0
        var wheelHorizontal = 0
        var wheelVerticalHiRes = 0
        var wheelHorizontalHiRes = 0
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
                    EV_KEY -> {
                        val name = LINUX_KEYS[code]
                        val mouseInput = path in knownMousePaths || code in BTN_LEFT..BTN_TASK
                        val keyboardInput = path in knownKeyboardPaths && code !in BTN_LEFT..BTN_TASK
                        if (value == 2) {
                            if (captureKeyboard && keyboardInput && path in grabbedKeyboardPaths &&
                                name != null && name !in bindings && !chatMode
                            ) {
                                injector?.injectKeyRepeat(name)
                            }
                        } else if ((value == 0 || value == 1) && name != null) {
                            onKey(
                                name,
                                value == 1,
                                mouseInput && path in grabbedPaths,
                                keyboardInput && (
                                    path in grabbedKeyboardPaths ||
                                        (path in grabbedPaths && !captureKeyboard)
                                    ),
                                path
                            )
                        }
                    }
                    EV_REL -> when (code) {
                        REL_X -> {
                            noteMouseMotionPath(path)
                            relativeX += value
                        }
                        REL_Y -> {
                            noteMouseMotionPath(path)
                            relativeY += value
                        }
                        REL_WHEEL -> wheelVertical += value
                        REL_HWHEEL -> wheelHorizontal += value
                        REL_WHEEL_HI_RES -> wheelVerticalHiRes += value
                        REL_HWHEEL_HI_RES -> wheelHorizontalHiRes += value
                    }
                    EV_SYN -> if (code == SYN_REPORT) {
                        if (relativeX != 0 || relativeY != 0) onMouseMove(path, relativeX, relativeY)
                        val vertical = if (wheelVerticalHiRes != 0) wheelVerticalHiRes / 120f
                            else wheelVertical.toFloat()
                        val horizontal = if (wheelHorizontalHiRes != 0) wheelHorizontalHiRes / 120f
                            else wheelHorizontal.toFloat()
                        if (vertical != 0f || horizontal != 0f) {
                            onMouseScroll(path, vertical, horizontal)
                        }
                        relativeX = 0
                        relativeY = 0
                        wheelVertical = 0
                        wheelHorizontal = 0
                        wheelVerticalHiRes = 0
                        wheelHorizontalHiRes = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (isRunning(runGeneration)) Log.i(tag, "Leitura de $path encerrada: ${error.message}")
        } finally {
            if (isRunning(runGeneration)) {
                val releasedKeys = synchronized(lock) {
                    pressedKeySources.filterValues { path in it }.keys.toList()
                }
                releasedKeys.forEach {
                    onKey(it, false, path in grabbedPaths, path in grabbedKeyboardPaths, path)
                }
            }
            if (devices[path] === stream) {
                devices.remove(path)
            }
            try { stream.close() } catch (_: Exception) { }
        }
    }

    // ---------------------------------------------------------------- foreground game

    private fun ensureForegroundWatcher() {
        if (watcherStarted) return
        watcherStarted = true
        registerTaskStackListener()
        Thread({ watchForeground() }, "NexoPlay-Foreground").apply { isDaemon = true }.start()
    }

    private fun registerTaskStackListener() {
        try {
            val listenerClass = Class.forName("android.app.ITaskStackListener")
            val manager = Class.forName("android.app.ActivityTaskManager")
                .getDeclaredMethod("getService")
                .apply { isAccessible = true }
                .invoke(null)
            val callbackBinder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == IBinder.INTERFACE_TRANSACTION) {
                        reply?.writeString(listenerClass.name)
                        return true
                    }
                    return try {
                        data.enforceInterface(listenerClass.name)
                        requestForegroundCheck()
                        true
                    } catch (error: RuntimeException) {
                        Log.w(tag, "Callback ITaskStackListener inválido", error)
                        false
                    }
                }
            }
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "asBinder" -> callbackBinder
                    "toString" -> "NexoPlayTaskStackListener"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            val register = manager.javaClass.methods.firstOrNull {
                it.name == "registerTaskStackListener" && it.parameterTypes.size == 1
            } ?: error("registerTaskStackListener indisponível")
            register.invoke(manager, listener)
            taskStackListenerBinder = callbackBinder
            taskStackListenerStatus = "registrado"
            requestForegroundCheck()
        } catch (error: Throwable) {
            taskStackListenerStatus = "indisponível: ${error.cause?.message ?: error.message ?: error.javaClass.simpleName}"
            Log.w(tag, "ITaskStackListener indisponível; usando consulta de fallback", error)
        }
    }

    private fun requestForegroundCheck() {
        val game = watchedPackage
        if (game.isEmpty() || !foregroundCheckQueued.compareAndSet(false, true)) return
        foregroundExecutor.schedule({
            try {
                if (game == watchedPackage) applyForeground(readForegroundState(game))
            } finally {
                foregroundCheckQueued.set(false)
            }
        }, 50, TimeUnit.MILLISECONDS)
    }

    /** Event-driven foreground checks plus a slower fallback poll. */
    private fun watchForeground() {
        while (true) {
            val game = watchedPackage
            if (game.isEmpty()) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return }
                continue
            }
            val state = readForegroundState(game)
            if (game == watchedPackage) applyForeground(state)
            val fallbackInterval = if (taskStackListenerStatus == "registrado") 5000L else 1500L
            try { Thread.sleep(fallbackInterval) } catch (_: InterruptedException) { return }
        }
    }

    private fun readForegroundState(game: String): Int = try {
        val process = ProcessBuilder("/system/bin/dumpsys", "activity", "activities")
            .redirectErrorStream(true).start()
        val resumed = process.inputStream.bufferedReader().useLines { lines ->
            lines.firstOrNull {
                it.contains("topResumedActivity", ignoreCase = true) ||
                    it.contains("mResumedActivity", ignoreCase = true) ||
                    it.contains("ResumedActivity", ignoreCase = true)
            }
        }
        process.destroy()
        when {
            resumed == null -> 2
            resumed.contains("$game/") -> 1
            else -> 0
        }
    } catch (error: Exception) {
        Log.w(tag, "Não foi possível conferir o jogo ativo", error)
        2
    }

    private fun applyForeground(state: Int) {
        val wasInFront = foreground
        foregroundState = state
        val inFront = state == 1
        foreground = inFront
        if (wasInFront && !inFront) {
            // Left the game: drop every virtual finger so nothing stays pressed.
            synchronized(lock) {
                bindings.values.flatten().forEach { it.control.reset() }
                pressedKeySources.clear()
                injector?.releaseAll()
                cursorFinger = -1
            }
        }
        refreshMouseGrab()
        refreshKeyboardGrab()
        publishCursorState()
    }

    // ---------------------------------------------------------------- mouse FPS and own cursor

    private fun onMouseMove(path: String, deltaX: Int, deltaY: Int) {
        if (!foreground || !mouseManaged || path !in mouseMotionPaths ||
            (!mouseLookActive && path !in grabbedPaths) || (deltaX == 0 && deltaY == 0)
        ) return
        lastMouseDeltaX = deltaX
        lastMouseDeltaY = deltaY
        lastMouseMoveAt = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!running) return
            pendingMouseDeltaX += deltaX.toDouble()
            pendingMouseDeltaY += deltaY.toDouble()
            if (!mouseMotionFlushScheduled) {
                scheduleMouseMotionFlush(generation)
            }
        }
    }

    private fun onMouseScroll(path: String, vertical: Float, horizontal: Float) {
        if (!foreground || path !in grabbedPaths || (vertical == 0f && horizontal == 0f)) return
        val touch = injector ?: return
        var injectedVertical = 0f
        var injectedHorizontal = 0f
        if (vertical != 0f) {
            val key = if (vertical > 0f) "MOUSE_WHEEL_UP" else "MOUSE_WHEEL_DOWN"
            val mapped = bindings[key].orEmpty()
            if (mapped.isEmpty()) {
                injectedVertical = vertical
            } else {
                val steps = synchronized(lock) {
                    wheelRemainderVertical += vertical
                    wheelRemainderVertical.toInt().also { wheelRemainderVertical -= it }
                }
                repeat(kotlin.math.abs(steps).coerceAtMost(10)) {
                    triggerWheelBindings(key)
                }
            }
        }
        if (horizontal != 0f) {
            val key = if (horizontal > 0f) "MOUSE_WHEEL_RIGHT" else "MOUSE_WHEEL_LEFT"
            val mapped = bindings[key].orEmpty()
            if (mapped.isEmpty()) {
                injectedHorizontal = horizontal
            } else {
                val steps = synchronized(lock) {
                    wheelRemainderHorizontal += horizontal
                    wheelRemainderHorizontal.toInt().also { wheelRemainderHorizontal -= it }
                }
                repeat(kotlin.math.abs(steps).coerceAtMost(10)) {
                    triggerWheelBindings(key)
                }
            }
        }
        if (injectedVertical != 0f || injectedHorizontal != 0f) {
            touch.injectScroll(injectedVertical, injectedHorizontal, cursorX, cursorY)
        }
    }

    private fun triggerWheelBindings(key: String) {
        if (key == emergencyKey) {
            Log.w(tag, "Tecla de emergência acionada pela roda do mouse")
            stop()
            return
        }
        bindings[key].orEmpty().forEach { binding ->
            val delay = binding.control.responseMs.toLong()
            if (delay == 0L) performBinding(binding, true)
            else responseExecutor.schedule({ performBinding(binding, true) }, delay, TimeUnit.MILLISECONDS)
            responseExecutor.schedule({ performBinding(binding, false) }, delay + 80L, TimeUnit.MILLISECONDS)
        }
    }

    private fun scheduleMouseMotionFlush(runGeneration: Int) {
        mouseMotionFlushScheduled = true
        mouseMotionExecutor.schedule(
            { flushMouseMotion(runGeneration) },
            MOUSE_MOTION_FRAME_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun flushMouseMotion(runGeneration: Int) {
        val pendingMoves = ArrayList<TouchMove>()
        var movementInjector: TouchInjector? = null
        synchronized(lock) {
            if (generation != runGeneration || !running) return
            val inputDeltaX = pendingMouseDeltaX.toFloat()
            val inputDeltaY = pendingMouseDeltaY.toFloat()
            pendingMouseDeltaX = 0.0
            pendingMouseDeltaY = 0.0
            mouseMotionFlushScheduled = false
            if (!foreground || !mouseManaged) return

            val touch = injector?.takeIf { it.available }
            val draggingControl = bindings.values.asSequence()
                .flatten()
                .any { it.control.type == "MOUSE_DRAG" && it.control.finger >= 0 }
            if (touch != null && draggingControl && (inputDeltaX != 0f || inputDeltaY != 0f)) {
                movementInjector = touch
                pendingMoves.addAll(moveMouseDragControls(inputDeltaX, inputDeltaY))
            }

            if (mouseLookActive) {
                if (draggingControl) {
                    smoothedLookDeltaX = 0f
                    smoothedLookDeltaY = 0f
                    lastLookMotionAt = 0L
                } else if (touch != null && (inputDeltaX != 0f || inputDeltaY != 0f)) {
                    val now = SystemClock.elapsedRealtime()
                    val elapsedSinceLastMotion = now - lastLookMotionAt
                    if (elapsedSinceLastMotion > LOOK_SMOOTHING_RESET_MS) {
                        smoothedLookDeltaX = 0f
                        smoothedLookDeltaY = 0f
                    }
                    val elapsedMs = elapsedSinceLastMotion
                        .takeIf { it in 1..LOOK_SMOOTHING_RESET_MS }
                        ?: MOUSE_MOTION_FRAME_MS
                    val smoothingPercent = (lookControl?.smoothing ?: DEFAULT_LOOK_SMOOTHING)
                        .coerceIn(0, 100) / 100f
                    val smoothingTimeMs = smoothingPercent * MAX_LOOK_SMOOTHING_TAU_MS
                    val previousWeight = if (smoothingTimeMs == 0f) {
                        0f
                    } else {
                        kotlin.math.exp(-elapsedMs.toDouble() / smoothingTimeMs.toDouble()).toFloat()
                    }
                    lastLookMotionAt = now
                    smoothedLookDeltaX = if (inputDeltaX == 0f) 0f else
                        smoothedLookDeltaX * previousWeight + inputDeltaX * (1f - previousWeight)
                    smoothedLookDeltaY = if (inputDeltaY == 0f) 0f else
                        smoothedLookDeltaY * previousWeight + inputDeltaY * (1f - previousWeight)
                    if (kotlin.math.abs(smoothedLookDeltaX) < LOOK_DELTA_EPSILON) smoothedLookDeltaX = 0f
                    if (kotlin.math.abs(smoothedLookDeltaY) < LOOK_DELTA_EPSILON) smoothedLookDeltaY = 0f
                    if (smoothedLookDeltaX != 0f || smoothedLookDeltaY != 0f) {
                        movementInjector = touch
                        lookControl?.let {
                            moveLook(touch, it, smoothedLookDeltaX, smoothedLookDeltaY)?.let(pendingMoves::add)
                        }
                    }
                }
            } else {
                smoothedLookDeltaX = 0f
                smoothedLookDeltaY = 0f
                lastLookMotionAt = 0L
                if (inputDeltaX != 0f || inputDeltaY != 0f) {
                    moveCursor(inputDeltaX, inputDeltaY)
                    if (cursorFinger >= 0) {
                        injector?.let {
                            movementInjector = it
                            pendingMoves.add(TouchMove(cursorFinger, cursorX, cursorY))
                        }
                    }
                    publishCursorState()
                }
            }
        }
        movementInjector?.let { touch ->
            pendingMoves.forEach { touch.move(it.finger, it.x, it.y) }
        }
    }

    private fun moveMouseDragControls(deltaX: Float, deltaY: Float): List<TouchMove> =
        bindings.values.asSequence()
            .flatten()
            .map { it.control }
            .distinct()
            .filter { it.type == "MOUSE_DRAG" && it.finger >= 0 }
            .map { control ->
                control.lookX = (control.lookX + deltaX * control.sensitivityX * MOUSE_DRAG_SCALE)
                    .coerceIn(0f, (width - 1).toFloat())
                control.lookY = (control.lookY + deltaY * control.sensitivityY * MOUSE_DRAG_SCALE)
                    .coerceIn(0f, (height - 1).toFloat())
                TouchMove(control.finger, control.lookX, control.lookY)
            }
            .toList()
    }

    /** Applies relative mouse movement to one touch, keeping injected coordinates on the display. */
    private fun moveLook(touch: TouchInjector, control: Control, deltaX: Float, deltaY: Float): TouchMove? {
        val anchorX = pixelX(control)
        val anchorY = pixelY(control)
        if (control.finger < 0) {
            control.finger = touch.down(anchorX, anchorY)
            control.lookX = anchorX
            control.lookY = anchorY
        }
        if (control.finger < 0) return null
        control.lookX = (control.lookX + deltaX * control.sensitivityX * MOUSE_LOOK_SCALE)
            .coerceIn(0f, (width - 1).toFloat())
        control.lookY = (control.lookY + deltaY * control.sensitivityY * MOUSE_LOOK_SCALE)
            .coerceIn(0f, (height - 1).toFloat())
        return TouchMove(control.finger, control.lookX, control.lookY)
    }

    private fun moveCursor(deltaX: Float, deltaY: Float) {
        cursorX = (cursorX + deltaX * cursorSpeed).coerceIn(0f, (width - 1).toFloat())
        cursorY = (cursorY + deltaY * cursorSpeed).coerceIn(0f, (height - 1).toFloat())
    }

    private fun setMouseLookActive(active: Boolean) {
        synchronized(lock) {
            if (!running || (active && lookControl == null) || mouseLookActive == active) return
            val touch = injector
            lookControl?.let { control ->
                if (control.finger >= 0) touch?.cancel(control.finger)
                control.finger = -1
                control.lookX = 0f
                control.lookY = 0f
            }
            if (cursorFinger >= 0) touch?.cancel(cursorFinger)
            cursorFinger = -1
            if (cursorRightFinger >= 0) touch?.cancel(cursorRightFinger)
            cursorRightFinger = -1
            pendingMouseDeltaX = 0.0
            pendingMouseDeltaY = 0.0
            smoothedLookDeltaX = 0f
            smoothedLookDeltaY = 0f
            mouseLookActive = active
            publishCursorState()
            Log.i(tag, if (active) "Mouse FPS: mira ativa" else "Mouse FPS: cursor próprio")
        }
        if (!active) retryMouseCapture()
    }

    private fun toggleMouseMode() {
        setMouseLookActive(!mouseLookActive)
    }

    /** The app cursor is only shown while FPS camera control is suspended. */
    private fun cursorVisible(): Boolean =
        running && foreground && mouseManaged && !mouseLookActive && mouseCaptureComplete()

    private fun capturedMouseGroups(): Set<String> =
        mouseNodeGroups.entries
            .groupBy({ it.value }, { it.key })
            .filterValues { paths -> paths.any { it in mouseMotionPaths && it in grabbedPaths } }
            .keys

    private fun mouseCaptureComplete(): Boolean = capturedMouseGroups().isNotEmpty()

    private fun startCursorUpdates(socketName: String, runGeneration: Int) {
        if (socketName.isBlank()) return
        Thread({
            while (isRunning(runGeneration)) {
                val socket = android.net.LocalSocket()
                try {
                    socket.connect(
                        android.net.LocalSocketAddress(
                            socketName,
                            android.net.LocalSocketAddress.Namespace.ABSTRACT
                        )
                    )
                    cursorSocket = socket
                    val output = java.io.DataOutputStream(socket.outputStream)
                    while (isRunning(runGeneration)) {
                        val update = cursorUpdates.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        output.writeInt(update.x)
                        output.writeInt(update.y)
                        output.writeBoolean(update.visible)
                        output.writeBoolean(update.mouseLookActive)
                        output.flush()
                    }
                } catch (error: Exception) {
                    if (isRunning(runGeneration)) {
                        Log.w(tag, "Canal de cursor desconectado; tentando novamente", error)
                    }
                } finally {
                    if (cursorSocket === socket) cursorSocket = null
                    try { socket.close() } catch (_: Exception) { }
                }
                if (isRunning(runGeneration)) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }, "NexoPlay-CursorPush").apply { isDaemon = true }.start()
        publishCursorState()
    }

    private fun publishCursorState() {
        val update = CursorUpdate(
            cursorX.toInt(),
            cursorY.toInt(),
            cursorVisible(),
            mouseLookActive
        )
        val delayMs = synchronized(cursorPublishLock) {
            pendingCursorPublish = update
            if (cursorPublishScheduled) return
            cursorPublishScheduled = true
            (CURSOR_PUSH_FRAME_MS - (SystemClock.elapsedRealtime() - lastCursorPublishAt))
                .coerceAtLeast(0L)
        }
        responseExecutor.schedule({
            val latest = synchronized(cursorPublishLock) {
                val state = pendingCursorPublish
                pendingCursorPublish = null
                cursorPublishScheduled = false
                if (state != null) lastCursorPublishAt = SystemClock.elapsedRealtime()
                state
            }
            if (latest != null) enqueueCursorUpdate(latest)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun enqueueCursorUpdate(update: CursorUpdate) {
        if (!cursorUpdates.offer(update)) {
            cursorUpdates.poll()
            cursorUpdates.offer(update)
        }
    }

    private data class CursorUpdate(
        val x: Int,
        val y: Int,
        val visible: Boolean,
        val mouseLookActive: Boolean
    )

    private data class TouchMove(val finger: Int, val x: Float, val y: Float)

    private fun noteMouseMotionPath(path: String) {
        if (!mouseManaged) return
        mouseMotionPaths.add(path)
        knownMousePaths.add(path)
        mouseCandidateSummary = knownMousePaths.sorted().joinToString { candidate ->
            File(candidate).name + if (candidate in mouseMotionPaths) " (REL_X/Y)" else " (botões)"
        }
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
        lines += "Foco por evento: $taskStackListenerStatus"
        val names = devices.keys.map { File(it).name }.sorted()
        lines += "Entrada lida: " + if (names.isEmpty()) "nenhum dispositivo" else names.joinToString()
        lines += "Cursor próprio do app: ${mouseGrabStatus()}"
        lines += "Modo do mouse: " + if (mouseLookActive) "mira ativa" else "cursor (mira suspensa)"
        lines += "Tecla de emergência: $emergencyKey"
        lines += "Captura do teclado: " + when {
            !captureKeyboard -> "desativada"
            chatMode -> "modo chat (liberado)"
            grabbedKeyboardPaths.isNotEmpty() -> "ativa (${grabbedKeyboardPaths.size} nó(s))"
            else -> "aguardando ou sem permissão"
        }
        lines += "Nós de mouse: $mouseCandidateSummary"
        if (mouseGrabFailures.isNotEmpty()) lines += "Falhas de detecção/captura: " +
            mouseGrabFailures.entries.sortedBy { it.key }.joinToString { "${File(it.key).name}: ${it.value}" }
        if (keyboardGrabFailures.isNotEmpty()) lines += "Falhas de captura do teclado: " +
            keyboardGrabFailures.entries.sortedBy { it.key }
                .joinToString { "${File(it.key).name}: ${it.value}" }
        lines += "Toques: " + (injector?.summary() ?: "motor parado")
        lines += "Teclas mapeadas: ${bindings.size}"
        lines += "Último movimento do mouse: " + if (lastMouseMoveAt == 0L) {
            "nenhum recebido"
        } else {
            "${lastMouseDeltaX}, ${lastMouseDeltaY} (há ${(SystemClock.elapsedRealtime() - lastMouseMoveAt) / 1000}s)"
        }
        lines += if (lastKeyAt == 0L) {
            "Última tecla: nenhuma recebida ainda"
        } else {
            "Última tecla: $lastKeyText (há ${(SystemClock.elapsedRealtime() - lastKeyAt) / 1000}s)"
        }
        return lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- key handling

    private fun onKey(
        key: String,
        down: Boolean,
        capturedMouseInput: Boolean,
        capturedKeyboardInput: Boolean,
        sourcePath: String
    ) {
        lastKeyText = "$key ${if (down) "↓" else "↑"}"
        lastKeyAt = SystemClock.elapsedRealtime()
        if (down && key == emergencyKey) {
            Log.w(tag, "Tecla de emergência acionada; liberando entradas e pausando o motor")
            stop()
            return
        }
        if (key.startsWith("MOUSE_") && !capturedMouseInput && down) return
        val isTransition = synchronized(lock) {
            if (down) {
                val sources = pressedKeySources.getOrPut(key) { HashSet() }
                sources.add(sourcePath) && sources.size == 1
            } else {
                val sources = pressedKeySources[key] ?: return@synchronized false
                if (!sources.remove(sourcePath)) {
                    false
                } else if (sources.isEmpty()) {
                    pressedKeySources.remove(key)
                    true
                } else {
                    false
                }
            }
        }
        if (!isTransition) return
        if (captureKeyboard && key == "ENTER") {
            if (!down && chatMode) {
                if (chatEnterReleasePending) chatEnterReleasePending = false
                else {
                    chatMode = false
                    refreshKeyboardGrab()
                }
            } else if (down && chatMode) {
                return
            } else if (down && capturedKeyboardInput) {
                chatMode = true
                chatEnterReleasePending = true
                synchronized(lock) {
                    bindings.values.flatten().forEach { it.control.reset() }
                    injector?.releaseAll()
                }
                refreshKeyboardGrab()
                injector?.let { touch ->
                    touch.injectKey("ENTER", true)
                    responseExecutor.schedule(
                        { touch.injectKey("ENTER", false) },
                        40,
                        TimeUnit.MILLISECONDS
                    )
                }
            }
            return
        }
        if (chatMode) return
        if (capturedKeyboardInput && key !in bindings && !key.startsWith("MOUSE_")) {
            injector?.injectKey(key, down)
            return
        }
        val mouseLookShortcut = bindings[key].orEmpty().any { it.control.type == "MOUSE_LOOK" }
        val mouseDragShortcut = bindings[key].orEmpty().any { it.control.type == "MOUSE_DRAG" }
        if (key == "MOUSE_LEFT" && !mouseLookActive && !mouseLookShortcut && !mouseDragShortcut) {
            synchronized(lock) {
                val touch = injector ?: return
                if (down) {
                    if (cursorFinger < 0) cursorFinger = touch.down(cursorX, cursorY)
                } else if (cursorFinger >= 0) {
                    touch.up(cursorFinger)
                    cursorFinger = -1
                }
            }
            return
        }
        if (!mouseLookActive && key == "MOUSE_RIGHT" && bindings[key].isNullOrEmpty()) {
            if (rightClickMode == "LONG_PRESS") {
                synchronized(lock) {
                    val touch = injector ?: return
                    if (down && cursorRightFinger < 0) {
                        cursorRightFinger = touch.down(cursorX, cursorY)
                    } else if (!down && cursorRightFinger >= 0) {
                        touch.up(cursorRightFinger)
                        cursorRightFinger = -1
                    }
                }
            } else if (down) {
                injector?.injectKey("BACK", true)
                responseExecutor.schedule({ injector?.injectKey("BACK", false) }, 40L, TimeUnit.MILLISECONDS)
            }
            return
        }
        if (!mouseLookActive && key == "MOUSE_MIDDLE" && bindings[key].isNullOrEmpty()) {
            if (down) injector?.tap(cursorX, cursorY)
            return
        }
        // In aim mode MOUSE_LEFT stays mapped to its held touch while Mouse FPS moves a separate finger.
        val list = bindings[key] ?: return
        if (!foreground && down) return
        list.forEach { binding ->
            val delay = binding.control.responseMs.toLong()
            if (delay == 0L) performBinding(binding, down)
            else responseExecutor.schedule({ performBinding(binding, down) }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun performBinding(binding: Binding, down: Boolean) {
        synchronized(lock) {
            val touch = injector ?: return
            val control = binding.control
            if (control.pressMode == "TAP" &&
                control.type != "JOYSTICK" && control.type != "MOUSE_DRAG"
            ) {
                if (down) when (control.type) {
                    "TOUCH", "MOUSE_PICK" -> {
                        pressTouch(touch, control)
                        responseExecutor.schedule({ synchronized(lock) { injector?.let { releaseTouch(it, control) } } }, 55, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    "SWIPE" -> swipe(touch, control)
                }
                return
            }
            when (control.type) {
                "MOUSE_LOOK" -> if (down) toggleMouseMode()
                "TOUCH", "MOUSE_PICK" -> if (down) pressTouch(touch, control) else releaseTouch(touch, control)
                "SWIPE" -> if (down) swipe(touch, control)
                "MOUSE_DRAG" -> if (down) pressMouseDrag(touch, control) else releaseTouch(touch, control)
                "JOYSTICK" -> moveJoystick(touch, control, binding.direction, down)
            }
        }
    }

    private fun pressMouseDrag(touch: TouchInjector, control: Control) {
        if (control.finger >= 0 || !touch.available) return
        control.lookX = pixelX(control)
        control.lookY = pixelY(control)
        control.finger = touch.down(control.lookX, control.lookY)
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
        if (down) {
            if (direction !in control.held) control.held.add(direction)
        } else {
            control.held.remove(direction)
        }

        val vertical = axis(control, UP, DOWN)
        val horizontal = axis(control, LEFT, RIGHT)
        if (vertical == 0 && horizontal == 0) {
            // Many keyboards emit key-up for W before key-down for A when the player
            // rolls between directions. Keep the virtual finger down briefly so Free
            // Fire does not see a full joystick release during that handoff.
            if (control.finger >= 0) {
                val releaseToken = ++control.joystickReleaseToken
                responseExecutor.schedule({
                    synchronized(lock) {
                        val stillNeutral = axis(control, UP, DOWN) == 0 && axis(control, LEFT, RIGHT) == 0
                        if (control.joystickReleaseToken == releaseToken && stillNeutral && control.finger >= 0) {
                            touch.up(control.finger)
                            control.finger = -1
                        }
                    }
                }, JOYSTICK_RELEASE_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            return
        }
        // A new direction arrived inside the handoff window: cancel the pending up
        // and move the existing finger instead of starting a fresh joystick touch.
        control.joystickReleaseToken++
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
                parseMouseSensitivity(item, "sensitivityX"),
                parseMouseSensitivity(item, "sensitivityY"),
                item.optInt("smoothing", DEFAULT_LOOK_SMOOTHING).coerceIn(0, 100)
            )
            control.pressMode = item.optString("pressMode", "HOLD")
            control.responseMs = item.optInt("responseMs", 0).coerceIn(0, 300)
            if (type == "MOUSE_LOOK") cursorSpeed = item.optDouble("cursorSpeed", 1.6).toFloat().coerceIn(0f, 4f)
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

    private fun parseMouseSensitivity(item: org.json.JSONObject, key: String): Float {
        val value = item.optDouble(key, 1.0).toFloat()
        return when (item.optInt("sensitivityScale", 0)) {
            100 -> (value.coerceIn(0f, 100f) / 100f) * MAX_MOUSE_SENSITIVITY
            1000 -> (value.coerceIn(0f, 3000f) / 1000f).coerceAtMost(MAX_MOUSE_SENSITIVITY)
            else -> value.coerceIn(0.1f, 4f).coerceAtMost(MAX_MOUSE_SENSITIVITY)
        }
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
        val sensitivityY: Float,
        val smoothing: Int
    ) {
        var finger = -1
        var lookX = 0f
        var lookY = 0f
        var pressMode = "HOLD"
        var responseMs = 0
        var joystickReleaseToken = 0
        val held = ArrayList<Int>()

        fun reset() {
            finger = -1
            lookX = 0f
            lookY = 0f
            joystickReleaseToken++
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
        const val TRANSACTION_MOUSE_MODE = IBinder.FIRST_CALL_TRANSACTION + 4
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_REL = 2
        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273
        const val BTN_TASK = 279
        const val SYN_REPORT = 0
        const val REL_X = 0
        const val REL_Y = 1
        const val REL_HWHEEL = 6
        const val REL_WHEEL = 8
        const val REL_HWHEEL_HI_RES = 11
        const val REL_WHEEL_HI_RES = 12
        const val MOUSE_LOOK_SCALE = 1.0f
        const val DEFAULT_LOOK_SMOOTHING = 35
        const val MAX_LOOK_SMOOTHING_TAU_MS = 8f
        const val LOOK_SMOOTHING_RESET_MS = 80L
        const val MAX_MOUSE_SENSITIVITY = 3f
        const val LOOK_DELTA_EPSILON = 0.001f
        const val MOUSE_MOTION_FRAME_MS = 8L
        const val CURSOR_PUSH_FRAME_MS = 16L
        const val MOUSE_DRAG_SCALE = 1.0f
        const val CURSOR_SPEED = 1.6f
        const val UP = 0
        const val DOWN = 1
        const val LEFT = 2
        const val RIGHT = 3
        const val SWIPE_DISTANCE = 96f
        const val DEFAULT_JOYSTICK_RANGE = 120f
        const val JOYSTICK_RELEASE_GRACE_MS = 80L
        val SUPPORTED_TYPES = setOf("TOUCH", "SWIPE", "MOUSE_PICK", "JOYSTICK", "MOUSE_LOOK", "MOUSE_DRAG")

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

private object NativeMouseGrab {
    const val MOUSE_DEVICE = 1
    const val MOUSE_MOTION = 2
    const val KEYBOARD_DEVICE = 4

    init { System.loadLibrary("mapeador_input") }
    external fun capabilities(fd: FileDescriptor): Int
    external fun identity(fd: FileDescriptor): String?
    external fun grab(fd: FileDescriptor, enabled: Boolean): Int
}

package com.mapeador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.net.LocalServerSocket
import android.view.Gravity
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.io.DataInputStream
import kotlin.math.abs

internal object FloatingBubbleIntent {
    const val GAME_PACKAGE = "com.mapeador.extra.GAME_PACKAGE"
    const val MAPPING_JSON = "com.mapeador.extra.MAPPING_JSON"
}

private data class OverlayControl(
    val id: String,
    val type: String,
    val key: String,
    val x: Float,
    val y: Float,
    val radius: Float = 0.85f,
    val stopOnOpposite: Boolean = true,
    val mousePosition: String = "Centro",
    val cursorX: Float = 0.5f,
    val cursorY: Float = 0.5f,
    val preventViewFreeze: Boolean = true,
    val controllerAcceleration: Float = 0.5f,
    val sensitivityX: Float = 100f / 3f,
    val sensitivityY: Float = 100f / 3f,
    val smoothing: Int = 35,
    val sizeScale: Float = 1f,
    val cursorSpeed: Float = 1.6f,
    val cursorSize: Float = 1f,
    val pressMode: String = "HOLD",
    val responseMs: Int = 0
)

private fun JSONObject.sensitivityValue(name: String): Float {
    if (!has(name)) return 100f / 3f
    val value = optDouble(name, 100.0).toFloat()
    return when (optInt("sensitivityScale", 0)) {
        100 -> value.coerceIn(0f, 100f)
        1000 -> (value / 30f).coerceIn(0f, 100f)
        else -> (value * (100f / 3f)).coerceIn(0f, 100f)
    }
}

/** The app's own mouse arrow. Its tip is the top-left corner of the view. */
private class CursorView(context: android.content.Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = B13Theme.ACCENT_ARGB
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private val arrow = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat() - outline.strokeWidth
        val h = height.toFloat() - outline.strokeWidth
        arrow.reset()
        arrow.moveTo(0f, 0f)
        arrow.lineTo(0f, h * 0.82f)
        arrow.lineTo(w * 0.26f, h * 0.62f)
        arrow.lineTo(w * 0.46f, h)
        arrow.lineTo(w * 0.62f, h * 0.92f)
        arrow.lineTo(w * 0.42f, h * 0.56f)
        arrow.lineTo(w * 0.85f, h * 0.56f)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
    }
}

private class HexagonIconView(
    context: android.content.Context,
    private val symbol: String,
    private val label: String,
    enabled: Boolean
) : View(context) {
    private val polygon = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = B13Theme.PANEL_DARK_ARGB
        alpha = if (enabled) 255 else 120
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = B13Theme.ACCENT_ARGB
        alpha = if (enabled) 255 else 120
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        alpha = if (enabled) 255 else 150
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.density * 24f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    init {
        contentDescription = label
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = minOf(width, height) * 0.46f
        val centerX = width / 2f
        val centerY = height / 2f
        polygon.reset()
        for (index in 0 until 6) {
            val angle = Math.toRadians((index * 60 - 90).toDouble())
            val x = centerX + kotlin.math.cos(angle).toFloat() * radius
            val y = centerY + kotlin.math.sin(angle).toFloat() * radius
            if (index == 0) polygon.moveTo(x, y) else polygon.lineTo(x, y)
        }
        polygon.close()
        canvas.drawPath(polygon, fill)
        canvas.drawPath(polygon, outline)
        val baseline = centerY - (text.ascent() + text.descent()) / 2f
        canvas.drawText(symbol, centerX, baseline, text)
    }
}

private fun withOpacity(color: Int, opacity: Float): Int =
    (color and 0x00FFFFFF) or ((opacity.coerceIn(0f, 1f) * 255).toInt() shl 24)

private class JoystickControlView(
    context: android.content.Context,
    private val key: String,
    private val indicatorColor: Int,
    private val opacity: Float
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(centerX, centerY) * 0.88f

        paint.color = withOpacity(indicatorColor, opacity * 0.72f)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, radius, paint)

        paint.color = withOpacity(indicatorColor, opacity)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density * 2f
        canvas.drawCircle(centerX, centerY, radius, paint)
        canvas.drawCircle(centerX, centerY, radius * 0.48f, paint)
        canvas.drawLine(centerX - radius * 0.7f, centerY, centerX + radius * 0.7f, centerY, paint)
        canvas.drawLine(centerX, centerY - radius * 0.7f, centerX, centerY + radius * 0.7f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = resources.displayMetrics.density * 9f
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        canvas.drawText(key, centerX, centerY + paint.textSize * 0.35f, paint)
        paint.textSize = resources.displayMetrics.density * 8f
        val arrows = key.equals("SETAS", ignoreCase = true)
        canvas.drawText(if (arrows) "↑" else "W", centerX, centerY - radius * 0.54f, paint)
        canvas.drawText(if (arrows) "←" else "A", centerX - radius * 0.57f, centerY + paint.textSize * 0.35f, paint)
        canvas.drawText(if (arrows) "↓" else "S", centerX, centerY + radius * 0.68f, paint)
        canvas.drawText(if (arrows) "→" else "D", centerX + radius * 0.57f, centerY + paint.textSize * 0.35f, paint)
    }
}

private fun keyLabel(key: String): String = when (key.uppercase()) {
    "SHIFT_LEFT" -> "L-Shift"
    "SHIFT_RIGHT" -> "R-Shift"
    "CTRL_LEFT" -> "L-Ctrl"
    "CTRL_RIGHT" -> "R-Ctrl"
    "ALT_LEFT" -> "L-Alt"
    "ALT_RIGHT" -> "R-Alt"
    "SPACE" -> "Espaço"
    "CAPS_LOCK" -> "Caps"
    "GRAVE" -> "~"
    "ESCAPE" -> "Esc"
    "DEL" -> "Backspace"
    "FORWARD_DEL" -> "Del"
    "MOUSE_LEFT" -> "Mouse-E"
    "MOUSE_RIGHT" -> "Mouse-D"
    "MOUSE_MIDDLE" -> "Mouse-M"
    "MOUSE_WHEEL_UP" -> "Roda ↑"
    "MOUSE_WHEEL_DOWN" -> "Roda ↓"
    "MOUSE_WHEEL_LEFT" -> "Roda ←"
    "MOUSE_WHEEL_RIGHT" -> "Roda →"
    "DPAD_UP" -> "↑"
    "DPAD_DOWN" -> "↓"
    "DPAD_LEFT" -> "←"
    "DPAD_RIGHT" -> "→"
    "PAGE_UP" -> "PgUp"
    "PAGE_DOWN" -> "PgDn"
    "TECLA?" -> "?"
    else -> if (key.uppercase().startsWith("NUMPAD_")) "Num" + key.substring(7) else key
}

private class KeyCaptureFrame(context: android.content.Context) : FrameLayout(context) {
    var onKeyCaptured: ((String) -> Unit)? = null
    var onBackgroundDoubleTap: ((Float, Float) -> Unit)? = null
    var captureEnabled = false
    private var lastBackgroundTapAt = 0L
    private var lastBackgroundTapX = 0f
    private var lastBackgroundTapY = 0f
    private var backgroundTapCandidate = false
    private var backgroundTapDownX = 0f
    private var backgroundTapDownY = 0f

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (captureEnabled && event.action == KeyEvent.ACTION_DOWN) {
            onKeyCaptured?.invoke(KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_"))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (captureEnabled && event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.actionMasked == MotionEvent.ACTION_SCROLL
        ) return captureMouseWheel(event)
        if (captureEnabled && event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                event.actionMasked == MotionEvent.ACTION_DOWN)
        ) {
            val button = if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
                event.actionButton
            } else {
                event.buttonState
            }
            val name = when {
                button and MotionEvent.BUTTON_PRIMARY != 0 -> "MOUSE_LEFT"
                button and MotionEvent.BUTTON_SECONDARY != 0 -> "MOUSE_RIGHT"
                button and MotionEvent.BUTTON_TERTIARY != 0 -> "MOUSE_MIDDLE"
                else -> null
            }
            if (name != null) {
                onKeyCaptured?.invoke(name)
                return true
            }
        }

        if (!captureEnabled && event.pointerCount == 1 &&
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
        ) {
            fun isOverChild(x: Float, y: Float): Boolean = (0 until childCount).any { index ->
                val child = getChildAt(index)
                child.visibility == View.VISIBLE &&
                    x >= child.x && x <= child.x + child.width &&
                    y >= child.y && y <= child.y + child.height
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    backgroundTapDownX = event.x
                    backgroundTapDownY = event.y
                    backgroundTapCandidate = !isOverChild(event.x, event.y)
                    if (!backgroundTapCandidate) lastBackgroundTapAt = 0L
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - backgroundTapDownX
                    val dy = event.y - backgroundTapDownY
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (dx * dx + dy * dy > slop * slop) backgroundTapCandidate = false
                }
                MotionEvent.ACTION_UP -> {
                    if (backgroundTapCandidate && !isOverChild(event.x, event.y)) {
                        val now = event.eventTime
                        val dx = event.x - lastBackgroundTapX
                        val dy = event.y - lastBackgroundTapY
                        val slop = ViewConfiguration.get(context).scaledTouchSlop * 2
                        if (lastBackgroundTapAt != 0L &&
                            now - lastBackgroundTapAt <= ViewConfiguration.getDoubleTapTimeout() &&
                            dx * dx + dy * dy <= slop * slop
                        ) {
                            lastBackgroundTapAt = 0L
                            backgroundTapCandidate = false
                            onBackgroundDoubleTap?.invoke(event.x, event.y)
                            return true
                        }
                        lastBackgroundTapAt = now
                        lastBackgroundTapX = event.x
                        lastBackgroundTapY = event.y
                    } else {
                        lastBackgroundTapAt = 0L
                    }
                    backgroundTapCandidate = false
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (captureEnabled && event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.actionMasked == MotionEvent.ACTION_SCROLL
        ) return captureMouseWheel(event)
        return super.dispatchGenericMotionEvent(event)
    }

    private fun captureMouseWheel(event: MotionEvent): Boolean {
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val name = when {
            vertical > 0f -> "MOUSE_WHEEL_UP"
            vertical < 0f -> "MOUSE_WHEEL_DOWN"
            horizontal > 0f -> "MOUSE_WHEEL_RIGHT"
            horizontal < 0f -> "MOUSE_WHEEL_LEFT"
            else -> null
        } ?: return false
        onKeyCaptured?.invoke(name)
        return true
    }
}

class FloatingBubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: ImageView? = null
    private var keymapOverlay: FrameLayout? = null
    private var menu: View? = null
    private var editor: KeyCaptureFrame? = null
    private var componentPicker: View? = null
    private var resetConfirmation: View? = null
    private var controlSettings: View? = null
    private var importDialog: View? = null
    private var hud: TextView? = null
    private var hudStart = 0L
    private val hudTicker = object : Runnable {
        override fun run() {
            updateHud()
            handler.postDelayed(this, 1000)
        }
    }
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var editorParams: WindowManager.LayoutParams? = null
    private var keymapParams: WindowManager.LayoutParams? = null
    private var engineSize: Pair<Int, Int>? = null
    private var captureTarget: String? = null
    private var activeGamePackage: String? = null
    @Volatile private var lastEngineState: ShizukuInputBridge.EngineState? = null
    private var menuPreviousMouseLookActive: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())
    /** False while the game is not in front: the bubble and the key indicators are hidden. */
    private var overlaysVisible = true
    private var cursorView: View? = null
    private var cursorOverlay: FrameLayout? = null
    private var cursorShown = false
    private var cursorNeedsElevation = false
    private var cursorParams: WindowManager.LayoutParams? = null
    private var mouseModeIndicator: TextView? = null
    private var mouseModeParams: WindowManager.LayoutParams? = null
    private var hasMouseLookControl = false
    private var cursorScale = 1f
    private val cursorPushLock = Any()
    private var pendingCursorState: ShizukuInputBridge.EngineState? = null
    private var cursorFrameCallbackPosted = false
    private val cursorFrameCallback = Choreographer.FrameCallback {
        val latest = synchronized(cursorPushLock) {
            pendingCursorState.also {
                pendingCursorState = null
                cursorFrameCallbackPosted = false
            }
        } ?: return@FrameCallback
        lastEngineState = latest
        updateCursor(latest)
    }
    @Volatile private var cursorSocketRunning = false
    private val cursorSocketName = "mapeador_cursor_${UUID.randomUUID().toString().replace("-", "")}"
    private var cursorServerSocket: LocalServerSocket? = null
    private var cursorSocketThread: Thread? = null
    private var editorMouseDownTime = 0L
    private var missedPolls = 0
    private var lastReconnectAt = 0L
    private var gameLaunchAt = 0L
    private var diagnosticsView: View? = null
    private var diagnosticsText: TextView? = null
    private val statePoller = object : Runnable {
        override fun run() {
            pollEngineState()
            val starting = SystemClock.elapsedRealtime() - gameLaunchAt < 4000L &&
                lastEngineState?.foreground != 1
            handler.postDelayed(this, if (starting || cursorShown) 250L else 750L)
        }
    }
    private val diagnosticsTicker = object : Runnable {
        override fun run() {
            val text = diagnosticsText ?: return
            text.text = appVersionLine() + "\n" +
                (ShizukuInputBridge.diagnostics() ?: ShizukuInputBridge.connectionReport())
            handler.postDelayed(this, 500)
        }
    }
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf(MAPPING, "indicators", "opacity", "indicator_color2")) {
            handler.post {
                keymapOverlay?.let(::renderControls)
                if (key == MAPPING && editor == null) startEngine()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        migrateSensitivitySchemaOnce()
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferenceListener)
        startAsForegroundService()
        startCursorSocket()
        if (Settings.canDrawOverlays(this)) showBubble() else stopSelf()
    }

    private fun migrateSensitivitySchemaOnce() {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean("sensitivity_schema_v1", false)) return
        val saved = try {
            JSONArray(preferences.getString(MAPPING, "[]") ?: "[]")
        } catch (error: JSONException) {
            Log.e(TAG, "Não foi possível migrar a escala de sensibilidade; o mapeamento foi mantido", error)
            preferences.edit().putBoolean("sensitivity_schema_v1", true).apply()
            return
        }
        var changed = false
        for (index in 0 until saved.length()) {
            val item = saved.optJSONObject(index) ?: continue
            val scale = item.optInt("sensitivityScale", 0)
            if (scale == 100 || (!item.has("sensitivityX") && !item.has("sensitivityY"))) continue
            try {
                if (item.has("sensitivityX")) item.put("sensitivityX", item.sensitivityValue("sensitivityX").toDouble())
                if (item.has("sensitivityY")) item.put("sensitivityY", item.sensitivityValue("sensitivityY").toDouble())
                item.put("sensitivityScale", 100)
                changed = true
            } catch (error: JSONException) {
                Log.e(TAG, "Não foi possível migrar a sensibilidade de um controle", error)
            }
        }
        val update = preferences.edit().putBoolean("sensitivity_schema_v1", true)
        if (changed) update.putString(MAPPING, saved.toString())
        update.apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            handler.removeCallbacks(statePoller)
            ShizukuInputBridge.stopMapping(true)
            activeGamePackage = null
            stopSelf()
            return START_NOT_STICKY
        }
        val gamePackage = intent?.getStringExtra(FloatingBubbleIntent.GAME_PACKAGE)
        if (!gamePackage.isNullOrBlank()) {
            activeGamePackage = gamePackage
            gameLaunchAt = SystemClock.elapsedRealtime()
            if (bubble == null && Settings.canDrawOverlays(this)) showBubble()
            // The game is still opening and may be rotating to landscape: start now and
            // once more shortly after, so the engine always has the final screen size.
            startEngine()
            handler.postDelayed({ if (editor == null) relayoutForScreenSize() }, 2500)
            // Show the bubble and the keys only while the game is in front.
            handler.removeCallbacks(statePoller)
            handler.postDelayed(statePoller, 700)
        }
        if (bubble == null && Settings.canDrawOverlays(this)) showBubble()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Wait for the rotation to finish before reading the new screen size.
        handler.postDelayed({ relayoutForScreenSize() }, 400)
    }

    /** Resizes the full-screen overlays to the current rotation and restarts the key engine. */
    private fun relayoutForScreenSize() {
        val manager = windowManager ?: return
        val (width, height) = realScreenSize()
        listOf(
            keymapOverlay to keymapParams,
            editor to editorParams,
            cursorOverlay to cursorParams
        ).forEach { (view, params) ->
            if (view != null && params != null && (params.width != width || params.height != height)) {
                params.width = width
                params.height = height
                try {
                    manager.updateViewLayout(view, params)
                } catch (error: IllegalArgumentException) {
                    Log.w(TAG, "A janela não está mais anexada", error)
                }
            }
        }
        bubbleParams?.let { params ->
            bubble?.let { view ->
                params.x = params.x.coerceIn(0, (width - dp(56)).coerceAtLeast(0))
                params.y = params.y.coerceIn(0, (height - dp(56)).coerceAtLeast(0))
                try {
                    manager.updateViewLayout(view, params)
                } catch (error: IllegalArgumentException) {
                    Log.w(TAG, "A bolha não está mais anexada", error)
                }
            }
        }
        keymapOverlay?.let(::renderControls)
        editor?.let(::renderControls)
        if (editor == null && realScreenSize() != engineSize) startEngine()
    }

    /** Asks the engine if the game is in front and where the app's own mouse cursor is. */
    private fun pollEngineState() {
        if (activeGamePackage == null) return
        val state = ShizukuInputBridge.queryState()
        if (state == null) {
            // The engine inside Shizuku is not answering: red bubble, and try to reconnect.
            missedPolls++
            setBubbleStatus(0xFFFF3B30.toInt())
            val now = SystemClock.elapsedRealtime()
            if (missedPolls >= 3 && now - lastReconnectAt > 6000 && editor == null) {
                lastReconnectAt = now
                startEngine()
            }
            return
        }
        missedPolls = 0
        lastEngineState = state
        // Green = engine running and the game in front; yellow = running but cannot tell.
        setBubbleStatus(if (state.foreground == 2) 0xFFFFCC00.toInt() else 0xFF34C759.toInt())
        applyGameVisibility(state.foreground == 1)
        publishPushedCursor(state)
    }

    /** The bubble border shows if the key engine is connected (green), unsure (yellow) or down (red). */
    private fun setBubbleStatus(color: Int) {
        (bubble?.background as? GradientDrawable)?.setStroke(dp(2), color)
    }

    private fun appVersionLine(): String = try {
        "App: B13 MAUSE PRO ${packageManager.getPackageInfo(packageName, 0).versionName}"
    } catch (_: Exception) {
        "App: B13 MAUSE PRO"
    }

    /** Hides everything this app draws over other apps when the game is not in front. */
    private fun applyGameVisibility(visible: Boolean) {
        if (visible == overlaysVisible) return
        overlaysVisible = visible
        val mode = if (visible) View.VISIBLE else View.GONE
        bubble?.visibility = mode
        keymapOverlay?.visibility = mode
        editor?.visibility = mode
        cursorOverlay?.visibility = mode
        if (!visible) {
            cursorShown = false
            closeMenu()
            closeDiagnostics()
        }
    }

    /** Receives cursor positions pushed by the Shizuku process and renders a dedicated window. */
    private fun updateCursor(state: ShizukuInputBridge.EngineState) {
        updateMouseModeIndicator(state)
        val manager = windowManager
        if (manager == null || !state.cursorVisible || !overlaysVisible) {
            cursorShown = false
            removeOverlay(cursorOverlay)
            return
        }
        cursorShown = true
        var overlay = cursorOverlay
        if (overlay == null) {
            val view = CursorView(this).apply {
                alpha = 1f
                isClickable = false
                isFocusable = false
            }
            cursorView = view
            overlay = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                addView(view, FrameLayout.LayoutParams(
                    dp((24 * cursorScale).toInt().coerceIn(18, 60)),
                    dp((34 * cursorScale).toInt().coerceIn(24, 80))
                ))
            }
            cursorOverlay = overlay
        }
        val params = cursorParams ?: run {
            cursorScale = readControls().firstOrNull { it.type == "MOUSE_LOOK" }?.cursorSize ?: 1f
            fullScreenParams(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            ).apply { alpha = 0.8f }.also { cursorParams = it }
        }
        val cursorWidth = dp((24 * cursorScale).toInt().coerceIn(18, 60))
        val cursorHeight = dp((34 * cursorScale).toInt().coerceIn(24, 80))
        cursorView?.let { view ->
            val viewParams = view.layoutParams as? FrameLayout.LayoutParams
            if (viewParams == null || viewParams.width != cursorWidth || viewParams.height != cursorHeight) {
                view.layoutParams = FrameLayout.LayoutParams(cursorWidth, cursorHeight)
            }
        }
        overlay.visibility = View.VISIBLE
        cursorView?.apply {
            visibility = View.VISIBLE
            translationX = state.cursorX.toFloat()
            translationY = state.cursorY.toFloat()
            invalidate()
        }
        try {
            if (overlay.parent == null) manager.addView(overlay, params)
            if (cursorNeedsElevation) {
                cursorNeedsElevation = false
                bringCursorOverlayToFront()
            }
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "Não foi possível exibir a janela do cursor", error)
            cursorShown = false
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "A janela do cursor não está anexada", error)
            cursorShown = false
        }
    }

    private fun updateMouseModeIndicator(state: ShizukuInputBridge.EngineState) {
        val manager = windowManager ?: return
        if (!hasMouseLookControl || !overlaysVisible) {
            mouseModeIndicator?.visibility = View.GONE
            return
        }
        val indicator = mouseModeIndicator ?: TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBackground(0xE620232B.toInt(), 0xFFE5484D.toInt(), dp(16))
            alpha = 1f
            isClickable = false
            isFocusable = false
        }.also { mouseModeIndicator = it }
        val modeLabel = if (state.mouseLookActive) "MIRA" else "CURSOR"
        indicator.text = modeLabel
        val params = mouseModeParams ?: WindowManager.LayoutParams(
            dp(108),
            dp(34),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
                gravity = Gravity.TOP or Gravity.END
            alpha = 1f
        }.also { mouseModeParams = it }
        params.x = dp(12)
        params.y = dp(if (editor == null) 8 else 132)
        indicator.visibility = View.VISIBLE
        try {
            if (indicator.parent == null) manager.addView(indicator, params)
            else manager.updateViewLayout(indicator, params)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Não foi possível exibir o indicador do modo do mouse", error)
            indicator.visibility = View.GONE
        }
    }

    private fun publishPushedCursor(state: ShizukuInputBridge.EngineState) {
        var scheduleFrame = false
        synchronized(cursorPushLock) {
            pendingCursorState = state
            if (!cursorFrameCallbackPosted) {
                cursorFrameCallbackPosted = true
                scheduleFrame = true
            }
        }
        if (scheduleFrame) handler.post {
            Choreographer.getInstance().postFrameCallback(cursorFrameCallback)
        }
    }

    private fun startCursorSocket() {
        if (cursorSocketRunning) return
        cursorSocketRunning = true
        try {
            val server = LocalServerSocket(cursorSocketName)
            cursorServerSocket = server
            cursorSocketThread = Thread({
                while (cursorSocketRunning) {
                    try {
                        server.accept().use { socket ->
                            DataInputStream(socket.inputStream).use { input ->
                                while (cursorSocketRunning && !socket.isClosed) {
                                    val x = input.readInt()
                                    val y = input.readInt()
                                    val visible = input.readBoolean()
                                    val mouseLookActive = input.readBoolean()
                                    val action = input.readInt()
                                    val foreground = lastEngineState?.foreground ?: 2
                                    if (action == -1) {
                                        publishPushedCursor(
                                            ShizukuInputBridge.EngineState(
                                                foreground,
                                                visible,
                                                x,
                                                y,
                                                mouseLookActive
                                            )
                                        )
                                    } else {
                                        dispatchEditorPointerEvent(action, x, y)
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        if (cursorSocketRunning) {
                            Log.w(TAG, "Conexão de atualização do cursor encerrada", error)
                        }
                    }
                }
            }, "NexoPlay-CursorSocket").apply { isDaemon = true }.also { it.start() }
        } catch (error: Exception) {
            cursorSocketRunning = false
            Log.e(TAG, "Não foi possível abrir o canal local do cursor", error)
        }
    }

    private fun stopCursorSocket() {
        cursorSocketRunning = false
        try {
            cursorServerSocket?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Não foi possível fechar o canal local do cursor", error)
        }

        cursorServerSocket = null
        cursorSocketThread = null
    }

    private fun dispatchEditorPointerEvent(action: Int, x: Int, y: Int) {
        handler.post {
            val target = editor ?: return@post
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) editorMouseDownTime = now
            val downTime = editorMouseDownTime.takeIf { it > 0L } ?: now
            val event = MotionEvent.obtain(
                downTime,
                now,
                action,
                x.toFloat(),
                y.toFloat(),
                0
            )
            event.setSource(InputDevice.SOURCE_MOUSE)
            try {
                target.dispatchTouchEvent(event)
            } finally {
                event.recycle()
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    editorMouseDownTime = 0L
                }
            }
        }
    }

    private fun showDiagnostics() {
        if (diagnosticsView != null) return
        val manager = windowManager ?: return
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99080B12.toInt())
            setOnClickListener { closeDiagnostics() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBackground(0xFF4A1218.toInt(), 0xFFE5484D.toInt(), dp(20))
            isClickable = true
            setOnClickListener { }
            elevation = dp(16).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "Diagnóstico do keymap"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "Aperte uma tecla ou clique com o mouse: a última tecla recebida aparece abaixo."
            textSize = 12f
            setTextColor(0xFFFFE3E4.toInt())
            setPadding(0, dp(6), 0, dp(10))
        })
        val body = TextView(this).apply {
            text = "Consultando…"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        card.addView(body)
        card.addView(
            actionButton("Fechar") { closeDiagnostics() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) }
        )
        scrim.addView(
            card,
            FrameLayout.LayoutParams(
                (screenWidth() - dp(36)).coerceAtMost(dp(480)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        val params = fullScreenParams(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        try {
            manager.addView(scrim, params)
            diagnosticsView = scrim
            diagnosticsText = body
            handler.post(diagnosticsTicker)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Não foi possível abrir o diagnóstico", error)
        }
    }

    private fun closeDiagnostics() {
        handler.removeCallbacks(diagnosticsTicker)
        removeOverlay(diagnosticsView)
        diagnosticsView = null
        diagnosticsText = null
    }

    /** (Re)starts the key engine with the saved controls and the real screen size. */
    private fun startEngine() {
        val game = activeGamePackage ?: return
        val size = realScreenSize()
        engineSize = size
        hasMouseLookControl = readControls().any { it.type == "MOUSE_LOOK" }
        ShizukuInputBridge.startMapping(
            this,
            game,
            mappingPayload(),
            size.first,
            size.second,
            cursorSocketName,
            getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(EMERGENCY_KEY, "F12").orEmpty(),
            getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean(CAPTURE_KEYBOARD, false),
            getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(RIGHT_CLICK_MODE, "BACK").orEmpty(),
            getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(INITIAL_MOUSE_MODE, "CURSOR").orEmpty()
        )
    }

    /** What the engine needs from each control: type, key, position and joystick reach. */
    private fun mappingPayload(): String {
        val array = JSONArray()
        readControls().forEach { control ->
            val item = JSONObject()
                .put("type", control.type)
                .put("key", control.key)
                .put("x", control.x.toDouble())
                .put("y", control.y.toDouble())
                .put("stopOnOpposite", control.stopOnOpposite)
            if (control.type == "JOYSTICK") {
                item.put("rangePx", (controlSizePx(control) / 2f * 0.88f).toDouble())
            }
            if (control.type == "MOUSE_LOOK") {
                item.put("sensitivityX", control.sensitivityX.toDouble())
                item.put("sensitivityY", control.sensitivityY.toDouble())
                item.put("sensitivityScale", 100)
                item.put("smoothing", control.smoothing)
                item.put("cursorSpeed", control.cursorSpeed.toDouble())
            }
            item.put("pressMode", control.pressMode).put("responseMs", control.responseMs)
            array.put(item)
        }
        return array.toString()
    }

    private fun controlSizePx(control: OverlayControl): Int =
        if (control.type == "JOYSTICK") {
            (dp(JOYSTICK_SIZE_DP) * control.radius).toInt().coerceAtLeast(dp(JOYSTICK_MIN_DP))
        } else {
            (dp(KEY_SIZE_DP) * control.sizeScale).toInt()
        }

    private fun styledSeekBar(): SeekBar = SeekBar(this).apply {
        progressTintList = ColorStateList.valueOf(B13Theme.ACCENT_ARGB)
        thumbTintList = ColorStateList.valueOf(0xFFFF8A8A.toInt())
        progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
    }

    /** Real display size in the current rotation, in the same pixels used to inject touches. */
    private fun realScreenSize(): Pair<Int, Int> {
        val manager = windowManager ?: getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }

    private fun screenWidth(): Int = realScreenSize().first

    private fun screenHeight(): Int = realScreenSize().second

    /** A window that covers the whole display (even under the notch) starting at 0,0. */
    private fun fullScreenParams(flags: Int): WindowManager.LayoutParams {
        val (width, height) = realScreenSize()
        return WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideHud()
        handler.removeCallbacks(statePoller)
        stopCursorSocket()
        closeDiagnostics()
        removeOverlay(cursorOverlay)
        removeOverlay(mouseModeIndicator)
        cursorOverlay = null
        cursorView = null
        cursorParams = null
        mouseModeIndicator = null
        mouseModeParams = null
        ShizukuInputBridge.stopMapping(true)
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        removeOverlay(editor)
        removeOverlay(menu)
        removeOverlay(bubble)
        removeOverlay(keymapOverlay)
        editor = null
        componentPicker = null
        resetConfirmation = null
        controlSettings = null
        importDialog = null
        activeGamePackage = null
        menu = null
        bubble = null
        keymapOverlay = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        val channelId = "nexoplay_bubble"
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, "Botão flutuante", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_stat_nexoplay)
            .setContentTitle("B13 MAUSE PRO ativo")
            .setContentText("Toque na bolha para editar os controles sobre o jogo.")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_stat_nexoplay, "Desativar", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showBubble() {
        if (bubble != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = manager

        val size = dp(56)
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - size
            y = screenHeight() / 3
        }
        bubbleParams = params

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.b13_logo)
            contentDescription = "B13 MAUSE PRO. Toque para abrir o painel."
            setPadding(size / 6, size / 6, size / 6, size / 6)
            background = roundedBackground(0xFF4A1218.toInt(), 0xFFFF6B6B.toInt(), dp(28))
            clipToOutline = true
            elevation = dp(8).toFloat()
        }
        icon.setOnTouchListener(BubbleTouchListener())

        try {
            manager.addView(icon, params)
            bubble = icon
            showKeymapOverlay()
        } catch (error: SecurityException) {
            Log.e(TAG, "Permissão para exibir a bolha foi recusada", error)
            Toast.makeText(this, "Não foi possível exibir o botão flutuante.", Toast.LENGTH_LONG).show()
            stopSelf()
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "O Android recusou a janela flutuante", error)
            stopSelf()
        }
    }

    private fun showMenu() {
        if (menu != null || editor != null) return
        val manager = windowManager ?: return
        val previousMouseLookActive =
            ShizukuInputBridge.queryState()?.mouseLookActive ?: lastEngineState?.mouseLookActive ?: true
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val panelWidth = (screenWidth() * 0.42f).toInt()
            .coerceIn(dp(260), dp(420))

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { closeMenu() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF2B3202A.toInt(), 0xF2400D12.toInt(), 0xF01A0508.toInt())
            )
            isClickable = true
            setOnClickListener { }
            elevation = dp(12).toFloat()
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(
            circleButton("⌂") {
                closeMenu()
                openApp()
            },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) }
        )
        topRow.addView(
            circleButton("⚙") {
                closeMenu()
                openApp()
            },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )
        panel.addView(topRow)

        val indicatorsOn = preferences.getBoolean("indicators", true)
        val items = listOf(
            PanelItem("⌖", "Editar keymap", true) { openEditor() },
            PanelItem("◉", if (indicatorsOn) "Ocultar indicadores" else "Mostrar indicadores", true) {
                preferences.edit().putBoolean("indicators", !indicatorsOn).apply()
                closeMenu()
            },
            PanelItem("▤", if (hud == null) "Monitor de informações" else "Fechar monitor", true) {
                closeMenu()
                if (hud == null) showHud() else hideHud()
            },
            PanelItem("☰", "Diagnóstico", true) {
                closeMenu()
                showDiagnostics()
            },
            PanelItem("▣", "Abrir app", true) {
                closeMenu()
                openApp()
            },
            PanelItem("⊕", "Mira virtual", false) { },
            PanelItem("⟲", "Macro", false) { },
            PanelItem("⇣", "Controle de recuo", false) { },
            PanelItem("✕", "Desativar", true) { stopSelf() }
        )
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { item ->
                row.addView(
                    panelButton(item),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(dp(4), dp(10), dp(4), dp(0)) }
                )
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(row)
        }
        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(grid)
        }
        panel.addView(
            scroller,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        scrim.addView(
            panel,
            FrameLayout.LayoutParams(
                panelWidth,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        menuParams = params
        try {
            manager.addView(scrim, params)
            menu = scrim
            bringCursorOverlayToFront()
            menuPreviousMouseLookActive = previousMouseLookActive
            ShizukuInputBridge.setMouseLookActive(false)
            pollEngineState()
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "Não foi possível abrir o menu flutuante", error)
            Toast.makeText(this, "Não foi possível abrir o menu flutuante.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private data class PanelItem(
        val symbol: String,
        val label: String,
        val enabled: Boolean,
        val onClick: () -> Unit
    )

    private fun circleButton(symbol: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2A0A0E.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun panelButton(item: PanelItem): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = if (item.enabled) 1f else 0.45f
            isClickable = item.enabled
            isFocusable = item.enabled
            if (item.enabled) setOnClickListener { item.onClick() }
            addView(
                HexagonIconView(this@FloatingBubbleService, item.symbol, item.label, item.enabled),
                LinearLayout.LayoutParams(dp(60), dp(60))
            )
            addView(
                TextView(this@FloatingBubbleService).apply {
                    text = if (item.enabled) item.label else "${item.label}\n(em breve)"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }

    private fun showHud() {
        if (hud != null) return
        val manager = windowManager ?: return
        val view = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedBackground(0x99000000.toInt(), 0x33FFFFFF, dp(10))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(8)
        }
        try {
            manager.addView(view, params)
            hud = view
            hudStart = SystemClock.elapsedRealtime()
            updateHud()
            handler.postDelayed(hudTicker, 1000)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Não foi possível exibir o monitor", error)
        }
    }

    private fun hideHud() {
        handler.removeCallbacks(hudTicker)
        removeOverlay(hud)
        hud = null
    }

    private fun updateHud() {
        val view = hud ?: return
        val battery = (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val hours = (SystemClock.elapsedRealtime() - hudStart) / 3_600_000.0
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        view.text = String.format(java.util.Locale.US, "%d%%   %.2fh   %d MB", battery, hours, usedMb)
    }

    private fun openEditor() {
        if (editor != null) return
        closeMenu()
        hideKeymapOverlay()
        val manager = windowManager ?: return
        val root = KeyCaptureFrame(this).apply {
            isFocusableInTouchMode = true
            isClickable = true
            setBackgroundColor(Color.TRANSPARENT)
            onKeyCaptured = { key -> finishCapture(key) }
            onBackgroundDoubleTap = { x, y ->
                addControl(
                    "TOUCH",
                    (x / screenWidth().coerceAtLeast(1)).coerceIn(0f, 1f) to
                        (y / screenHeight().coerceAtLeast(1)).coerceIn(0f, 1f)
                )
            }
        }
        val params = fullScreenParams(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        editorParams = params
        try {
            manager.addView(root, params)
            editor = root
            ShizukuInputBridge.setEditorMode(true)
            bringCursorOverlayToFront()
            buildEditorHeader(root)
            renderControls(root)
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "Não foi possível abrir o editor sobre o jogo", error)
            Toast.makeText(this, "Não foi possível abrir o editor.", Toast.LENGTH_LONG).show()
            closeEditor()
        } catch (error: SecurityException) {
            Log.e(TAG, "Permissão de sobreposição não está disponível", error)
            Toast.makeText(this, "Ative a permissão de sobreposição do B13 MAUSE PRO.", Toast.LENGTH_LONG).show()
            closeEditor()
        }
    }

    private fun buildEditorHeader(root: KeyCaptureFrame) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(0xE6B3202A.toInt(), 0x00000000, dp(18))
            elevation = dp(2).toFloat()
        }
        fun cell(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(3), dp(4), dp(3), dp(4))
            minHeight = dp(B13Theme.MIN_TOUCH_TARGET_DP)
            maxLines = 2
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        fun row(vararg cells: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            cells.forEach { addView(it, LinearLayout.LayoutParams(0, dp(B13Theme.MIN_TOUCH_TARGET_DP), 1f)) }
        }
        fun divider(): View = View(this).apply { setBackgroundColor(0x55FFFFFF) }

        val actions: List<Pair<String, () -> Unit>> = listOf(
            "Sair" to { closeEditor() },
            "Adicionar" to { showComponentPicker() },
            "Salvar" to {
                    writeControls(readControls())
                    Toast.makeText(this@FloatingBubbleService, "Mapeamento salvo.", Toast.LENGTH_SHORT).show()
                },
            "Compart." to { shareControls() },
            "Importar" to { showImportDialog() },
            "Limpar" to { showResetConfirmation() }
        )
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.forEachIndexed { index, (label, action) ->
            actionRow.addView(cell(label, action), LinearLayout.LayoutParams(0, dp(B13Theme.MIN_TOUCH_TARGET_DP), 1f))
            if (index != actions.lastIndex) {
                actionRow.addView(
                    View(this).apply { setBackgroundColor(0x66FFFFFF) },
                    LinearLayout.LayoutParams(dp(1), dp(28))
                )
            }
        }
        panel.addView(actionRow)
        panel.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        val keyboardCaptureCell = cell(
            if (getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .getBoolean(CAPTURE_KEYBOARD, false)
            ) "Teclado: capturado" else "Teclado: normal"
        ) { }
        keyboardCaptureCell.setOnClickListener {
            val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            val enabled = !preferences.getBoolean(CAPTURE_KEYBOARD, false)
            preferences.edit().putBoolean(CAPTURE_KEYBOARD, enabled).apply()
            keyboardCaptureCell.text = if (enabled) "Teclado: capturado" else "Teclado: normal"
            Toast.makeText(
                this@FloatingBubbleService,
                "A opção será aplicada ao sair do editor.",
                Toast.LENGTH_SHORT
            ).show()
        }
        val rightClickCell = cell("Clique direito: ${rightClickLabel()}") { }
        rightClickCell.setOnClickListener {
            val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            val next = if (preferences.getString(RIGHT_CLICK_MODE, "BACK") == "BACK") {
                "LONG_PRESS"
            } else {
                "BACK"
            }
            preferences.edit().putString(RIGHT_CLICK_MODE, next).apply()
            rightClickCell.text = "Direito: ${rightClickLabel()}"
        }
        panel.addView(
            row(
                cell("Emergência: ${keyLabel(getEmergencyKey())}") { beginEmergencyKeyCapture() },
                keyboardCaptureCell,
                rightClickCell
            )
        )
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                (screenWidth() - dp(32)).coerceAtMost(dp(420)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(8) }
        )
    }

    private fun showComponentPicker() {
        val root = editor ?: return
        if (componentPicker != null) return

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99080B12.toInt())
            elevation = dp(30).toFloat()
            setOnClickListener { closeComponentPicker() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedBackground(0xFF4A1218.toInt(), 0xFFE5484D.toInt(), dp(20))
            isClickable = true
            setOnClickListener { }
            elevation = dp(16).toFloat()
        }
        card.addView(
            TextView(this).apply {
                text = "Adicionar componente"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        )
        card.addView(
            TextView(this).apply {
                text = "Escolha um controle para colocar na tela do jogo."
                textSize = 12f
                setTextColor(0xFFB7C0CD.toInt())
                setPadding(0, dp(4), 0, dp(12))
            }
        )

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        COMPONENT_TYPES.chunked(4).forEach { rowTypes ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowTypes.forEach { (type, label, symbol) ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = roundedBackground(0xFF2B0C10.toInt(), 0xFF6B2A30.toInt(), dp(12))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        closeComponentPicker()
                        addControl(type)
                    }
                    addView(
                        TextView(this@FloatingBubbleService).apply {
                            text = symbol
                            textSize = 24f
                            gravity = Gravity.CENTER
                            setTextColor(0xFFFF6B6B.toInt())
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(34)
                        )
                    )
                    addView(
                        TextView(this@FloatingBubbleService).apply {
                            text = label
                            textSize = 11f
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                        }
                    )
                }
                row.addView(
                    tile,
                    LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                )
            }
            repeat(4 - rowTypes.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(82), 1f))
            }
            grid.addView(row)
        }
        card.addView(grid)
        card.addView(
            actionButton("Voltar ao editor") { closeComponentPicker() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
        scrim.addView(
            card,
            FrameLayout.LayoutParams(
                (screenWidth() - dp(32)).coerceAtMost(dp(560)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        scrim.bringToFront()
        scrim.invalidate()
        componentPicker = scrim
    }

    /** Shows saved controls during gameplay without consuming touch or mouse input. */
    private fun showKeymapOverlay() {
        if (editor != null || keymapOverlay != null) return
        val manager = windowManager ?: return
        val root = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val params = fullScreenParams(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        ).apply {
            // Android 12+ allows touches through a non-touchable overlay below this opacity.
            alpha = 0.75f
        }
        keymapParams = params
        try {
            manager.addView(root, params)
            keymapOverlay = root
            cursorNeedsElevation = true
            root.visibility = if (overlaysVisible) View.VISIBLE else View.GONE
            renderControls(root)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Não foi possível exibir os indicadores do keymap", error)
            keymapOverlay = null
        }
    }

    private fun hideKeymapOverlay() {
        removeOverlay(keymapOverlay)
        keymapOverlay = null
    }

    private fun showResetConfirmation() {
        val root = editor ?: return
        if (resetConfirmation != null) return

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99080B12.toInt())
            elevation = dp(32).toFloat()
            setOnClickListener { closeResetConfirmation() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBackground(0xFF4A1218.toInt(), 0xFFE5484D.toInt(), dp(18))
            isClickable = true
            setOnClickListener { }
        }
        card.addView(
            TextView(this).apply {
                text = "Limpar mapeamento?"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        )
        card.addView(
            TextView(this).apply {
                text = "Todos os controles deste perfil serão removidos."
                textSize = 13f
                setTextColor(0xFFB7C0CD.toInt())
                setPadding(0, dp(8), 0, dp(14))
            }
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                actionButton("Cancelar") { closeResetConfirmation() },
                LinearLayout.LayoutParams(0, dp(44), 1f)
            )
            addView(
                actionButton("Limpar controles") {
                    writeControls(emptyList())
                    closeResetConfirmation()
                    renderControls(root)
                    Toast.makeText(this@FloatingBubbleService, "Mapeamento limpo.", Toast.LENGTH_SHORT).show()
                },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) }
            )
        }
        card.addView(buttons)
        scrim.addView(
            card,
            FrameLayout.LayoutParams(
                (screenWidth() - dp(40)).coerceAtMost(dp(360)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        resetConfirmation = scrim
    }

    private fun closeComponentPicker() {
        val picker = componentPicker ?: return
        editor?.removeView(picker)
        componentPicker = null
    }

    private fun closeResetConfirmation() {
        val confirmation = resetConfirmation ?: return
        editor?.removeView(confirmation)
        resetConfirmation = null
    }

    private fun showControlSettings(id: String) {
        val root = editor ?: return
        if (controlSettings != null) return
        val control = readControls().firstOrNull { it.id == id } ?: return
        if (control.type != "MOUSE_LOOK" && control.type != "JOYSTICK") {
            showKeySettings(id, control)
            return
        }
        if (control.type == "MOUSE_LOOK") {
            showMouseLookSettings(id, control)
            return
        }
        if (control.type != "JOYSTICK") return

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99080B12.toInt())
            elevation = dp(32).toFloat()
            setOnClickListener { closeControlSettings() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBackground(0xFF4A1218.toInt(), 0xFFE5484D.toInt(), dp(20))
            isClickable = true
            setOnClickListener { }
            elevation = dp(16).toFloat()
        }
        card.addView(
            TextView(this).apply {
                text = "Configurar joystick"
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        )
        card.addView(
            TextView(this).apply {
                text = "Controle direcional • ${control.key}"
                textSize = 12f
                setTextColor(0xFFFFE3E4.toInt())
                setPadding(0, dp(4), 0, dp(12))
            }
        )
        val radiusLabel = TextView(this).apply {
            text = "Tamanho do joystick: ${(control.radius * 100).toInt()}%"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        card.addView(radiusLabel)
        card.addView(
            styledSeekBar().apply {
                max = 150
                progress = (control.radius * 100).toInt().coerceIn(20, 150)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val radius = progress.coerceIn(20, 150) / 100f
                        radiusLabel.text = "Tamanho do joystick: ${(radius * 100).toInt()}%"
                        writeControls(readControls().map {
                            if (it.id == id) it.copy(radius = radius) else it
                        })
                        editor?.let(::renderControls)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
        )
        val analogDelayLabel = TextView(this).apply {
            text = "Resposta do analógico: ${control.responseMs} ms"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(analogDelayLabel)
        card.addView(styledSeekBar().apply {
            max = 30
            progress = (control.responseMs / 10).coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val delay = progress * 10
                    analogDelayLabel.text = "Resposta do analógico: ${delay} ms"
                    writeControls(readControls().map { if (it.id == id) it.copy(responseMs = delay) else it })
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        card.addView(
            Switch(this).apply {
                text = "Bloquear direções opostas"
                setTextColor(Color.WHITE)
                isChecked = control.stopOnOpposite
                setOnCheckedChangeListener { _, checked ->
                    writeControls(readControls().map {
                        if (it.id == id) it.copy(stopOnOpposite = checked) else it
                    })
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        card.addView(
            TextView(this).apply {
                text = "Tipo de controle"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(0, dp(10), 0, dp(4))
            }
        )
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("WASD" to "WASD", "SETAS" to "Setas").forEach { (value, label) ->
            typeRow.addView(
                actionButton(label) {
                    writeControls(readControls().map {
                        if (it.id == id) it.copy(key = value) else it
                    })
                    editor?.let(::renderControls)
                    Toast.makeText(this@FloatingBubbleService, "Tipo: $label", Toast.LENGTH_SHORT).show()
                },
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) }
            )
        }
        card.addView(typeRow)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                actionButton("Alterar tecla") {
                    closeControlSettings()
                    beginCapture(id)
                },
                LinearLayout.LayoutParams(0, dp(44), 1f)
            )
            addView(
                actionButton("Concluir") { closeControlSettings() },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) }
            )
        }
        card.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        scrim.addView(
            card,
            FrameLayout.LayoutParams(
                (screenWidth() - dp(36)).coerceAtMost(dp(380)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        controlSettings = scrim
    }

    private fun showKeySettings(id: String, control: OverlayControl) {
        val root = editor ?: return
        if (controlSettings != null) return
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99080B12.toInt())
            elevation = dp(32).toFloat()
            setOnClickListener { closeControlSettings() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBackground(0xFF4A1218.toInt(), 0xFFE5484D.toInt(), dp(20))
            isClickable = true
            setOnClickListener { }
        }
        card.addView(TextView(this).apply {
            text = "Configurar ${componentLabel(control.type)} • ${keyLabel(control.key)}"
            textSize = 19f
            setTextColor(Color.WHITE)
        })
        card.addView(TextView(this).apply {
            text = if (control.type == "MOUSE_DRAG") {
                "Segure a tecla e mova o mouse para arrastar o toque."
            } else {
                "Resposta: atraso antes de enviar o toque"
            }
            textSize = 12f
            setTextColor(0xFFFFE3E4.toInt())
            setPadding(0, dp(6), 0, dp(8))
        })
        val delayLabel = TextView(this).apply {
            text = "Atraso: ${control.responseMs} ms"
            setTextColor(Color.WHITE)
        }
        card.addView(delayLabel)
        card.addView(styledSeekBar().apply {
            max = 30
            progress = (control.responseMs / 10).coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val delay = progress * 10
                    delayLabel.text = "Atraso: $delay ms"
                    writeControls(readControls().map { if (it.id == id) it.copy(responseMs = delay) else it })
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        val sizeLabel = TextView(this).apply {
            text = "Tamanho da tecla: ${(control.sizeScale * 100).toInt()}%"
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(sizeLabel)
        card.addView(styledSeekBar().apply {
            max = 200
            progress = (control.sizeScale * 100).toInt().coerceIn(50, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val size = progress.coerceIn(50, 200) / 100f
                    sizeLabel.text = "Tamanho da tecla: ${(size * 100).toInt()}%"
                    writeControls(readControls().map {
                        if (it.id == id) it.copy(sizeScale = size) else it
                    })
                    editor?.let(::renderControls)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        card.addView(TextView(this).apply {
            text = "Ação ao pressionar"
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
        })
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pressModes = if (control.type == "MOUSE_DRAG") {
            listOf("Manter pressionado" to "HOLD")
        } else {
            listOf("Tocar" to "TAP", "Manter pressionado" to "HOLD")
        }
        pressModes.forEach { (label, mode) ->
            modeRow.addView(actionButton(label) {
                writeControls(readControls().map { if (it.id == id) it.copy(pressMode = mode) else it })
                Toast.makeText(this@FloatingBubbleService, "Ação: $label", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        }
        card.addView(modeRow)
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("Alterar tecla") { closeControlSettings(); beginCapture(id) }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(actionButton("Concluir") { closeControlSettings() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        }
        card.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        scrim.addView(card, FrameLayout.LayoutParams((screenWidth() - dp(36)).coerceAtMost(dp(380)), -2, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        controlSettings = scrim
    }

    private fun showMouseLookSettings(id: String, control: OverlayControl) {
        val root = editor ?: return
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99080B12.toInt())
            elevation = dp(32).toFloat()
            setOnClickListener { closeControlSettings() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(0xFF4A1218.toInt(), 0xFFE5484D.toInt(), dp(20))
            isClickable = true
            setOnClickListener { }
            elevation = dp(16).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "Mouse FPS"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "A tecla ${keyLabel(control.key)} alterna MIRA e CURSOR. Arraste o marcador para posicionar a área da câmera."
            textSize = 11f
            setTextColor(0xFFFFE3E4.toInt())
            setPadding(0, dp(4), 0, dp(8))
        })
        fun sensitivitySlider(axis: String, value: Float, minimum: Float = 0.1f, maximum: Float = 4.0f, update: (Float) -> Unit) {
            val label = TextView(this).apply {
                text = "Sensibilidade $axis: ${"%.1f".format(java.util.Locale.US, value)}x"
                textSize = 13f
                setTextColor(Color.WHITE)
            }
            card.addView(label)
            card.addView(styledSeekBar().apply {
                max = ((maximum - minimum) * 10f).toInt()
                progress = ((value - minimum) * 10f).toInt().coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val next = minimum + progress / 10f
                        label.text = "Sensibilidade $axis: ${"%.1f".format(java.util.Locale.US, next)}x"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        update(minimum + (seekBar?.progress ?: 0) / 10f)
                    }
                })
            })
        }
        fun sensitivityPercentSlider(axis: String, value: Float, update: (Float) -> Unit) {
            val label = TextView(this).apply {
                text = "Sensibilidade $axis: ${value.toInt()}%"
                textSize = 13f
                setTextColor(Color.WHITE)
            }
            card.addView(label)
            card.addView(styledSeekBar().apply {
                max = 100
                progress = value.toInt().coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        label.text = "Sensibilidade $axis: $progress%"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        update((seekBar?.progress ?: value.toInt()).toFloat())
                    }
                })
            })
        }
        sensitivityPercentSlider("X", control.sensitivityX) { next ->
            writeControls(readControls().map { if (it.id == id) it.copy(sensitivityX = next) else it })
        }
        sensitivityPercentSlider("Y", control.sensitivityY) { next ->
            writeControls(readControls().map { if (it.id == id) it.copy(sensitivityY = next) else it })
        }
        card.addView(TextView(this).apply {
            text = "Curva progressiva; a velocidade do cursor é independente."
            textSize = 11f
            setTextColor(0xFFFFE3E4.toInt())
            setPadding(0, dp(2), 0, dp(6))
        })
        val smoothingLabel = TextView(this).apply {
            text = "Suavização da mira: ${control.smoothing}%"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        card.addView(smoothingLabel)
        card.addView(styledSeekBar().apply {
            max = 100
            progress = control.smoothing.coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    smoothingLabel.text = "Suavização da mira: $progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val smoothing = seekBar?.progress ?: control.smoothing
                    writeControls(readControls().map { if (it.id == id) it.copy(smoothing = smoothing) else it })
                }
            })
        })
        val markerSizeLabel = TextView(this).apply {
            text = "Tamanho do marcador: ${(control.sizeScale * 100).toInt()}%"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        card.addView(markerSizeLabel)
        card.addView(styledSeekBar().apply {
            max = 200
            progress = (control.sizeScale * 100).toInt().coerceIn(50, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val size = progress.coerceIn(50, 200) / 100f
                    markerSizeLabel.text = "Tamanho do marcador: ${(size * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val size = (seekBar?.progress ?: (control.sizeScale * 100).toInt())
                        .coerceIn(50, 200) / 100f
                    writeControls(readControls().map { if (it.id == id) it.copy(sizeScale = size) else it })
                    editor?.let(::renderControls)
                }
            })
        })
        val cursorSpeedLabel = TextView(this).apply {
            text = "Velocidade do cursor: ${(control.cursorSpeed * 25f).toInt().coerceIn(0, 100)}%"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        card.addView(cursorSpeedLabel)
        card.addView(styledSeekBar().apply {
            max = 100
            progress = (control.cursorSpeed * 25f).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    cursorSpeedLabel.text = "Velocidade do cursor: $progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val speed = (seekBar?.progress ?: (control.cursorSpeed * 25f).toInt()) / 25f
                    writeControls(readControls().map {
                        if (it.id == id) it.copy(cursorSpeed = speed) else it
                    })
                }
            })
        })
        sensitivitySlider("tamanho da seta", control.cursorSize, 0.6f, 1.8f) { size ->
            writeControls(readControls().map { if (it.id == id) it.copy(cursorSize = size) else it })
            cursorScale = size
            cursorView?.let { view ->
                view.layoutParams = FrameLayout.LayoutParams(
                    dp((24 * size).toInt().coerceIn(18, 60)),
                    dp((34 * size).toInt().coerceIn(24, 80))
                )
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("Alterar tecla") {
                closeControlSettings()
                beginCapture(id)
            }, LinearLayout.LayoutParams(0, dp(B13Theme.MIN_TOUCH_TARGET_DP), 1f))
            addView(actionButton("Restaurar padrão") {
                writeControls(readControls().map {
                    if (it.id == id) it.copy(
                        sensitivityX = 100f / 3f,
                        sensitivityY = 100f / 3f,
                        smoothing = 35,
                        sizeScale = 1f,
                        cursorSpeed = 1.6f,
                        cursorSize = 1f
                    ) else it
                })
                cursorScale = 1f
                closeControlSettings()
                showControlSettings(id)
            }, LinearLayout.LayoutParams(0, dp(B13Theme.MIN_TOUCH_TARGET_DP), 1f))
            addView(actionButton("Fechar") { closeControlSettings() },
                LinearLayout.LayoutParams(0, dp(B13Theme.MIN_TOUCH_TARGET_DP), 1f))
        }
        card.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val settingsHeight = minOf(
            (screenHeight() * 0.85f).toInt(),
            (screenHeight() - dp(150)).coerceAtLeast(dp(120)),
            dp(500)
        )
        scrim.addView(scroll, FrameLayout.LayoutParams(
            (screenWidth() - dp(32)).coerceAtMost(dp(340)),
            settingsHeight,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(12) })
        root.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        controlSettings = scrim
    }

    private fun closeControlSettings() {
        val settings = controlSettings ?: return
        editor?.removeView(settings)
        controlSettings = null
        editor?.let(::renderControls)
    }

    private fun showImportDialog() {
        val root = editor ?: return
        if (importDialog != null) return
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99080B12.toInt())
            elevation = dp(32).toFloat()
            setOnClickListener { closeImportDialog() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedBackground(0xFF4A1218.toInt(), 0xFFE5484D.toInt(), dp(20))
            isClickable = true
            setOnClickListener { }
        }
        card.addView(
            TextView(this).apply {
                text = "Importar mapeamento"
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        )
        card.addView(
            TextView(this).apply {
                text = "Cole abaixo o JSON exportado pelo B13 MAUSE PRO."
                textSize = 12f
                setTextColor(0xFFFFE3E4.toInt())
                setPadding(0, dp(4), 0, dp(10))
            }
        )
        val input = EditText(this).apply {
            hint = "JSON do mapeamento"
            setHintTextColor(0xFFE0B4B8.toInt())
            setTextColor(Color.WHITE)
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(0xFF2B0C10.toInt(), 0xFFE5484D.toInt(), dp(10))
        }
        card.addView(input)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                actionButton("Cancelar") { closeImportDialog() },
                LinearLayout.LayoutParams(0, dp(44), 1f)
            )
            addView(
                actionButton("Importar") {
                    try {
                        val controls = decodeOverlayControls(input.text.toString())
                        writeControls(controls)
                        closeImportDialog()
                        renderControls(root)
                        Toast.makeText(this@FloatingBubbleService, "Mapeamento importado.", Toast.LENGTH_SHORT).show()
                    } catch (error: JSONException) {
                        Toast.makeText(this@FloatingBubbleService, "JSON inválido: ${error.message}", Toast.LENGTH_LONG).show()
                    } catch (error: IllegalArgumentException) {
                        Toast.makeText(this@FloatingBubbleService, "Mapeamento inválido: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) }
            )
        }
        card.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        scrim.addView(
            card,
            FrameLayout.LayoutParams(
                (screenWidth() - dp(36)).coerceAtMost(dp(380)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        importDialog = scrim
        updateCaptureWindow(focusable = true)
        input.requestFocus()
        input.post {
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeImportDialog() {
        val dialog = importDialog ?: return
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        inputMethodManager?.hideSoftInputFromWindow(dialog.windowToken, 0)
        editor?.removeView(dialog)
        importDialog = null
        updateCaptureWindow(focusable = false)
    }

    private fun shareControls() {
        writeControls(readControls())
        val encoded = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(MAPPING, "[]") ?: "[]"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Mapeamento B13 MAUSE PRO")
            putExtra(Intent.EXTRA_TEXT, encoded)
        }
        try {
            startActivity(Intent.createChooser(sendIntent, "Compartilhar mapeamento").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "Não há um app disponível para compartilhar o mapeamento", error)
            Toast.makeText(this, "Não há um app disponível para compartilhar.", Toast.LENGTH_LONG).show()
        }
    }

    private fun decodeOverlayControls(encoded: String): List<OverlayControl> {
        val array = JSONArray(encoded)
        require(array.length() <= 100) { "o arquivo excede o limite de 100 controles" }
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val type = item.getString("type")
            require(COMPONENT_TYPES.any { it.first == type }) { "componente desconhecido: $type" }
            val x = item.optDouble("x", 0.5).toFloat()
            val y = item.optDouble("y", 0.5).toFloat()
            require(x.isFinite() && y.isFinite()) { "posição inválida no controle ${index + 1}" }
            OverlayControl(
                id = item.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                type = type,
                key = item.optString("key", "Tecla?"),
                x = x.coerceIn(0f, 1f),
                y = y.coerceIn(0f, 1f),
                radius = item.optDouble("radius", 0.85).toFloat().coerceIn(0.2f, 1.5f),
                sizeScale = item.optDouble("sizeScale", 1.0).toFloat().coerceIn(0.5f, 2f),
                stopOnOpposite = item.optBoolean("stopOnOpposite", true),
                mousePosition = item.optString("mousePosition", "Centro"),
                cursorX = item.optDouble("cursorX", 0.5).toFloat().coerceIn(0f, 1f),
                cursorY = item.optDouble("cursorY", 0.5).toFloat().coerceIn(0f, 1f),
                preventViewFreeze = item.optBoolean("preventViewFreeze", true),
                controllerAcceleration = item.optDouble("controllerAcceleration", 0.5).toFloat().coerceIn(0f, 1f),
                sensitivityX = item.sensitivityValue("sensitivityX"),
                sensitivityY = item.sensitivityValue("sensitivityY"),
                smoothing = item.optInt("smoothing", 35).coerceIn(0, 100),
                cursorSpeed = item.optDouble("cursorSpeed", 1.6).toFloat().coerceIn(0f, 4f),
                cursorSize = item.optDouble("cursorSize", 1.0).toFloat().coerceIn(0.6f, 1.8f),
                pressMode = item.optString("pressMode", "HOLD"),
                responseMs = item.optInt("responseMs", 0).coerceIn(0, 300)
            )
        }
    }

    private fun addControl(type: String, position: Pair<Float, Float>? = null) {
        val root = editor ?: return
        val items = readControls()
        if (type == "MOUSE_LOOK" && items.any { it.type == "MOUSE_LOOK" }) {
            Toast.makeText(this, "Já existe um controle Mouse FPS. Configure o marcador atual.", Toast.LENGTH_SHORT).show()
            return
        }
        val offset = (items.size % 5) * 0.1f
        val control = OverlayControl(
            id = UUID.randomUUID().toString(),
            type = type,
            key = when (type) {
                "JOYSTICK" -> "WASD"
                "MOUSE_LOOK" -> "ALT_LEFT"
                else -> "Tecla?"
            },
            x = position?.first ?: if (type == "MOUSE_LOOK") 0.76f else 0.28f + offset,
            y = position?.second ?: if (type == "MOUSE_LOOK") 0.56f else 0.56f + (items.size % 3) * 0.08f
        )
        writeControls(items + control)
        renderControls(root)
        if (type == "JOYSTICK") {
            Toast.makeText(this, "Joystick adicionado com WASD.", Toast.LENGTH_SHORT).show()
        } else {
            beginCapture(control.id)
        }
    }

    private fun renderControls(root: FrameLayout) {
        val oldViews = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filter { it.tag == CONTROL_TAG }
        oldViews.forEach(root::removeView)

        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        if (!preferences.getBoolean("indicators", true)) return
        val opacity = preferences.getFloat("opacity", 0.8f).coerceIn(0.15f, 1f)
        val indicatorColor = when (preferences.getString("indicator_color2", "Branco")) {
            "Azul" -> 0xFF70B7FF.toInt()
            "Roxo" -> 0xFFBE91FF.toInt()
            "Verde" -> 0xFF66E3A4.toInt()
            "Vermelho" -> 0xFFFF5A5F.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        val inEditor = root === editor
        val displayWidth = screenWidth()
        val displayHeight = screenHeight()
        readControls().forEach { control ->
            if (control.type == "MOUSE_LOOK" && !inEditor) return@forEach
            val label = componentLabel(control.type)
            val isJoystick = control.type == "JOYSTICK"
            val view = if (isJoystick) {
                JoystickControlView(this, control.key, indicatorColor, opacity)
            } else {
                val waiting = control.id == captureTarget
                val shown = if (waiting) "…" else keyLabel(control.key)
                val suffix = if (waiting) "" else when (control.type) {
                    "SWIPE" -> "\n↗"
                    "MOUSE_PICK" -> "\n⊙"
                    "MOUSE_DRAG" -> "\n↔"
                    else -> ""
                }
                TextView(this).apply {
                    text = shown + suffix
                    textSize = when {
                        shown.length <= 2 -> 12f
                        shown.length <= 4 -> 10f
                        else -> 8f
                    }
                    gravity = Gravity.CENTER
                    maxLines = 2
                    includeFontPadding = false
                    setTextColor(Color.WHITE)
                    setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
                    setPadding(dp(1), dp(1), dp(1), dp(1))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (inEditor) {
                            setColor(0xCC2A0A0E.toInt())
                            setStroke(dp(1), if (waiting) 0xFFFFFFFF.toInt() else 0xFFE5484D.toInt())
                        } else {
                            setColor(withOpacity(indicatorColor, 0.16f))
                            setStroke(dp(1), withOpacity(indicatorColor, opacity))
                        }
                    }
                }
            }
            view.elevation = dp(6).toFloat()
            view.tag = CONTROL_TAG
            view.contentDescription = if (isJoystick) {
                "$label, teclas ${control.key}. Toque para configurar. Segure para remover."
            } else {
                "$label, tecla ${control.key}. Toque para alterar. Segure para remover."
            }
            val controlSize = controlSizePx(control)
            val controlWidth = controlSize
            val controlHeight = controlSize
            val params = FrameLayout.LayoutParams(controlWidth, controlHeight).apply {
                leftMargin = (control.x * displayWidth - controlWidth / 2)
                    .toInt().coerceIn(0, (displayWidth - controlWidth).coerceAtLeast(0))
                topMargin = (control.y * displayHeight - controlHeight / 2)
                    .toInt().coerceIn(0, (displayHeight - controlHeight).coerceAtLeast(0))
            }
            view.setOnTouchListener(ControlTouchListener(control.id))
            root.addView(view, params)
        }
    }

    private fun beginCapture(id: String) {
        val root = editor ?: return
        captureTarget = id
        root.captureEnabled = true
        updateCaptureWindow(focusable = true)
        root.requestFocus()
        Toast.makeText(this, "Pressione a tecla ou o botão do mouse.", Toast.LENGTH_SHORT).show()
        renderControls(root)
    }

    private fun beginEmergencyKeyCapture() {
        val root = editor ?: return
        captureTarget = EMERGENCY_CAPTURE_TARGET
        root.captureEnabled = true
        updateCaptureWindow(focusable = true)
        root.requestFocus()
        Toast.makeText(this, "Pressione a tecla de emergência.", Toast.LENGTH_SHORT).show()
    }

    private fun finishCapture(key: String) {
        val target = captureTarget ?: return
        captureTarget = null
        editor?.captureEnabled = false
        if (target == EMERGENCY_CAPTURE_TARGET) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit().putString(EMERGENCY_KEY, key).apply()
            updateCaptureWindow(focusable = false)
            Toast.makeText(this, "Tecla de emergência: ${keyLabel(key)}", Toast.LENGTH_SHORT).show()
            return
        }
        val controls = readControls().map { if (it.id == target) it.copy(key = key) else it }
        writeControls(controls)
        editor?.let(::renderControls)
        updateCaptureWindow(focusable = false)
    }

    private fun updateCaptureWindow(focusable: Boolean) {
        val root = editor ?: return
        val params = editorParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            windowManager?.updateViewLayout(root, params)
            if (focusable) root.requestFocus() else root.clearFocus()
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "A janela do editor não está mais anexada", error)
        }
    }

    private fun closeEditor() {
        captureTarget = null
        editor?.captureEnabled = false
        removeOverlay(editor)
        editor = null
        editorParams = null
        componentPicker = null
        resetConfirmation = null
        controlSettings = null
        importDialog = null
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        ShizukuInputBridge.updateMapping(
            mappingPayload(),
            preferences.getBoolean(CAPTURE_KEYBOARD, false),
            preferences.getString(EMERGENCY_KEY, "F12").orEmpty(),
            preferences.getString(RIGHT_CLICK_MODE, "BACK").orEmpty()
        )
        ShizukuInputBridge.setEditorMode(false)
        showKeymapOverlay()
    }

    private fun closeMenu() {
        val wasOpen = menu != null
        removeOverlay(menu)
        menu = null
        menuParams = null
        if (wasOpen) {
            menuPreviousMouseLookActive?.let(ShizukuInputBridge::setMouseLookActive)
            menuPreviousMouseLookActive = null
            pollEngineState()
        }
    }

    private fun removeControl(id: String) {
        val root = editor ?: return
        writeControls(readControls().filterNot { it.id == id })
        renderControls(root)
    }

    private fun updateControlPosition(id: String, x: Float, y: Float) {
        val root = editor ?: return
        val width = screenWidth().toFloat().coerceAtLeast(1f)
        val height = screenHeight().toFloat().coerceAtLeast(1f)
        val controls = readControls().map {
            if (it.id == id) it.copy(
                x = (x / width).coerceIn(0.01f, 0.99f),
                y = (y / height).coerceIn(0.02f, 0.98f)
            ) else it
        }
        writeControls(controls)
        renderControls(root)
    }

    private fun readControls(): List<OverlayControl> {
        val encoded = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(MAPPING, "[]") ?: "[]"
        return try {
            val array = JSONArray(encoded)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                OverlayControl(
                    id = item.getString("id"),
                    type = item.getString("type"),
                    key = item.optString("key", "Tecla?"),
                    x = item.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                    y = item.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                    radius = item.optDouble("radius", 0.85).toFloat().coerceIn(0.2f, 1.5f),
                    stopOnOpposite = item.optBoolean("stopOnOpposite", true),
                    mousePosition = item.optString("mousePosition", "Centro"),
                    cursorX = item.optDouble("cursorX", 0.5).toFloat().coerceIn(0f, 1f),
                    cursorY = item.optDouble("cursorY", 0.5).toFloat().coerceIn(0f, 1f),
                    preventViewFreeze = item.optBoolean("preventViewFreeze", true),
                    controllerAcceleration = item.optDouble("controllerAcceleration", 0.5).toFloat().coerceIn(0f, 1f),
                    sensitivityX = item.sensitivityValue("sensitivityX"),
                    sensitivityY = item.sensitivityValue("sensitivityY"),
                    smoothing = item.optInt("smoothing", 35).coerceIn(0, 100),
                    sizeScale = item.optDouble("sizeScale", 1.0).toFloat().coerceIn(0.5f, 2f),
                    cursorSpeed = item.optDouble("cursorSpeed", 1.6).toFloat().coerceIn(0f, 4f),
                    cursorSize = item.optDouble("cursorSize", 1.0).toFloat().coerceIn(0.6f, 1.8f),
                    pressMode = item.optString("pressMode", "HOLD"),
                    responseMs = item.optInt("responseMs", 0).coerceIn(0, 300)
                )
            }
        } catch (error: JSONException) {
            Log.e(TAG, "Não foi possível ler os controles salvos", error)
            emptyList()
        }
    }

    private fun getEmergencyKey(): String =
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(EMERGENCY_KEY, "F12").orEmpty()

    private fun rightClickLabel(): String =
        if (getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getString(RIGHT_CLICK_MODE, "BACK") == "LONG_PRESS"
        ) "Toque longo" else "Voltar"

    private fun writeControls(controls: List<OverlayControl>) {
        val saved = try {
            JSONArray(
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(MAPPING, "[]") ?: "[]"
            )
        } catch (error: JSONException) {
            Log.e(TAG, "O mapeamento salvo está corrompido; será recriado com os controles atuais", error)
            JSONArray()
        }
        val oldById = mutableMapOf<String, JSONObject>()
        try {
            for (index in 0 until saved.length()) {
                val item = saved.optJSONObject(index) ?: continue
                oldById[item.optString("id")] = item
            }
        } catch (error: JSONException) {
            Log.e(TAG, "Não foi possível preservar as configurações dos controles", error)
        }

        val result = JSONArray()
        controls.forEach { control ->
            val item = oldById[control.id] ?: JSONObject()
            try {
                item.put("id", control.id)
                    .put("type", control.type)
                    .put("key", control.key)
                    .put("x", control.x.toDouble())
                    .put("y", control.y.toDouble())
                    .put("radius", control.radius.toDouble())
                    .put("sizeScale", control.sizeScale.toDouble())
                    .put("stopOnOpposite", control.stopOnOpposite)
                    .put("mousePosition", control.mousePosition)
                    .put("cursorX", control.cursorX.toDouble())
                    .put("cursorY", control.cursorY.toDouble())
                    .put("preventViewFreeze", control.preventViewFreeze)
                    .put("controllerAcceleration", control.controllerAcceleration.toDouble())
                    .put("sensitivityX", control.sensitivityX.toDouble())
                    .put("sensitivityY", control.sensitivityY.toDouble())
                    .put("sensitivityScale", 100)
                    .put("smoothing", control.smoothing)
                    .put("cursorSpeed", control.cursorSpeed.toDouble())
                    .put("cursorSize", control.cursorSize.toDouble())
                    .put("pressMode", control.pressMode)
                    .put("responseMs", control.responseMs)
                result.put(item)
            } catch (error: JSONException) {
                Log.e(TAG, "Não foi possível salvar um controle", error)
            }
        }
        // The key engine is restarted by the preference listener (and when the editor closes),
        // so it stays paused while the editor is open.
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(MAPPING, result.toString()).apply()
    }

    private inner class ControlTouchListener(private val id: String) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var moved = false
        private var longPressed = false
        private val removeRunnable = Runnable {
            longPressed = true
            removeControl(id)
            Toast.makeText(this@FloatingBubbleService, "Controle removido.", Toast.LENGTH_SHORT).show()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = view.x
                    startY = view.y
                    moved = false
                    longPressed = false
                    handler.postDelayed(removeRunnable, LONG_PRESS_MS)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) {
                        moved = true
                        handler.removeCallbacks(removeRunnable)
                    }
                    if (moved) {
                        view.x = (startX + dx).coerceIn(
                            0f,
                            (screenWidth() - view.width).coerceAtLeast(0).toFloat()
                        )
                        view.y = (startY + dy).coerceIn(
                            0f,
                            (screenHeight() - view.height).coerceAtLeast(0).toFloat()
                        )
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(removeRunnable)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !moved && !longPressed) {
                        val control = readControls().firstOrNull { it.id == id }
                        showControlSettings(id)
                    } else if (moved) {
                        updateControlPosition(
                            id,
                            view.x + view.width / 2f,
                            view.y + view.height / 2f
                        )
                    }
                    return true
                }
            }
            return true
        }
    }

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var downTime = 0L

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    downTime = event.eventTime
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.x = (startX + dx).coerceIn(0, screenWidth() - view.width)
                        params.y = (startY + dy).coerceIn(0, screenHeight() - view.height)
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (error: IllegalArgumentException) {
                            Log.w(TAG, "A bolha não está mais anexada à janela", error)
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved && event.eventTime - downTime >= LONG_PRESS_MS) {
                        stopSelf()
                    } else if (!moved) {
                        if (menu == null) showMenu() else closeMenu()
                    }
                    return true
                }
            }
            return true
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(0xFF8E1B24.toInt(), 0xFFFF6B6B.toInt(), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun menuAction(symbol: String, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                TextView(this@FloatingBubbleService).apply {
                    text = symbol
                    textSize = 24f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFFB3202A.toInt())
                        setStroke(dp(1), 0xFFFF6B6B.toInt())
                    }
                },
                LinearLayout.LayoutParams(dp(48), dp(48))
            )
            addView(
                TextView(this@FloatingBubbleService).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }

    private fun componentLabel(type: String): String =
        COMPONENT_TYPES.firstOrNull { it.first == type }?.second ?: type

    private fun roundedBackground(color: Int, stroke: Int, radius: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun removeOverlay(view: View?) {
        if (view == null) return
        try {
            windowManager?.removeView(view)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "A janela flutuante já havia sido removida", error)
        }
    }

    private fun bringCursorOverlayToFront() {
        val manager = windowManager ?: return
        if (cursorShown) {
            val view = cursorOverlay
            val params = cursorParams
            if (view != null && params != null && view.parent != null) {
                try {
                    manager.removeView(view)
                    manager.addView(view, params)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Não foi possível elevar a janela do cursor", error)
                }
            }
        }
        val indicator = mouseModeIndicator
        val indicatorParams = mouseModeParams
        if (indicator?.parent != null && indicatorParams != null) {
            try {
                manager.removeView(indicator)
                manager.addView(indicator, indicatorParams)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Não foi possível elevar o indicador do modo do mouse", error)
            }
        }
    }

    private companion object {
        const val TAG = "NexoPlay"
        const val NOTIFICATION_ID = 7321
        const val LONG_PRESS_MS = 600L
        const val ACTION_STOP = "com.mapeador.action.STOP_FLOATING_PANEL"
        const val PREFERENCES = "mapeador"
        const val MAPPING = "mapping"
        const val EMERGENCY_KEY = "emergency_key"
        const val EMERGENCY_CAPTURE_TARGET = "__emergency_key__"
        const val CAPTURE_KEYBOARD = "capture_keyboard"
        const val RIGHT_CLICK_MODE = "right_click_mode"
        const val INITIAL_MOUSE_MODE = "initial_mouse_mode"
        const val CONTROL_TAG = "nexoplay-mapping-control"
        const val KEY_SIZE_DP = 40
        const val JOYSTICK_SIZE_DP = 88
        const val JOYSTICK_MIN_DP = 24
        val COMPONENT_TYPES = listOf(
            Triple("TOUCH", "Toque", "●"),
            Triple("SWIPE", "Deslizar", "↗"),
            Triple("MOUSE_PICK", "Seleção do Mouse", "⊙"),
            Triple("JOYSTICK", "Joystick", "✥"),
            Triple("MOUSE_LOOK", "Mouse FPS", "⌖"),
            Triple("MOUSE_DRAG", "Arrasto Mouse", "↔")
        )
    }
}

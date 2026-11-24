package com.mapeador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
    val sensitivityX: Float = 1f,
    val sensitivityY: Float = 1f
)

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
    var captureEnabled = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (captureEnabled && event.action == KeyEvent.ACTION_DOWN) {
            onKeyCaptured?.invoke(KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_"))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
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
        return super.dispatchTouchEvent(event)
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
    private val handler = Handler(Looper.getMainLooper())
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
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferenceListener)
        startAsForegroundService()
        if (Settings.canDrawOverlays(this)) showBubble() else stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ShizukuInputBridge.stopMapping()
            activeGamePackage = null
            stopSelf()
            return START_NOT_STICKY
        }
        val gamePackage = intent?.getStringExtra(FloatingBubbleIntent.GAME_PACKAGE)
        if (!gamePackage.isNullOrBlank()) {
            activeGamePackage = gamePackage
            if (bubble == null && Settings.canDrawOverlays(this)) showBubble()
            // The game is still opening and may be rotating to landscape: start now and
            // once more shortly after, so the engine always has the final screen size.
            startEngine()
            handler.postDelayed({ if (editor == null) relayoutForScreenSize() }, 2500)
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
        listOf(keymapOverlay to keymapParams, editor to editorParams).forEach { (view, params) ->
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

    /** (Re)starts the key engine with the saved controls and the real screen size. */
    private fun startEngine() {
        val game = activeGamePackage ?: return
        val size = realScreenSize()
        engineSize = size
        ShizukuInputBridge.startMapping(this, game, mappingPayload(), size.first, size.second)
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
            array.put(item)
        }
        return array.toString()
    }

    private fun controlSizePx(control: OverlayControl): Int =
        if (control.type == "JOYSTICK") {
            (dp(JOYSTICK_SIZE_DP) * control.radius).toInt().coerceAtLeast(dp(JOYSTICK_MIN_DP))
        } else {
            dp(KEY_SIZE_DP)
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
        ShizukuInputBridge.stopMapping()
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
                TextView(this@FloatingBubbleService).apply {
                    text = item.symbol
                    textSize = 24f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(16).toFloat()
                        setColor(0xFF200608.toInt())
                        setStroke(dp(2), 0xFFE5484D.toInt())
                    }
                },
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
        ShizukuInputBridge.stopMapping()
        val manager = windowManager ?: return
        val root = KeyCaptureFrame(this).apply {
            isFocusableInTouchMode = true
            setBackgroundColor(Color.TRANSPARENT)
            onKeyCaptured = { key -> finishCapture(key) }
        }
        val params = fullScreenParams(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        editorParams = params
        try {
            manager.addView(root, params)
            editor = root
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
            elevation = dp(10).toFloat()
        }
        fun cell(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        fun row(vararg cells: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            cells.forEach { addView(it, LinearLayout.LayoutParams(0, dp(46), 1f)) }
        }
        fun divider(): View = View(this).apply { setBackgroundColor(0x55FFFFFF) }

        panel.addView(
            row(
                cell("Sair") { closeEditor() },
                cell("Adicionar\ncomponente") { showComponentPicker() },
                cell("Salvar") {
                    writeControls(readControls())
                    Toast.makeText(this@FloatingBubbleService, "Mapeamento salvo.", Toast.LENGTH_SHORT).show()
                }
            )
        )
        panel.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        panel.addView(
            row(
                cell("Compartilhar") { shareControls() },
                cell("Importar") { showImportDialog() },
                cell("Limpar") { showResetConfirmation() }
            )
        )
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                (screenWidth() - dp(32)).coerceAtMost(dp(460)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(16) }
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
        if (control.type != "JOYSTICK") return

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99080B12.toInt())
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
            SeekBar(this).apply {
                max = 100
                progress = (control.radius * 100).toInt().coerceIn(35, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val radius = (progress.coerceIn(35, 100) / 100f)
                        radiusLabel.text = "Tamanho do joystick: ${(radius * 100).toInt()}%"
                        writeControls(readControls().map {
                            if (it.id == id) it.copy(radius = radius) else it
                        })
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
        )
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
                radius = item.optDouble("radius", 0.85).toFloat().coerceIn(0.35f, 1f),
                stopOnOpposite = item.optBoolean("stopOnOpposite", true),
                mousePosition = item.optString("mousePosition", "Centro"),
                cursorX = item.optDouble("cursorX", 0.5).toFloat().coerceIn(0f, 1f),
                cursorY = item.optDouble("cursorY", 0.5).toFloat().coerceIn(0f, 1f),
                preventViewFreeze = item.optBoolean("preventViewFreeze", true),
                controllerAcceleration = item.optDouble("controllerAcceleration", 0.5).toFloat().coerceIn(0f, 1f),
                sensitivityX = item.optDouble("sensitivityX", 1.0).toFloat().coerceIn(0.1f, 2f),
                sensitivityY = item.optDouble("sensitivityY", 1.0).toFloat().coerceIn(0.1f, 2f)
            )
        }
    }

    private fun addControl(type: String) {
        val root = editor ?: return
        val items = readControls()
        val offset = (items.size % 5) * 0.1f
        val control = OverlayControl(
            id = UUID.randomUUID().toString(),
            type = type,
            key = if (type == "JOYSTICK") "WASD" else "Tecla?",
            x = 0.28f + offset,
            y = 0.56f + (items.size % 3) * 0.08f
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

    private fun finishCapture(key: String) {
        val target = captureTarget ?: return
        captureTarget = null
        editor?.captureEnabled = false
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
        showKeymapOverlay()
        startEngine()
    }

    private fun closeMenu() {
        removeOverlay(menu)
        menu = null
        menuParams = null
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
                    radius = item.optDouble("radius", 0.85).toFloat().coerceIn(0.35f, 1f),
                    stopOnOpposite = item.optBoolean("stopOnOpposite", true),
                    mousePosition = item.optString("mousePosition", "Centro"),
                    cursorX = item.optDouble("cursorX", 0.5).toFloat().coerceIn(0f, 1f),
                    cursorY = item.optDouble("cursorY", 0.5).toFloat().coerceIn(0f, 1f),
                    preventViewFreeze = item.optBoolean("preventViewFreeze", true),
                    controllerAcceleration = item.optDouble("controllerAcceleration", 0.5).toFloat().coerceIn(0f, 1f),
                    sensitivityX = item.optDouble("sensitivityX", 1.0).toFloat().coerceIn(0.1f, 2f),
                    sensitivityY = item.optDouble("sensitivityY", 1.0).toFloat().coerceIn(0.1f, 2f)
                )
            }
        } catch (error: JSONException) {
            Log.e(TAG, "Não foi possível ler os controles salvos", error)
            emptyList()
        }
    }

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
                    .put("stopOnOpposite", control.stopOnOpposite)
                    .put("mousePosition", control.mousePosition)
                    .put("cursorX", control.cursorX.toDouble())
                    .put("cursorY", control.cursorY.toDouble())
                    .put("preventViewFreeze", control.preventViewFreeze)
                    .put("controllerAcceleration", control.controllerAcceleration.toDouble())
                    .put("sensitivityX", control.sensitivityX.toDouble())
                    .put("sensitivityY", control.sensitivityY.toDouble())
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
                        if (control?.type == "JOYSTICK") showControlSettings(id) else beginCapture(id)
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

    private companion object {
        const val TAG = "NexoPlay"
        const val NOTIFICATION_ID = 7321
        const val LONG_PRESS_MS = 600L
        const val ACTION_STOP = "com.mapeador.action.STOP_FLOATING_PANEL"
        const val PREFERENCES = "mapeador"
        const val MAPPING = "mapping"
        const val CONTROL_TAG = "nexoplay-mapping-control"
        const val KEY_SIZE_DP = 40
        const val JOYSTICK_SIZE_DP = 88
        const val JOYSTICK_MIN_DP = 56
        val COMPONENT_TYPES = listOf(
            Triple("TOUCH", "Toque", "●"),
            Triple("SWIPE", "Deslizar", "↗"),
            Triple("MOUSE_PICK", "Seleção do Mouse", "⊙"),
            Triple("JOYSTICK", "Joystick", "✥")
        )
    }
}

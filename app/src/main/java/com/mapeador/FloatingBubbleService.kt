package com.mapeador

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The floating bubble + its menu. Owns the single [Keymap] for the session,
 * starts/stops the native engine through [ShizukuBridge] and shows the
 * in-game indicators while the target app is in the foreground.
 */
class FloatingBubbleService : Service() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val ACTION_STOP = "com.mapeador.action.STOP"
        private const val CHANNEL_ID = "mapeador_bubble"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "mapeador"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val keymap = Keymap()
    private var targetPackage: String = ""

    private var bubbleView: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuView: LinearLayout? = null
    private var indicatorsView: IndicatorsView? = null
    private var editorOverlay: KeymapEditorOverlay? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cursorExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MapeadorCursorUi").apply { isDaemon = true }
    }
    @Volatile private var cursorPollingEnabled = false
    private var polling = false
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            pollForegroundAndCursor()
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadKeymap()
        startForegroundNotification()
        addBubble()
        addIndicators()
        cursorExecutor.scheduleWithFixedDelay({
            if (!cursorPollingEnabled) return@scheduleWithFixedDelay
            val cursor = ShizukuBridge.cursorPosition() ?: return@scheduleWithFixedDelay
            if (cursor.size >= 3) {
                mainHandler.post {
                    if (cursorPollingEnabled) {
                        indicatorsView?.updateCursor(cursor[0], cursor[1], cursor[2] > 0.5f)
                    }
                }
            }
        }, 0L, 16L, TimeUnit.MILLISECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_PACKAGE_NAME)?.let { pkg ->
            targetPackage = pkg
            startEngine()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        polling = false
        cursorPollingEnabled = false
        cursorExecutor.shutdownNow()
        ShizukuBridge.stop()
        editorOverlay?.hide()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        menuView?.let { runCatching { windowManager.removeView(it) } }
        indicatorsView?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- engine lifecycle

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun startEngine() {
        ShizukuBridge.bind(this) { ok, error ->
            if (!ok) {
                android.widget.Toast.makeText(this, "Shizuku: $error", android.widget.Toast.LENGTH_LONG).show()
                return@bind
            }
            val (w, h) = screenSize()
            ShizukuBridge.start(targetPackage, w, h, keymap.toJsonString())
            polling = true
            mainHandler.post(pollRunnable)
        }
    }

    private fun pollForegroundAndCursor() {
        val diagnostics = ShizukuBridge.diagnostics()
        val foreground = try { JSONObject(diagnostics).optBoolean("foreground", false) } catch (_: Exception) { false }
        cursorPollingEnabled = foreground && editorOverlay?.isShowing != true
        indicatorsView?.visibility = if (cursorPollingEnabled) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- keymap persistence

    private fun loadKeymap() {
        val loaded = Keymap.fromJsonString(prefs.getString("mapping", null))
        keymap.controls.clear()
        keymap.controls += loaded.controls
        keymap.emergencyKey = loaded.emergencyKey
        keymap.captureKeyboard = loaded.captureKeyboard
        keymap.initialMouseMode = loaded.initialMouseMode
    }

    private fun saveKeymap() {
        prefs.edit().putString("mapping", keymap.toJsonString()).apply()
        ShizukuBridge.updateKeymap(keymap.toJsonString())
        indicatorsView?.invalidate()
    }

    // ---------------------------------------------------------------- notification

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "B13 MAUSE PRO", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("B13 MAUSE PRO")
            .setContentText("Botão flutuante ativo")
            .setSmallIcon(R.drawable.ic_stat_nexoplay)
            .addAction(Notification.Action.Builder(null, "Desativar", stopIntent).build())
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    // ---------------------------------------------------------------- bubble

    private fun overlayWindowType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun addBubble() {
        val bubble = FrameLayout(this).apply {
            setBackgroundColor(B13Theme.ACCENT_ARGB)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(B13Theme.ACCENT_ARGB)
            }
        }
        val sizePx = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx, overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }

        var downRawX = 0f; var downRawY = 0f; var startX = 0; var startY = 0; var dragged = false
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = params.x; startY = params.y; dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) dragged = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { windowManager.updateViewLayout(bubble, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) toggleMenu()
                    true
                }
                else -> false
            }
        }
        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleParams = params
    }

    private fun toggleMenu() {
        menuView?.let { runCatching { windowManager.removeView(it) }; menuView = null; return }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(B13Theme.PANEL_DARK_ARGB)
            setPadding(24, 24, 24, 24)
        }
        fun item(label: String, action: () -> Unit) {
            menu.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setPadding(16, 20, 16, 20)
                setOnClickListener { toggleMenu(); action() }
            })
        }
        item("Editar keymap neste jogo") { openEditor() }
        item("Diagnóstico") { showDiagnostics() }
        item("Desativar botão flutuante") { stopSelf() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams?.x ?: 20
            y = (bubbleParams?.y ?: 300) + 70
        }
        windowManager.addView(menu, params)
        menuView = menu
    }

    private fun openEditor() {
        if (editorOverlay?.isShowing == true) return
        ShizukuBridge.setEditorMode(true)
        indicatorsView?.visibility = View.GONE
        editorOverlay = KeymapEditorOverlay(
            context = this,
            windowManager = windowManager,
            keymap = keymap,
            onSave = { saveKeymap() },
            onExit = {
                ShizukuBridge.setEditorMode(false)
                saveKeymap()
            },
        ).also { it.show() }
    }

    private fun showDiagnostics() {
        val json = try { JSONObject(ShizukuBridge.diagnostics()) } catch (_: Exception) { JSONObject() }
        val text = buildString {
            appendLine("Pacote: ${json.optString("package", "-")}")
            appendLine("Em execução: ${json.optBoolean("running", false)}")
            appendLine("Jogo em primeiro plano: ${json.optBoolean("foreground", false)}")
            appendLine("Dispositivos lidos: ${json.optInt("deviceCount", 0)}")
            appendLine("Injeção de toques: ${json.optString("injectionPath", "indisponível")}")
            appendLine("Toques enviados: ${json.optInt("touchesSent", 0)}")
            appendLine("Toques com falha: ${json.optInt("touchesFailed", 0)}")
            appendLine("Modo do mouse: ${json.optString("mouseMode", "-")}")
            appendLine("Última tecla: ${json.optString("lastKey", "-")}")
            if (json.optString("lastError").isNotBlank()) appendLine("Último erro: ${json.optString("lastError")}")
        }
        AlertDialog.Builder(this)
            .setTitle("Diagnóstico")
            .setMessage(text)
            .setPositiveButton("Fechar", null)
            .create()
            .showAsOverlay()
    }

    // ---------------------------------------------------------------- indicators overlay

    private fun addIndicators() {
        val view = IndicatorsView(this, keymap)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        view.visibility = View.GONE
        windowManager.addView(view, params)
        indicatorsView = view
    }
}

/** Non-interactive overlay drawn over the game: keymap indicators + the own cursor arrow. */
private class IndicatorsView(context: Context, private val keymap: Keymap) : View(context) {
    private var cursorX = 0f
    private var cursorY = 0f
    private var aimMode = false
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    fun updateCursor(x: Float, y: Float, aim: Boolean) {
        cursorX = x; cursorY = y; aimMode = aim
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val prefs = context.getSharedPreferences("mapeador", Context.MODE_PRIVATE)
        if (prefs.getBoolean("indicators", true)) {
            keymap.controls.forEach { c ->
                canvas.drawCircle(c.x * width, c.y * height, (48f * c.sizeScale).coerceIn(24f, 120f), circlePaint)
            }
        }
        if (!aimMode) {
            val path = android.graphics.Path().apply {
                moveTo(cursorX, cursorY)
                lineTo(cursorX, cursorY + 36f)
                lineTo(cursorX + 10f, cursorY + 28f)
                lineTo(cursorX + 18f, cursorY + 40f)
                lineTo(cursorX + 24f, cursorY + 34f)
                lineTo(cursorX + 15f, cursorY + 22f)
                lineTo(cursorX + 24f, cursorY + 18f)
                close()
            }
            canvas.drawPath(path, arrowPaint)
        }
    }
}

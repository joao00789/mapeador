package com.mapeador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

private data class OverlayControl(
    val id: String,
    val type: String,
    val key: String,
    val x: Float,
    val y: Float
)

private class KeyCaptureFrame(context: android.content.Context) : FrameLayout(context) {
    var onKeyCaptured: ((String) -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            onKeyCaptured?.invoke(KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_"))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) &&
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
    private var menu: View? = null
    private var editor: KeyCaptureFrame? = null
    private var componentPicker: View? = null
    private var resetConfirmation: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var editorParams: WindowManager.LayoutParams? = null
    private var captureTarget: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startAsForegroundService()
        if (Settings.canDrawOverlays(this)) showBubble() else stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null && Settings.canDrawOverlays(this)) showBubble()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay(editor)
        removeOverlay(menu)
        removeOverlay(bubble)
        editor = null
        componentPicker = null
        resetConfirmation = null
        menu = null
        bubble = null
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
            .setContentTitle("NexoPlay ativo")
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
            x = resources.displayMetrics.widthPixels - size
            y = resources.displayMetrics.heightPixels / 3
        }
        bubbleParams = params

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_nexoplay)
            contentDescription = "NexoPlay. Toque para abrir o editor sobre o jogo."
            setPadding(size / 6, size / 6, size / 6, size / 6)
            background = roundedBackground(0xFF1A1F2B.toInt(), 0xFF77E4C1.toInt(), dp(28))
            clipToOutline = true
            elevation = dp(8).toFloat()
        }
        icon.setOnTouchListener(BubbleTouchListener())

        try {
            manager.addView(icon, params)
            bubble = icon
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
        val width = dp(280)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(0xF21A1F2B.toInt(), 0xFF394657.toInt(), dp(18))
            elevation = dp(12).toFloat()
        }

        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_nexoplay)
            contentDescription = "Logo NexoPlay"
        }
        title.addView(logo, LinearLayout.LayoutParams(dp(36), dp(36)))
        title.addView(
            TextView(this).apply {
                text = "NexoPlay"
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(dp(10), 0, 0, 0)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(title)
        root.addView(
            TextView(this).apply {
                text = "Editor sobre o jogo"
                setTextColor(0xFFB7C0CD.toInt())
                textSize = 12f
                setPadding(0, dp(6), 0, dp(10))
            }
        )
        root.addView(actionButton("Editar keymap neste jogo") { openEditor() })
        root.addView(actionButton("Desativar botão flutuante") { stopSelf() })
        root.addView(actionButton("Abrir NexoPlay") {
            closeMenu()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        })

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val bubblePosition = bubbleParams
            x = (bubblePosition?.x ?: 0).coerceIn(0, resources.displayMetrics.widthPixels - width)
            y = (bubblePosition?.y ?: dp(100)) + dp(64)
        }
        menuParams = params
        try {
            manager.addView(root, params)
            menu = root
        } catch (error: WindowManager.BadTokenException) {
            Log.e(TAG, "Não foi possível abrir o menu flutuante", error)
            Toast.makeText(this, "Não foi possível abrir o menu flutuante.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openEditor() {
        if (editor != null) return
        closeMenu()
        val manager = windowManager ?: return
        val root = KeyCaptureFrame(this).apply {
            isFocusableInTouchMode = true
            setBackgroundColor(Color.TRANSPARENT)
            onKeyCaptured = { key -> finishCapture(key) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
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
            Toast.makeText(this, "Ative a permissão de sobreposição do NexoPlay.", Toast.LENGTH_LONG).show()
            closeEditor()
        }
    }

    private fun buildEditorHeader(root: KeyCaptureFrame) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(0xF21A1F2B.toInt(), 0xFF394657.toInt(), dp(16))
            elevation = dp(10).toFloat()
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_nexoplay)
            contentDescription = "Logo NexoPlay"
        }
        titleRow.addView(logo, LinearLayout.LayoutParams(dp(28), dp(28)))
        titleRow.addView(
            TextView(this).apply {
                text = "Editando keymap no jogo"
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(actionButton("Sair") { closeEditor() })
        header.addView(titleRow)

        header.addView(
            TextView(this).apply {
                text = "Adicione controles, associe as teclas e posicione no HUD."
                textSize = 11f
                setTextColor(0xFFB7C0CD.toInt())
                setPadding(0, dp(6), 0, dp(6))
            }
        )
        root.addView(
            header,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                leftMargin = dp(8)
                topMargin = dp(28)
                rightMargin = dp(8)
            }
        )
        val actionsScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("+ Adicionar") { showComponentPicker() })
            addView(actionButton("Limpar") { showResetConfirmation() })
            addView(actionButton("Salvar") {
                writeControls(readControls())
                Toast.makeText(this@FloatingBubbleService, "Mapeamento salvo.", Toast.LENGTH_SHORT).show()
            })
            addView(actionButton("Desativar") { stopSelf() })
        }
        actionsScroller.addView(actions)
        header.addView(
            actionsScroller,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
    }

    private fun showComponentPicker() {
        val root = editor ?: return
        if (componentPicker != null) return

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99080B12.toInt())
            setOnClickListener { closeComponentPicker() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedBackground(0xFF1A1F2B.toInt(), 0xFF536273.toInt(), dp(20))
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
        COMPONENT_TYPES.chunked(3).forEach { rowTypes ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowTypes.forEach { (type, label, symbol) ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = roundedBackground(0xFF242D3A.toInt(), 0xFF455366.toInt(), dp(12))
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
                            setTextColor(0xFF77E4C1.toInt())
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
            repeat(3 - rowTypes.size) {
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
                (resources.displayMetrics.widthPixels - dp(32)).coerceAtMost(dp(380)),
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
        componentPicker = scrim
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
            background = roundedBackground(0xFF1A1F2B.toInt(), 0xFF536273.toInt(), dp(18))
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
                (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(360)),
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

    private fun renderControls(root: KeyCaptureFrame) {
        val oldViews = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filter { it.tag == CONTROL_TAG }
        oldViews.forEach(root::removeView)

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        readControls().forEach { control ->
            val label = componentLabel(control.type)
            val view = TextView(this).apply {
                text = "${control.key}\n$label"
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(Color.BLACK)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC77E4C1.toInt())
                    setStroke(dp(2), 0xFF77E4C1.toInt())
                }
                elevation = dp(6).toFloat()
                tag = CONTROL_TAG
                contentDescription = "$label, tecla ${control.key}. Toque para alterar. Segure para remover."
            }
            val controlWidth = dp(68)
            val controlHeight = dp(68)
            val params = FrameLayout.LayoutParams(controlWidth, controlHeight).apply {
                leftMargin = (control.x * screenWidth - controlWidth / 2)
                    .toInt().coerceIn(0, (screenWidth - controlWidth).coerceAtLeast(0))
                topMargin = (control.y * screenHeight - controlHeight / 2)
                    .toInt().coerceIn(dp(100), (screenHeight - controlHeight).coerceAtLeast(dp(100)))
            }
            view.setOnTouchListener(ControlTouchListener(control.id))
            root.addView(view, params)
        }
    }

    private fun beginCapture(id: String) {
        val root = editor ?: return
        captureTarget = id
        updateCaptureWindow(focusable = true)
        root.requestFocus()
        Toast.makeText(this, "Pressione a tecla ou o botão do mouse.", Toast.LENGTH_SHORT).show()
        renderControls(root)
    }

    private fun finishCapture(key: String) {
        val target = captureTarget ?: return
        captureTarget = null
        val controls = readControls().map { if (it.id == target) it.copy(key = key) else it }
        writeControls(controls)
        editor?.let(::renderControls)
        updateCaptureWindow(focusable = false)
    }

    private fun updateCaptureWindow(focusable: Boolean) {
        val root = editor ?: return
        val params = editorParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
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
        removeOverlay(editor)
        editor = null
        editorParams = null
        componentPicker = null
        resetConfirmation = null
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
        val width = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val height = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
        val controls = readControls().map {
            if (it.id == id) it.copy(
                x = (x / width).coerceIn(0.03f, 0.97f),
                y = (y / height).coerceIn(0.08f, 0.95f)
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
                    y = item.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
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
                result.put(item)
            } catch (error: JSONException) {
                Log.e(TAG, "Não foi possível salvar um controle", error)
            }
        }
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
                            (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0).toFloat()
                        )
                        view.y = (startY + dy).coerceIn(
                            dp(96).toFloat(),
                            (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(dp(96)).toFloat()
                        )
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(removeRunnable)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !moved && !longPressed) {
                        beginCapture(id)
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
                        params.x = (startX + dx).coerceIn(0, resources.displayMetrics.widthPixels - view.width)
                        params.y = (startY + dy).coerceIn(0, resources.displayMetrics.heightPixels - view.height)
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
            background = roundedBackground(0xFF2A3442.toInt(), 0xFF3B4B5D.toInt(), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
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
        val COMPONENT_TYPES = listOf(
            Triple("TOUCH", "Toque", "●"),
            Triple("SWIPE", "Deslizar", "↗"),
            Triple("SCROLL", "Scroll do mouse", "↕"),
            Triple("MACRO", "Macro", "M"),
            Triple("JOYSTICK", "Joystick WASD", "WASD"),
            Triple("FPS_VIEW", "Visão FPS", "◎"),
            Triple("FREE_LOOK", "Olhar livre", "◉"),
            Triple("MOUSE_PICK", "Mouse Pick", "⊙")
        )
    }
}

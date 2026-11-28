package com.mapeador

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

private val MUTED_ARGB = B13Theme.MUTED_ARGB

/**
 * Draws every configured [Control] as a draggable circle over the game and
 * routes taps/drags/long-presses back to the editor. Pure Android [View] (no
 * Compose) because this canvas lives inside a raw [WindowManager] overlay,
 * which cannot host Compose without its own lifecycle/ViewModelStore plumbing.
 */
class EditorCanvasView(
    context: Context,
    private val keymap: Keymap,
    private val onConfigure: (Control) -> Unit,
    private val onChanged: () -> Unit,
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 229, 72, 77) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 32f; isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = 36f
    }
    private val dragGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 80, 220, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var activeIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressFired = false
    private var lastTapAtMs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val longPressRunnable = Runnable {
        if (activeIndex in keymap.controls.indices && !moved) {
            keymap.controls.removeAt(activeIndex)
            activeIndex = -1
            longPressFired = true
            onChanged()
            invalidate()
        }
    }

    private fun baseRadiusPx(control: Control) = (56f * control.sizeScale).coerceIn(28f, 160f)

    private fun hitTest(x: Float, y: Float): Int {
        for (index in keymap.controls.indices.reversed()) {
            val c = keymap.controls[index]
            val cx = c.x * width
            val cy = c.y * height
            if (hypot((x - cx).toDouble(), (y - cy).toDouble()) <= baseRadiusPx(c) + 16f) return index
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (keymap.controls.isEmpty()) {
            canvas.drawText("Toque duas vezes em qualquer lugar", width / 2f, height / 2f - 24f, hintPaint)
            canvas.drawText("para criar um controle", width / 2f, height / 2f + 24f, hintPaint)
        }
        if (moved && activeIndex in keymap.controls.indices) {
            val control = keymap.controls[activeIndex]
            val x = control.x * width
            val y = control.y * height
            canvas.drawLine(x, 0f, x, height.toFloat(), dragGuidePaint)
            canvas.drawLine(0f, y, width.toFloat(), y, dragGuidePaint)
        }
        keymap.controls.forEach { c ->
            val cx = c.x * width
            val cy = c.y * height
            val r = baseRadiusPx(c)
            if (c.type == ControlType.JOYSTICK) {
                val range = if (c.radius > 1.5f) c.radius else 120f
                canvas.drawCircle(cx, cy, range, rangePaint)
            }
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, strokePaint)
            val label = if (c.type == ControlType.JOYSTICK) {
                if (c.key.equals("SETAS", true)) "Setas" else "WASD"
            } else if (c.key.isBlank() || c.key == "Tecla?") "?" else c.key
            canvas.drawText(label, cx, cy + 10f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; moved = false; longPressFired = false
                activeIndex = hitTest(event.x, event.y)
                postDelayed(longPressRunnable, 500L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop) moved = true
                if (activeIndex in keymap.controls.indices && moved) {
                    removeCallbacks(longPressRunnable)
                    val c = keymap.controls[activeIndex]
                    c.x = (event.x / width).coerceIn(0f, 1f)
                    c.y = (event.y / height).coerceIn(0f, 1f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (!longPressFired) {
                    if (activeIndex in keymap.controls.indices && !moved) {
                        onConfigure(keymap.controls[activeIndex])
                    } else if (activeIndex < 0 && !moved) {
                        val now = System.currentTimeMillis()
                        val sameSpot = hypot((event.x - lastTapX).toDouble(), (event.y - lastTapY).toDouble()) < touchSlop * 4
                        if (now - lastTapAtMs < 300L && sameSpot) {
                            val control = Control(x = event.x / width, y = event.y / height)
                            keymap.controls += control
                            onChanged()
                            invalidate()
                            onConfigure(control)
                            lastTapAtMs = 0L
                        } else {
                            lastTapAtMs = now; lastTapX = event.x; lastTapY = event.y
                        }
                    } else if (moved) {
                        onChanged()
                        invalidate()
                    }
                }
                activeIndex = -1
                moved = false
            }
        }
        return true
    }
}

/**
 * Owns the editor's overlay window: a compact top bar (Sair/Adicionar/Salvar/
 * Compartilhar/Importar/Limpar) plus the [EditorCanvasView].
 */
class KeymapEditorOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val keymap: Keymap,
    private val onSave: (Keymap) -> Unit,
    private val onExit: () -> Unit,
) {
    private var root: FrameLayout? = null

    fun show() {
        if (root != null) return
        val container = FrameLayout(context).apply { setBackgroundColor(Color.argb(60, 0, 0, 0)) }

        lateinit var canvas: EditorCanvasView
        canvas = EditorCanvasView(context, keymap, onConfigure = { control ->
            showConfigureDialog(context, control, onDelete = {
                keymap.controls.remove(control)
                onSave(keymap)
                canvas.invalidate()
            }, onSaved = { onSave(keymap); canvas.invalidate() })
        }, onChanged = { onSave(keymap) })
        container.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = buildTopBar(context,
            onExit = { hide(); onExit() },
            onAdd = { type ->
                val control = Control(type = type, x = 0.5f, y = 0.6f)
                if (type == ControlType.JOYSTICK) control.key = "WASD"
                keymap.controls += control
                onSave(keymap)
                canvas.invalidate()
                // Open the config dialog right away so a new control never sits
                // unconfigured (that used to be the #1 cause of "mapping doesn't work").
                showConfigureDialog(context, control, onDelete = {
                    keymap.controls.remove(control)
                    onSave(keymap)
                    canvas.invalidate()
                }, onSaved = { onSave(keymap); canvas.invalidate() })
            },
            onSave = { onSave(keymap); Toast.makeText(context, "Keymap salvo", Toast.LENGTH_SHORT).show() },
            onClear = {
                keymap.controls.clear()
                onSave(keymap)
                canvas.invalidate()
            },
            onShare = { shareKeymap(context, keymap) },
            onImport = { importKeymapFromClipboard(context, keymap) { canvas.invalidate() } },
        )
        container.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(container, params)
        root = container
    }

    fun hide() {
        ShizukuBridge.setKeyCapture(false)
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    val isShowing: Boolean get() = root != null
}

// ---------------------------------------------------------------- small UI helpers

private fun pillDrawable(colorArgb: Int, radiusDp: Float = 14f, density: Float = 1f) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radiusDp * density
    setColor(colorArgb)
}

private fun accentButton(context: Context, label: String, action: () -> Unit): TextView {
    val density = context.resources.displayMetrics.density
    return TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
        background = pillDrawable(B13Theme.ACCENT_ARGB, density = density)
        isClickable = true
        setOnClickListener { action() }
    }
}

private fun sectionTitle(context: Context, text: String): TextView = TextView(context).apply {
    this.text = text
    setTextColor(B13Theme.ACCENT_ARGB)
    textSize = 14f
    typeface = Typeface.DEFAULT_BOLD
    setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
}

// ---------------------------------------------------------------- top bar

private fun buildTopBar(
    context: Context,
    onExit: () -> Unit,
    onAdd: (ControlType) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onImport: () -> Unit,
): LinearLayout {
    val density = context.resources.displayMetrics.density
    val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(B13Theme.PANEL_DARK_ARGB)
        setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
    }
    fun add(label: String, action: () -> Unit) {
        bar.addView(
            accentButton(context, label, action),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (6 * density).toInt() },
        )
    }
    add("\u2715 Sair", onExit)
    add("\uFF0B Adicionar") { showAddComponentDialog(context, onAdd) }
    add("\uD83D\uDCBE Salvar", onSave)
    add("\u2197 Compartilhar", onShare)
    add("\u2199 Importar", onImport)
    add("\uD83D\uDDD1 Limpar", onClear)
    return bar
}

private fun showAddComponentDialog(context: Context, onAdd: (ControlType) -> Unit) {
    val options = ControlType.entries.map { it.label }.toTypedArray()
    AlertDialog.Builder(context)
        .setTitle("Adicionar componente")
        .setItems(options) { _, which -> onAdd(ControlType.entries[which]) }
        .setNegativeButton("Cancelar", null)
        .create()
        .showAsOverlay()
}

private fun labeledSeekBar(context: Context, label: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): LinearLayout {
    val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val title = TextView(context).apply { text = "$label: $value"; textSize = 14f }
    val seek = SeekBar(context).apply {
        this.max = max - min
        progress = (value - min).coerceIn(0, max - min)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val actual = progress + min
                title.text = "$label: $actual"
                if (fromUser) onChange(actual)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    layout.addView(title)
    layout.addView(seek)
    return layout
}

// ---------------------------------------------------------------- configure dialog

/**
 * Builds a button that captures the next physically-pressed key or mouse
 * button (via [ShizukuBridge]/the native engine) instead of making the user
 * pick one from a list.
 */
private fun physicalKeyCaptureRow(context: Context, initialKey: String, onCaptured: (String) -> Unit): LinearLayout {
    val density = context.resources.displayMetrics.density
    val handler = Handler(Looper.getMainLooper())
    var pollRunnable: Runnable? = null
    var attempts = 0

    val keyLabel = TextView(context).apply {
        text = if (initialKey.isBlank() || initialKey == "Tecla?") "Nenhuma tecla ainda" else "Tecla atual: $initialKey"
        setTextColor(Color.WHITE)
        textSize = 15f
    }
    val captureButton = accentButton(context, "\uD83C\uDFAE Pressione para capturar") {}
    captureButton.setOnClickListener {
        pollRunnable?.let(handler::removeCallbacks)
        attempts = 0
        captureButton.text = "Aguardando... pressione uma tecla"
        captureButton.background = pillDrawable(Color.argb(255, 90, 90, 90), density = density)
        ShizukuBridge.setKeyCapture(true)
        val runnable = object : Runnable {
            override fun run() {
                val captured = ShizukuBridge.consumeCapturedKey()
                if (captured.isNotBlank()) {
                    keyLabel.text = "Tecla atual: $captured"
                    captureButton.text = "\uD83C\uDFAE Pressione para capturar"
                    captureButton.background = pillDrawable(B13Theme.ACCENT_ARGB, density = density)
                    onCaptured(captured)
                } else {
                    attempts++
                    if (attempts > 200) { // ~30s timeout
                        ShizukuBridge.setKeyCapture(false)
                        captureButton.text = "\uD83C\uDFAE Pressione para capturar"
                        captureButton.background = pillDrawable(B13Theme.ACCENT_ARGB, density = density)
                        Toast.makeText(context, "Nada foi detectado. O serviço Shizuku está conectado?", Toast.LENGTH_LONG).show()
                    } else {
                        handler.postDelayed(this, 150L)
                    }
                }
            }
        }
        pollRunnable = runnable
        handler.postDelayed(runnable, 150L)
    }

    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(keyLabel)
        addView(captureButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (10 * density).toInt() })
        addView(TextView(context).apply {
            text = "Funciona com teclado USB e também com os botões do mouse."
            setTextColor(MUTED_ARGB)
            textSize = 12f
            setPadding(0, (6 * density).toInt(), 0, 0)
        })
    }
}

private fun showConfigureDialog(
    context: Context,
    control: Control,
    onDelete: () -> Unit,
    onSaved: () -> Unit,
) {
    val density = context.resources.displayMetrics.density
    val form = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((28 * density).toInt(), (8 * density).toInt(), (28 * density).toInt(), 0)
    }

    var pendingDismiss: (() -> Unit)? = null
    var dismissAfterKeyCapture: (() -> Unit)? = null

    if (control.type == ControlType.JOYSTICK) {
        form.addView(sectionTitle(context, "ESQUEMA"))
        var useArrows = control.key.equals("SETAS", true)
        val schemeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var wasdBtn: TextView
        lateinit var arrowsBtn: TextView
        fun refreshScheme() {
            wasdBtn.background = pillDrawable(if (!useArrows) B13Theme.ACCENT_ARGB else Color.argb(255, 70, 70, 70), density = density)
            arrowsBtn.background = pillDrawable(if (useArrows) B13Theme.ACCENT_ARGB else Color.argb(255, 70, 70, 70), density = density)
        }
        wasdBtn = accentButton(context, "WASD") { useArrows = false; refreshScheme() }
        arrowsBtn = accentButton(context, "Setas \u2191\u2193\u2190\u2192") { useArrows = true; refreshScheme() }
        schemeRow.addView(wasdBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * density).toInt() })
        schemeRow.addView(arrowsBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        refreshScheme()
        form.addView(schemeRow)

        form.addView(sectionTitle(context, "ALCANCE E COMPORTAMENTO"))
        form.addView(labeledSeekBar(context, "Alcance (px)", 60, 240, control.radius.let { if (it > 1.5f) it.toInt() else 120 }) {
            control.radius = it.toFloat()
        })
        val stopSwitch = Switch(context).apply {
            text = "Parar ao soltar a tecla oposta"
            isChecked = control.stopOnOpposite
        }
        form.addView(stopSwitch)

        pendingDismiss = {
            control.key = if (useArrows) "SETAS" else "WASD"
            control.stopOnOpposite = stopSwitch.isChecked
        }
    } else {
        form.addView(sectionTitle(context, "TECLA / BOTÃO"))
        form.addView(physicalKeyCaptureRow(context, control.key) { captured ->
            control.key = captured
            dismissAfterKeyCapture?.invoke()
        })

        form.addView(sectionTitle(context, "TAMANHO"))
        form.addView(labeledSeekBar(context, "Tamanho", 50, 200, (control.sizeScale * 100).toInt()) {
            control.sizeScale = it / 100f
        })

        var tapMode = control.pressMode == "TAP"
        if (control.type == ControlType.TOUCH || control.type == ControlType.MOUSE_PICK || control.type == ControlType.SWIPE) {
            form.addView(sectionTitle(context, "MODO"))
            val tapSwitch = Switch(context).apply {
                text = "Toque rápido (não segura o dedo na tela)"
                isChecked = tapMode
                setOnCheckedChangeListener { _, checked -> tapMode = checked }
            }
            form.addView(tapSwitch)
        }

        if (control.type == ControlType.MOUSE_LOOK) {
            form.addView(sectionTitle(context, "MOUSE FPS"))
            form.addView(labeledSeekBar(context, "Sensibilidade X", 0, 100, control.sensitivityX.toInt()) { control.sensitivityX = it.toFloat() })
            form.addView(labeledSeekBar(context, "Sensibilidade Y", 0, 100, control.sensitivityY.toInt()) { control.sensitivityY = it.toFloat() })
            form.addView(labeledSeekBar(context, "Suavização", 0, 100, control.smoothing) { control.smoothing = it })
            form.addView(labeledSeekBar(context, "Velocidade do cursor", 10, 400, (control.cursorSpeed * 100).toInt()) { control.cursorSpeed = it / 100f })
        }

        pendingDismiss = { control.pressMode = if (tapMode) "TAP" else "HOLD" }
    }

    val scroll = ScrollView(context).apply { addView(form) }

    val dialog = AlertDialog.Builder(context)
        .setTitle(control.type.label)
        .setView(scroll)
        .setNegativeButton("Excluir") { _, _ -> onDelete() }
        .setPositiveButton("OK") { _, _ -> pendingDismiss?.invoke(); onSaved() }
        .create()
    dismissAfterKeyCapture = {
        pendingDismiss?.invoke()
        onSaved()
        dialog.dismiss()
    }
    dialog.setOnDismissListener { ShizukuBridge.setKeyCapture(false) }
    dialog.showAsOverlay()
}

private fun shareKeymap(context: Context, keymap: Keymap) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, keymap.toJsonString())
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Compartilhar keymap").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun importKeymapFromClipboard(context: Context, keymap: Keymap, onDone: () -> Unit) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    if (text.isNullOrBlank()) {
        Toast.makeText(context, "Copie um keymap para a área de transferência antes", Toast.LENGTH_LONG).show()
        return
    }
    val imported = Keymap.fromJsonString(text)
    keymap.controls.clear()
    keymap.controls += imported.controls
    keymap.emergencyKey = imported.emergencyKey
    keymap.captureKeyboard = imported.captureKeyboard
    keymap.initialMouseMode = imported.initialMouseMode
    Toast.makeText(context, "Keymap importado (${imported.controls.size} componentes)", Toast.LENGTH_SHORT).show()
    onDone()
}

/** Dialogs built from a [Service] context need an overlay window type to show at all. */
internal fun android.app.Dialog.showAsOverlay() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    } else {
        @Suppress("DEPRECATION")
        window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
    }
    show()
}

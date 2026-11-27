package com.mapeador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import kotlin.math.hypot

private val COMMON_KEYS = listOf(
    "SPACE", "ENTER", "SHIFT_LEFT", "SHIFT_RIGHT", "CTRL_LEFT", "CTRL_RIGHT", "ALT_LEFT", "ALT_RIGHT",
    "TAB", "CAPS_LOCK", "ESCAPE",
    "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
    "A", "S", "D", "F", "G", "H", "J", "K", "L",
    "Z", "X", "C", "V", "B", "N", "M",
    "1", "2", "3", "4", "5",
    "F1", "F2", "F3", "F4",
    "MOUSE_LEFT", "MOUSE_RIGHT", "MOUSE_MIDDLE",
)

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

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 229, 72, 77) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 30f
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
            } else c.key
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
        val container = FrameLayout(context).apply { setBackgroundColor(Color.argb(40, 0, 0, 0)) }

        val canvas = EditorCanvasView(context, keymap, onConfigure = { control ->
            showConfigureDialog(context, control, keymap, onDelete = {
                keymap.controls.remove(control)
                onSave(keymap)
            }, onSaved = { onSave(keymap) })
        }, onChanged = { onSave(keymap) })
        container.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = buildTopBar(context, keymap,
            onExit = { hide(); onExit() },
            onAdd = { type ->
                val control = Control(type = type, x = 0.5f, y = 0.6f)
                if (type == ControlType.JOYSTICK) control.key = "WASD"
                keymap.controls += control
                onSave(keymap)
                canvas.invalidate()
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
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    val isShowing: Boolean get() = root != null
}

private fun buildTopBar(
    context: Context,
    keymap: Keymap,
    onExit: () -> Unit,
    onAdd: (ControlType) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onImport: () -> Unit,
): LinearLayout {
    val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(B13Theme.PANEL_DARK_ARGB)
        setPadding(12, 12, 12, 12)
    }
    fun button(label: String, action: () -> Unit) {
        bar.addView(TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setPadding(18, 10, 18, 10)
            setBackgroundColor(B13Theme.ACCENT_ARGB)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 10 })
    }
    button("Sair", onExit)
    button("Adicionar") { showAddComponentDialog(context, onAdd) }
    button("Salvar", onSave)
    button("Compartilhar", onShare)
    button("Importar", onImport)
    button("Limpar", onClear)
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
    val title = TextView(context).apply { text = "$label: $value" }
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

private fun showConfigureDialog(
    context: Context,
    control: Control,
    keymap: Keymap,
    onDelete: () -> Unit,
    onSaved: () -> Unit,
) {
    val form = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 20, 40, 0)
    }

    if (control.type == ControlType.JOYSTICK) {
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("WASD", "Setas"))
            setSelection(if (control.key.equals("SETAS", true)) 1 else 0)
        }
        form.addView(TextView(context).apply { text = "Tipo de joystick" })
        form.addView(spinner)
        form.addView(labeledSeekBar(context, "Alcance (px)", 60, 240, control.radius.let { if (it > 1.5f) it.toInt() else 120 }) {
            control.radius = it.toFloat()
        })
        val stopSwitch = Switch(context).apply {
            text = "Parar ao soltar a tecla oposta"
            isChecked = control.stopOnOpposite
        }
        form.addView(stopSwitch)
        form.addView(buildButtonsRow(context, onCancel = {}, onDelete = onDelete, onOk = {
            control.key = if (spinner.selectedItemPosition == 1) "SETAS" else "WASD"
            control.stopOnOpposite = stopSwitch.isChecked
            onSaved()
        }))
    } else {
        val keySpinner = Spinner(context).apply {
            val keys = (COMMON_KEYS + control.key).distinct()
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, keys)
            setSelection(keys.indexOf(control.key).coerceAtLeast(0))
        }
        form.addView(TextView(context).apply { text = "Tecla / botão" })
        form.addView(keySpinner)
        form.addView(labeledSeekBar(context, "Tamanho", 50, 200, (control.sizeScale * 100).toInt()) {
            control.sizeScale = it / 100f
        })

        var tapMode = control.pressMode == "TAP"
        if (control.type == ControlType.TOUCH || control.type == ControlType.MOUSE_PICK || control.type == ControlType.SWIPE) {
            val tapSwitch = Switch(context).apply {
                text = "Toque rápido (não segura)"
                isChecked = tapMode
                setOnCheckedChangeListener { _, checked -> tapMode = checked }
            }
            form.addView(tapSwitch)
        }

        if (control.type == ControlType.MOUSE_LOOK) {
            form.addView(labeledSeekBar(context, "Sensibilidade X", 0, 100, control.sensitivityX.toInt()) { control.sensitivityX = it.toFloat() })
            form.addView(labeledSeekBar(context, "Sensibilidade Y", 0, 100, control.sensitivityY.toInt()) { control.sensitivityY = it.toFloat() })
            form.addView(labeledSeekBar(context, "Suavização", 0, 100, control.smoothing) { control.smoothing = it })
            form.addView(labeledSeekBar(context, "Velocidade do cursor", 10, 400, (control.cursorSpeed * 100).toInt()) { control.cursorSpeed = it / 100f })
        }

        form.addView(buildButtonsRow(context, onCancel = {}, onDelete = onDelete, onOk = {
            control.key = keySpinner.selectedItem as? String ?: control.key
            control.pressMode = if (tapMode) "TAP" else "HOLD"
            onSaved()
        }))
    }

    AlertDialog.Builder(context)
        .setTitle(control.type.label)
        .setView(form)
        .create()
        .showAsOverlay()
}

private fun buildButtonsRow(context: Context, onCancel: () -> Unit, onDelete: () -> Unit, onOk: () -> Unit): LinearLayout {
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 20) }
    fun btn(label: String, action: () -> Unit) {
        row.addView(TextView(context).apply {
            text = label
            setPadding(24, 16, 24, 16)
            setOnClickListener { action() }
        })
    }
    btn("Excluir") { onDelete() }
    btn("OK") { onOk() }
    return row
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

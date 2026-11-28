package com.mapeador.diagnostics

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class MouseDeviceInfo(
    val id: Int,
    val name: String,
    val descriptor: String,
    val relativeMotion: Boolean,
    val buttons: Boolean,
)

data class MouseEventSample(
    val timeMillis: Long,
    val action: String,
    val deviceName: String,
    val x: Float,
    val y: Float,
    val deltaX: Float,
    val deltaY: Float,
    val scrollX: Float,
    val scrollY: Float,
    val buttonState: Int,
    val coalescedEvents: Int,
)

data class MouseDiagnosticsState(
    val isListening: Boolean = false,
    val devices: List<MouseDeviceInfo> = emptyList(),
    val events: List<MouseEventSample> = emptyList(),
    val totalEvents: Long = 0,
)

object MouseEventClassifier {
    fun isMouseSource(source: Int, apiLevel: Int = Build.VERSION.SDK_INT): Boolean {
        val relativeMouseSource =
            (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        if (relativeMouseSource && apiLevel < Build.VERSION_CODES.O) return false
        val mouse = (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        return mouse || (apiLevel >= Build.VERSION_CODES.O && relativeMouseSource)
    }
}

class MouseDiagnosticsController(context: Context) : InputManager.InputDeviceListener {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val listenerHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(MouseDiagnosticsState())
    private var registered = false
    private var lastMotionPublishedAt = 0L
    private var pendingMotionEvents = 0
    private var pendingDeltaX = 0f
    private var pendingDeltaY = 0f
    private val lastPositionByDevice = ConcurrentHashMap<Int, Pair<Float, Float>>()

    val state: StateFlow<MouseDiagnosticsState> = mutableState.asStateFlow()

    @Synchronized
    fun start() {
        if (!registered) {
            inputManager.registerInputDeviceListener(this, listenerHandler)
            registered = true
        }
        refreshDevices()
        mutableState.update { it.copy(isListening = true) }
    }

    @Synchronized
    fun stop() {
        if (registered) {
            inputManager.unregisterInputDeviceListener(this)
            registered = false
        }
        mutableState.update { it.copy(isListening = false) }
    }

    fun record(event: MotionEvent) {
        if (!mutableState.value.isListening || !MouseEventClassifier.isMouseSource(event.source)) return

        val action = actionName(event.actionMasked)
        val isMotion = event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE
        val now = System.currentTimeMillis()
        val axisRelativeX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        } else {
            0f
        }
        val axisRelativeY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        } else {
            0f
        }
        val previousPosition = lastPositionByDevice.put(event.deviceId, event.x to event.y)
        val relativeX = axisRelativeX.takeUnless { it == 0f }
            ?: previousPosition?.let { event.x - it.first }
            ?: 0f
        val relativeY = axisRelativeY.takeUnless { it == 0f }
            ?: previousPosition?.let { event.y - it.second }
            ?: 0f

        if (isMotion && now - lastMotionPublishedAt < MOTION_UPDATE_INTERVAL_MS) {
            pendingMotionEvents++
            pendingDeltaX += relativeX
            pendingDeltaY += relativeY
            return
        }

        val coalesced = pendingMotionEvents + 1
        val sample = MouseEventSample(
            timeMillis = now,
            action = action,
            deviceName = InputDevice.getDevice(event.deviceId)?.name ?: "Dispositivo ${event.deviceId}",
            x = event.x,
            y = event.y,
            deltaX = pendingDeltaX + relativeX,
            deltaY = pendingDeltaY + relativeY,
            scrollX = event.getAxisValue(MotionEvent.AXIS_HSCROLL),
            scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL),
            buttonState = event.buttonState,
            coalescedEvents = coalesced,
        )
        pendingMotionEvents = 0
        pendingDeltaX = 0f
        pendingDeltaY = 0f
        if (isMotion) lastMotionPublishedAt = now

        mutableState.update { current ->
            current.copy(
                events = (listOf(sample) + current.events).take(MAX_EVENT_SAMPLES),
                totalEvents = current.totalEvents + coalesced,
            )
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()

    override fun onInputDeviceRemoved(deviceId: Int) {
        lastPositionByDevice.remove(deviceId)
        refreshDevices()
    }

    override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()

    private fun refreshDevices() {
        val devices = InputDevice.getDeviceIds()
            .map { InputDevice.getDevice(it) }
            .filterNotNull()
            .filter { device ->
                MouseEventClassifier.isMouseSource(device.sources)
            }
            .map { device ->
                MouseDeviceInfo(
                    id = device.id,
                    name = device.name,
                    descriptor = device.descriptor,
                    relativeMotion = device.motionRanges.any { range ->
                        range.axis == MotionEvent.AXIS_RELATIVE_X ||
                            range.axis == MotionEvent.AXIS_RELATIVE_Y
                    },
                    buttons = device.supportsSource(InputDevice.SOURCE_MOUSE),
                )
            }
            .sortedBy { it.name.lowercase() }
        mutableState.update { it.copy(devices = devices) }
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_MOVE -> "movimento"
        MotionEvent.ACTION_HOVER_MOVE -> "hover"
        MotionEvent.ACTION_SCROLL -> "roda"
        MotionEvent.ACTION_BUTTON_PRESS -> "botão pressionado"
        MotionEvent.ACTION_BUTTON_RELEASE -> "botão liberado"
        MotionEvent.ACTION_DOWN -> "pressionado"
        MotionEvent.ACTION_UP -> "solto"
        else -> "ação $action"
    }

    private companion object {
        const val MAX_EVENT_SAMPLES = 20
        const val MOTION_UPDATE_INTERVAL_MS = 33L
    }
}

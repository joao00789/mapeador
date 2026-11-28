package com.mapeador

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Control types the editor can place. Keep in sync with keymap.h's ControlType. */
enum class ControlType(val wireName: String, val label: String) {
    TOUCH("TOUCH", "Toque"),
    SWIPE("SWIPE", "Deslizar"),
    MOUSE_PICK("MOUSE_PICK", "Mouse Pick"),
    JOYSTICK("JOYSTICK", "Joystick"),
    MOUSE_LOOK("MOUSE_LOOK", "Mouse FPS"),
    MOUSE_DRAG("MOUSE_DRAG", "Arrasto Mouse");

    companion object {
        fun fromWire(value: String): ControlType? = entries.firstOrNull { it.wireName == value }
    }
}

data class Control(
    var id: String = UUID.randomUUID().toString(),
    var type: ControlType = ControlType.TOUCH,
    var key: String = "Tecla?",
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var radius: Float = 0.85f,
    var sizeScale: Float = 1f,
    var stopOnOpposite: Boolean = true,
    var mousePosition: String = "Centro",
    var cursorX: Float = 0.5f,
    var cursorY: Float = 0.5f,
    var preventViewFreeze: Boolean = true,
    var sensitivityX: Float = 80f,
    var sensitivityY: Float = 80f,
    var smoothing: Int = 0,
    var cursorSpeed: Float = 3.2f,
    var cursorSize: Float = 1f,
    var pressMode: String = "HOLD",
    var responseMs: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.wireName)
        .put("key", key)
        .put("x", x.toDouble())
        .put("y", y.toDouble())
        .put("radius", radius.toDouble())
        .put("sizeScale", sizeScale.toDouble())
        .put("stopOnOpposite", stopOnOpposite)
        .put("mousePosition", mousePosition)
        .put("cursorX", cursorX.toDouble())
        .put("cursorY", cursorY.toDouble())
        .put("preventViewFreeze", preventViewFreeze)
        .put("sensitivityX", sensitivityX.toDouble())
        .put("sensitivityY", sensitivityY.toDouble())
        .put("smoothing", smoothing)
        .put("cursorSpeed", cursorSpeed.toDouble())
        .put("cursorSize", cursorSize.toDouble())
        .put("pressMode", pressMode)
        .put("responseMs", responseMs)

    companion object {
        fun fromJson(item: JSONObject): Control? {
            val type = ControlType.fromWire(item.optString("type")) ?: return null
            val isMouseLook = type == ControlType.MOUSE_LOOK
            val sensitivityX = item.optDouble("sensitivityX", 80.0).toFloat()
            val sensitivityY = item.optDouble("sensitivityY", 80.0).toFloat()
            val smoothing = item.optInt("smoothing", 0)
            val cursorSpeed = item.optDouble("cursorSpeed", 3.2).toFloat()
            return Control(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                type = type,
                key = item.optString("key", "Tecla?"),
                x = item.optDouble("x", 0.5).toFloat(),
                y = item.optDouble("y", 0.5).toFloat(),
                radius = item.optDouble("radius", 0.85).toFloat(),
                sizeScale = item.optDouble("sizeScale", 1.0).toFloat(),
                stopOnOpposite = item.optBoolean("stopOnOpposite", true),
                mousePosition = item.optString("mousePosition", "Centro"),
                cursorX = item.optDouble("cursorX", 0.5).toFloat(),
                cursorY = item.optDouble("cursorY", 0.5).toFloat(),
                preventViewFreeze = item.optBoolean("preventViewFreeze", true),
                sensitivityX = if (isMouseLook && sensitivityX == 50f) 80f else sensitivityX,
                sensitivityY = if (isMouseLook && sensitivityY == 50f) 80f else sensitivityY,
                smoothing = if (isMouseLook && smoothing == 35) 0 else smoothing,
                cursorSpeed = if (isMouseLook && kotlin.math.abs(cursorSpeed - 1.6f) < 0.001f) 3.2f else cursorSpeed,
                cursorSize = item.optDouble("cursorSize", 1.0).toFloat(),
                pressMode = item.optString("pressMode", "HOLD"),
                responseMs = item.optInt("responseMs", 0),
            )
        }
    }
}

data class Keymap(
    val controls: MutableList<Control> = mutableListOf(),
    var emergencyKey: String = "F12",
    var captureKeyboard: Boolean = false,
    var initialMouseMode: Int = 0, // 0 = CURSOR, 1 = MIRA
) {
    fun toJsonString(): String {
        val array = JSONArray()
        controls.forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("controls", array)
            .put("emergencyKey", emergencyKey)
            .put("captureKeyboard", captureKeyboard)
            .put("initialMouseMode", initialMouseMode)
            .toString()
    }

    companion object {
        fun fromJsonString(text: String?): Keymap {
            val keymap = Keymap()
            if (text.isNullOrBlank()) return keymap
            return try {
                val root = JSONObject(text)
                keymap.emergencyKey = root.optString("emergencyKey", "F12")
                keymap.captureKeyboard = root.optBoolean("captureKeyboard", false)
                keymap.initialMouseMode = root.optInt("initialMouseMode", 0)
                val array = root.optJSONArray("controls") ?: JSONArray()
                for (i in 0 until array.length()) {
                    Control.fromJson(array.getJSONObject(i))?.let { keymap.controls += it }
                }
                keymap
            } catch (_: Exception) {
                // Older saves stored a bare array (no envelope object) — try that too.
                try {
                    val array = JSONArray(text)
                    for (i in 0 until array.length()) {
                        Control.fromJson(array.getJSONObject(i))?.let { keymap.controls += it }
                    }
                } catch (_: Exception) {
                }
                keymap
            }
        }

        fun fromJsonStringStrict(text: String?): Keymap {
            if (text.isNullOrBlank()) return Keymap()
            val root = try {
                JSONObject(text)
            } catch (_: JSONException) {
                null
            }
            val array = if (root == null) {
                JSONArray(text)
            } else {
                root.optJSONArray("controls")
                    ?: throw JSONException("O mapa não contém uma lista de controles.")
            }
            val keymap = Keymap(
                emergencyKey = root?.optString("emergencyKey", "F12") ?: "F12",
                captureKeyboard = root?.optBoolean("captureKeyboard", false) ?: false,
                initialMouseMode = root?.optInt("initialMouseMode", 0) ?: 0,
            )
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw JSONException("O controle na posição $index não é um objeto.")
                val control = Control.fromJson(item)
                    ?: throw JSONException("O tipo de controle na posição $index não é reconhecido.")
                keymap.controls += control
            }
            return keymap
        }
    }
}

package com.mapeador

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONException
import kotlin.math.roundToInt

class KeyMappingAccessibilityService : AccessibilityService() {
    private val handledKeys = mutableSetOf<Int>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        handledKeys.clear()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            return handledKeys.remove(event.keyCode)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return event.keyCode in handledKeys
        if (!isMappingEnabledForActiveGame()) return false

        val keyName = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
        val mapping = findMapping(keyName) ?: return false
        handledKeys.add(event.keyCode)

        val accepted = when (mapping.type) {
            "TOUCH", "MOUSE_PICK" -> dispatchTap(mapping.x, mapping.y)
            "SWIPE" -> dispatchSwipe(mapping.x, mapping.y)
            else -> {
                handledKeys.remove(event.keyCode)
                return false
            }
        }
        if (!accepted) {
            Log.w(TAG, "O Android não aceitou o toque mapeado para $keyName")
        }
        return true
    }

    private fun isMappingEnabledForActiveGame(): Boolean {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean("typing", true)) return false

        val activePackage = rootInActiveWindow?.packageName?.toString() ?: return false
        if (activePackage == packageName) return false

        val encodedGames = preferences.getString("games", "[]") ?: "[]"
        return try {
            val games = JSONArray(encodedGames)
            (0 until games.length()).any { games.optString(it) == activePackage }
        } catch (error: JSONException) {
            Log.e(TAG, "Não foi possível ler a lista de jogos", error)
            false
        }
    }

    private fun findMapping(keyName: String): TouchMapping? {
        val encodedMapping = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString("mapping", "[]") ?: "[]"
        return try {
            val mappings = JSONArray(encodedMapping)
            for (index in 0 until mappings.length()) {
                val item = mappings.optJSONObject(index) ?: continue
                val savedKey = normalizeKey(item.optString("key"))
                if (savedKey != normalizeKey(keyName)) continue
                val type = item.optString("type")
                if (type !in SUPPORTED_TYPES) continue
                return TouchMapping(
                    type = type,
                    x = item.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                    y = item.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                )
            }
            null
        } catch (error: JSONException) {
            Log.e(TAG, "Não foi possível ler o mapeamento de teclas", error)
            null
        }
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(x * metrics.widthPixels, y * metrics.heightPixels)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun dispatchSwipe(x: Float, y: Float): Boolean {
        val metrics = resources.displayMetrics
        val startX = x * metrics.widthPixels
        val startY = y * metrics.heightPixels
        val distance = 96 * metrics.density
        val endX = if (startX + distance < metrics.widthPixels) startX + distance else startX - distance
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX.coerceIn(0f, metrics.widthPixels.toFloat()), startY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun normalizeKey(value: String): String =
        value.uppercase().removePrefix("KEYCODE_").replace(" ", "_")

    private data class TouchMapping(val type: String, val x: Float, val y: Float)

    private companion object {
        const val TAG = "NexoPlayKeys"
        const val PREFERENCES = "mapeador"
        const val TAP_DURATION_MS = 45L
        const val SWIPE_DURATION_MS = 220L
        val SUPPORTED_TYPES = setOf("TOUCH", "SWIPE", "MOUSE_PICK")
    }
}

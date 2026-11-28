package com.mapeador.diagnostics

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseEventClassifierTest {
    @Test
    fun acceptsMouseAndRelativeMouseSources() {
        assertTrue(MouseEventClassifier.isMouseSource(InputDevice.SOURCE_MOUSE, apiLevel = 35))
        assertTrue(
            MouseEventClassifier.isMouseSource(
                InputDevice.SOURCE_MOUSE_RELATIVE,
                apiLevel = 35,
            ),
        )
    }

    @Test
    fun ignoresTouchscreenAndKeyboardSources() {
        assertFalse(
            MouseEventClassifier.isMouseSource(
                InputDevice.SOURCE_TOUCHSCREEN,
                apiLevel = 35,
            ),
        )
        assertFalse(
            MouseEventClassifier.isMouseSource(
                InputDevice.SOURCE_KEYBOARD,
                apiLevel = 35,
            ),
        )
    }

    @Test
    fun ignoresRelativeMouseBeforeAndroidO() {
        assertFalse(
            MouseEventClassifier.isMouseSource(
                InputDevice.SOURCE_MOUSE_RELATIVE,
                apiLevel = 25,
            ),
        )
    }
}

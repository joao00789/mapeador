// engine.h — the mapper itself: reads evdev, decides what every key/mouse
// movement means according to the active Keymap, and injects the resulting
// touches/keys/scrolls into the foreground game. This is the native
// replacement for the old ShizukuInputUserService logic.
#pragma once

#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <thread>
#include <jni.h>

#include "keymap.h"
#include "evdev.h"

namespace mapeador {

// One finger currently held down on the virtual touchscreen.
struct VirtualFinger {
    int id = -1;
    float x = 0;
    float y = 0;
};

// Runtime (mutable, per-session) state for one configured Control. Kept
// separate from Control itself so the keymap stays a plain data document.
struct ControlRuntime {
    int finger = -1;            // active VirtualFinger id, or -1
    std::vector<int> held;      // JOYSTICK: currently-held directions (0=UP,1=DOWN,2=LEFT,3=RIGHT)
    int joystickReleaseToken = 0;
};

class Engine {
public:
    Engine();
    ~Engine();

    // Loads a new keymap and (re)builds the key->control bindings table.
    // Safe to call while running (e.g. the editor just saved changes).
    void updateKeymap(const std::string &keymapJson);

    void start(const std::string &packageName, int screenWidthPx, int screenHeightPx);
    void stop();

    void setForeground(bool foreground);
    void setEditorMode(bool editorMode);

    // One-shot physical key/mouse-button capture, used by the editor's
    // "Capturar tecla" button: enable it, wait for the user to press
    // anything, then read back what was pressed (empty if nothing yet).
    void setKeyCaptureMode(bool enabled);
    std::string consumeCapturedKey();

    // JSON status blob the UI polls for its "Diagnóstico" screen.
    std::string diagnostics();

    // Current virtual cursor position in pixels, for the Kotlin overlay arrow.
    void cursorPositionPx(float *outX, float *outY, bool *outAimMode);

private:
    std::mutex lock_;
    EvdevManager evdev_;
    Keymap keymap_;
    std::vector<ControlRuntime> runtime_;
    // key name -> list of (control index, direction) — direction is -1 for
    // every control type except JOYSTICK, where it is UP/DOWN/LEFT/RIGHT.
    std::vector<std::pair<std::string, std::vector<std::pair<int, int>>>> bindings_;

    std::string packageName_;
    int screenWidth_ = 1920;
    int screenHeight_ = 1080;

    std::atomic<bool> running_{false};
    std::atomic<bool> foreground_{false};
    std::atomic<bool> editorMode_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> chatMode_{false};
    std::atomic<bool> keyCaptureEnabled_{false};
    std::string capturedKey_;
    bool mouseAimMode_ = false;
    float cursorX_ = 0.5f;
    float cursorY_ = 0.5f;
    int cursorFinger_ = -1;
    int cursorRightFinger_ = -1;
    int cameraFinger_ = -1;
    float cameraAnchorX_ = 0, cameraAnchorY_ = 0;
    float cameraOffsetX_ = 0, cameraOffsetY_ = 0;
    float smoothedDx_ = 0, smoothedDy_ = 0;

    int relativeX_ = 0, relativeY_ = 0;
    int wheelVertical_ = 0, wheelHorizontal_ = 0;
    int wheelVerticalHiRes_ = 0, wheelHorizontalHiRes_ = 0;

    std::thread focusThread_;
    std::atomic<bool> focusRunning_{false};

    std::atomic<int> touchesSent_{0};
    std::atomic<int> touchesFailed_{0};
    std::string lastInjectionError_;
    std::string injectionPathDescription_;
    std::string lastKeyPressed_;

    std::vector<VirtualFinger> fingers_;

    // Every call site attaches (or reuses) a JNIEnv for whichever native
    // thread is currently running — the evdev reader thread, a detached
    // helper thread, or a Binder thread calling start()/stop()/diagnostics()
    // are all different OS threads and each needs its own JNIEnv.
    static JNIEnv *currentEnv();

    void rebuildBindings();
    void onRawEvent(const RawEvent &event);
    void onKeyEvent(const std::string &devicePath, int code, int value, bool isMouseButton);
    void onSyn();
    void onMouseMove(int dx, int dy);
    void onMouseScroll(float vertical, float horizontal);
    void onKey(const std::string &name, bool down);
    void performBinding(int controlIndex, int direction, bool down);

    int fingerDown(float x, float y);
    void fingerMove(int id, float x, float y);
    void fingerUp(int id);
    void releaseAllFingers();
    void injectAllFingers(int action);

    void pressTouch(int controlIndex);
    void releaseTouch(int controlIndex);
    void swipe(int controlIndex);
    void moveJoystick(int controlIndex, int direction, bool down);
    void pressMouseDrag(int controlIndex);
    void toggleMouseMode();

    float pixelX(const Control &c) const;
    float pixelY(const Control &c) const;

    void focusLoop();
};

} // namespace mapeador

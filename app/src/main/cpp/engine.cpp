#include "engine.h"
#include "android_api.h"
#include "keycodes.h"

#include <linux/input.h>
#include <cmath>
#include <algorithm>
#include <chrono>
#include <cstdio>

namespace mapeador {

namespace {
constexpr const char *TAG = "MapeadorEngine";
constexpr int ACTION_DOWN = 0;
constexpr int ACTION_UP = 1;
constexpr int ACTION_MOVE = 2;
constexpr int ACTION_POINTER_DOWN = 5;
constexpr int ACTION_POINTER_UP = 6;
constexpr int ACTION_POINTER_INDEX_SHIFT = 8;
constexpr int MAX_FINGERS = 10;
constexpr float SWIPE_DISTANCE_PX = 96.0f;
constexpr float MAX_MOUSE_SENSITIVITY = 3.0f;
constexpr auto MOUSE_MOVE_BATCH_INTERVAL = std::chrono::milliseconds(2);
constexpr float DEFAULT_JOYSTICK_RANGE_PX = 120.0f;
constexpr int JOYSTICK_RELEASE_GRACE_MS = 80;
constexpr float CAMERA_REGION_FRACTION = 0.35f;
constexpr float MIN_CAMERA_REGION_PX = 64.0f;

enum Direction { DIR_UP = 0, DIR_DOWN = 1, DIR_LEFT = 2, DIR_RIGHT = 3 };

float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

int axis(const ControlRuntime &runtime, const Control &control, int negative, int positive) {
    auto has = [&](int d) {
        return std::find(runtime.held.begin(), runtime.held.end(), d) != runtime.held.end();
    };
    bool neg = has(negative);
    bool pos = has(positive);
    if (!neg && !pos) return 0;
    if (!neg) return 1;
    if (!pos) return -1;
    if (!control.stopOnOpposite) {
        // Whichever direction key was pressed most recently wins.
        auto negIt = std::find(runtime.held.begin(), runtime.held.end(), negative);
        auto posIt = std::find(runtime.held.begin(), runtime.held.end(), positive);
        return (posIt - runtime.held.begin()) > (negIt - runtime.held.begin()) ? 1 : -1;
    }
    return 0;
}

} // namespace

Engine::Engine() = default;

Engine::~Engine() { stop(); }

void Engine::rebuildBindings() {
    bindings_.clear();
    runtime_.assign(keymap_.controls.size(), ControlRuntime{});

    auto addBinding = [&](const std::string &key, int controlIndex, int direction) {
        for (auto &entry : bindings_) {
            if (entry.first == key) {
                entry.second.emplace_back(controlIndex, direction);
                return;
            }
        }
        bindings_.push_back({key, {{controlIndex, direction}}});
    };

    for (size_t i = 0; i < keymap_.controls.size(); i++) {
        const Control &c = keymap_.controls[i];
        if (c.type == ControlType::Joystick) {
            bool useArrows = c.key == "SETAS" || c.key == "Setas" || c.key == "ARROWS";
            const char *keys[4] = {"DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"};
            const char *wasd[4] = {"W", "S", "A", "D"};
            const char **chosen = useArrows ? keys : wasd;
            for (int d = 0; d < 4; d++) addBinding(chosen[d], (int) i, d);
        } else {
            std::string key = c.key;
            if (key.empty() || key == "Tecla?" || key == "TOQUE") {
                if (c.type == ControlType::MouseLook) key = "ALT_LEFT";
                else continue;
            }
            addBinding(key, (int) i, -1);
        }
    }
}

void Engine::updateKeymap(const std::string &keymapJson) {
    std::lock_guard<std::mutex> lock(lock_);
    keymap_ = Keymap::fromJsonString(keymapJson);
    mouseAimMode_ = keymap_.initialMouseMode == 1;
    rebuildBindings();
}

void Engine::start(const std::string &packageName, int screenWidthPx, int screenHeightPx) {
    std::lock_guard<std::mutex> lock(lock_);
    if (running_) return;
    packageName_ = packageName;
    screenWidth_ = screenWidthPx > 0 ? screenWidthPx : 1920;
    screenHeight_ = screenHeightPx > 0 ? screenHeightPx : 1080;
    cursorX_ = screenWidth_ / 2.0f;
    cursorY_ = screenHeight_ / 2.0f;

    JNIEnv *startEnv = currentEnv();
    injectionPathDescription_ = androidApiResolveInjection(startEnv);
    androidApiLogInfo(TAG, injectionPathDescription_.empty()
        ? "Nenhum caminho de injecao de toques disponivel"
        : ("Injecao de toques pronta: " + injectionPathDescription_).c_str());

    evdev_.rescan();
    int grabbedMouse = evdev_.setMouseGrabbed(true);
    androidApiLogInfo(TAG, ("Mouse capturado com exclusividade em " + std::to_string(grabbedMouse) +
        " nó(s); dispositivos encontrados: " + std::to_string(evdev_.deviceCount())).c_str());
    running_ = true;
    pendingMouseDeltaX_ = 0;
    pendingMouseDeltaY_ = 0;
    lastMouseMotionAt_ = {};
    mouseMotionRunning_ = true;
    mouseMotionThread_ = std::thread(&Engine::mouseMotionLoop, this);
    evdev_.startReading(
        [this](const RawEvent &e) { onRawEvent(e); },
        [](const std::string &msg) { androidApiLogInfo(TAG, msg.c_str()); });

    focusRunning_ = true;
    focusThread_ = std::thread(&Engine::focusLoop, this);
}

void Engine::stop() {
    if (!running_) return;
    running_ = false;
    focusRunning_ = false;
    mouseMotionRunning_ = false;
    mouseMotionCv_.notify_all();
    if (mouseMotionThread_.joinable()) mouseMotionThread_.join();
    if (focusThread_.joinable()) focusThread_.join();
    evdev_.stop();
    releaseAllFingers();
    androidApiDetachCurrentThread();
}

JNIEnv *Engine::currentEnv() { return androidApiAttachCurrentThread("MapeadorEngine"); }

void Engine::setForeground(bool foreground) {
    bool was = foreground_.exchange(foreground);
    if (was && !foreground) {
        std::lock_guard<std::mutex> lock(lock_);
        releaseAllFingers();
    }
}

void Engine::setEditorMode(bool editorMode) {
    bool was = editorMode_.exchange(editorMode);
    if (!was && editorMode) {
        std::lock_guard<std::mutex> lock(lock_);
        releaseAllFingers();
    }
}

void Engine::setKeyCaptureMode(bool enabled) {
    std::lock_guard<std::mutex> lock(lock_);
    keyCaptureEnabled_ = enabled;
    if (enabled) capturedKey_.clear();
}

std::string Engine::consumeCapturedKey() {
    std::lock_guard<std::mutex> lock(lock_);
    std::string result = capturedKey_;
    capturedKey_.clear();
    return result;
}

void Engine::cursorPositionPx(float *outX, float *outY, bool *outAimMode) {
    std::lock_guard<std::mutex> lock(lock_);
    if (outX) *outX = cursorX_;
    if (outY) *outY = cursorY_;
    if (outAimMode) *outAimMode = mouseAimMode_;
}

std::string Engine::diagnostics() {
    std::lock_guard<std::mutex> lock(lock_);
    json::Value root = json::Value::makeObject();
    root.set("running", json::Value::fromBool(running_));
    root.set("foreground", json::Value::fromBool(foreground_));
    root.set("editorMode", json::Value::fromBool(editorMode_));
    root.set("paused", json::Value::fromBool(paused_));
    root.set("deviceCount", json::Value::fromNumber(evdev_.deviceCount()));
    root.set("injectionPath", json::Value::fromString(injectionPathDescription_));
    root.set("injectionAvailable", json::Value::fromBool(androidApiInjectionAvailable()));
    root.set("touchesSent", json::Value::fromNumber(touchesSent_));
    root.set("touchesFailed", json::Value::fromNumber(touchesFailed_));
    root.set("lastError", json::Value::fromString(lastInjectionError_));
    root.set("lastKey", json::Value::fromString(lastKeyPressed_));
    root.set("mouseMode", json::Value::fromString(mouseAimMode_ ? "MIRA" : "CURSOR"));
    root.set("package", json::Value::fromString(packageName_));
    json::Value devicesArr = json::Value::makeArray();
    for (const auto &d : evdev_.snapshotDevices()) {
        json::Value dv = json::Value::makeObject();
        dv.set("path", json::Value::fromString(d.path));
        dv.set("mouseMotion", json::Value::fromBool(d.hasRelativeMotion));
        dv.set("mouseButtons", json::Value::fromBool(d.hasMouseButtons));
        dv.set("keyboard", json::Value::fromBool(d.hasKeyboardKeys));
        dv.set("grabbed", json::Value::fromBool(d.grabbed));
        devicesArr.push(dv);
    }
    root.set("devices", devicesArr);
    return json::dump(root);
}

// ---------------------------------------------------------------- touch fingers

int Engine::fingerDown(float x, float y) {
    if (fingers_.size() >= MAX_FINGERS) return -1;
    int id = -1;
    for (int candidate = 0; candidate < MAX_FINGERS; candidate++) {
        bool used = std::any_of(fingers_.begin(), fingers_.end(),
            [&](const VirtualFinger &f) { return f.id == candidate; });
        if (!used) { id = candidate; break; }
    }
    if (id < 0) return -1;
    fingers_.push_back({id, x, y});
    int action = fingers_.size() == 1
        ? ACTION_DOWN
        : (ACTION_POINTER_DOWN | ((int) (fingers_.size() - 1) << ACTION_POINTER_INDEX_SHIFT));
    injectAllFingers(action);
    return id;
}

void Engine::fingerMove(int id, float x, float y) {
    for (auto &f : fingers_) {
        if (f.id == id) { f.x = x; f.y = y; injectAllFingers(ACTION_MOVE); return; }
    }
}

void Engine::fingerUp(int id) {
    auto it = std::find_if(fingers_.begin(), fingers_.end(),
        [&](const VirtualFinger &f) { return f.id == id; });
    if (it == fingers_.end()) return;
    size_t index = it - fingers_.begin();
    int action = fingers_.size() == 1
        ? ACTION_UP
        : (ACTION_POINTER_UP | ((int) index << ACTION_POINTER_INDEX_SHIFT));
    injectAllFingers(action);
    fingers_.erase(it);
}

void Engine::releaseAllFingers() {
    while (!fingers_.empty()) fingerUp(fingers_.back().id);
    cursorFinger_ = -1;
    cursorRightFinger_ = -1;
    cameraFinger_ = -1;
    for (auto &r : runtime_) { r.finger = -1; r.held.clear(); }
}

void Engine::injectAllFingers(int action) {
    if (fingers_.empty()) return;
    JNIEnv *env = currentEnv();
    if (!env) return;
    std::vector<int> ids;
    std::vector<float> xs, ys;
    for (const auto &f : fingers_) { ids.push_back(f.id); xs.push_back(f.x); ys.push_back(f.y); }
    int64_t now = androidApiUptimeMillis(env);
    bool ok = androidApiInjectTouch(env, now, now, action, (int) ids.size(), ids.data(), xs.data(), ys.data());
    if (ok) touchesSent_++;
    else { touchesFailed_++; lastInjectionError_ = "Falha ao injetar toque"; }
}

// ---------------------------------------------------------------- control actions

float Engine::pixelX(const Control &c) const { return clampf(c.x * screenWidth_, 0.0f, screenWidth_ - 1.0f); }
float Engine::pixelY(const Control &c) const { return clampf(c.y * screenHeight_, 0.0f, screenHeight_ - 1.0f); }

void Engine::pressTouch(int controlIndex) {
    ControlRuntime &r = runtime_[controlIndex];
    if (r.finger >= 0) return;
    const Control &c = keymap_.controls[controlIndex];
    r.finger = fingerDown(pixelX(c), pixelY(c));
}

void Engine::releaseTouch(int controlIndex) {
    ControlRuntime &r = runtime_[controlIndex];
    if (r.finger < 0) return;
    fingerUp(r.finger);
    r.finger = -1;
}

void Engine::swipe(int controlIndex) {
    const Control &c = keymap_.controls[controlIndex];
    float startX = pixelX(c);
    float startY = pixelY(c);
    float endX = (startX + SWIPE_DISTANCE_PX < screenWidth_) ? startX + SWIPE_DISTANCE_PX
                                                              : std::max(0.0f, startX - SWIPE_DISTANCE_PX);
    std::thread([this, startX, startY, endX]() {
        JNIEnv *env = androidApiAttachCurrentThread("MapeadorSwipe");
        (void) env;
        int id;
        {
            std::lock_guard<std::mutex> lock(lock_);
            id = fingerDown(startX, startY);
        }
        if (id < 0) { androidApiDetachCurrentThread(); return; }
        const int steps = 8;
        for (int step = 1; step <= steps; step++) {
            std::this_thread::sleep_for(std::chrono::milliseconds(25));
            std::lock_guard<std::mutex> lock(lock_);
            fingerMove(id, startX + (endX - startX) * step / steps, startY);
        }
        {
            std::lock_guard<std::mutex> lock(lock_);
            fingerUp(id);
        }
        androidApiDetachCurrentThread();
    }).detach();
}

void Engine::moveJoystick(int controlIndex, int direction, bool down) {
    ControlRuntime &r = runtime_[controlIndex];
    const Control &c = keymap_.controls[controlIndex];
    if (down) {
        if (std::find(r.held.begin(), r.held.end(), direction) == r.held.end()) r.held.push_back(direction);
    } else {
        r.held.erase(std::remove(r.held.begin(), r.held.end(), direction), r.held.end());
    }

    int vertical = axis(r, c, DIR_UP, DIR_DOWN);
    int horizontal = axis(r, c, DIR_LEFT, DIR_RIGHT);
    if (vertical == 0 && horizontal == 0) {
        if (r.finger >= 0) {
            int token = ++r.joystickReleaseToken;
            int idx = controlIndex;
            std::thread([this, idx, token]() {
                std::this_thread::sleep_for(std::chrono::milliseconds(JOYSTICK_RELEASE_GRACE_MS));
                std::lock_guard<std::mutex> lock(lock_);
                if (idx >= (int) runtime_.size()) return;
                ControlRuntime &rt = runtime_[idx];
                const Control &cc = keymap_.controls[idx];
                int v = axis(rt, cc, DIR_UP, DIR_DOWN);
                int h = axis(rt, cc, DIR_LEFT, DIR_RIGHT);
                if (rt.joystickReleaseToken == token && v == 0 && h == 0 && rt.finger >= 0) {
                    fingerUp(rt.finger);
                    rt.finger = -1;
                }
            }).detach();
        }
        return;
    }
    r.joystickReleaseToken++;
    float length = std::sqrt((float) (vertical * vertical + horizontal * horizontal));
    float range = c.radius > 1.5f ? c.radius : DEFAULT_JOYSTICK_RANGE_PX; // radius already stores px range for joysticks
    float centerX = pixelX(c);
    float centerY = pixelY(c);
    float targetX = clampf(centerX + (float) horizontal / length * range, 0.0f, screenWidth_ - 1.0f);
    float targetY = clampf(centerY + (float) vertical / length * range, 0.0f, screenHeight_ - 1.0f);
    if (r.finger < 0) r.finger = fingerDown(centerX, centerY);
    if (r.finger >= 0) fingerMove(r.finger, targetX, targetY);
}

void Engine::pressMouseDrag(int controlIndex) {
    ControlRuntime &r = runtime_[controlIndex];
    if (r.finger >= 0) return;
    const Control &c = keymap_.controls[controlIndex];
    r.finger = fingerDown(pixelX(c), pixelY(c));
}

void Engine::toggleMouseMode() {
    mouseAimMode_ = !mouseAimMode_;
    if (!mouseAimMode_ && cameraFinger_ >= 0) { fingerUp(cameraFinger_); cameraFinger_ = -1; }
}

void Engine::performBinding(int controlIndex, int direction, bool down) {
    const Control &c = keymap_.controls[controlIndex];
    auto schedule = [&](int delayMs, auto action) {
        if (delayMs <= 0) { action(); return; }
        std::thread([this, delayMs, action]() {
            std::this_thread::sleep_for(std::chrono::milliseconds(delayMs));
            std::lock_guard<std::mutex> lock(lock_);
            action();
        }).detach();
    };

    if (c.pressMode == "TAP" && c.type != ControlType::Joystick && c.type != ControlType::MouseDrag) {
        if (!down) return;
        if (c.type == ControlType::Touch || c.type == ControlType::MousePick) {
            pressTouch(controlIndex);
            schedule(55, [this, controlIndex]() { releaseTouch(controlIndex); });
        } else if (c.type == ControlType::Swipe) {
            swipe(controlIndex);
        }
        return;
    }

    switch (c.type) {
        case ControlType::MouseLook:
            if (down) toggleMouseMode();
            break;
        case ControlType::Touch:
        case ControlType::MousePick:
            if (down) pressTouch(controlIndex); else releaseTouch(controlIndex);
            break;
        case ControlType::Swipe:
            if (down) swipe(controlIndex);
            break;
        case ControlType::MouseDrag:
            if (down) pressMouseDrag(controlIndex); else releaseTouch(controlIndex);
            break;
        case ControlType::Joystick:
            moveJoystick(controlIndex, direction, down);
            break;
        default:
            break;
    }
}

// ---------------------------------------------------------------- key routing

void Engine::onKey(const std::string &name, bool down) {
    lastKeyPressed_ = name;

    // The editor's "Capturar tecla" button takes priority over everything
    // else: whatever is physically pressed next is reported back as-is and
    // nothing else happens with it (no binding fires, no emergency toggle).
    if (keyCaptureEnabled_) {
        if (down) {
            capturedKey_ = name;
            keyCaptureEnabled_ = false;
        }
        return;
    }

    if (name == keymap_.emergencyKey) {
        if (down) {
            bool nowPaused = !paused_.load();
            paused_.store(nowPaused);
            if (nowPaused) releaseAllFingers();
        }
        return;
    }

    if (name == "ENTER" && keymap_.captureKeyboard) {
        if (down) chatMode_ = !chatMode_.load();
    }

    if (paused_ || editorMode_ || !foreground_) {
        if (down) return;
    }

    // Find bindings for this key.
    std::vector<std::pair<int, int>> matches;
    bool isMouseLookShortcut = false, isMouseDragShortcut = false;
    for (auto &entry : bindings_) {
        if (entry.first != name) continue;
        matches = entry.second;
        for (auto &m : matches) {
            ControlType t = keymap_.controls[m.first].type;
            if (t == ControlType::MouseLook) isMouseLookShortcut = true;
            if (t == ControlType::MouseDrag) isMouseDragShortcut = true;
        }
        break;
    }

    bool isMouseName = name.rfind("MOUSE_", 0) == 0;
    if (isMouseName && matches.empty()) {
        if (name == "MOUSE_LEFT" && !mouseAimMode_ && !isMouseLookShortcut && !isMouseDragShortcut) {
            if (down) { if (cursorFinger_ < 0) cursorFinger_ = fingerDown(cursorX_, cursorY_); }
            else if (cursorFinger_ >= 0) { fingerUp(cursorFinger_); cursorFinger_ = -1; }
            return;
        }
        if (name == "MOUSE_RIGHT" && !mouseAimMode_) {
            if (down) {
                JNIEnv *env = currentEnv();
                if (env) {
                    int backCode = androidApiKeyCodeFromName(env, "BACK");
                    int64_t now = androidApiUptimeMillis(env);
                    androidApiInjectKey(env, now, now, true, backCode, 0, 0);
                }
                std::thread([this]() {
                    std::this_thread::sleep_for(std::chrono::milliseconds(40));
                    std::lock_guard<std::mutex> lock(lock_);
                    JNIEnv *env = currentEnv();
                    if (env) {
                        int backCode = androidApiKeyCodeFromName(env, "BACK");
                        int64_t now = androidApiUptimeMillis(env);
                        androidApiInjectKey(env, now, now, false, backCode, 0, 0);
                    }
                }).detach();
            }
            return;
        }
        if (name == "MOUSE_MIDDLE" && !mouseAimMode_) {
            if (down) {
                int id = fingerDown(cursorX_, cursorY_);
                if (id >= 0) {
                    std::thread([this, id]() {
                        std::this_thread::sleep_for(std::chrono::milliseconds(35));
                        std::lock_guard<std::mutex> lock(lock_);
                        fingerUp(id);
                    }).detach();
                }
            }
            return;
        }
    }

    if (matches.empty()) {
        if (keymap_.captureKeyboard && !chatMode_ && !isMouseName) {
            JNIEnv *env = currentEnv();
            int code = env ? androidApiKeyCodeFromName(env, name) : 0;
            if (env && code != 0) {
                int64_t now = androidApiUptimeMillis(env);
                androidApiInjectKey(env, now, now, down, code, 0, 0);
            }
        }
        return;
    }
    if (!foreground_ && down) return;
    for (auto &m : matches) {
        int delay = keymap_.controls[m.first].responseMs;
        if (delay <= 0) {
            performBinding(m.first, m.second, down);
        } else {
            int controlIndex = m.first, direction = m.second;
            std::thread([this, controlIndex, direction, down, delay]() {
                std::this_thread::sleep_for(std::chrono::milliseconds(delay));
                std::lock_guard<std::mutex> lock(lock_);
                performBinding(controlIndex, direction, down);
            }).detach();
        }
    }
}

void Engine::onMouseMove(int dx, int dy) {
    if (dx == 0 && dy == 0) return;
    const auto now = std::chrono::steady_clock::now();
    const bool hasPendingMotion = pendingMouseDeltaX_ != 0 || pendingMouseDeltaY_ != 0;
    if (!hasPendingMotion &&
        (lastMouseMotionAt_ == std::chrono::steady_clock::time_point{} ||
         now - lastMouseMotionAt_ >= MOUSE_MOVE_BATCH_INTERVAL)) {
        lastMouseMotionAt_ = now;
        processMouseMove(dx, dy);
        return;
    }
    pendingMouseDeltaX_ += dx;
    pendingMouseDeltaY_ += dy;
    lastMouseMotionAt_ = now;
    mouseMotionCv_.notify_one();
}

void Engine::mouseMotionLoop() {
    std::unique_lock<std::mutex> lock(lock_);
    while (mouseMotionRunning_) {
        mouseMotionCv_.wait(lock, [this]() {
            return !mouseMotionRunning_ || pendingMouseDeltaX_ != 0 || pendingMouseDeltaY_ != 0;
        });
        if (!mouseMotionRunning_) break;

        mouseMotionCv_.wait_until(
            lock,
            std::chrono::steady_clock::now() + MOUSE_MOVE_BATCH_INTERVAL,
            [this]() { return !mouseMotionRunning_; }
        );
        if (!mouseMotionRunning_) break;

        const int dx = pendingMouseDeltaX_;
        const int dy = pendingMouseDeltaY_;
        pendingMouseDeltaX_ = 0;
        pendingMouseDeltaY_ = 0;
        processMouseMove(dx, dy);
    }
}

void Engine::processMouseMove(int dx, int dy) {
    if (dx == 0 && dy == 0) return;
    if (mouseAimMode_) {
        if (foreground_.load() && !editorMode_.load() && !paused_.load()) {
            // Find the active MOUSE_LOOK control (first one found) for sensitivity.
            float sensX = 50, sensY = 50;
            int smoothing = 35;
            for (size_t i = 0; i < keymap_.controls.size(); i++) {
                if (keymap_.controls[i].type == ControlType::MouseLook) {
                    sensX = keymap_.controls[i].sensitivityX;
                    sensY = keymap_.controls[i].sensitivityY;
                    smoothing = keymap_.controls[i].smoothing;
                    cameraAnchorX_ = pixelX(keymap_.controls[i]);
                    cameraAnchorY_ = pixelY(keymap_.controls[i]);
                    break;
                }
            }
            float scaleX = (sensX / 100.0f) * MAX_MOUSE_SENSITIVITY;
            float scaleY = (sensY / 100.0f) * MAX_MOUSE_SENSITIVITY;
            float rawDx = dx * scaleX;
            float rawDy = dy * scaleY;
            float smoothFactor = clampf(smoothing / 100.0f, 0.0f, 0.95f);
            smoothedDx_ += (rawDx - smoothedDx_) * (1.0f - smoothFactor);
            smoothedDy_ += (rawDy - smoothedDy_) * (1.0f - smoothFactor);

            if (cameraAnchorX_ == 0 && cameraAnchorY_ == 0) {
                cameraAnchorX_ = screenWidth_ / 2.0f;
                cameraAnchorY_ = screenHeight_ / 2.0f;
            }
            float regionRadius = std::max(MIN_CAMERA_REGION_PX,
                std::min(screenWidth_, screenHeight_) * CAMERA_REGION_FRACTION);
            cameraOffsetX_ += smoothedDx_;
            cameraOffsetY_ += smoothedDy_;
            bool recenter = std::sqrt(cameraOffsetX_ * cameraOffsetX_ + cameraOffsetY_ * cameraOffsetY_) > regionRadius;
            if (cameraFinger_ < 0) {
                cameraFinger_ = fingerDown(cameraAnchorX_, cameraAnchorY_);
                cameraOffsetX_ = 0;
                cameraOffsetY_ = 0;
            } else if (recenter) {
                fingerUp(cameraFinger_);
                cameraOffsetX_ = 0;
                cameraOffsetY_ = 0;
                cameraFinger_ = fingerDown(cameraAnchorX_, cameraAnchorY_);
            } else {
                fingerMove(cameraFinger_, clampf(cameraAnchorX_ + cameraOffsetX_, 0.0f, screenWidth_ - 1.0f),
                           clampf(cameraAnchorY_ + cameraOffsetY_, 0.0f, screenHeight_ - 1.0f));
            }
        }
    } else {
        float cursorSpeed = 3.2f;
        for (const auto &c : keymap_.controls) {
            if (c.type == ControlType::MouseLook) { cursorSpeed = c.cursorSpeed; break; }
        }
        cursorX_ = clampf(cursorX_ + dx * cursorSpeed, 0.0f, screenWidth_ - 1.0f);
        cursorY_ = clampf(cursorY_ + dy * cursorSpeed, 0.0f, screenHeight_ - 1.0f);
        if (cursorFinger_ >= 0) fingerMove(cursorFinger_, cursorX_, cursorY_);
    }

    // MOUSE_DRAG controls follow the raw delta while their key is held.
    for (size_t i = 0; i < keymap_.controls.size(); i++) {
        if (keymap_.controls[i].type != ControlType::MouseDrag) continue;
        ControlRuntime &r = runtime_[i];
        if (r.finger < 0) continue;
        auto it = std::find_if(fingers_.begin(), fingers_.end(),
            [&](const VirtualFinger &f) { return f.id == r.finger; });
        if (it == fingers_.end()) continue;
        float nx = clampf(it->x + dx, 0.0f, screenWidth_ - 1.0f);
        float ny = clampf(it->y + dy, 0.0f, screenHeight_ - 1.0f);
        fingerMove(r.finger, nx, ny);
    }
}

void Engine::onMouseScroll(float vertical, float horizontal) {
    if (!foreground_.load() || editorMode_.load() || paused_.load()) return;
    JNIEnv *env = currentEnv();
    if (!env) return;
    androidApiInjectScroll(env, androidApiUptimeMillis(env), vertical, horizontal, cursorX_, cursorY_);
}

// ---------------------------------------------------------------- raw event dispatch

void Engine::onRawEvent(const RawEvent &event) {
    std::lock_guard<std::mutex> lock(lock_);
    if (!running_) return;
    switch (event.type) {
        case EV_KEY: {
            if (event.value != 0 && event.value != 1) return; // ignore autorepeat (value==2)
            std::string name = linuxKeyName(event.code);
            if (name.empty()) return;
            onKey(name, event.value == 1);
            break;
        }
        case EV_REL:
            switch (event.code) {
                case REL_X: relativeX_ += event.value; break;
                case REL_Y: relativeY_ += event.value; break;
                case REL_WHEEL: wheelVertical_ += event.value; break;
                case REL_HWHEEL: wheelHorizontal_ += event.value; break;
                case REL_WHEEL_HI_RES: wheelVerticalHiRes_ += event.value; break;
                case REL_HWHEEL_HI_RES: wheelHorizontalHiRes_ += event.value; break;
                default: break;
            }
            break;
        case EV_SYN:
            if (event.code == SYN_REPORT) onSyn();
            break;
        default:
            break;
    }
}

void Engine::onSyn() {
    if (relativeX_ != 0 || relativeY_ != 0) {
        onMouseMove(relativeX_, relativeY_);
        relativeX_ = 0;
        relativeY_ = 0;
    }
    float vertical = wheelVerticalHiRes_ != 0 ? wheelVerticalHiRes_ / 120.0f : (float) wheelVertical_;
    float horizontal = wheelHorizontalHiRes_ != 0 ? wheelHorizontalHiRes_ / 120.0f : (float) wheelHorizontal_;
    if (vertical != 0.0f || horizontal != 0.0f) onMouseScroll(vertical, horizontal);
    wheelVertical_ = 0;
    wheelHorizontal_ = 0;
    wheelVerticalHiRes_ = 0;
    wheelHorizontalHiRes_ = 0;
}

// ---------------------------------------------------------------- foreground polling

void Engine::focusLoop() {
    JNIEnv *env = androidApiAttachCurrentThread("MapeadorFocus");
    while (focusRunning_) {
        bool nowForeground = false;
        if (!packageName_.empty()) {
            std::string command = "dumpsys activity activities 2>/dev/null | grep -m1 -E "
                "'(mResumedActivity|topResumedActivity|mFocusedApp)'";
            FILE *pipe = popen(command.c_str(), "r");
            if (pipe) {
                char buffer[512];
                std::string output;
                while (fgets(buffer, sizeof(buffer), pipe)) output += buffer;
                pclose(pipe);
                nowForeground = output.find(packageName_) != std::string::npos;
            }
        }
        setForeground(nowForeground);
        std::this_thread::sleep_for(std::chrono::milliseconds(nowForeground ? 700 : 1500));
    }
    (void) env;
    androidApiDetachCurrentThread();
}

} // namespace mapeador

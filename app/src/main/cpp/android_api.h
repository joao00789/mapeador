// android_api.h — the only place in the engine that talks to the JVM/Android
// framework. Everything else (evdev reading, keymap logic, engine state) is
// plain C++ and knows nothing about JNI.
#pragma once

#include <jni.h>
#include <string>

namespace mapeador {

// Call once from JNI_OnLoad.
void androidApiSetJavaVM(JavaVM *vm);

// Attaches the calling native thread to the JVM (idempotent) and returns a
// valid JNIEnv*, or nullptr on failure. Call detachCurrentThread() when the
// thread that attached is about to exit.
JNIEnv *androidApiAttachCurrentThread(const char *threadName);
void androidApiDetachCurrentThread();

// Resolves the hidden InputManager#injectInputEvent path (InputManager,
// InputManagerGlobal or IInputManager$Stub, in that order, mirroring what the
// previous Kotlin implementation did). Must be called once with a valid env
// before any inject* call. Returns a human readable description of the path
// used, or an empty string if no injection path was found.
std::string androidApiResolveInjection(JNIEnv *env);

bool androidApiInjectionAvailable();

// Injects a multi-touch MotionEvent with `pointerCount` active fingers.
// ids/xs/ys must each have pointerCount entries. action is a raw
// MotionEvent.ACTION_* int (already combined with the pointer index for
// ACTION_POINTER_DOWN/UP as needed by the caller).
bool androidApiInjectTouch(JNIEnv *env, int64_t downTimeMs, int64_t eventTimeMs, int action,
                           int pointerCount, const int *ids, const float *xs, const float *ys);

bool androidApiInjectScroll(JNIEnv *env, int64_t eventTimeMs, float vertical, float horizontal,
                            float x, float y);

// Injects a relative mouse movement (not a touchscreen drag), so movement is
// not constrained by screen edges and does not synthesize tap-like releases.
bool androidApiInjectRelativeMouse(JNIEnv *env, int64_t eventTimeMs, float x, float y,
                                   float deltaX, float deltaY);

bool androidApiMoveMouseCursor(JNIEnv *env, int64_t eventTimeMs, float x, float y);

bool androidApiInjectKey(JNIEnv *env, int64_t downTimeMs, int64_t eventTimeMs, bool down,
                         int androidKeyCode, int repeat, int flags);

// KeyEvent.keyCodeFromString("KEYCODE_" + name). Returns KEYCODE_UNKNOWN (0) if unknown.
int androidApiKeyCodeFromName(JNIEnv *env, const std::string &name);

int64_t androidApiUptimeMillis(JNIEnv *env);

void androidApiLogInfo(const char *tag, const char *message);
void androidApiLogWarn(const char *tag, const char *message);

} // namespace mapeador

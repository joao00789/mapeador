#include "android_api.h"

#include <android/log.h>
#include <mutex>

namespace mapeador {

namespace {

// --- stable Android framework constants (unchanged since API 1 / frozen ABI) ---
constexpr int ACTION_DOWN = 0;
constexpr int ACTION_UP = 1;
constexpr int ACTION_MOVE = 2;
constexpr int ACTION_CANCEL = 3;
constexpr int ACTION_SCROLL = 8;
constexpr int TOOL_TYPE_FINGER = 1;
constexpr int TOOL_TYPE_MOUSE = 3;
constexpr int AXIS_VSCROLL = 9;
constexpr int AXIS_HSCROLL = 10;
constexpr int SOURCE_KEYBOARD = 0x00000101;
constexpr int SOURCE_TOUCHSCREEN = 0x00001002;
constexpr int SOURCE_MOUSE = 0x00002002;

JavaVM *gJavaVm = nullptr;

jclass gMotionEventClass = nullptr;
jclass gPointerPropertiesClass = nullptr;
jclass gPointerCoordsClass = nullptr;
jclass gKeyEventClass = nullptr;
jclass gObjectClass = nullptr;
jclass gIntegerClass = nullptr;

jmethodID gObtainMethod = nullptr;
jmethodID gPointerPropertiesCtor = nullptr;
jmethodID gPointerCoordsCtor = nullptr;
jmethodID gSetAxisValueMethod = nullptr;
jmethodID gKeyEventCtor = nullptr;
jmethodID gKeyCodeFromStringMethod = nullptr;
jmethodID gRecycleMethod = nullptr;
jfieldID gIdField = nullptr;
jfieldID gToolTypeField = nullptr;
jfieldID gXField = nullptr;
jfieldID gYField = nullptr;
jfieldID gPressureField = nullptr;
jfieldID gSizeField = nullptr;
jmethodID gUptimeMillisMethod = nullptr;
jclass gSystemClockClass = nullptr;
jmethodID gIntegerValueOf = nullptr;
jmethodID gMethodInvoke = nullptr;
jmethodID gMethodSetAccessible = nullptr;

jobject gInjectTarget = nullptr;   // global ref: object the reflective method is invoked on (may be null for a static call)
jobject gInjectMethod = nullptr;   // global ref: java.lang.reflect.Method for injectInputEvent
std::string gInjectDescription;
std::string gResolveLog; // human-readable trail of every path tried, for the "Diagnóstico" screen

std::mutex gInitMutex;
bool gBasicsReady = false;

bool clearPendingException(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return false;
}

// Like clearPendingException, but also grabs the exception's toString() (e.g.
// "java.lang.NoSuchMethodException: getInstance []") so resolution failures
// show up with a real reason in the "Diagnóstico" screen instead of silence.
std::string describeAndClearException(JNIEnv *env) {
    if (!env->ExceptionCheck()) return "";
    jthrowable ex = env->ExceptionOccurred();
    env->ExceptionClear();
    if (!ex) return "exceção desconhecida";
    std::string result = "exceção desconhecida";
    jclass throwableClass = env->FindClass("java/lang/Throwable");
    if (throwableClass) {
        jmethodID toStringId = env->GetMethodID(throwableClass, "toString", "()Ljava/lang/String;");
        if (toStringId) {
            auto *msgObj = (jstring) env->CallObjectMethod(ex, toStringId);
            if (msgObj) {
                const char *chars = env->GetStringUTFChars(msgObj, nullptr);
                if (chars) { result = chars; env->ReleaseStringUTFChars(msgObj, chars); }
                env->DeleteLocalRef(msgObj);
            }
        }
        env->DeleteLocalRef(throwableClass);
    }
    env->DeleteLocalRef(ex);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return result;
}

void logResolveStep(const std::string &message) {
    androidApiLogWarn("MapeadorInject", message.c_str());
    if (!gResolveLog.empty()) gResolveLog += " | ";
    gResolveLog += message;
}

jclass findGlobalClass(JNIEnv *env, const char *name) {
    jclass local = env->FindClass(name);
    if (!local) {
        clearPendingException(env);
        return nullptr;
    }
    jclass global = (jclass) env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    return global;
}

bool ensureBasics(JNIEnv *env) {
    std::lock_guard<std::mutex> lock(gInitMutex);
    if (gBasicsReady) return true;

    gMotionEventClass = findGlobalClass(env, "android/view/MotionEvent");
    gPointerPropertiesClass = findGlobalClass(env, "android/view/MotionEvent$PointerProperties");
    gPointerCoordsClass = findGlobalClass(env, "android/view/MotionEvent$PointerCoords");
    gKeyEventClass = findGlobalClass(env, "android/view/KeyEvent");
    gObjectClass = findGlobalClass(env, "java/lang/Object");
    gIntegerClass = findGlobalClass(env, "java/lang/Integer");
    gSystemClockClass = findGlobalClass(env, "android/os/SystemClock");

    if (!gMotionEventClass || !gPointerPropertiesClass || !gPointerCoordsClass ||
        !gKeyEventClass || !gObjectClass || !gIntegerClass || !gSystemClockClass) {
        return false;
    }

    gObtainMethod = env->GetStaticMethodID(gMotionEventClass, "obtain",
        "(JJII[Landroid/view/MotionEvent$PointerProperties;"
        "[Landroid/view/MotionEvent$PointerCoords;IIFFIIII)Landroid/view/MotionEvent;");
    gPointerPropertiesCtor = env->GetMethodID(gPointerPropertiesClass, "<init>", "()V");
    gPointerCoordsCtor = env->GetMethodID(gPointerCoordsClass, "<init>", "()V");
    gSetAxisValueMethod = env->GetMethodID(gPointerCoordsClass, "setAxisValue", "(IF)V");
    gKeyEventCtor = env->GetMethodID(gKeyEventClass, "<init>", "(JJIIIIIIII)V");
    gKeyCodeFromStringMethod = env->GetStaticMethodID(gKeyEventClass, "keyCodeFromString",
        "(Ljava/lang/String;)I");
    gRecycleMethod = env->GetMethodID(gMotionEventClass, "recycle", "()V");
    gIdField = env->GetFieldID(gPointerPropertiesClass, "id", "I");
    gToolTypeField = env->GetFieldID(gPointerPropertiesClass, "toolType", "I");
    gXField = env->GetFieldID(gPointerCoordsClass, "x", "F");
    gYField = env->GetFieldID(gPointerCoordsClass, "y", "F");
    gPressureField = env->GetFieldID(gPointerCoordsClass, "pressure", "F");
    gSizeField = env->GetFieldID(gPointerCoordsClass, "size", "F");
    gUptimeMillisMethod = env->GetStaticMethodID(gSystemClockClass, "uptimeMillis", "()J");
    gIntegerValueOf = env->GetStaticMethodID(gIntegerClass, "valueOf", "(I)Ljava/lang/Integer;");

    jclass methodClass = env->FindClass("java/lang/reflect/Method");
    if (methodClass) {
        gMethodInvoke = env->GetMethodID(methodClass, "invoke",
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
        gMethodSetAccessible = env->GetMethodID(methodClass, "setAccessible", "(Z)V");
        env->DeleteLocalRef(methodClass);
    }
    clearPendingException(env);

    gBasicsReady = gObtainMethod && gPointerPropertiesCtor && gPointerCoordsCtor &&
        gSetAxisValueMethod && gKeyEventCtor && gKeyCodeFromStringMethod &&
        gUptimeMillisMethod && gIntegerValueOf && gMethodInvoke && gMethodSetAccessible;
    return gBasicsReady;
}

// Looks up java.lang.Class for a primitive via its boxed TYPE field, e.g. Integer.TYPE -> int.class.
jobject primitiveClass(JNIEnv *env, const char *boxedName) {
    jclass boxed = env->FindClass(boxedName);
    if (!boxed) { clearPendingException(env); return nullptr; }
    jfieldID typeField = env->GetStaticFieldID(boxed, "TYPE", "Ljava/lang/Class;");
    jobject result = typeField ? env->GetStaticObjectField(boxed, typeField) : nullptr;
    env->DeleteLocalRef(boxed);
    return result;
}

// Resolves java.lang.reflect.Method for name(params...) via Class#getMethod, falling back to
// getDeclaredMethod + setAccessible(true) for hidden/greylisted members.
jobject resolveMethod(JNIEnv *env, jobject classObj, const char *name, jobjectArray paramTypes,
                      bool declaredFallback) {
    jclass classClass = env->GetObjectClass(classObj);
    jmethodID getMethodId = env->GetMethodID(classClass, "getMethod",
        "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
    jstring nameStr = env->NewStringUTF(name);
    jobject method = getMethodId ? env->CallObjectMethod(classObj, getMethodId, nameStr, paramTypes) : nullptr;
    std::string publicFailReason;
    if (env->ExceptionCheck()) { publicFailReason = describeAndClearException(env); method = nullptr; }
    if (!method && declaredFallback) {
        jmethodID getDeclaredId = env->GetMethodID(classClass, "getDeclaredMethod",
            "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
        method = getDeclaredId ? env->CallObjectMethod(classObj, getDeclaredId, nameStr, paramTypes) : nullptr;
        if (env->ExceptionCheck()) {
            std::string declaredFailReason = describeAndClearException(env);
            logResolveStep("getMethod(\"" + std::string(name) + "\") falhou (" + publicFailReason +
                "); getDeclaredMethod(\"" + name + "\") também falhou: " + declaredFailReason);
            method = nullptr;
        }
    } else if (!method) {
        logResolveStep("getMethod(\"" + std::string(name) + "\") falhou: " + publicFailReason);
    }
    env->DeleteLocalRef(nameStr);
    env->DeleteLocalRef(classClass);
    if (method && gMethodSetAccessible) {
        env->CallVoidMethod(method, gMethodSetAccessible, JNI_TRUE);
        if (env->ExceptionCheck()) {
            logResolveStep("setAccessible(true) em \"" + std::string(name) + "\" falhou: " + describeAndClearException(env));
        }
    }
    return method;
}

jobject classForName(JNIEnv *env, const char *dottedName) {
    jclass classClass = env->FindClass("java/lang/Class");
    if (!classClass) { clearPendingException(env); return nullptr; }
    jmethodID forNameId = env->GetStaticMethodID(classClass, "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring nameStr = env->NewStringUTF(dottedName);
    jobject cls = forNameId ? env->CallStaticObjectMethod(classClass, forNameId, nameStr) : nullptr;
    if (clearPendingException(env)) cls = nullptr;
    env->DeleteLocalRef(nameStr);
    env->DeleteLocalRef(classClass);
    return cls;
}

// Tries InputManager#getInstance() / InputManagerGlobal#getInstance() (both hidden statics) and
// resolves injectInputEvent(InputEvent, int) on whichever responds first.
bool tryInputManagerPath(JNIEnv *env, const char *dottedName) {
    jobject cls = classForName(env, dottedName);
    if (!cls) {
        std::string reason = describeAndClearException(env);
        logResolveStep(std::string("[") + dottedName + "] classe não encontrada" +
            (reason.empty() ? "" : (": " + reason)));
        return false;
    }

    jclass classClassForParams = env->FindClass("java/lang/Class");
    jobjectArray emptyParams = env->NewObjectArray(0, classClassForParams, nullptr);
    jobject getInstance = resolveMethod(env, cls, "getInstance", emptyParams, /*declaredFallback=*/true);
    if (!getInstance) {
        logResolveStep(std::string("[") + dottedName + "] método getInstance() não encontrado");
        env->DeleteLocalRef(cls);
        return false;
    }

    jobjectArray emptyArgs = env->NewObjectArray(0, gObjectClass, nullptr);
    jobject instance = env->CallObjectMethod(getInstance, gMethodInvoke, (jobject) nullptr, emptyArgs);
    if (env->ExceptionCheck()) {
        std::string reason = describeAndClearException(env);
        logResolveStep(std::string("[") + dottedName + "] getInstance() lançou exceção: " + reason);
        env->DeleteLocalRef(cls);
        return false;
    }
    if (!instance) {
        logResolveStep(std::string("[") + dottedName + "] getInstance() retornou null");
        env->DeleteLocalRef(cls);
        return false;
    }

    jclass inputEventClass = env->FindClass("android/view/InputEvent");
    jobject intClass = primitiveClass(env, "java/lang/Integer");
    jobjectArray paramTypes = env->NewObjectArray(2, env->FindClass("java/lang/Class"), nullptr);
    env->SetObjectArrayElement(paramTypes, 0, inputEventClass);
    env->SetObjectArrayElement(paramTypes, 1, intClass);

    jobject injectMethod = resolveMethod(env, cls, "injectInputEvent", paramTypes, /*declaredFallback=*/false);
    env->DeleteLocalRef(cls);
    if (!injectMethod) {
        logResolveStep(std::string("[") + dottedName + "] getInstance() funcionou, mas injectInputEvent não foi encontrado");
        return false;
    }

    gInjectTarget = env->NewGlobalRef(instance);
    gInjectMethod = env->NewGlobalRef(injectMethod);
    gInjectDescription = std::string("InputManager reflexivo (") + dottedName + ")";
    return true;
}

bool tryServiceManagerPath(JNIEnv *env) {
    jclass serviceManagerClass = env->FindClass("android/os/ServiceManager");
    if (!serviceManagerClass) {
        logResolveStep("[ServiceManager] classe não encontrada: " + describeAndClearException(env));
        return false;
    }
    jmethodID getServiceId = env->GetStaticMethodID(serviceManagerClass, "getService",
        "(Ljava/lang/String;)Landroid/os/IBinder;");
    if (!getServiceId) {
        logResolveStep("[ServiceManager] getService(String) não encontrado: " + describeAndClearException(env));
        return false;
    }
    jstring inputStr = env->NewStringUTF("input");
    jobject binder = env->CallStaticObjectMethod(serviceManagerClass, getServiceId, inputStr);
    if (env->ExceptionCheck()) {
        logResolveStep("[ServiceManager] getService(\"input\") lançou exceção: " + describeAndClearException(env));
        return false;
    }
    if (!binder) { logResolveStep("[ServiceManager] getService(\"input\") retornou null"); return false; }

    jclass stubClass = env->FindClass("android/hardware/input/IInputManager$Stub");
    if (!stubClass) {
        logResolveStep("[IInputManager.Stub] classe não encontrada: " + describeAndClearException(env));
        return false;
    }
    jmethodID asInterfaceId = env->GetStaticMethodID(stubClass, "asInterface",
        "(Landroid/os/IBinder;)Landroid/hardware/input/IInputManager;");
    if (!asInterfaceId) {
        logResolveStep("[IInputManager.Stub] asInterface não encontrado: " + describeAndClearException(env));
        return false;
    }
    jobject service = env->CallStaticObjectMethod(stubClass, asInterfaceId, binder);
    if (env->ExceptionCheck()) {
        logResolveStep("[IInputManager.Stub] asInterface lançou exceção: " + describeAndClearException(env));
        return false;
    }
    if (!service) { logResolveStep("[IInputManager.Stub] asInterface retornou null"); return false; }

    jclass inputEventClass = env->FindClass("android/view/InputEvent");
    jobject intClass = primitiveClass(env, "java/lang/Integer");
    jobjectArray paramTypes = env->NewObjectArray(2, env->FindClass("java/lang/Class"), nullptr);
    env->SetObjectArrayElement(paramTypes, 0, inputEventClass);
    env->SetObjectArrayElement(paramTypes, 1, intClass);

    jclass serviceClass = env->GetObjectClass(service);
    jobject injectMethod = resolveMethod(env, serviceClass, "injectInputEvent", paramTypes, true);
    if (!injectMethod) {
        logResolveStep("[IInputManager.Stub] serviço obtido, mas injectInputEvent não foi encontrado");
        return false;
    }

    gInjectTarget = env->NewGlobalRef(service);
    gInjectMethod = env->NewGlobalRef(injectMethod);
    gInjectDescription = "IInputManager via ServiceManager";
    return true;
}

jobject buildMotionEvent(JNIEnv *env, int64_t downTimeMs, int64_t eventTimeMs, int action,
                         int pointerCount, const int *ids, const float *xs, const float *ys,
                         int source, bool scroll, float vertical, float horizontal) {
    jobjectArray properties = env->NewObjectArray(pointerCount, gPointerPropertiesClass, nullptr);
    jobjectArray coords = env->NewObjectArray(pointerCount, gPointerCoordsClass, nullptr);
    for (int i = 0; i < pointerCount; i++) {
        jobject prop = env->NewObject(gPointerPropertiesClass, gPointerPropertiesCtor);
        env->SetIntField(prop, gIdField, ids[i]);
        env->SetIntField(prop, gToolTypeField, scroll ? TOOL_TYPE_MOUSE : TOOL_TYPE_FINGER);
        env->SetObjectArrayElement(properties, i, prop);
        env->DeleteLocalRef(prop);

        jobject coord = env->NewObject(gPointerCoordsClass, gPointerCoordsCtor);
        env->SetFloatField(coord, gXField, xs[i]);
        env->SetFloatField(coord, gYField, ys[i]);
        env->SetFloatField(coord, gPressureField, scroll ? 0.0f : 1.0f);
        env->SetFloatField(coord, gSizeField, scroll ? 0.0f : 1.0f);
        if (scroll) {
            env->CallVoidMethod(coord, gSetAxisValueMethod, AXIS_VSCROLL, vertical);
            env->CallVoidMethod(coord, gSetAxisValueMethod, AXIS_HSCROLL, horizontal);
        }
        env->SetObjectArrayElement(coords, i, coord);
        env->DeleteLocalRef(coord);
    }

    jobject event = env->CallStaticObjectMethod(gMotionEventClass, gObtainMethod,
        (jlong) downTimeMs, (jlong) eventTimeMs, action, pointerCount, properties, coords,
        0, 0, 1.0f, 1.0f, 0, 0, source, 0);
    env->DeleteLocalRef(properties);
    env->DeleteLocalRef(coords);
    return event;
}

bool invokeInject(JNIEnv *env, jobject event) {
    if (!gInjectMethod || !event) return false;
    jobjectArray args = env->NewObjectArray(2, gObjectClass, nullptr);
    jobject modeBoxed = env->CallStaticObjectMethod(gIntegerClass, gIntegerValueOf, 0);
    env->SetObjectArrayElement(args, 0, event);
    env->SetObjectArrayElement(args, 1, modeBoxed);
    env->CallObjectMethod(gInjectMethod, gMethodInvoke, gInjectTarget, args);
    bool failed = clearPendingException(env);
    env->DeleteLocalRef(args);
    env->DeleteLocalRef(modeBoxed);
    if (gRecycleMethod) env->CallVoidMethod(event, gRecycleMethod);
    return !failed;
}

} // namespace

void androidApiSetJavaVM(JavaVM *vm) { gJavaVm = vm; }

namespace {
// Tracks, per-thread, whether *we* attached it (vs. it already being a JVM
// thread, e.g. a Binder pool thread). We must never detach a thread we did
// not attach ourselves.
thread_local bool tlAttachedByUs = false;
} // namespace

JNIEnv *androidApiAttachCurrentThread(const char *threadName) {
    if (!gJavaVm) return nullptr;
    JNIEnv *env = nullptr;
    jint result = gJavaVm->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (result == JNI_OK) return env;
    JavaVMAttachArgs args{JNI_VERSION_1_6, threadName, nullptr};
    if (gJavaVm->AttachCurrentThread(&env, &args) != JNI_OK) return nullptr;
    tlAttachedByUs = true;
    return env;
}

void androidApiDetachCurrentThread() {
    if (gJavaVm && tlAttachedByUs) {
        gJavaVm->DetachCurrentThread();
        tlAttachedByUs = false;
    }
}

std::string androidApiResolveInjection(JNIEnv *env) {
    if (!ensureBasics(env)) return "FALHA: APIs básicas do MotionEvent/KeyEvent não resolveram (ver logcat tag MapeadorInject)";
    if (gInjectMethod) return gInjectDescription;

    gResolveLog.clear();
    if (tryInputManagerPath(env, "android.hardware.input.InputManager")) return gInjectDescription;
    if (tryInputManagerPath(env, "android.hardware.input.InputManagerGlobal")) return gInjectDescription;
    if (tryServiceManagerPath(env)) return gInjectDescription;
    return "FALHA (nenhum dos 3 caminhos funcionou): " + gResolveLog;
}

bool androidApiInjectionAvailable() { return gInjectMethod != nullptr; }

bool androidApiInjectTouch(JNIEnv *env, int64_t downTimeMs, int64_t eventTimeMs, int action,
                           int pointerCount, const int *ids, const float *xs, const float *ys) {
    if (!gBasicsReady || !gInjectMethod || pointerCount <= 0) return false;
    jobject event = buildMotionEvent(env, downTimeMs, eventTimeMs, action, pointerCount, ids, xs, ys,
        SOURCE_TOUCHSCREEN, false, 0, 0);
    if (!event) return false;
    bool ok = invokeInject(env, event);
    env->DeleteLocalRef(event);
    return ok;
}

bool androidApiInjectScroll(JNIEnv *env, int64_t eventTimeMs, float vertical, float horizontal,
                            float x, float y) {
    if (!gBasicsReady || !gInjectMethod) return false;
    int id = 0;
    jobject event = buildMotionEvent(env, eventTimeMs, eventTimeMs, ACTION_SCROLL, 1, &id, &x, &y,
        SOURCE_MOUSE, true, vertical, horizontal);
    if (!event) return false;
    bool ok = invokeInject(env, event);
    env->DeleteLocalRef(event);
    return ok;
}

bool androidApiInjectKey(JNIEnv *env, int64_t downTimeMs, int64_t eventTimeMs, bool down,
                         int androidKeyCode, int repeat, int flags) {
    if (!gBasicsReady || !gInjectMethod) return false;
    jobject event = env->NewObject(gKeyEventClass, gKeyEventCtor,
        (jlong) downTimeMs, (jlong) eventTimeMs, down ? ACTION_DOWN : ACTION_UP,
        androidKeyCode, repeat, 0, 0, 0, flags, SOURCE_KEYBOARD);
    if (!event) { clearPendingException(env); return false; }
    bool ok = invokeInject(env, event);
    env->DeleteLocalRef(event);
    return ok;
}

int androidApiKeyCodeFromName(JNIEnv *env, const std::string &name) {
    if (!ensureBasics(env)) return 0;
    jstring str = env->NewStringUTF(("KEYCODE_" + name).c_str());
    jint code = env->CallStaticIntMethod(gKeyEventClass, gKeyCodeFromStringMethod, str);
    env->DeleteLocalRef(str);
    if (clearPendingException(env)) return 0;
    return code;
}

int64_t androidApiUptimeMillis(JNIEnv *env) {
    if (!ensureBasics(env)) return 0;
    return (int64_t) env->CallStaticLongMethod(gSystemClockClass, gUptimeMillisMethod);
}

void androidApiLogInfo(const char *tag, const char *message) {
    __android_log_print(ANDROID_LOG_INFO, tag, "%s", message);
}

void androidApiLogWarn(const char *tag, const char *message) {
    __android_log_print(ANDROID_LOG_WARN, tag, "%s", message);
}

} // namespace mapeador

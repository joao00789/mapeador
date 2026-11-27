// jni_bridge.cpp — the only file the JVM calls into directly. It just
// forwards each call to an Engine instance; all real logic lives in engine.cpp.
#include <jni.h>
#include "engine.h"
#include "android_api.h"

using mapeador::Engine;

namespace {
Engine *handle(jlong h) { return reinterpret_cast<Engine *>(h); }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    mapeador::androidApiSetJavaVM(vm);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_mapeador_NativeEngine_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new Engine());
}

JNIEXPORT void JNICALL
Java_com_mapeador_NativeEngine_nativeDestroy(JNIEnv *, jobject, jlong h) {
    delete handle(h);
}

JNIEXPORT void JNICALL
Java_com_mapeador_NativeEngine_nativeUpdateKeymap(JNIEnv *env, jobject, jlong h, jstring keymapJson) {
    const char *chars = env->GetStringUTFChars(keymapJson, nullptr);
    handle(h)->updateKeymap(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(keymapJson, chars);
}

JNIEXPORT void JNICALL
Java_com_mapeador_NativeEngine_nativeStart(JNIEnv *env, jobject, jlong h, jstring packageName,
                                           jint screenWidth, jint screenHeight) {
    const char *chars = env->GetStringUTFChars(packageName, nullptr);
    handle(h)->start(chars ? chars : "", screenWidth, screenHeight);
    if (chars) env->ReleaseStringUTFChars(packageName, chars);
}

JNIEXPORT void JNICALL
Java_com_mapeador_NativeEngine_nativeStop(JNIEnv *, jobject, jlong h) {
    handle(h)->stop();
}

JNIEXPORT void JNICALL
Java_com_mapeador_NativeEngine_nativeSetForeground(JNIEnv *, jobject, jlong h, jboolean foreground) {
    handle(h)->setForeground(foreground == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_mapeador_NativeEngine_nativeSetEditorMode(JNIEnv *, jobject, jlong h, jboolean enabled) {
    handle(h)->setEditorMode(enabled == JNI_TRUE);
}

JNIEXPORT jstring JNICALL
Java_com_mapeador_NativeEngine_nativeDiagnostics(JNIEnv *env, jobject, jlong h) {
    std::string json = handle(h)->diagnostics();
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jfloatArray JNICALL
Java_com_mapeador_NativeEngine_nativeCursorPosition(JNIEnv *env, jobject, jlong h) {
    float x = 0, y = 0;
    bool aim = false;
    handle(h)->cursorPositionPx(&x, &y, &aim);
    jfloatArray result = env->NewFloatArray(3);
    jfloat values[3] = {x, y, aim ? 1.0f : 0.0f};
    env->SetFloatArrayRegion(result, 0, 3, values);
    return result;
}

} // extern "C"

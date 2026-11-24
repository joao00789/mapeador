#include <jni.h>
#include <errno.h>
#include <linux/input.h>
#include <sys/ioctl.h>

JNIEXPORT jint JNICALL
Java_com_mapeador_NativeMouseGrab_grab(JNIEnv *env, jobject thiz, jobject file_descriptor,
                                       jboolean enabled) {
    (void) thiz;
    if (file_descriptor == NULL) return -EBADF;

    jclass fd_class = (*env)->GetObjectClass(env, file_descriptor);
    jfieldID descriptor_field = (*env)->GetFieldID(env, fd_class, "descriptor", "I");
    if (descriptor_field == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        jmethodID get_int = (*env)->GetMethodID(env, fd_class, "getInt$", "()I");
        if (get_int == NULL) return -ENOSYS;
        jint fd = (*env)->CallIntMethod(env, file_descriptor, get_int);
        if ((*env)->ExceptionCheck(env)) return -EIO;
        if (fd < 0) return -EBADF;
        int grab = enabled ? 1 : 0;
        if (ioctl(fd, EVIOCGRAB, &grab) < 0) return -errno;
        return 0;
    }

    jint fd = (*env)->GetIntField(env, file_descriptor, descriptor_field);
    if (fd < 0) return -EBADF;
    int grab = enabled ? 1 : 0;
    if (ioctl(fd, EVIOCGRAB, &grab) < 0) return -errno;
    return 0;
}

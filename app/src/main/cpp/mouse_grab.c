#include <jni.h>
#include <errno.h>
#include <linux/input.h>
#include <stdio.h>
#include <sys/ioctl.h>

static int get_fd(JNIEnv *env, jobject file_descriptor) {
    if (file_descriptor == NULL) return -EBADF;

    jclass fd_class = (*env)->GetObjectClass(env, file_descriptor);
    jfieldID descriptor_field = (*env)->GetFieldID(env, fd_class, "descriptor", "I");
    if (descriptor_field != NULL) {
        jint fd = (*env)->GetIntField(env, file_descriptor, descriptor_field);
        return fd < 0 ? -EBADF : fd;
    }

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    jmethodID get_int = (*env)->GetMethodID(env, fd_class, "getInt$", "()I");
    if (get_int == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return -ENOSYS;
    }
    jint fd = (*env)->CallIntMethod(env, file_descriptor, get_int);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return -EIO;
    }
    return fd < 0 ? -EBADF : fd;
}

static int has_bit(const unsigned long *bits, unsigned int bit) {
    const unsigned int bits_per_word = (unsigned int) (sizeof(unsigned long) * 8);
    return (bits[bit / bits_per_word] & (1UL << (bit % bits_per_word))) != 0;
}

/*
 * Return bit 0 for a mouse-like device, bit 1 for relative X/Y and bit 2 for a keyboard.
 * The event-node names in /proc/bus/input/devices vary across Android vendors,
 * so inspect the kernel capabilities instead of guessing from the product name.
 */
JNIEXPORT jint JNICALL
Java_com_mapeador_NativeMouseGrab_capabilities(JNIEnv *env, jobject thiz, jobject file_descriptor) {
    (void) thiz;
    int fd = get_fd(env, file_descriptor);
    if (fd < 0) return fd;

    unsigned long event_bits[8] = {0};
    if (ioctl(fd, EVIOCGBIT(0, sizeof(event_bits)), event_bits) < 0) return -errno;
    const int has_relative = has_bit(event_bits, EV_REL);
    const int has_keys = has_bit(event_bits, EV_KEY);

    unsigned long relative_bits[8] = {0};
    const int has_xy = has_relative &&
        ioctl(fd, EVIOCGBIT(EV_REL, sizeof(relative_bits)), relative_bits) >= 0 &&
        has_bit(relative_bits, REL_X) && has_bit(relative_bits, REL_Y);

    unsigned long key_bits[32] = {0};
    const int has_mouse_button = has_keys &&
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits) >= 0 &&
        (has_bit(key_bits, BTN_LEFT) || has_bit(key_bits, BTN_RIGHT) ||
         has_bit(key_bits, BTN_MIDDLE) || has_bit(key_bits, BTN_SIDE) ||
         has_bit(key_bits, BTN_EXTRA));
    const int has_keyboard = has_keys &&
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits) >= 0 &&
        (has_bit(key_bits, KEY_A) || has_bit(key_bits, KEY_ENTER) ||
         has_bit(key_bits, KEY_SPACE));

    if (!has_xy && !has_mouse_button && !has_keyboard) return 0;
    return ((has_xy || has_mouse_button) ? 1 : 0) |
        (has_xy ? 2 : 0) |
        (has_keyboard ? 4 : 0);
}

JNIEXPORT jstring JNICALL
Java_com_mapeador_NativeMouseGrab_identity(JNIEnv *env, jobject thiz, jobject file_descriptor) {
    (void) thiz;
    int fd = get_fd(env, file_descriptor);
    if (fd < 0) return NULL;

    char phys[256] = {0};
    char uniq[256] = {0};
    char name[256] = {0};
    struct input_id id = {0};
    ioctl(fd, EVIOCGPHYS(sizeof(phys)), phys);
    ioctl(fd, EVIOCGUNIQ(sizeof(uniq)), uniq);
    ioctl(fd, EVIOCGNAME(sizeof(name)), name);
    ioctl(fd, EVIOCGID, &id);

    char identity[768] = {0};
    if (phys[0] != '\0') {
        snprintf(identity, sizeof(identity), "phys:%s", phys);
    } else if (uniq[0] != '\0') {
        snprintf(identity, sizeof(identity), "uniq:%s", uniq);
    } else {
        snprintf(identity, sizeof(identity), "device:%04x:%04x:%04x:%s",
                 id.bustype, id.vendor, id.product, name);
    }
    return (*env)->NewStringUTF(env, identity);
}

JNIEXPORT jint JNICALL
Java_com_mapeador_NativeMouseGrab_grab(JNIEnv *env, jobject thiz, jobject file_descriptor,
                                       jboolean enabled) {
    (void) thiz;
    int fd = get_fd(env, file_descriptor);
    if (fd < 0) return fd;
    if (ioctl(fd, EVIOCGRAB, enabled ? 1 : 0) < 0) return -errno;
    return 0;
}

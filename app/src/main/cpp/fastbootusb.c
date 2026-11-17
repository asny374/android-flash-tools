#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>

#include "libusb.h"

#define LOG_TAG "FastbootUsb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * 非 root 接入方式（libusb 官方 android/examples/unrooted_android.c 的做法）：
 *   Java 侧 usbManager.openDevice() 拿到 UsbDeviceConnection
 *   取其 getFileDescriptor() 的 fd 传进来
 *   libusb_init + libusb_wrap_sys_device 把 usbfs fd 包装成 libusb handle
 *
 * 这样完全绕开 Android 的 Java USB 栈，走 libusb 自己的 URB 与事件循环，
 * 也就是 CableBee 那个 App 走的路子。
 */

typedef struct {
    libusb_context *ctx;
    libusb_device_handle *handle;
    int dup_fd;
} usb_conn;

JNIEXPORT jstring JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeVersion(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, "libusb 1.0.27 (static)");
}

static void android_log_cb(libusb_context *ctx, enum libusb_log_level level, const char *str) {
    int prio;
    switch (level) {
        case LIBUSB_LOG_LEVEL_ERROR:   prio = ANDROID_LOG_ERROR; break;
        case LIBUSB_LOG_LEVEL_WARNING: prio = ANDROID_LOG_WARN;  break;
        case LIBUSB_LOG_LEVEL_INFO:    prio = ANDROID_LOG_INFO;  break;
        default:                       prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_print(prio, "libusb", "%s", str);
}

JNIEXPORT jlong JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeOpen(JNIEnv *env, jclass clazz, jint fd) {
    if (fd < 0) {
        LOGE("invalid fd: %d", fd);
        return 0;
    }

    /*
     * 关键修正：NO_DEVICE_DISCOVERY 必须在 libusb_init 之前设置（ctx 传 NULL）。
     * 之前写在 init 之后，实际是无效的 —— 没有 root 时 libusb 会去枚举
     * /dev/bus/usb，权限不足会让后续初始化路径异常。
     */
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, 1);
    libusb_set_option(NULL, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_INFO);

    libusb_context *ctx = NULL;
    int r = libusb_init(&ctx);
    if (r != 0) {
        LOGE("libusb_init failed: %d", r);
        return 0;
    }

    libusb_set_log_cb(ctx, android_log_cb, LIBUSB_LOG_CB_CONTEXT);

    /*
     * 复制一份 fd 交给 libusb 独立使用。
     * UsbDeviceConnection 持有的原始 fd 可能被它自己的生命周期影响，
     * dup 出来的这份只归 libusb 所有，避免两者互相干扰。
     */
    int dupFd = dup(fd);
    if (dupFd < 0) {
        LOGE("dup(fd) failed: %d", dupFd);
        libusb_exit(ctx);
        return 0;
    }
    LOGI("fd %d duplicated to %d", fd, dupFd);

    libusb_device_handle *handle = NULL;
    r = libusb_wrap_sys_device(ctx, (intptr_t) dupFd, &handle);
    if (r != 0 || handle == NULL) {
        LOGE("libusb_wrap_sys_device failed: %d", r);
        close(dupFd);
        libusb_exit(ctx);
        return 0;
    }

    // claim 时自动解绑内核驱动：设备在 fastboot 模式下一般没有驱动绑定，
    // 但部分主机会挂载一个占位驱动，不放开这一项 claim 会返回 BUSY
    int detach = libusb_set_auto_detach_kernel_driver(handle, 1);
    LOGI("auto_detach_kernel_driver -> %d", detach);

    usb_conn *conn = (usb_conn *) calloc(1, sizeof(usb_conn));
    if (conn == NULL) {
        libusb_close(handle);
        close(dupFd);
        libusb_exit(ctx);
        return 0;
    }
    conn->ctx = ctx;
    conn->handle = handle;
    conn->dup_fd = dupFd;

    LOGI("libusb handle ready");
    return (jlong) (intptr_t) conn;
}

JNIEXPORT jint JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeClaim(JNIEnv *env, jclass clazz,
                                                  jlong ptr, jint interfaceNumber) {
    usb_conn *conn = (usb_conn *) (intptr_t) ptr;
    if (conn == NULL || conn->handle == NULL) return -1000;
    int r = libusb_claim_interface(conn->handle, interfaceNumber);
    LOGI("claim_interface(%d) -> %d", interfaceNumber, r);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeRelease(JNIEnv *env, jclass clazz,
                                                    jlong ptr, jint interfaceNumber) {
    usb_conn *conn = (usb_conn *) (intptr_t) ptr;
    if (conn == NULL || conn->handle == NULL) return -1000;
    return libusb_release_interface(conn->handle, interfaceNumber);
}

JNIEXPORT jint JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeClearHalt(JNIEnv *env, jclass clazz,
                                                      jlong ptr, jint endpoint) {
    usb_conn *conn = (usb_conn *) (intptr_t) ptr;
    if (conn == NULL || conn->handle == NULL) return -1000;
    return libusb_clear_halt(conn->handle, (unsigned char) endpoint);
}

JNIEXPORT jint JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeReset(JNIEnv *env, jclass clazz, jlong ptr) {
    usb_conn *conn = (usb_conn *) (intptr_t) ptr;
    if (conn == NULL || conn->handle == NULL) return -1000;
    return libusb_reset_device(conn->handle);
}

/*
 * 返回值约定：
 *   >= 0  实际传输字节数
 *   < 0   libusb 错误码原样返回（如 -7 = LIBUSB_ERROR_TIMEOUT）
 */
JNIEXPORT jint JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeBulkOut(JNIEnv *env, jclass clazz,
                                                    jlong ptr, jint endpoint,
                                                    jbyteArray data, jint offset,
                                                    jint length, jint timeoutMs) {
    usb_conn *conn = (usb_conn *) (intptr_t) ptr;
    if (conn == NULL || conn->handle == NULL) return -1000;

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) return -1001;

    int transferred = 0;
    int r = libusb_bulk_transfer(conn->handle, (unsigned char) endpoint,
                                 (unsigned char *) (buf + offset), (int) length,
                                 &transferred, (unsigned int) timeoutMs);

    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);

    if (r != 0) return r;
    return transferred;
}

JNIEXPORT jint JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeBulkIn(JNIEnv *env, jclass clazz,
                                                   jlong ptr, jint endpoint,
                                                   jbyteArray buffer, jint offset,
                                                   jint length, jint timeoutMs) {
    usb_conn *conn = (usb_conn *) (intptr_t) ptr;
    if (conn == NULL || conn->handle == NULL) return -1000;

    unsigned char *tmp = (unsigned char *) malloc((size_t) length);
    if (tmp == NULL) return -1001;

    int transferred = 0;
    int r = libusb_bulk_transfer(conn->handle, (unsigned char) endpoint,
                                 tmp, (int) length, &transferred,
                                 (unsigned int) timeoutMs);

    if ((r == 0 || r == LIBUSB_ERROR_TIMEOUT) && transferred > 0) {
        (*env)->SetByteArrayRegion(env, buffer, offset, transferred, (jbyte *) tmp);
    }
    free(tmp);

    if (r == LIBUSB_ERROR_TIMEOUT && transferred > 0) return transferred;
    if (r != 0) return r;
    return transferred;
}

JNIEXPORT jint JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeControl(JNIEnv *env, jclass clazz,
                                                    jlong ptr, jint requestType,
                                                    jint request, jint value, jint index,
                                                    jbyteArray data, jint length,
                                                    jint timeoutMs) {
    usb_conn *conn = (usb_conn *) (intptr_t) ptr;
    if (conn == NULL || conn->handle == NULL) return -1000;

    unsigned char *buf = NULL;
    jbyte *javaBuf = NULL;
    if (data != NULL && length > 0) {
        if ((requestType & 0x80) != 0) {
            // device-to-host，需要一块接收缓冲
            buf = (unsigned char *) calloc(1, (size_t) length);
            if (buf == NULL) return -1001;
        } else {
            javaBuf = (*env)->GetByteArrayElements(env, data, NULL);
            if (javaBuf == NULL) return -1001;
            buf = (unsigned char *) javaBuf;
        }
    }

    int r = libusb_control_transfer(conn->handle,
                                    (uint8_t) requestType, (uint8_t) request,
                                    (uint16_t) value, (uint16_t) index,
                                    buf, (uint16_t) length, (unsigned int) timeoutMs);

    if (javaBuf != NULL) {
        (*env)->ReleaseByteArrayElements(env, data, javaBuf, JNI_ABORT);
    }
    if (buf != NULL && (requestType & 0x80) != 0) {
        if (r > 0) {
            (*env)->SetByteArrayRegion(env, data, 0, r, (jbyte *) buf);
        }
        free(buf);
    }

    return r;
}

JNIEXPORT void JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeClose(JNIEnv *env, jclass clazz, jlong ptr) {
    usb_conn *conn = (usb_conn *) (intptr_t) ptr;
    if (conn == NULL) return;

    if (conn->handle != NULL) {
        libusb_release_interface(conn->handle, 0);
        libusb_close(conn->handle);
    }
    if (conn->dup_fd >= 0) {
        close(conn->dup_fd);
    }
    if (conn->ctx != NULL) {
        libusb_exit(conn->ctx);
    }
    free(conn);
    LOGI("libusb handle closed");
}

JNIEXPORT jstring JNICALL
Java_com_dz_fastboototg_usb_NativeUsb_nativeErrorText(JNIEnv *env, jclass clazz, jint code) {
    const char *text = libusb_error_name(code);
    return (*env)->NewStringUTF(env, text != NULL ? text : "UNKNOWN");
}

cmake_minimum_required(VERSION 3.22.1)
project(fastbootusb C)

set(CMAKE_C_STANDARD 99)

set(LIBUSB ${CMAKE_CURRENT_SOURCE_DIR}/libusb)

# libusb 1.0.27 官方没有 CMakeLists，这里手工列出 Linux/Android 后端所需的源文件。
# linux_udev.c 不编译：Android 没有 udev，走 usbfs + netlink 即可。
add_library(fastbootusb SHARED
        fastbootusb.c
        ${LIBUSB}/core.c
        ${LIBUSB}/descriptor.c
        ${LIBUSB}/hotplug.c
        ${LIBUSB}/io.c
        ${LIBUSB}/strerror.c
        ${LIBUSB}/sync.c
        ${LIBUSB}/os/events_posix.c
        ${LIBUSB}/os/threads_posix.c
        ${LIBUSB}/os/linux_netlink.c
        ${LIBUSB}/os/linux_usbfs.c)

target_include_directories(fastbootusb PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${LIBUSB}
        ${LIBUSB}/os)

# HAVE_CONFIG_H 让 libusb 源文件去 include 我们放在 cpp 根目录的 config.h，
# 那份是从 libusb/android/config.h 复制来的官方 Android 配置。
target_compile_definitions(fastbootusb PRIVATE
        HAVE_CONFIG_H
        _GNU_SOURCE)

find_library(log-lib log)
target_link_libraries(fastbootusb ${log-lib})

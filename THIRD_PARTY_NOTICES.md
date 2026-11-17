# Third-party notices

No project-wide license has been selected for original application code.

- libusb 1.0.27: vendored under app/src/main/cpp/libusb, LGPL-2.1-or-later. Original copyright headers retained; license text in licenses/libusb-COPYING. Upstream: https://github.com/libusb/libusb/tree/v1.0.27 . The JNI shared library statically includes libusb sources; retain corresponding source and review distribution requirements before shipping binaries.
- Apache Commons Compress 1.27.1: Apache-2.0, https://commons.apache.org/proper/commons-compress/ . License and notices in licenses/commons-compress-LICENSE and licenses/commons-compress-NOTICE.
- AndroidX / Compose, Kotlin / coroutines, Gradle wrapper: preserve upstream notices in their distributed artifacts. These dependencies are resolved by Gradle; vendored wrapper JAR is retained.

This list documents major included components, not a legal certification or exhaustive transitive dependency audit.

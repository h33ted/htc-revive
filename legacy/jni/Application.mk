# Sense 1/2 phones: ARMv6 CPUs (armeabi) on Android 2.2+, built with NDK r13b and GCC 4.9.
# android-9 is r13b's oldest platform; the library also loads on Android 2.2 (API 8): its
# imports were checked against an HTC Wildfire's libc, and it runs there.
APP_ABI := armeabi
APP_PLATFORM := android-9
APP_BUILD_SCRIPT := Android.mk
NDK_TOOLCHAIN_VERSION := 4.9

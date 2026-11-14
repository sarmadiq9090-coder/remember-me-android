#!/usr/bin/env bash
# Local build environment. JDK, SDK and Gradle are reused read-only from kiwi-scan's toolchain;
# this repo keeps its own Gradle caches so the two builds never share them.
export RM="/e/fast kiwi pro neq/remember-me-android"
TOOLS="E:\fast kiwi pro neq\kiwi-scan\.tools"
export JAVA_HOME="$TOOLS\jdk-21.0.13+9"
export ANDROID_HOME="$TOOLS\android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="E:\fast kiwi pro neq\remember-me-android\.tools\android-user"
export GRADLE_USER_HOME="E:\fast kiwi pro neq\remember-me-android\.gradle-home"
KS="/e/fast kiwi pro neq/kiwi-scan"
export PATH="$KS/.tools/jdk-21.0.13+9/bin:$KS/.tools/android-sdk/build-tools/36.0.0:$KS/.tools/gradle-8.14.3/bin:$PATH"

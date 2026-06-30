#!/bin/sh
# Cross-compile r08waked for arm64 Android using the NDK clang.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)
[ -n "$NDK" ] || { echo "NDK not found under $SDK/ndk (set ANDROID_SDK_ROOT)"; exit 1; }
HOST=$(ls -d "$NDK"/toolchains/llvm/prebuilt/* 2>/dev/null | head -1)
# lowest API target for max device compatibility (we only use API21 libc)
CC=$(ls "$HOST"/bin/aarch64-linux-android*-clang 2>/dev/null | sort -V | head -1)
[ -n "$CC" ] || { echo "aarch64 clang not found in $HOST"; exit 1; }

echo "CC=$CC"
"$CC" -O2 -Wall -o r08waked r08waked.c
echo "built: $(file r08waked)"

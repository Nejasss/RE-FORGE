#!/usr/bin/env bash
set -euo pipefail

R2_VERSION="${R2_VERSION:-5.9.8}"
NDK_ROOT="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must be set}"
OUT_JNILIBS_RAW="${1:?Usage: build-radare2-arm64.sh <output_jniLibs_arm64_dir>}"

mkdir -p "$OUT_JNILIBS_RAW"
OUT_JNILIBS="$(cd "$OUT_JNILIBS_RAW" && pwd)"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

cd "$WORK_DIR"

curl -fsSL "https://github.com/radareorg/radare2/archive/refs/tags/${R2_VERSION}.tar.gz" -o r2.tar.gz
tar -xzf r2.tar.gz
cd "radare2-${R2_VERSION}"

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
export CC="$TOOLCHAIN/bin/aarch64-linux-android26-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android26-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"

cat > "$WORK_DIR/android-arm64.ini" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkgconfig = 'false'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

meson setup build_arm64 \
  --cross-file "$WORK_DIR/android-arm64.ini" \
  --buildtype=release \
  -Ddefault_library=static \
  -Duse_sys_capstone=false \
  -Duse_sys_magic=false \
  -Duse_sys_zip=false \
  -Duse_sys_zlib=false \
  -Duse_sys_lz4=false \
  -Duse_sys_xxhash=false \
  -Duse_sys_openssl=false \
  -Denable_tests=false \
  -Denable_r2r=false

ninja -C build_arm64 binr/radare2/radare2
"$STRIP" --strip-unneeded build_arm64/binr/radare2/radare2
cp build_arm64/binr/radare2/radare2 "$OUT_JNILIBS/libr2.so"
chmod 755 "$OUT_JNILIBS/libr2.so"
echo "Built ARM64 Radare2 binary at $OUT_JNILIBS/libr2.so"

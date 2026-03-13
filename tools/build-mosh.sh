#!/usr/bin/env bash
#
# Cross-compile mosh-client for Android (arm64-v8a, x86_64).
#
# Prerequisites:
#   - Android NDK r27+ (set ANDROID_NDK_ROOT)
#   - Standard build tools: autoconf, automake, libtool, pkg-config, cmake
#
# Output:
#   core/mosh/src/main/jniLibs/{abi}/libmoshclient.so
#
# Named libmoshclient.so so Android packaging includes them in lib/.
# At runtime: context.applicationInfo.nativeLibraryDir + "/libmoshclient.so"
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_ROOT="${BUILD_ROOT:-$PROJECT_ROOT/build-mosh}"
JNILIBS="$PROJECT_ROOT/core/mosh/src/main/jniLibs"

: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to your NDK path (r27+)}"
TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
API=26

OPENSSL_VERSION=3.3.2
NCURSES_VERSION=6.5
PROTOBUF_VERSION=25.5
MOSH_VERSION=1.4.0

# ABI → (triple, cmake_arch)
declare -A ABI_TRIPLE=(
    [arm64-v8a]=aarch64-linux-android
    [x86_64]=x86_64-linux-android
)
declare -A ABI_OSSL=(
    [arm64-v8a]=android-arm64
    [x86_64]=android-x86_64
)

fetch() {
    local url=$1 dest=$2
    if [ ! -f "$dest" ]; then
        echo "Downloading $url"
        curl -fSL "$url" -o "$dest"
    fi
}

build_openssl() {
    local abi=$1 prefix=$2
    [ -f "$prefix/lib/libcrypto.a" ] && return 0

    local src="$BUILD_ROOT/openssl-$OPENSSL_VERSION-$abi"
    rm -rf "$src"
    tar xf "$BUILD_ROOT/dl/openssl-$OPENSSL_VERSION.tar.gz" -C "$BUILD_ROOT"
    mv "$BUILD_ROOT/openssl-$OPENSSL_VERSION" "$src"
    cd "$src"

    export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
    export PATH="$TOOLCHAIN/bin:$PATH"

    ./Configure "${ABI_OSSL[$abi]}" \
        -D__ANDROID_API__=$API \
        --prefix="$prefix" \
        no-shared no-tests no-ui-console
    make -j"$(nproc)"
    make install_sw
}

build_ncurses() {
    local abi=$1 prefix=$2
    [ -f "$prefix/lib/libncurses.a" ] && return 0

    local triple="${ABI_TRIPLE[$abi]}"
    local src="$BUILD_ROOT/ncurses-$NCURSES_VERSION-$abi"
    rm -rf "$src"
    tar xf "$BUILD_ROOT/dl/ncurses-$NCURSES_VERSION.tar.gz" -C "$BUILD_ROOT"
    mv "$BUILD_ROOT/ncurses-$NCURSES_VERSION" "$src"
    cd "$src"

    export CC="$TOOLCHAIN/bin/${triple}${API}-clang"
    export CXX="$TOOLCHAIN/bin/${triple}${API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"

    ./configure \
        --host="$triple" \
        --prefix="$prefix" \
        --without-shared \
        --without-debug \
        --without-ada \
        --without-tests \
        --enable-widec \
        --with-terminfo-dirs=/etc/terminfo:/usr/share/terminfo
    make -j"$(nproc)"
    make install
}

build_protobuf() {
    local abi=$1 prefix=$2
    [ -f "$prefix/lib/libprotobuf.a" ] && return 0

    local triple="${ABI_TRIPLE[$abi]}"
    local src="$BUILD_ROOT/protobuf-$PROTOBUF_VERSION-$abi"
    rm -rf "$src"
    tar xf "$BUILD_ROOT/dl/protobuf-$PROTOBUF_VERSION.tar.gz" -C "$BUILD_ROOT"
    mv "$BUILD_ROOT/protobuf-$PROTOBUF_VERSION" "$src"
    cd "$src"

    # Build host protoc first (needed for cross-compilation)
    if [ ! -f "$BUILD_ROOT/host-protoc/bin/protoc" ]; then
        mkdir -p "$BUILD_ROOT/host-protoc-build"
        cd "$BUILD_ROOT/host-protoc-build"
        cmake "$src" \
            -DCMAKE_INSTALL_PREFIX="$BUILD_ROOT/host-protoc" \
            -Dprotobuf_BUILD_TESTS=OFF \
            -Dprotobuf_BUILD_SHARED_LIBS=OFF \
            -DABSL_PROPAGATE_CXX_STD=ON
        make -j"$(nproc)"
        make install
    fi

    # Cross-compile protobuf for Android
    mkdir -p "$BUILD_ROOT/protobuf-build-$abi"
    cd "$BUILD_ROOT/protobuf-build-$abi"
    cmake "$src" \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-$API \
        -DCMAKE_INSTALL_PREFIX="$prefix" \
        -Dprotobuf_BUILD_TESTS=OFF \
        -Dprotobuf_BUILD_SHARED_LIBS=OFF \
        -Dprotobuf_BUILD_PROTOC_BINARIES=OFF \
        -DABSL_PROPAGATE_CXX_STD=ON
    make -j"$(nproc)"
    make install
}

build_mosh() {
    local abi=$1 prefix=$2
    local triple="${ABI_TRIPLE[$abi]}"
    local src="$BUILD_ROOT/mosh-$MOSH_VERSION-$abi"
    rm -rf "$src"
    tar xf "$BUILD_ROOT/dl/mosh-$MOSH_VERSION.tar.gz" -C "$BUILD_ROOT"
    mv "$BUILD_ROOT/mosh-$MOSH_VERSION" "$src"
    cd "$src"

    export CC="$TOOLCHAIN/bin/${triple}${API}-clang"
    export CXX="$TOOLCHAIN/bin/${triple}${API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export PKG_CONFIG_PATH="$prefix/lib/pkgconfig:$prefix/lib64/pkgconfig"
    export LDFLAGS="-L$prefix/lib -static-libstdc++"
    export CPPFLAGS="-I$prefix/include -I$prefix/include/ncursesw"
    export protobuf_LIBS="-L$prefix/lib -lprotobuf -labsl_log_internal_message -labsl_log_internal_check_op"
    export protobuf_CFLAGS="-I$prefix/include"

    ./configure \
        --host="$triple" \
        --prefix="$prefix" \
        --with-crypto-library=openssl \
        --enable-static-libraries \
        --disable-server \
        --disable-hardening \
        PROTOC="$BUILD_ROOT/host-protoc/bin/protoc"

    make -j"$(nproc)"

    # Output the mosh-client binary, named libmoshclient.so for Android packaging
    local outdir="$JNILIBS/$abi"
    mkdir -p "$outdir"
    cp src/frontend/mosh-client "$outdir/libmoshclient.so"
    "$TOOLCHAIN/bin/llvm-strip" "$outdir/libmoshclient.so"

    echo "Built: $outdir/libmoshclient.so ($(du -h "$outdir/libmoshclient.so" | cut -f1))"
}

# --- Main ---

mkdir -p "$BUILD_ROOT/dl"

# Download sources
fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" \
    "$BUILD_ROOT/dl/openssl-$OPENSSL_VERSION.tar.gz"
fetch "https://ftp.gnu.org/gnu/ncurses/ncurses-$NCURSES_VERSION.tar.gz" \
    "$BUILD_ROOT/dl/ncurses-$NCURSES_VERSION.tar.gz"
fetch "https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOBUF_VERSION/protobuf-$PROTOBUF_VERSION.tar.gz" \
    "$BUILD_ROOT/dl/protobuf-$PROTOBUF_VERSION.tar.gz"
fetch "https://github.com/mobile-shell/mosh/releases/download/mosh-$MOSH_VERSION/mosh-$MOSH_VERSION.tar.gz" \
    "$BUILD_ROOT/dl/mosh-$MOSH_VERSION.tar.gz"

for abi in arm64-v8a x86_64; do
    echo "=== Building for $abi ==="
    PREFIX="$BUILD_ROOT/install-$abi"
    mkdir -p "$PREFIX"

    build_openssl "$abi" "$PREFIX"
    build_ncurses "$abi" "$PREFIX"
    build_protobuf "$abi" "$PREFIX"
    build_mosh "$abi" "$PREFIX"
done

echo ""
echo "Done. Binaries are in:"
ls -la "$JNILIBS"/*/libmoshclient.so

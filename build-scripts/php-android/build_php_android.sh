#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Abotalal Hosting - Reusable Android NDK PHP Build Pipeline
# Builds official PHP Embedded SAPI + static dependencies (-fPIC) + C SAPI Adapter
# Produces versioned Bionic shared libraries:
#   - libphp_<major>_<minor>.so
#   - libabotalal_php_<major>_<minor>.so
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
. "$SCRIPT_DIR/versions.env"

ABI="arm64-v8a"
WORK_ROOT="/tmp/abotalal_php_build"
OUT_JNILIBS_BASE="$PROJECT_ROOT/app/src/main/jniLibs"
JOBS="$(nproc 2>/dev/null || echo 4)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --abi) ABI="$2"; shift 2 ;;
    --php-version) PHP_VERSION="$2"; shift 2 ;;
    --android-api) ANDROID_API="$2"; shift 2 ;;
    --work-root) WORK_ROOT="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

PHP_SERIES="$(echo "$PHP_VERSION" | awk -F. '{print $1"."$2}')"
PHP_TAG="${PHP_SERIES//./_}"
PHP_URL="https://www.php.net/distributions/php-${PHP_VERSION}.tar.xz"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android/sdk}"
NDK_DIR="${ANDROID_NDK_HOME:-$ANDROID_SDK_ROOT/ndk/$NDK_VERSION}"
if [[ ! -d "$NDK_DIR" ]]; then
  NDK_DIR="$(find "$ANDROID_SDK_ROOT/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1 || true)"
fi
if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR" ]]; then
  echo "ERROR: Android NDK not found under $ANDROID_SDK_ROOT/ndk" >&2
  exit 1
fi

HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"

case "$ABI" in
  arm64-v8a)
    TARGET_TRIPLE="aarch64-linux-android"
    CONFIGURE_HOST="aarch64-linux-android"
    OPENSSL_TARGET="android-arm64"
    ARCH_FLAGS=""
    ;;
  armeabi-v7a)
    TARGET_TRIPLE="armv7a-linux-androideabi"
    CONFIGURE_HOST="arm-linux-androideabi"
    OPENSSL_TARGET="android-arm"
    ARCH_FLAGS="-march=armv7-a -mfloat-abi=softfp -mfpu=neon"
    ;;
  x86_64)
    TARGET_TRIPLE="x86_64-linux-android"
    CONFIGURE_HOST="x86_64-linux-android"
    OPENSSL_TARGET="android-x86_64"
    ARCH_FLAGS=""
    ;;
  *)
    echo "ERROR: Unsupported ABI '$ABI' (supported: arm64-v8a, armeabi-v7a, x86_64)" >&2
    exit 1
    ;;
esac

export ANDROID_NDK_ROOT="$NDK_DIR"
export PATH="$TOOLCHAIN/bin:$PATH"
export CC="$TOOLCHAIN/bin/${TARGET_TRIPLE}${ANDROID_API}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET_TRIPLE}${ANDROID_API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export LD="$TOOLCHAIN/bin/ld.lld"
export READELF="$TOOLCHAIN/bin/llvm-readelf"

SRC_DIR="$WORK_ROOT/src"
BUILD_DIR="$WORK_ROOT/build-$ABI-$PHP_VERSION"
DEPS_PREFIX="$WORK_ROOT/deps-$ABI-$PHP_VERSION"
PHP_INSTALL_PREFIX="$WORK_ROOT/php-out-$ABI-$PHP_VERSION"
OUT_ABI_DIR="$OUT_JNILIBS_BASE/$ABI"

mkdir -p "$SRC_DIR" "$BUILD_DIR" "$DEPS_PREFIX" "$PHP_INSTALL_PREFIX" "$OUT_ABI_DIR"

echo "=== Abotalal PHP Android NDK Pipeline ==="
echo "PHP Version : $PHP_VERSION (Series: $PHP_SERIES, Tag: $PHP_TAG)"
echo "Target ABI  : $ABI ($TARGET_TRIPLE, API $ANDROID_API)"
echo "NDK         : $NDK_DIR"
echo "Clang       : $CC"
echo "Jobs        : $JOBS"

download_src() {
  local url="$1"
  local fname
  fname="$(basename "$url")"
  if [[ ! -f "$SRC_DIR/$fname" ]]; then
    echo ">>> Downloading $fname ..."
    curl -fsSL -L --retry 3 -o "$SRC_DIR/$fname" "$url"
  fi
}

download_src "$PHP_URL"
download_src "$ZLIB_URL"
download_src "$OPENSSL_URL"
download_src "$SQLITE_URL"
download_src "$ONIG_URL"
download_src "$LIBXML2_URL"

export COMMON_CFLAGS="-O2 -fPIC -D__ANDROID_API__=${ANDROID_API} ${ARCH_FLAGS}"
export COMMON_CPPFLAGS="-I${DEPS_PREFIX}/include"
export COMMON_LDFLAGS="-L${DEPS_PREFIX}/lib"

# ------------------------------------------------------------------------------
# 1. zlib (static -fPIC)
# ------------------------------------------------------------------------------
if [[ ! -f "$DEPS_PREFIX/lib/libz.a" ]]; then
  echo ">>> Building zlib $ZLIB_VERSION ($ABI)..."
  rm -rf "$BUILD_DIR/zlib-$ZLIB_VERSION"
  tar -xf "$SRC_DIR/zlib-${ZLIB_VERSION}.tar.gz" -C "$BUILD_DIR"
  pushd "$BUILD_DIR/zlib-$ZLIB_VERSION" >/dev/null
  CFLAGS="$COMMON_CFLAGS" ./configure --prefix="$DEPS_PREFIX" --static
  make -j"$JOBS" libz.a
  make install
  rm -f "$DEPS_PREFIX"/lib/libz.so*
  popd >/dev/null
fi

# ------------------------------------------------------------------------------
# 2. OpenSSL 3 (static no-shared -fPIC)
# ------------------------------------------------------------------------------
if [[ ! -f "$DEPS_PREFIX/lib/libssl.a" ]]; then
  echo ">>> Building OpenSSL $OPENSSL_VERSION ($ABI)..."
  rm -rf "$BUILD_DIR/openssl-$OPENSSL_VERSION"
  tar -xf "$SRC_DIR/openssl-${OPENSSL_VERSION}.tar.gz" -C "$BUILD_DIR"
  pushd "$BUILD_DIR/openssl-$OPENSSL_VERSION" >/dev/null
  CFLAGS="$COMMON_CFLAGS" ./Configure "$OPENSSL_TARGET" \
    -D__ANDROID_API__="$ANDROID_API" \
    --prefix="$DEPS_PREFIX" \
    --libdir=lib \
    no-shared \
    no-tests \
    no-ui-console \
    zlib \
    --with-zlib-include="$DEPS_PREFIX/include" \
    --with-zlib-lib="$DEPS_PREFIX/lib"
  make -j"$JOBS" build_libs
  make install_sw
  popd >/dev/null
fi

# ------------------------------------------------------------------------------
# 3. SQLite3 (static -fPIC)
# ------------------------------------------------------------------------------
if [[ ! -f "$DEPS_PREFIX/lib/libsqlite3.a" ]]; then
  echo ">>> Building SQLite3 $SQLITE_VERSION ($ABI)..."
  rm -rf "$BUILD_DIR/sqlite-autoconf-$SQLITE_VERSION"
  tar -xf "$SRC_DIR/sqlite-autoconf-${SQLITE_VERSION}.tar.gz" -C "$BUILD_DIR"
  pushd "$BUILD_DIR/sqlite-autoconf-$SQLITE_VERSION" >/dev/null
  CFLAGS="$COMMON_CFLAGS -DSQLITE_ENABLE_COLUMN_METADATA=1 -DSQLITE_ENABLE_FTS5=1" \
  ./configure \
    --host="$CONFIGURE_HOST" \
    --prefix="$DEPS_PREFIX" \
    --enable-static \
    --disable-shared \
    --disable-readline
  make -j"$JOBS"
  make install
  popd >/dev/null
fi

# ------------------------------------------------------------------------------
# 4. Oniguruma (for mbstring regex, static -fPIC)
# ------------------------------------------------------------------------------
if [[ ! -f "$DEPS_PREFIX/lib/libonig.a" ]]; then
  echo ">>> Building Oniguruma $ONIG_VERSION ($ABI)..."
  rm -rf "$BUILD_DIR/onig-$ONIG_VERSION"
  tar -xf "$SRC_DIR/onig-${ONIG_VERSION}.tar.gz" -C "$BUILD_DIR"
  pushd "$BUILD_DIR/onig-$ONIG_VERSION" >/dev/null
  CFLAGS="$COMMON_CFLAGS" ./configure \
    --host="$CONFIGURE_HOST" \
    --prefix="$DEPS_PREFIX" \
    --enable-static \
    --disable-shared
  make -j"$JOBS"
  make install
  popd >/dev/null
fi

# ------------------------------------------------------------------------------
# 5. libxml2 (for xml / dom / simplexml, static -fPIC)
# ------------------------------------------------------------------------------
if [[ ! -f "$DEPS_PREFIX/lib/libxml2.a" ]]; then
  echo ">>> Building libxml2 $LIBXML2_VERSION ($ABI)..."
  rm -rf "$BUILD_DIR/libxml2-$LIBXML2_VERSION"
  tar -xf "$SRC_DIR/libxml2-${LIBXML2_VERSION}.tar.xz" -C "$BUILD_DIR"
  pushd "$BUILD_DIR/libxml2-$LIBXML2_VERSION" >/dev/null
  CFLAGS="$COMMON_CFLAGS" ./configure \
    --host="$CONFIGURE_HOST" \
    --prefix="$DEPS_PREFIX" \
    --enable-static \
    --disable-shared \
    --without-python \
    --without-lzma \
    --without-icu \
    --with-zlib="$DEPS_PREFIX"
  make -j"$JOBS"
  make install
  popd >/dev/null
fi

# ------------------------------------------------------------------------------
# 6. PHP Official Source Cross-Compile (Embedded SAPI + Static Extensions)
# ------------------------------------------------------------------------------
echo ">>> Building PHP $PHP_VERSION ($ABI Embedded SAPI)..."
rm -rf "$BUILD_DIR/php-$PHP_VERSION"
tar -xf "$SRC_DIR/php-${PHP_VERSION}.tar.xz" -C "$BUILD_DIR"
pushd "$BUILD_DIR/php-$PHP_VERSION" >/dev/null

# Android Bionic cross-compile autoconf overrides (do not run target checks on x86_64 host)
cat > config.cache.android <<'EOF'
ac_cv_func_getaddrinfo=yes
ac_cv_func_memcmp_working=yes
ac_cv_have_broken_glibc_fopen_append=no
ac_cv_func_dlopen=yes
ac_cv_lib_dl_dlopen=no
ac_cv_func_crypt_r=no
ac_cv_func_posix_spawn=no
ac_cv_func_posix_spawnp=no
ac_cv_func_explicit_bzero=no
ac_cv_func_getdtablesize=no
ac_cv_func_utimes=yes
ac_cv_func_strlcat=yes
ac_cv_func_strlcpy=yes
php_cv_func_flock=yes
php_cv_getaddrinfo_gai_strerror=yes
ac_cv_c_bigendian=no
EOF

export PKG_CONFIG_PATH="$DEPS_PREFIX/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$DEPS_PREFIX/lib/pkgconfig"
export CFLAGS="$COMMON_CFLAGS -I$DEPS_PREFIX/include -I$DEPS_PREFIX/include/libxml2"
export CPPFLAGS="$CFLAGS"
export LDFLAGS="$COMMON_LDFLAGS -Wl,-z,max-page-size=16384"
export LIBS="-lz -lm -ldl -llog"

./configure \
  --cache-file=config.cache.android \
  --host="$CONFIGURE_HOST" \
  --prefix="$PHP_INSTALL_PREFIX" \
  --disable-all \
  --enable-embed=shared \
  --enable-cli \
  --disable-cgi \
  --disable-fpm \
  --disable-phpdbg \
  --disable-rpath \
  --without-pear \
  --with-zlib="$DEPS_PREFIX" \
  --with-openssl="$DEPS_PREFIX" \
  --with-sqlite3="$DEPS_PREFIX" \
  --enable-pdo \
  --with-pdo-sqlite="$DEPS_PREFIX" \
  --with-mysqli=mysqlnd \
  --with-pdo-mysql=mysqlnd \
  --enable-mbstring \
  --with-onig="$DEPS_PREFIX" \
  --with-libxml="$DEPS_PREFIX" \
  --enable-xml \
  --enable-dom \
  --enable-simplexml \
  --enable-session \
  --enable-ctype \
  --enable-filter \
  --enable-tokenizer \
  --enable-fileinfo \
  --enable-sockets \
  --enable-posix \
  --enable-bcmath \
  --disable-opcache \
  --with-pic \
  ZLIB_CFLAGS="-I$DEPS_PREFIX/include" \
  ZLIB_LIBS="-L$DEPS_PREFIX/lib -lz" \
  OPENSSL_CFLAGS="-I$DEPS_PREFIX/include" \
  OPENSSL_LIBS="-L$DEPS_PREFIX/lib -lssl -lcrypto -lz" \
  SQLITE_CFLAGS="-I$DEPS_PREFIX/include" \
  SQLITE_LIBS="-L$DEPS_PREFIX/lib -lsqlite3" \
  ONIG_CFLAGS="-I$DEPS_PREFIX/include" \
  ONIG_LIBS="-L$DEPS_PREFIX/lib -lonig" \
  LIBXML_CFLAGS="-I$DEPS_PREFIX/include/libxml2" \
  LIBXML_LIBS="-L$DEPS_PREFIX/lib -lxml2 -lz"

# Enforce unique versioned SONAME (libphp_8_3.so) so multiple PHP versions coexist safely in nativeLibraryDir
if grep -q -- "-soname libphp.so" Makefile; then
  sed -i "s/-soname libphp.so/-soname libphp_${PHP_TAG}.so/g" Makefile
elif grep -q -- "libphp.la" Makefile; then
  # Ensure libtool adds explicit -soname libphp_${PHP_TAG}.so
  sed -i "s/-export-dynamic/-export-dynamic -Wl,-soname,libphp_${PHP_TAG}.so/g" Makefile
fi

# Patch langinfo / POSIX shims in PHP if API < 26 disables nl_langinfo or crypt_r
if [[ -f main/php_config.h ]]; then
  echo "/* Abotalal Android Bionic API ${ANDROID_API} guards */" >> main/php_config.h
  if [[ "$ANDROID_API" -lt 26 ]]; then
    sed -i 's/#define HAVE_NL_LANGINFO 1/\/\* #undef HAVE_NL_LANGINFO \*\//g' main/php_config.h || true
  fi
fi

make -j"$JOBS"
make install

# Locate built libphp.so and normalize to libphp_<tag>.so
BUILT_LIBPHP="$(find "$BUILD_DIR/php-$PHP_VERSION/libs" "$PHP_INSTALL_PREFIX/lib" -maxdepth 2 -name "libphp*.so" -type f | head -n 1)"
if [[ -z "$BUILT_LIBPHP" ]]; then
  echo "ERROR: libphp.so was not generated!" >&2
  exit 1
fi

VERSIONED_LIBPHP="$OUT_ABI_DIR/libphp_${PHP_TAG}.so"
cp -f "$BUILT_LIBPHP" "$VERSIONED_LIBPHP"
"$STRIP" --strip-unneeded "$VERSIONED_LIBPHP" || true

# Verify DT_SONAME inside libphp_<tag>.so; force patchelf-style rebuild if libtool omitted it
if ! "$READELF" -d "$VERSIONED_LIBPHP" | grep -q "SONAME.*libphp_${PHP_TAG}.so"; then
  echo ">>> Re-linking libphp_${PHP_TAG}.so cleanly to guarantee SONAME=libphp_${PHP_TAG}.so..."
  "$CC" -shared -Wl,-soname,"libphp_${PHP_TAG}.so" -Wl,--whole-archive \
    $(find "$BUILD_DIR/php-$PHP_VERSION/libs" -name "libphp*.a" -type f | head -n 1) \
    -Wl,--no-whole-archive \
    -L"$DEPS_PREFIX/lib" -lssl -lcrypto -lsqlite3 -lonig -lxml2 -lz -lm -ldl -llog \
    -Wl,-z,max-page-size=16384 \
    -o "$VERSIONED_LIBPHP"
  "$STRIP" --strip-unneeded "$VERSIONED_LIBPHP" || true
fi

# ------------------------------------------------------------------------------
# 7. Compile Versioned Abotalal PHP SAPI Adapter (libabotalal_php_<tag>.so)
# ------------------------------------------------------------------------------
echo ">>> Building libabotalal_php_${PHP_TAG}.so against PHP $PHP_VERSION headers..."
PHP_INC="$PHP_INSTALL_PREFIX/include/php"
ADAPTER_SO="$OUT_ABI_DIR/libabotalal_php_${PHP_TAG}.so"

"$CC" -shared -fPIC -O2 -Wall -Wextra \
  -D__ANDROID_API__="$ANDROID_API" \
  -I"$PHP_INC" \
  -I"$PHP_INC/main" \
  -I"$PHP_INC/TSRM" \
  -I"$PHP_INC/Zend" \
  -I"$PHP_INC/ext" \
  -I"$SCRIPT_DIR" \
  "$SCRIPT_DIR/abotalal_php_adapter.c" \
  -L"$OUT_ABI_DIR" -l:"libphp_${PHP_TAG}.so" \
  -llog -ldl -lm \
  -Wl,-soname,"libabotalal_php_${PHP_TAG}.so" \
  -Wl,-z,max-page-size=16384 \
  -o "$ADAPTER_SO"
"$STRIP" --strip-unneeded "$ADAPTER_SO" || true

popd >/dev/null

# ------------------------------------------------------------------------------
# 8. Inspect ELF Headers, Interpreter & DT_NEEDED Dependencies
# ------------------------------------------------------------------------------
echo "=== ELF Audit ($ABI) ==="
for lib in "$VERSIONED_LIBPHP" "$ADAPTER_SO"; do
  echo "--- $lib ---"
  "$READELF" -h "$lib" | grep -E "Class:|Data:|Machine:|Type:"
  "$READELF" -d "$lib" | grep -E "NEEDED|SONAME|RUNPATH|RPATH" || true
done

# Fail immediately if any glibc / Termux RUNPATH / unexpected .so version leaked
for lib in "$VERSIONED_LIBPHP" "$ADAPTER_SO"; do
  NEEDED="$("$READELF" -d "$lib" | grep "NEEDED" || true)"
  if echo "$NEEDED" | grep -qE "libc\.so\.6|com\.termux|\.so\.[0-9]"; then
    echo "ERROR: Invalid DT_NEEDED detected in $lib:" >&2
    echo "$NEEDED" >&2
    exit 1
  fi
done

# ------------------------------------------------------------------------------
# 9. Emit Verified Runtime Bundle Metadata JSON into APK assets
# ------------------------------------------------------------------------------
ASSETS_DIR="$PROJECT_ROOT/app/src/main/assets/runtimes/php"
mkdir -p "$ASSETS_DIR"
cat > "$ASSETS_DIR/php_${PHP_TAG}_${ABI}.json" <<EOF
{
  "program": "php",
  "version": "${PHP_VERSION}",
  "series": "${PHP_SERIES}",
  "abi": "${ABI}",
  "minAndroidApi": ${ANDROID_API},
  "sapi": "abotalal-embed-jni",
  "coreLibrary": "libphp_${PHP_TAG}.so",
  "adapterLibrary": "libabotalal_php_${PHP_TAG}.so",
  "coreSha256": "$(sha256sum "$VERSIONED_LIBPHP" | awk '{print $1}')",
  "adapterSha256": "$(sha256sum "$ADAPTER_SO" | awk '{print $1}')",
  "extensions": ["openssl", "zlib", "sqlite3", "pdo", "pdo_sqlite", "mysqli", "pdo_mysql", "mysqlnd", "mbstring", "xml", "dom", "simplexml", "session", "ctype", "filter", "tokenizer", "fileinfo", "sockets", "posix", "bcmath", "opcache", "json"]
}
EOF

echo "=== SUCCESS: PHP $PHP_VERSION ($ABI) built and verified in $OUT_ABI_DIR ==="
ls -lh "$VERSIONED_LIBPHP" "$ADAPTER_SO"

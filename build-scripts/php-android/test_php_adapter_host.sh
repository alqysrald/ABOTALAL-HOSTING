#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/versions.env"

WORK_ROOT="/tmp/abotalal_php_build"
HOST_BUILD="$WORK_ROOT/build-host-x86_64-$PHP_VERSION"
HOST_PREFIX="$WORK_ROOT/php-host-$PHP_VERSION"
SRC_TAR="$WORK_ROOT/src/php-${PHP_VERSION}.tar.xz"

if [[ ! -f "$SRC_TAR" ]]; then
  mkdir -p "$WORK_ROOT/src"
  curl -fsSL -L --retry 3 -o "$SRC_TAR" "$PHP_URL"
fi

if [[ ! -f "$HOST_PREFIX/lib/libphp.so" ]]; then
  echo ">>> Building PHP $PHP_VERSION host x86_64 Embed SAPI to run real behavioral verification..."
  rm -rf "$HOST_BUILD" "$HOST_PREFIX"
  mkdir -p "$HOST_BUILD" "$HOST_PREFIX"
  tar -xf "$SRC_TAR" -C "$HOST_BUILD"
  pushd "$HOST_BUILD/php-$PHP_VERSION" >/dev/null
  ./configure \
    --prefix="$HOST_PREFIX" \
    --disable-all \
    --enable-embed=shared \
    --disable-cgi \
    --disable-cli \
    --disable-fpm \
    --disable-phpdbg \
    --enable-session \
    --enable-ctype \
    --enable-filter \
    --enable-tokenizer \
    --enable-bcmath \
    --without-pear
  make -j"$(nproc)"
  make install
  popd >/dev/null
fi

PHP_INC="$HOST_PREFIX/include/php"
TEST_BIN="$WORK_ROOT/test_php_adapter_host"
gcc -O2 -Wall -Wextra \
  -I"$PHP_INC" \
  -I"$PHP_INC/main" \
  -I"$PHP_INC/TSRM" \
  -I"$PHP_INC/Zend" \
  -I"$PHP_INC/ext" \
  -I"$SCRIPT_DIR" \
  "$SCRIPT_DIR/abotalal_php_adapter.c" \
  "$SCRIPT_DIR/test_php_adapter_host.c" \
  -L"$HOST_PREFIX/lib" -Wl,-rpath,"$HOST_PREFIX/lib" -lphp -lpthread -ldl -lm \
  -o "$TEST_BIN"

"$TEST_BIN"

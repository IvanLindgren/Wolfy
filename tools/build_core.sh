#!/usr/bin/env bash
#
# Собирает ядро на Rust под платформы клиента.
#
# Gradle этого не делает намеренно: у Rust свой инструмент со своим кэшем, и
# запускать cargo из каждой сборки клиента значило бы ждать его без нужды.
# Скрипт вызывается руками при изменении ядра и в CI.
#
# Использование:
#   tools/build_core.sh            собрать под текущую систему (для desktop)
#   tools/build_core.sh android    собрать под все ABI Android
#   tools/build_core.sh all        и то, и другое
#
# Для Android нужен cargo-ndk и NDK:
#   cargo install cargo-ndk
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/core"
JNI_LIBS="$ROOT/client/shared/src/androidMain/jniLibs"

# ABI, под которые собираем.
#
# arm64 — все современные телефоны, armv7 — старые, x86_64 — эмулятор, без
# которого разработка под Android невозможна. x86 (32 бита) не собираем:
# устройств с ним не осталось, а вес пакета он увеличивает на треть.
ANDROID_TARGETS=(
    "arm64-v8a:aarch64-linux-android"
    "armeabi-v7a:armv7-linux-androideabi"
    "x86_64:x86_64-linux-android"
)

build_host() {
    echo "==> Ядро под текущую систему"
    cargo build --release --manifest-path "$CORE/Cargo.toml"

    local built
    built="$(ls "$CORE"/target/release/{wolfy_core.dll,libwolfy_core.so,libwolfy_core.dylib} 2>/dev/null | head -1 || true)"
    if [ -z "$built" ]; then
        echo "не нашлась собранная библиотека в $CORE/target/release" >&2
        return 1
    fi
    echo "    готово: $built"
}

build_android() {
    if ! command -v cargo-ndk >/dev/null 2>&1; then
        echo "нет cargo-ndk. Установите: cargo install cargo-ndk" >&2
        return 1
    fi

    echo "==> Ядро под Android"
    mkdir -p "$JNI_LIBS"

    for entry in "${ANDROID_TARGETS[@]}"; do
        local abi="${entry%%:*}"
        local target="${entry##*:}"
        echo "    $abi ($target)"
        cargo ndk --manifest-path "$CORE/Cargo.toml" \
            --target "$target" \
            --platform 26 \
            --output-dir "$JNI_LIBS" \
            build --release
    done

    echo "    готово: $JNI_LIBS"
}

case "${1:-host}" in
    host)    build_host ;;
    android) build_android ;;
    all)     build_host; build_android ;;
    *)
        echo "неизвестная цель «$1». Доступно: host, android, all" >&2
        exit 1
        ;;
esac

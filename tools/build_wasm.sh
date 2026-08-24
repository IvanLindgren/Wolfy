#!/usr/bin/env bash
#
# Собирает ядро под браузер и кладёт результат в веб-приложение.
#
# Отдельно от `build_core.sh` по той же причине, по какой тот отдельно от
# Gradle: у Rust свой кэш, и запускать cargo из каждой пересборки Vite значило
# бы ждать его на каждом сохранении файла. Скрипт зовётся руками при правке
# ядра и в CI.
#
# Что делает:
#   1. Собирает `wolfy-core` под wasm32 без встроенного лексикона и без
#      pdf-extract — оба в браузере не нужны и не работают.
#   2. Прогоняет `wasm-bindgen --target web` в `web/src/core/pkg`.
#   3. Кладёт лексикон в `web/public/lexicon`: он забирается отдельным
#      запросом, а не едет внутри `.wasm`, иначе первый заход тянет лишний
#      мегабайт до первой буквы текста.
#
# Что нужно один раз:
#   rustup target add wasm32-unknown-unknown
#   cargo install wasm-bindgen-cli --version 0.2.127

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/core"
OUT="$ROOT/web/src/core/pkg"
PUBLIC="$ROOT/web/public"

if ! command -v wasm-bindgen >/dev/null 2>&1; then
    echo "нет wasm-bindgen. Установите: cargo install wasm-bindgen-cli --version 0.2.127" >&2
    exit 1
fi

echo "==> Ядро под wasm32"
cargo build --manifest-path "$CORE/Cargo.toml" \
    --lib --release --target wasm32-unknown-unknown \
    --no-default-features --features wasm

echo "==> Привязки для браузера"
mkdir -p "$OUT"
wasm-bindgen --target web --out-dir "$OUT" --out-name wolfy_core \
    "$CORE/target/wasm32-unknown-unknown/release/wolfy_core.wasm"

echo "==> Лексикон отдельным ресурсом"
mkdir -p "$PUBLIC/lexicon"
cp "$CORE/data/english_lexicon.tsv" "$PUBLIC/lexicon/english_lexicon.tsv"

# Размер важен: бюджет — мегабайт gzip на `.wasm` без лексикона.
wasm_size=$(wc -c < "$OUT/wolfy_core_bg.wasm")
gz_size=$(gzip -9 -c "$OUT/wolfy_core_bg.wasm" | wc -c)
printf '    .wasm: %s байт, %s байт gzip\n' "$wasm_size" "$gz_size"
if [ "$gz_size" -gt 1048576 ]; then
    echo "    ВНИМАНИЕ: бюджет §8 (1 МБ gzip) превышен" >&2
fi
echo "    готово: $OUT"

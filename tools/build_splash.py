#!/usr/bin/env python3
"""Собирает заставку, которую Windows показывает, пока грузится приложение.

Зачем она вообще. От щелчка по значку до окна проходит чуть больше двух
секунд, и это не так уж много — JVM со Skia быстрее не стартуют. Плохо другое:
все эти две секунды на экране не происходит ничего. Пользователь не знает,
попал ли он по значку, и щёлкает второй раз.

Заставку показывает сам запускатель JVM по доводу `-splash:` — до того, как
загружен хоть один класс приложения. Она появляется примерно за десятую долю
секунды, то есть практически сразу.

Почему картинка собирается скриптом, а не лежит готовым файлом. Она обязана
совпадать с приложением: та же бумага, тот же волк, тот же шрифт заголовка.
Стоит поменять палитру в `theme/Colors.kt` — и нарисованная однажды заставка
тихо разойдётся с тем, что видит читатель через две секунды.

Запуск:

    pip install pillow
    python tools/build_splash.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONTS = ROOT / "client/shared/src/commonMain/composeResources/font"
STICKER = ROOT / "client/assets/wolfy_stickers/vulfie_sticker_01_wave.png"
TARGET = ROOT / "client/desktopApp/icons"

# Палитра темы Paper из client/shared/.../theme/Colors.kt.
PAPER = (244, 244, 241)
INK = (17, 17, 17)
MUTED = (107, 107, 102)
RULE = (217, 216, 210)
ACCENT = (184, 58, 42)

WIDTH, HEIGHT = 460, 280


def draw(scale: int) -> Image.Image:
    """Рисует заставку в заданном масштабе.

    Масштаб нужен из-за экранов с высокой плотностью: Java сама подхватит файл
    `splash@2x.png`, если он лежит рядом, а без него растянет обычный и получит
    мыло на половине ноутбуков.
    """
    width, height = WIDTH * scale, HEIGHT * scale
    canvas = Image.new("RGBA", (width, height), PAPER + (255,))
    pen = ImageDraw.Draw(canvas)

    # Рамка в один пиксель: окно заставки без рамки системы, и без этой линии
    # светлая карточка растворяется в светлом фоне рабочего стола.
    pen.rectangle([0, 0, width - 1, height - 1], outline=RULE, width=scale)

    wolf = Image.open(STICKER).convert("RGBA")
    wolf_height = 150 * scale
    wolf_width = round(wolf.width * wolf_height / wolf.height)
    wolf = wolf.resize((wolf_width, wolf_height), Image.LANCZOS)
    wolf_x = 38 * scale
    canvas.alpha_composite(wolf, (wolf_x, (height - wolf_height) // 2 - 6 * scale))

    text_x = wolf_x + wolf_width + 26 * scale
    title = ImageFont.truetype(str(FONTS / "PlayfairDisplay.ttf"), 52 * scale)
    tagline = ImageFont.truetype(str(FONTS / "Inter.ttf"), 13 * scale)

    baseline = height // 2
    pen.text((text_x, baseline - 46 * scale), "Wolfy", font=title, fill=INK)

    # Короткая черта под заголовком — тот же приём, что у заголовков разделов
    # в самом приложении.
    pen.rectangle(
        [text_x, baseline + 42 * scale, text_x + 34 * scale, baseline + 42 * scale + scale],
        fill=ACCENT,
    )
    pen.text((text_x, baseline + 54 * scale), "чтение по-английски", font=tagline, fill=MUTED)

    return canvas


def main() -> None:
    TARGET.mkdir(parents=True, exist_ok=True)
    for scale, name in ((1, "splash.png"), (2, "splash@2x.png")):
        path = TARGET / name
        draw(scale).save(path, "PNG", optimize=True)
        print(f"{path.relative_to(ROOT)}: {path.stat().st_size / 1024:.0f} КБ")


if __name__ == "__main__":
    main()

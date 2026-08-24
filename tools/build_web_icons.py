#!/usr/bin/env python3
"""Собирает PWA-иконки из готового общего арта Вульфи."""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "client/assets/wolfy_stickers/wolfy_card.png"
TARGET = ROOT / "web/public/icons"
PAPER = (251, 249, 245, 255)


def icon(size: int, fill: float) -> Image.Image:
    source = Image.open(SOURCE).convert("RGBA")
    box = source.getbbox()
    if box:
        source = source.crop(box)
    side = round(size * fill)
    source.thumbnail((side, side), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), PAPER)
    canvas.alpha_composite(source, ((size - source.width) // 2, (size - source.height) // 2))
    return canvas


def main() -> None:
    TARGET.mkdir(parents=True, exist_ok=True)
    for size, name, fill in (
        (192, "icon-192.png", 0.84),
        (512, "icon-512.png", 0.84),
        (512, "maskable-512.png", 0.68),
        (180, "apple-touch-icon.png", 0.82),
    ):
        icon(size, fill).save(TARGET / name, "PNG", optimize=True)
    icon(64, 0.82).save(ROOT / "web/public/favicon.png", "PNG", optimize=True)


if __name__ == "__main__":
    main()

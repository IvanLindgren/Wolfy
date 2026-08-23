#!/usr/bin/env python3
"""Собирает офлайн-словарь Wolfy: перевод, толкования и произношение.

Карточке слова не хватало двух вещей: что слово значит само по себе и как оно
звучит. Перевод отвечает на первый вопрос лишь отчасти — «library» это и
«библиотека», и «книгохранилище», и выбрать между ними помогает толкование, а
не второй перевод.

Источники те же, что у лексикона, и по той же причине: разбор одного слова
обязан совпадать везде.

* Толкования — WordNet (лицензия Принстона, свободное распространение).
* Произношение — CMUdict (BSD), переложенный из ARPAbet в МФА.
* Русские эквиваленты — FreeDict/WikDict eng-rus (CC BY-SA 3.0).

Почему отдельный файл, а не внутрь ядра. Словарь весит на порядок больше
лексикона. Его сжатый архив входит в установщик, но распаковывается только
после согласия читателя и лежит рядом с библиотекой.

Почему отсортирован. Ядро ищет в нём двоичным поиском прямо по файлу, не читая
его в память: полтораста тысяч статей в памяти телефона — это непозволительно
много ради строки, которую показывают раз в минуту.

Запуск (один раз ставятся корпуса):

    pip install nltk
    python -c "import nltk; [nltk.download(p) for p in ('wordnet','cmudict')]"
    python tools/build_dictionary.py --freedict path/to/freedict-eng-rus.src.tar.xz

Результат — `dist/wolfy_dictionary.tsv` и его сжатая копия для раздачи.
"""

from __future__ import annotations

import argparse
import gzip
import re
import sys
import tarfile
import unicodedata
import xml.etree.ElementTree as ET

from collections import defaultdict
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "dist" / "wolfy_dictionary.tsv"

# Сколько толкований на часть речи оставлять.
#
# Два. У «set» в WordNet под шесть десятков значений, и все они правдивы, но
# читателю посреди книги нужен смысл, а не полнота: третье толкование он уже
# не дочитывает, а файл от него растёт вдвое.
SENSES_PER_POS = 2

# Коды частей речи — те же однобуквенные, что в лексиконе.
WORDNET_POS = {"n": "n", "v": "v", "a": "a", "s": "a", "r": "r"}

TEI = "{http://www.tei-c.org/ns/1.0}"
TRANSLATIONS_PER_WORD = 5

# ARPAbet → МФА, общеамериканское произношение.
#
# Гласные с ударением обрабатываются отдельно: цифра в конце кода — это
# ударение, а не часть звука.
ARPABET = {
    "AA": "ɑ", "AE": "æ", "AH": "ʌ", "AO": "ɔ", "AW": "aʊ", "AY": "aɪ",
    "B": "b", "CH": "tʃ", "D": "d", "DH": "ð", "EH": "ɛ", "ER": "ɝ",
    "EY": "eɪ", "F": "f", "G": "ɡ", "HH": "h", "IH": "ɪ", "IY": "i",
    "JH": "dʒ", "K": "k", "L": "l", "M": "m", "N": "n", "NG": "ŋ",
    "OW": "oʊ", "OY": "ɔɪ", "P": "p", "R": "ɹ", "S": "s", "SH": "ʃ",
    "T": "t", "TH": "θ", "UH": "ʊ", "UW": "u", "V": "v", "W": "w",
    "Y": "j", "Z": "z", "ZH": "ʒ",
}

# Безударные варианты: в живой речи они редуцируются, и «ʌ» без ударения
# звучит как «ə». Без этой пары транскрипция «about» выглядела бы как «ʌˈbaʊt»
# вместо «əˈbaʊt» — то есть неверно в самом частом слове языка.
UNSTRESSED = {"AH": "ə", "ER": "ɚ"}

VOWELS = {
    "AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER",
    "EY", "IH", "IY", "OW", "OY", "UH", "UW",
}


def ipa(phones: list[str]) -> str:
    """Переводит запись CMUdict в МФА.

    Ударение ставится перед слогом, а не перед гласной: «ˈlaɪbɹɛɹi», а не
    «lˈaɪbɹɛɹi». Слог отсчитывается назад от гласной по согласным — приём
    грубый, но в английском он ошибается редко и всегда в мелочи, а
    альтернатива — таблица слогоделения на полтораста тысяч слов.
    """
    # У односложных слов знак ударения не ставят: ударять там больше не на
    # что, и «/ˈθɹu/» выглядит как ошибка набора.
    syllables = sum(1 for phone in phones if phone.rstrip("012") in VOWELS)

    out: list[str] = []
    marks: dict[int, str] = {}

    for phone in phones:
        stress = ""
        code = phone
        if code[-1].isdigit():
            stress, code = code[-1], code[:-1]

        if code in VOWELS:
            sound = UNSTRESSED[code] if (stress == "0" and code in UNSTRESSED) else ARPABET.get(code, "")
            if syllables > 1:
                if stress == "1":
                    marks[len(out)] = "ˈ"
                elif stress == "2":
                    marks[len(out)] = "ˌ"
        else:
            sound = ARPABET.get(code, "")
        if sound:
            out.append(sound)

    # Отодвигаем знак ударения к началу слога: назад по согласным, но не
    # дальше начала слова и не через уже поставленный знак.
    consonants = {value for key, value in ARPABET.items() if key not in VOWELS}
    placed: dict[int, str] = {}
    for at, mark in sorted(marks.items()):
        start = at
        while start > 0 and out[start - 1] in consonants and (start - 1) not in placed:
            start -= 1
        placed[start] = mark

    result = []
    for index, sound in enumerate(out):
        if index in placed:
            result.append(placed[index])
        result.append(sound)
    return "".join(result)


def without_stress(text: str) -> str:
    """Убирает словарное ударение: «библиоте́ка» и «библиотека» — одно."""
    decomposed = unicodedata.normalize("NFD", text.strip())
    return unicodedata.normalize(
        "NFC",
        "".join(char for char in decomposed if unicodedata.category(char) != "Mn"),
    )


def clean_translation(text: str) -> str:
    """Убирает разметку Wiktionary, которая человеку в карточке не нужна."""
    text = re.sub(r"\[\[(?:[^|\]]+\|)?([^\]]+)\]\]", r"\1", text)
    text = re.sub(r"\{\{[^}]+\}\}", "", text)
    return without_stress(text).replace("_", " ").strip()


def freedict_translations(archive: Path | None) -> dict[str, list[str]]:
    """Читает eng-rus TEI прямо из tar.xz, не распаковывая его на диск."""
    if archive is None:
        return {}
    if not archive.is_file():
        sys.exit(f"архив FreeDict не найден: {archive}")

    result: dict[str, list[str]] = defaultdict(list)
    with tarfile.open(archive, "r:xz") as bundle:
        member = next((item for item in bundle if item.name.endswith("/eng-rus.tei")), None)
        if member is None:
            sys.exit("в архиве FreeDict нет eng-rus.tei")
        source = bundle.extractfile(member)
        if source is None:
            sys.exit("не получилось прочитать eng-rus.tei")

        for _, entry in ET.iterparse(source, events=("end",)):
            if entry.tag != TEI + "entry":
                continue
            orth = entry.find(f"./{TEI}form/{TEI}orth")
            word = (orth.text or "").strip().lower() if orth is not None else ""
            if not word.isascii() or not word.isalpha():
                entry.clear()
                continue

            bucket = result[word]
            for quote in entry.findall(f".//{TEI}cit[@type='trans']/{TEI}quote"):
                translated = clean_translation("".join(quote.itertext()))
                if translated and translated not in bucket:
                    bucket.append(translated)
                    if len(bucket) >= TRANSLATIONS_PER_WORD:
                        break
            entry.clear()
    return result


def build(target: Path, limit: int | None, freedict: Path | None) -> None:
    try:
        from nltk.corpus import cmudict
        from nltk.corpus import wordnet as wn
    except ImportError:
        sys.exit("нужен nltk: pip install nltk")

    sounds = cmudict.dict()
    translations = freedict_translations(freedict)

    # Толкования по слову и части речи.
    senses: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
    for synset in wn.all_synsets():
        pos = WORDNET_POS.get(synset.pos())
        if pos is None:
            continue
        definition = synset.definition().strip()
        if not definition:
            continue
        for lemma in synset.lemma_names():
            # Составные статьи вида «library_card» пропускаем: читатель тапает
            # по одному слову, и найти по нему статью из двух ядро не сможет.
            if "_" in lemma or not lemma.isalpha():
                continue
            word = lemma.lower()
            bucket = senses[word][pos]
            if len(bucket) < SENSES_PER_POS and definition not in bucket:
                bucket.append(definition)

    words = sorted(senses)
    if limit:
        words = words[:limit]

    target.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    with target.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("# wolfy english dictionary v2\n")
        handle.write(f"# generated\t{date.today().isoformat()}\n")
        handle.write("# source\tWordNet (Princeton), CMUdict (BSD), FreeDict/WikDict eng-rus (CC BY-SA 3.0)\n")
        # Строка формата стоит в файле, а не только в этом скрипте: файл едет
        # к читателю отдельно от кода и обязан объяснять себя сам.
        handle.write("# format\tword<TAB>ipa<TAB>t|translation<TAB>pos|sense…\n")

        for word in words:
            phones = sounds.get(word)
            transcription = ipa(phones[0]) if phones else ""
            columns = [word, transcription]
            columns.extend(f"t|{value}" for value in translations.get(word, []))
            for pos in ("n", "v", "a", "r"):
                for definition in senses[word].get(pos, []):
                    columns.append(f"{pos}|{definition}")
            if len(columns) == 2:
                continue
            handle.write("\t".join(columns) + "\n")
            written += 1

    packed = target.with_suffix(target.suffix + ".gz")
    with target.open("rb") as source, gzip.open(packed, "wb", compresslevel=9) as sink:
        sink.writelines(source)

    print(f"статей: {written}")
    print(f"{target}: {target.stat().st_size / 1_048_576:.1f} МБ")
    print(f"{packed}: {packed.stat().st_size / 1_048_576:.1f} МБ")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=TARGET)
    parser.add_argument("--limit", type=int, default=None, help="только первые N статей — для проверки")
    parser.add_argument(
        "--freedict",
        type=Path,
        default=None,
        help="архив freedict-eng-rus-*.src.tar.xz с русскими переводами",
    )
    args = parser.parse_args()
    build(args.out, args.limit, args.freedict)


if __name__ == "__main__":
    main()

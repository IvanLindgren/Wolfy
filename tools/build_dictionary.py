#!/usr/bin/env python3
"""Собирает офлайн-словарь Wolfy: толкования и произношение.

Карточке слова не хватало двух вещей: что слово значит само по себе и как оно
звучит. Перевод отвечает на первый вопрос лишь отчасти — «library» это и
«библиотека», и «книгохранилище», и выбрать между ними помогает толкование, а
не второй перевод.

Источники те же, что у лексикона, и по той же причине: разбор одного слова
обязан совпадать везде.

* Толкования — WordNet (лицензия Принстона, свободное распространение).
* Произношение — CMUdict (BSD), переложенный из ARPAbet в МФА.

Почему отдельный файл, а не внутрь ядра. Словарь весит на порядок больше
лексикона, а нужен не всем: читатель, которому хватает перевода, не должен
платить за него размером установщика. Поэтому он скачивается отдельно и лежит
рядом с библиотекой.

Почему отсортирован. Ядро ищет в нём двоичным поиском прямо по файлу, не читая
его в память: полтораста тысяч статей в памяти телефона — это непозволительно
много ради строки, которую показывают раз в минуту.

Запуск (один раз ставятся корпуса):

    pip install nltk
    python -c "import nltk; [nltk.download(p) for p in ('wordnet','cmudict')]"
    python tools/build_dictionary.py

Результат — `dist/wolfy_dictionary.tsv` и его сжатая копия для раздачи.
"""

from __future__ import annotations

import argparse
import gzip
import sys

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


def build(target: Path, limit: int | None) -> None:
    try:
        from nltk.corpus import cmudict
        from nltk.corpus import wordnet as wn
    except ImportError:
        sys.exit("нужен nltk: pip install nltk")

    sounds = cmudict.dict()

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
        handle.write("# wolfy english dictionary v1\n")
        handle.write(f"# generated\t{date.today().isoformat()}\n")
        handle.write("# source\tWordNet (Princeton), CMUdict (BSD)\n")
        # Строка формата стоит в файле, а не только в этом скрипте: файл едет
        # к читателю отдельно от кода и обязан объяснять себя сам.
        handle.write("# format\tword<TAB>ipa<TAB>pos|sense<TAB>pos|sense…\n")

        for word in words:
            phones = sounds.get(word)
            transcription = ipa(phones[0]) if phones else ""
            columns = [word, transcription]
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
    args = parser.parse_args()
    build(args.out, args.limit)


if __name__ == "__main__":
    main()

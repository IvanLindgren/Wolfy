#!/usr/bin/env python3
"""Собирает английский лексикон для ядра Wolfy.

Карточке слова нужны пять вещей: начальная форма, часть речи, разбор формы,
частотность в живой речи и уровень CEFR. Первые три даёт WordNet, четвёртую —
корпус Brown, пятая выводится из четвёртой (см. `cefr_level`).

Логика лемм и исключений намеренно повторяет `tools/build_english_lexicon.py`
Читавука: разбор одного и того же слова обязан совпадать в двух приложениях,
иначе «children» в Wolfy сводилось бы к «child», а в Читавуке — нет.

Отличий от Читавука два, и оба от разницы задач. Там лексикон нужен, чтобы
опознать английское слово среди сербских, и шестнадцати тысяч лемм хватало.
Здесь по слову жмут в настоящем романе, где редкое слово встречается на каждой
странице, — поэтому берём весь обиходный слой WordNet. И добавляем два поля,
которых у Читавука нет: частотность и уровень.

Запуск (один раз ставятся корпуса):

    pip install nltk
    python -c "import nltk; [nltk.download(p) for p in ('wordnet','brown','universal_tagset')]"
    python tools/build_lexicon.py

Результат — `core/data/english_lexicon.tsv`, который ядро встраивает в бинарник.
"""

from __future__ import annotations

import argparse
import math
import sys

from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "core" / "data" / "english_lexicon.tsv"

# Однобуквенные коды частей речи: файл едет в мобильный бандл, и «NOUN» вместо
# «n» стоил бы лишних сотни килобайт на ровном месте.
CODES = {
    "n": "NOUN",
    "v": "VERB",
    "a": "ADJ",
    "r": "ADV",
    "p": "PRON",
    "d": "DET",
    "i": "ADP",
    "c": "CONJ",
    "t": "PART",
    "m": "NUM",
}

WORDNET_POS = {"n": "n", "v": "v", "a": "a", "s": "a", "r": "r"}

# Universal tagset → наши коды. Знаменательные части речи берутся из WordNet,
# поэтому здесь только служебные — их WordNet не описывает вовсе.
BROWN_CLOSED = {
    "DET": "d",
    "ADP": "i",
    "PRON": "p",
    "CONJ": "c",
    "PRT": "t",
    "NUM": "m",
}

# Открытые классы, которых может не быть в WordNet: модальные глаголы
# («would», «does») и неопределённые местоимения («everyone»).
BROWN_OPEN = {"VERB": "v", "NOUN": "n", "ADJ": "a", "ADV": "r"}

# Размер корпуса Brown в токенах — нужен, чтобы перевести счётчик в Zipf.
BROWN_TOKENS = 1_161_192


def usable(word: str) -> bool:
    """Годится ли слово для лексикона.

    Составные («ice_cream»), с дефисом и с цифрами выбрасываются: токенизатор
    читалки отдаёт по одному слову. Однобуквенные — тоже, кроме «a» и «i».
    """
    return word.isascii() and word.isalpha() and word.islower() and 2 <= len(word) <= 24


def zipf(count: int) -> float:
    """Частотность по шкале Zipf: log10 вхождений на миллиард слов.

    Шкала выбрана потому, что она линейна для человека: 6 — «the», 4 — обычное
    книжное слово, 2 — то, что читатель видит раз в жизни. Ползунок
    «редко ↔ часто» в карточке рисуется прямо по ней.
    """
    if count <= 0:
        return 0.0
    return round(math.log10(count / BROWN_TOKENS * 1_000_000_000), 1)


def cefr_level(z: float) -> str:
    """Уровень слова по шкале CEFR, выведенный из частотности.

    Честная оговорка: это аппроксимация, а не официальная разметка. Открытых
    списков CEFR с пригодной лицензией нет — Oxford 3000/5000 и EVP закрыты.
    Но связь частотности и уровня в реальных списках очень плотная, а читателю
    нужен ориентир «стоит ли учить это слово сейчас», а не сертификация.

    Пороги подобраны так, чтобы A1 накрыл служебные слова и бытовую тысячу,
    а C2 достался тому, что в миллионном корпусе встретилось единицы раз.
    """
    if z >= 5.5:
        return "A1"
    if z >= 4.8:
        return "A2"
    if z >= 4.2:
        return "B1"
    if z >= 3.6:
        return "B2"
    if z >= 3.0:
        return "C1"
    return "C2"


def load_wordnet_lemmas(wn) -> dict[str, set[str]]:
    """Лемма → множество кодов частей речи."""
    out: dict[str, set[str]] = defaultdict(set)
    for wn_pos, code in (("n", "n"), ("v", "v"), ("a", "a"), ("r", "r")):
        for name in wn.all_lemma_names(pos=wn_pos):
            if usable(name):
                out[name].add(code)
    return out


def load_exceptions(wn) -> dict[str, dict[str, str]]:
    """Неправильные формы из WordNet: код части речи → {форма: лемма}.

    Файлы `*.exc` — готовая таблица английской морфологии, ради неё WordNet
    здесь и нужен в первую очередь. Читаем через разобранную корпусом карту,
    а не по путям: корпус бывает и каталогом, и zip-архивом.
    """
    wn.ensure_loaded()
    out: dict[str, dict[str, str]] = {code: {} for code in ("n", "v", "a", "r")}
    for wn_pos, table in wn._exception_map.items():
        code = WORDNET_POS.get(wn_pos)  # 's' — сателлиты, таблица та же, что у 'a'
        if code is None:
            continue
        for form, candidates in table.items():
            if not usable(form) or not candidates:
                continue
            # Строка формата «форма лемма [лемма…]». Берём первую: остальные —
            # редкие омонимичные разборы.
            lemma = candidates[0]
            if usable(lemma):
                out[code].setdefault(form, lemma)
    return out


def load_brown(brown) -> tuple[Counter, dict[str, str], dict[str, str], dict[str, str]]:
    """Частотность, служебные части речи и преобладающая метка из корпуса.

    Из корпуса берутся только счётчики и метки; сам текст никуда не попадает.

    Преобладающая метка — четвёртый результат и самый неочевидный. Словарь
    отвечает, какими частями речи слово *бывает*, а разбору предложения нужно,
    какой оно *обычно является*: у «book» это существительное, у «run» —
    глагол, и порядок в списке значений об этом не говорит ничего. Корпус
    размечен руками, и посчитать по нему преобладание — единственный способ
    получить осмысленный ответ по умолчанию, не таща в ядро статистическую
    модель.
    """
    counts: Counter = Counter()
    tags: dict[str, Counter] = defaultdict(Counter)
    for word, tag in brown.tagged_words(tagset="universal"):
        low = word.lower()
        if low.isascii() and low.isalpha():
            counts[low] += 1
            tags[low][tag] += 1

    closed: dict[str, str] = {}
    open_class: dict[str, str] = {}
    dominant: dict[str, str] = {}
    for word, tag_counts in tags.items():
        tag, hits = tag_counts.most_common(1)[0]
        code = BROWN_CLOSED.get(tag) or BROWN_OPEN.get(tag)
        if code:
            # Слово, встреченное дважды, о преобладании не говорит ничего:
            # такая «статистика» врёт чаще, чем помогает, и разбор лучше
            # оставит выбор правилам.
            if hits >= 5:
                dominant[word] = code
            if tag in BROWN_CLOSED:
                closed[word] = code
            elif tag in BROWN_OPEN:
                open_class[word] = code
    return counts, closed, open_class, dominant


def resolvable(word: str, words: dict[str, set[str]]) -> bool:
    """Разложит ли движок это слово в уже известную лемму.

    Повторяет ту часть правил `core/src/lexicon/`, которая отвечает на вопрос
    «это форма известного слова?». Если да, отдельной леммой слово в словарь
    не попадает: иначе «states» перестало бы быть множественным от «state».
    """

    def known(candidate: str) -> bool:
        return len(candidate) >= 2 and candidate in words

    def stems(stem: str) -> set[str]:
        out = {stem, stem + "e"}
        if len(stem) >= 2:
            if stem[-1] == stem[-2] and stem[-1] not in "aeiou":
                out.add(stem[:-1])
            if stem.endswith("i"):
                out.add(stem[:-1] + "y")
        return out

    if word.endswith("s") and not word.endswith("ss") and len(word) > 2:
        stem = word[:-1]
        candidates = {stem}
        if stem.endswith("ie"):
            candidates.add(stem[:-2] + "y")
        if stem.endswith("e"):
            # «-es» и после шипящих, и после гласной: «boxes» → «box»,
            # «goes» → «go». Так же, как в `candidates` ядра.
            candidates.add(stem[:-1])
        if stem.endswith("ve"):
            candidates.update({stem[:-2] + "f", stem[:-2] + "fe"})
        if any(known(c) for c in candidates):
            return True

    for suffix in ("ing", "est", "ed", "er", "ly"):
        if len(word) > len(suffix) + 1 and word.endswith(suffix):
            if any(known(c) for c in stems(word[: -len(suffix)])):
                return True

    return False


def build(top: int, min_closed: int, min_missing: int) -> tuple[dict, dict, Counter, dict]:
    from nltk.corpus import brown
    from nltk.corpus import wordnet as wn

    lemmas = load_wordnet_lemmas(wn)
    exceptions = load_exceptions(wn)
    counts, closed, brown_open, dominant = load_brown(brown)

    words: dict[str, set[str]] = defaultdict(set)

    # 1. Служебные слова: их мало, они самые частые, и без них не разобрать ни
    #    одной живой фразы — в «the book is on the table» четыре слова из шести.
    for word, code in closed.items():
        if counts[word] >= min_closed and (len(word) >= 2 or word in {"a", "i"}):
            words[word].add(code)
    for word in ("a", "i"):
        if word in closed:
            words[word].add(closed[word])

    # 2. Знаменательные слова — все однословные леммы WordNet.
    #
    #    Соблазн взять «обиходный слой по частоте» здесь ловушка. Читатель
    #    жмёт по слову ровно тогда, когда оно редкое: «serendipity» в корпусе
    #    Brown не встречается ни разу, а в романе стоит на видном месте. Отбор
    #    по частоте выбросил бы именно те слова, ради которых карточку и
    #    открывают, — и оставил бы вместо них алфавитный хвост нулевой частоты.
    #
    #    Ограничение `--top` остаётся для отладочных сборок; по умолчанию оно
    #    выключено.
    ranked = sorted(lemmas, key=lambda w: (-counts.get(w, 0), w))
    for word in ranked[:top] if top else ranked:
        words[word] |= lemmas[word]

    # 2a. Частые слова, которых WordNet не знает: модальные («would», «does») и
    #     неопределённые местоимения («everyone»). Без них не разобрать ни
    #     одного составного времени. Но добавлять всё подряд нельзя: корпус
    #     метит существительными и формы («states»), а они обязаны разбираться
    #     как множественное число, а не становиться отдельными леммами.
    known_irregular = {form for table in exceptions.values() for form in table}
    for word, code in brown_open.items():
        if counts[word] < min_missing or word in lemmas:
            continue
        if not usable(word) or len(word) < 3 or resolvable(word, words):
            continue
        # Неправильные формы корпус метит как обычные слова: «ran» приходит
        # глаголом, «children» — существительным. Стать отдельной леммой они не
        # должны, иначе «ran» перестало бы сводиться к «run» и попало бы в
        # колоду пользователя самостоятельным словом.
        if word in known_irregular:
            continue
        words[word].add(code)

    # 3. Леммы, на которые ссылаются неправильные формы. Без этого «children»
    #    свелось бы к «child», которого нет в списке, и слово осталось бы
    #    неопознанным.
    irregular: dict[str, str] = {}
    for code, table in exceptions.items():
        for form, lemma in table.items():
            if lemma not in lemmas or code not in lemmas[lemma]:
                # Форма ссылается на лемму, которой WordNet не знает в этой
                # части речи, — такой строке доверять нельзя.
                continue
            words[lemma] |= lemmas[lemma]
            if form != lemma:  # форма, совпавшая с леммой, ничего не даёт
                irregular[form] = lemma + "/" + code

    # Омонимы вроде «saw» (прошедшее от «see» и существительное «пила»)
    # намеренно остаются в обеих таблицах: ядро покажет разбор формы, но
    # отметит, что слово бывает и самостоятельной леммой.
    encoded = {w: "".join(sorted(c)) for w, c in words.items() if c}
    return encoded, irregular, counts, dominant


def render(encoded: dict, irregular: dict, counts: Counter, dominant: dict, top: int) -> str:
    """Собирает построчный TSV.

    Формат построчный, а не JSON, ради одного: ядро читает файл за один проход
    без разбора дерева, и старт приложения не упирается в парсер.

        W<TAB>слово<TAB>коды частей речи<TAB>zipf<TAB>уровень[<TAB>преобладающая]
        I<TAB>форма<TAB>лемма<TAB>код части речи

    Шестое поле необязательно: у слова, которого в корпусе почти нет, честнее
    его не писать, чем выдумать преобладание по двум вхождениям.
    """
    lines = [
        "# wolfy english lexicon v2",
        "# generated\t" + date.today().isoformat(),
        "# source\tPrinceton WordNet 3.0 — леммы и таблицы исключений (*.exc)",
        "# source\tBrown Corpus (NLTK, universal tagset) — частотность и служебные части речи",
        "# note\tуровень CEFR выведен из частотности, официальной разметкой не является",
        "# words\t" + str(len(encoded)),
        "# irregular\t" + str(len(irregular)),
        "# top\t" + str(top),
    ]
    for word in sorted(encoded):
        z = zipf(counts.get(word, 0))
        row = ["W", word, encoded[word], str(z), cefr_level(z)]
        # Преобладающая часть речи пишется, только если она у слова вообще
        # есть среди словарных: корпус метит «states» существительным, но для
        # словаря это форма глагола «state», и такая метка сбила бы разбор.
        main = dominant.get(word)
        if main and main in encoded[word]:
            row.append(main)
        lines.append("	".join(row))
    for form in sorted(irregular):
        lemma, code = irregular[form].split("/")
        lines.append("I\t" + form + "\t" + lemma + "\t" + code)
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Сборка лексикона ядра Wolfy")
    parser.add_argument(
        "--top",
        type=int,
        default=0,
        help="ограничить число знаменательных лемм по частоте Brown (0 — все)",
    )
    parser.add_argument(
        "--min-closed",
        type=int,
        default=3,
        help="минимальная частота служебного слова в Brown",
    )
    parser.add_argument(
        "--min-missing",
        type=int,
        default=20,
        help="минимальная частота слова, которого нет в WordNet",
    )
    args = parser.parse_args()

    try:
        encoded, irregular, counts, dominant = build(
            args.top, args.min_closed, args.min_missing
        )
    except LookupError as err:
        print("нет корпуса nltk: " + str(err), file=sys.stderr)
        return 1

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(render(encoded, irregular, counts, dominant, args.top), encoding="utf-8")
    size = TARGET.stat().st_size / 1024
    print(
        "{}: {} слов, {} неправильных форм, {:.0f} КБ".format(
            TARGET.relative_to(ROOT), len(encoded), len(irregular), size
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

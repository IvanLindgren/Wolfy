#!/usr/bin/env python3
"""Собирает таблицу форм неправильных глаголов для ядра Wolfy.

Грамматическому движку мало знать, что «went» — форма «go». Ему нужно, какая
именно: от этого зависит, Past Simple перед ним или Present Perfect. WordNet
такого не хранит — его `verb.exc` это список исключений *написания*, где
«went» и «gone» лежат рядом и неразличимы, «cut» попал за удвоение согласной,
а «read» отсутствует вовсе, потому что пишется одинаково во всех трёх формах.

Поэтому роли выводятся правилами (см. `split_forms`), а руками дописаны только
два класса, которые из WordNet не выводятся никак: глаголы с тремя одинаковыми
формами и глаголы, у которых причастие совпадает с начальной формой.

Запуск (корпуса ставятся один раз, см. build_lexicon.py):

    python tools/build_irregular_verbs.py

Результат — `core/data/irregular_verbs.tsv`, который ядро встраивает в бинарник.
"""

from __future__ import annotations

import argparse
import sys
import zipfile

from collections import defaultdict
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "core" / "data" / "irregular_verbs.tsv"
LEXICON = ROOT / "core" / "data" / "english_lexicon.tsv"


def read_verb_exceptions() -> list[tuple[str, str]]:
    """Пары «форма — начальная форма» из таблицы исключений WordNet.

    Файл лежит либо распакованным, либо внутри `wordnet.zip`, — nltk скачивает
    его и так, и так, в зависимости от версии.
    """
    import nltk

    for root in nltk.data.path:
        plain = Path(root) / "corpora" / "wordnet" / "verb.exc"
        if plain.exists():
            raw = plain.read_text(encoding="utf-8")
            break
        packed = Path(root) / "corpora" / "wordnet.zip"
        if packed.exists():
            with zipfile.ZipFile(packed) as archive:
                raw = archive.read("wordnet/verb.exc").decode("utf-8")
            break
    else:
        sys.exit(
            "не нашёлся verb.exc. Поставьте корпус:\n"
            '  python -c "import nltk; nltk.download(\'wordnet\')"'
        )

    pairs = []
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) >= 2:
            pairs.append((parts[0], parts[1]))
    return pairs


def read_known_verbs() -> set[str]:
    """Начальные формы, известные лексикону как глаголы.

    Таблица форм для слова, которого нет в словаре, бесполезна: карточка всё
    равно не сможет ничего о нём рассказать, а вес файла вырастет.
    """
    if not LEXICON.exists():
        sys.exit(f"нет {LEXICON}. Сначала запустите tools/build_lexicon.py")

    verbs = set()
    for line in LEXICON.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) >= 3 and parts[0] == "W" and "v" in parts[2]:
            verbs.add(parts[1])
    return verbs


def regular_spellings(base: str) -> set[str]:
    """Формы, которые строятся от основы по обычным правилам.

    Нужны, чтобы отсеять записи, попавшие в WordNet из-за орфографии, а не из-за
    неправильности: «abetted» стоит в исключениях только потому, что согласная
    удвоилась, а глагол при этом самый обычный.
    """
    out = {base + "s", base + "es", base + "ing", base + "ed", base + "d"}

    # Немая «e» перед окончанием: «make» → «making».
    if base.endswith("e"):
        stem = base[:-1]
        out |= {stem + "ing", stem + "ed"}

    # Удвоение конечной согласной: «stop» → «stopped», «stopping».
    if len(base) >= 2 and base[-1].isalpha() and base[-1] not in "aeiouwxy":
        doubled = base + base[-1]
        out |= {doubled + "ing", doubled + "ed"}

    # «ie» перед «-ing» становится «y»: «lie» → «lying», «die» → «dying».
    if base.endswith("ie"):
        out.add(base[:-2] + "ying")

    # «k», вставленная после «c»: «panic» → «panicked», «panicking». Глагол
    # при этом обычный — в исключения он попал только из-за этой буквы.
    if base.endswith("c"):
        out |= {base + "ked", base + "king", base + "ks"}

    # «y» после согласной становится «i»: «carry» → «carried», «carries».
    if base.endswith("y") and len(base) >= 2 and base[-2] not in "aeiou":
        stem = base[:-1]
        out |= {stem + "ied", stem + "ies"}

    return out


def split_forms(base: str, forms: list[str]) -> tuple[list[str], list[str]]:
    """Делит формы глагола на вторую и третью.

    WordNet кладёт их вперемешку, но английское причастие узнаётся по виду:

    * «taken», «written», «gone» — причастие оканчивается на «-n», а вторая
      форма нет;
    * «began»/«begun», «sang»/«sung» — обе оканчиваются одинаково, и различает
      их гласная: «a» во второй форме, «u» в третьей;
    * «bought», «kept», «said» — форма одна на оба места, так в английском
      устроено большинство неправильных глаголов.

    Всё, что не разложилось ни по одному признаку, возвращается пустым: пусть
    лучше глагола не будет в таблице, чем он попадёт туда с перепутанными
    формами. Такие случаи печатаются при сборке и дописываются руками.
    """
    forms = sorted(set(forms) - {base})
    if not forms:
        return [], []

    if len(forms) == 1:
        return forms, forms

    if len(forms) == 2:
        first, second = forms
        # Различие в одну гласную: «began» — «begun», «sang» — «sung».
        vowel = vowel_pair(first, second)
        if vowel:
            return vowel
        # Причастие на «-n»: «took» — «taken», «flew» — «flown».
        ends_n = [f for f in forms if is_participle_shape(f)]
        if len(ends_n) == 1:
            past = first if second in ends_n else second
            return [past], ends_n
        return [], []

    # Три формы и больше — это варианты написания («learned»/«learnt») или
    # редкие случаи вроде «bear». Разбираем по тем же признакам, но группой.
    ends_n = [f for f in forms if is_participle_shape(f)]
    rest = [f for f in forms if not is_participle_shape(f)]
    if ends_n and rest:
        return rest, ends_n
    return [], []


def is_participle_shape(form: str) -> bool:
    """Похожа ли форма на третью по одному только виду.

    Признак — «-n» на конце, возможно с немой «e»: «taken», «flown», но и
    «gone», «done», «borne». Без учёта этой «e» правило теряло бы ровно те
    глаголы, ради которых таблица и затевалась.
    """
    return form.endswith("n") or form.endswith("ne")


def vowel_pair(first: str, second: str) -> tuple[list[str], list[str]] | None:
    """Пара «began» — «begun»: слова различаются одной гласной «a»/«u»."""
    if len(first) != len(second):
        return None
    diff = [i for i, (a, b) in enumerate(zip(first, second)) if a != b]
    if len(diff) != 1:
        return None
    a, b = first[diff[0]], second[diff[0]]
    if {a, b} != {"a", "u"}:
        return None
    past = first if a == "a" else second
    participle = second if a == "a" else first
    return [past], [participle]


# Глаголы, у которых все три формы совпадают. В WordNet их нет: исключение
# написания там заводят, только когда форма пишется иначе, а у этих она
# буква в букву совпадает с начальной.
INVARIANT = """
bet bid burst cast cost cut hit hurt let put quit read rid set shed shut slit
split spread thrust upset wed broadcast forecast
""".split()

# Глаголы, у которых с начальной формой совпадает только причастие: «come —
# came — come». По той же причине WordNet знает у них лишь вторую форму, и
# правило «одна форма на оба места» дало бы «came» там, где нужно «come».
PARTICIPLE_IS_BASE = "become come overcome overrun run".split()

# Два глагола, которые не подчиняются ни одному правилу, потому что спрягаются
# не как остальные: у «be» пять личных форм вместо двух, у «have» третье лицо
# «has» короче основы. Оба — вспомогательные, и грамматический движок без них
# не соберёт ни одного времени, так что задаём их прямо.
SPECIAL = {
    "be": (["was", "were"], ["been"]),
    "have": (["had"], ["had"]),
    # «got» стоит и во второй, и в третьей форме: «gotten» осталось в
    # американском английском, британское «I have got» встречается не реже.
    "get": (["got"], ["got", "gotten"]),
}


def build(verbose: bool) -> list[tuple[str, list[str], list[str]]]:
    known = read_known_verbs()

    by_base: dict[str, list[str]] = defaultdict(list)
    for form, base in read_verb_exceptions():
        # Составные вроде «give_up» разбирать незачем: читатель тыкает в одно
        # слово, и таблица индексируется по одному слову.
        if "_" in base or "_" in form or "-" in base:
            continue
        by_base[base].append(form)

    table: dict[str, tuple[list[str], list[str]]] = {}
    unresolved = []

    for base, forms in by_base.items():
        if base not in known:
            continue
        regular = regular_spellings(base)
        core = [f for f in forms if f not in regular]
        if not core:
            continue
        past, participle = split_forms(base, core)
        if not past:
            unresolved.append((base, sorted(core)))
            continue
        table[base] = (past, participle)

    for base, (past, participle) in SPECIAL.items():
        if base in known:
            table[base] = (past, participle)

    for base in INVARIANT:
        if base in known:
            table[base] = ([base], [base])

    for base in PARTICIPLE_IS_BASE:
        if base in known:
            past = table.get(base, ([], []))[0]
            if past:
                table[base] = (past, [base])

    # О глаголах, заданных руками, сообщать незачем: правила на них и не
    # рассчитаны — их для того и выписали.
    unresolved = [(base, forms) for base, forms in unresolved if base not in table]

    if verbose and unresolved:
        print(f"не разложились по правилам ({len(unresolved)}):", file=sys.stderr)
        for base, forms in sorted(unresolved)[:40]:
            print(f"  {base}: {' '.join(forms)}", file=sys.stderr)

    return [(base, *table[base]) for base in sorted(table)]


def write(rows: list[tuple[str, list[str], list[str]]]) -> None:
    lines = [
        "# wolfy irregular verbs v1",
        f"# generated\t{date.today().isoformat()}",
        "# source\tPrinceton WordNet 3.0 verb.exc + правила tools/build_irregular_verbs.py",
        "# format\tbase<TAB>вторая форма<TAB>третья форма, варианты через |",
    ]
    for base, past, participle in rows:
        lines.append(f"{base}\t{'|'.join(past)}\t{'|'.join(participle)}")

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--quiet",
        action="store_true",
        help="не печатать глаголы, которые не разложились по правилам",
    )
    args = parser.parse_args()

    rows = build(verbose=not args.quiet)
    write(rows)

    size = TARGET.stat().st_size
    print(f"{TARGET.relative_to(ROOT)}: {len(rows)} глаголов, {size // 1024} КБ")


if __name__ == "__main__":
    main()

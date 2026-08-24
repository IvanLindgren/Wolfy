/**
 * Состояние колод.
 *
 * Считает ядро (`srs/training.rs`), а не экран. Причина не в чистоте слоёв:
 * колода грамматики наполняется сама — новые правила подмешиваются порцией в
 * день, — и повторить это правило в интерфейсе значит однажды показать на
 * телефоне и в браузере разное число «к повторению» на одних и тех же данных.
 */

import { useEffect, useState } from 'react'

import { session, useSession } from '../core/session'
import type { CardKind, DeckStatus } from '../core/types'

export const DECKS: { kind: CardKind; title: string; hint: string }[] = [
  { kind: 'word', title: 'Слова', hint: 'То, что вы отметили в книгах' },
  { kind: 'phrase', title: 'Фразы', hint: 'Предложения целиком, с разбором' },
  { kind: 'rule', title: 'Грамматика', hint: 'Правила, которые встретились в тексте' },
]

/** Состояние всех трёх колод. Пересчитывается при каждой правке библиотеки. */
export function useDeckStatuses(): DeckStatus[] {
  const revision = useSession((state) => state.library.revision)
  const ready = useSession((state) => state.ready)
  const [statuses, setStatuses] = useState<DeckStatus[]>([])

  useEffect(() => {
    if (!ready) return
    let alive = true
    void Promise.all(DECKS.map((deck) => session.deckStatus(deck.kind))).then((found) => {
      if (!alive) return
      setStatuses(found.filter((status): status is DeckStatus => !!status))
    })
    return () => {
      alive = false
    }
  }, [ready, revision])

  return statuses
}

/** Сколько всего созрело — то число, что горит отметкой на значке раздела. */
export function useDueCount(): number {
  const statuses = useDeckStatuses()
  return statuses.reduce((sum, status) => sum + status.due, 0)
}

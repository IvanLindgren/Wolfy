import type { Grammar, PosTag, Sense } from '../core/types'

/**
 * Часть речи именно выбранного токена, а не наиболее частое значение леммы.
 *
 * Новое ядро отдаёт точный результат теггера в `parts`. Для старой WASM из
 * кеша остаётся синтаксическая группа: её цвет тоже является частью речи,
 * выбранной ядром по контексту. Сначала ищем группу, где слово — вершина,
 * затем любую содержащую его группу.
 */
export function contextualPos(
  grammar: Grammar | null | undefined,
  selectedToken: number | undefined,
): PosTag | undefined {
  if (selectedToken === undefined || selectedToken < 0) return undefined

  const tagged = grammar?.parts?.find((part) => part.token === selectedToken)
  if (tagged) return tagged.pos

  const chunks = grammar?.chunks ?? []
  return (
    chunks.find((chunk) => chunk.head === selectedToken)?.tint ??
    chunks.find((chunk) => chunk.start <= selectedToken && selectedToken < chunk.end)?.tint
  )
}

/** Толкование главной части речи важнее порядка статей в словаре. */
export function primarySense(senses: Sense[], contextPos?: string): Sense | undefined {
  return senses.find((sense) => contextPos && sense.pos === contextPos) ?? senses[0]
}

/** Остальные значения без уже показанного главного — независимо от его места. */
export function otherSenses(
  senses: Sense[],
  mainSense: Sense | undefined,
  limit = 5,
): Sense[] {
  const mainIndex = mainSense ? senses.indexOf(mainSense) : -1
  return senses.filter((_, index) => index !== mainIndex).slice(0, limit)
}

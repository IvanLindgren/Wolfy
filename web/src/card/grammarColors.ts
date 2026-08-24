/**
 * Цвет грамматики.
 *
 * Краску в вебе получают только части речи в самой фразе — пять цветов на всю
 * карточку. Семейство правил краски больше не получает: шесть пастельных
 * плашек поверх пяти цветов частей речи давали одиннадцать красок на одном
 * экране, и разбор переставал читаться. Семейство по-прежнему названо словом
 * (`FAMILY_TITLES`), и это единственное, что читателю от него нужно.
 *
 * Отнесение к семейству повторяет `RuleFamilyColors.forFamily` в клиенте на
 * Kotlin — по имени правила, а не по отдельной таблице. Таблица разошлась бы
 * с движком на первой же новой конструкции; имя правила приходит из ядра и
 * разойтись не может.
 *
 * Цвет не должен быть единственным носителем смысла — поэтому рядом с каждым
 * цветным словом стоит подпись части речи, а под фразой — легенда.
 */

import type { PosTag, RoleName } from '../core/types'

export type Family =
  | 'tense'
  | 'voice'
  | 'mood'
  | 'condition'
  | 'comparison'
  | 'reference'

export const FAMILY_TITLES: Record<Family, string> = {
  tense: 'Время',
  voice: 'Залог',
  mood: 'Наклонение',
  condition: 'Условие',
  comparison: 'Сравнение',
  reference: 'Ссылка',
}

export function familyOf(rule: string): Family {
  if (rule.includes('passive') || rule.includes('voice')) return 'voice'
  if (rule.includes('conditional') || rule.startsWith('if-')) return 'condition'
  if (rule.includes('compar') || rule.includes('superlative')) return 'comparison'
  if (rule.includes('relative') || rule.includes('reported') || rule.includes('reference')) {
    return 'reference'
  }
  if (rule.includes('subjunctive') || rule.includes('wish') || rule.includes('modal')) {
    return 'mood'
  }
  return 'tense'
}

/**
 * Цвет части речи.
 *
 * Пять цветов, а не десять: цвет получают знаменательные части речи и
 * местоимения — то, что помогает разобрать структуру фразы. Предлоги, артикли
 * и союзы остаются цветом чернил, иначе страница превращается в светофор.
 */
export function posColor(tag: PosTag | undefined): string | undefined {
  switch (tag) {
    case 'NOUN':
      return 'var(--pos-noun)'
    case 'VERB':
      return 'var(--pos-verb)'
    case 'ADJ':
      return 'var(--pos-adj)'
    case 'ADV':
      return 'var(--pos-adv)'
    case 'PRON':
      return 'var(--pos-pron)'
    default:
      return undefined
  }
}

export const POS_TITLES: Record<string, string> = {
  NOUN: 'существительное',
  VERB: 'глагол',
  ADJ: 'прилагательное',
  ADV: 'наречие',
  PRON: 'местоимение',
  DET: 'определитель',
  ADP: 'предлог',
  CONJ: 'союз',
  PART: 'частица',
  PRT: 'частица',
  NUM: 'числительное',
}

export const ROLE_TITLES: Record<RoleName, string> = {
  subject: 'подлежащее',
  predicate: 'сказуемое',
  object: 'дополнение',
  complement: 'дополнение сказуемого',
  adverbial: 'обстоятельство',
  connector: 'связка',
}

export const MARKER_TITLES: Record<string, string> = {
  auxiliary: 'вспомогательный глагол',
  ending: 'окончание',
  particle: 'частица',
  preposition: 'предлог',
}

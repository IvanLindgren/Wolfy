/**
 * Толкование и произношение: сначала офлайн-словарь, потом сеть.
 *
 * Порядок именно такой. Локальный словарь отвечает за микросекунды и работает
 * в самолёте; сеть — запасной путь для тех, кто словарь не скачал. Обратный
 * порядок означал бы ждать сеть там, где ответ уже лежит на устройстве.
 *
 * Различать «слова нет в исправном словаре» и «словаря нет» обязательно:
 * только во втором случае имеет смысл идти в сеть. Ядро отвечает на это
 * отдельным полем `dictionaryAvailable` — не зря.
 */

import { useEffect, useState } from 'react'

import * as api from '../api/client'
import * as bridge from '../core/bridge'
import type { DictionaryEntry } from '../core/types'
import { cacheDefinition, cachedDefinition } from '../storage/idb'

export type Definition =
  | { state: 'idle' }
  | { state: 'loading' }
  | { state: 'ready'; entry: DictionaryEntry }
  /** Слова нет в базе — либо словаря нет и сети тоже. */
  | { state: 'missing' }

export function useDefinition(lemma: string): Definition {
  const [state, setState] = useState<Definition>({ state: 'idle' })

  useEffect(() => {
    const word = lemma.trim().toLowerCase()
    if (!word) {
      setState({ state: 'idle' })
      return
    }

    const controller = new AbortController()
    let alive = true
    setState({ state: 'loading' })

    void (async () => {
      const cached = await cachedDefinition<DictionaryEntry>(word)
      if (!alive) return
      if (cached) {
        setState({ state: 'ready', entry: cached })
        return
      }

      const local = await bridge.define(word)
      if (!alive) return
      if (local.entry) {
        setState({ state: 'ready', entry: local.entry })
        await cacheDefinition(word, local.entry)
        return
      }
      if (local.available) {
        // Словарь исправен и слова в нём нет. Идти в сеть за тем же ответом
        // незачем — база там та же самая.
        setState({ state: 'missing' })
        return
      }

      try {
        const remote = await api.define(word, controller.signal)
        if (!alive) return
        setState({ state: 'ready', entry: remote })
        await cacheDefinition(word, remote)
      } catch {
        if (alive) setState({ state: 'missing' })
      }
    })()

    return () => {
      alive = false
      controller.abort()
    }
  }, [lemma])

  return state
}

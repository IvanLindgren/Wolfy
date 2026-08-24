/**
 * Контекстный перевод.
 *
 * Четыре правила, и все четыре — из §4.5 задания.
 *
 * 1. **Запрос уходит параллельно открытию карточки и не блокирует её.**
 *    Карточка появляется с локальным разбором за считанные миллисекунды;
 *    перевод вставляется в уже открытую.
 * 2. **Запросы дедуплицируются** по паре «предложение + слово»: два тапа по
 *    одному слову — один запрос.
 * 3. **Результат кладётся в IndexedDB.** Вторая встреча того же предложения
 *    бесплатна и работает офлайн.
 * 4. **Отмена при закрытии карточки обязательна.** Читатель, пролиставший
 *    десять слов, не должен оставить десять висящих запросов — за каждым
 *    стоит платный сервис.
 *
 * Двух переводов, а не одного: слово отдельно — это словарная строка
 * («library — библиотека»), предложение целиком отвечает на другой вопрос —
 * что здесь вообще сказано. Подменять первое вторым нельзя.
 */

import { useEffect, useState } from 'react'

import * as api from '../api/client'
import { cacheTranslation, cachedTranslation, translationKey } from '../storage/idb'

export type Translation =
  | { state: 'idle' }
  | { state: 'loading' }
  | { state: 'ready'; word: string; sentence: string }
  | { state: 'failed'; message: string }

/** Идущие запросы: ключ → обещание. Второй тап по тому же слову ждёт первый. */
const inflight = new Map<string, Promise<string>>()

async function once(key: string, text: string, signal: AbortSignal): Promise<string> {
  const cached = await cachedTranslation(key)
  if (cached !== null) return cached

  const running = inflight.get(key)
  if (running) return running

  const request = api
    .translate(text, signal)
    .then(async (translated) => {
      if (translated) await cacheTranslation(key, translated)
      return translated
    })
    .finally(() => inflight.delete(key))

  inflight.set(key, request)
  return request
}

/**
 * Перевод слова в контексте предложения.
 *
 * `word` пустое — переводим только предложение: так работает карточка фразы.
 */
export function useTranslation(word: string, sentence: string): Translation {
  const [state, setState] = useState<Translation>({ state: 'idle' })

  useEffect(() => {
    if (!sentence.trim() && !word.trim()) {
      setState({ state: 'idle' })
      return
    }

    const controller = new AbortController()
    let alive = true
    setState({ state: 'loading' })

    void (async () => {
      try {
        // Оба перевода уходят разом: они независимы, и ждать один ради
        // другого значит удвоить время до строки на экране.
        const [wordText, sentenceText] = await Promise.all([
          word.trim()
            ? once(translationKey('', word), word, controller.signal)
            : Promise.resolve(''),
          sentence.trim()
            ? once(translationKey(sentence, ''), sentence, controller.signal)
            : Promise.resolve(''),
        ])
        if (!alive) return
        setState({ state: 'ready', word: wordText, sentence: sentenceText })
      } catch (error) {
        if (!alive) return
        if (error instanceof DOMException && error.name === 'AbortError') return
        setState({
          state: 'failed',
          message:
            error instanceof api.OfflineError
              ? 'Перевод появится, когда будет сеть'
              : error instanceof api.ApiError
                ? error.message
                : 'Перевод сейчас недоступен',
        })
      }
    })()

    return () => {
      alive = false
      // Карточку закрыли — запрос больше никому не нужен.
      controller.abort()
    }
  }, [word, sentence])

  return state
}

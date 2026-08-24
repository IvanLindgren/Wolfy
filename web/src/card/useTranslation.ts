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
 * Двух переводов, а не одного: для слова отправляется короткий текст вместе с
 * предложением в поле `context`, чтобы `book` в «I will book…» не становилось
 * «книгой». Предложение целиком отвечает на другой вопрос — что здесь вообще
 * сказано. Подменять первое вторым нельзя.
 */

import { useEffect, useState } from 'react'

import * as api from '../api/client'
import { cacheTranslation, cachedTranslation, translationKey } from '../storage/idb'

export type Translation =
  | { state: 'idle' }
  | { state: 'loading' }
  | { state: 'ready'; word: string; sentence: string }
  | { state: 'failed'; message: string }

interface InflightTranslation {
  promise: Promise<string>
  controller: AbortController
  subscribers: number
}

/** Идущие запросы: второй потребитель ждёт первый, но не владеет его abort. */
const inflight = new Map<string, InflightTranslation>()

function abortError(): DOMException {
  return new DOMException('Перевод больше не нужен', 'AbortError')
}

async function subscribe(
  key: string,
  entry: InflightTranslation,
  signal: AbortSignal,
): Promise<string> {
  if (signal.aborted) throw abortError()
  entry.subscribers += 1

  let rejectAbort: ((reason: DOMException) => void) | undefined
  const cancelled = new Promise<never>((_, reject) => {
    rejectAbort = reject
  })
  const onAbort = () => rejectAbort?.(abortError())
  signal.addEventListener('abort', onAbort, { once: true })

  try {
    return await Promise.race([entry.promise, cancelled])
  } finally {
    signal.removeEventListener('abort', onAbort)
    entry.subscribers = Math.max(0, entry.subscribers - 1)
    if (entry.subscribers === 0 && inflight.get(key) === entry) {
      // Удаляем до abort: мгновенно открытая заново карточка должна создать
      // свежий запрос, а не подписаться на уже отменённое обещание.
      inflight.delete(key)
      entry.controller.abort()
    }
  }
}

async function once(
  key: string,
  text: string,
  context: string,
  signal: AbortSignal,
): Promise<string> {
  const cached = await cachedTranslation(key)
  if (signal.aborted) throw abortError()
  if (cached !== null) return cached

  let entry = inflight.get(key)
  if (!entry || entry.controller.signal.aborted) {
    const controller = new AbortController()
    entry = {
      controller,
      subscribers: 0,
      promise: Promise.resolve(''),
    }
    const current = entry
    entry.promise = api
      .translate(text, controller.signal, { context })
      .then(async (translated) => {
        if (translated) await cacheTranslation(key, translated)
        return translated
      })
      .finally(() => {
        if (inflight.get(key) === current) inflight.delete(key)
      })
    inflight.set(key, entry)
  }

  return subscribe(key, entry, signal)
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
            ? once(translationKey(sentence, word), word, sentence, controller.signal)
            : Promise.resolve(''),
          sentence.trim()
            ? once(translationKey(sentence, ''), sentence, '', controller.signal)
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

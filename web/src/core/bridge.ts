/**
 * Мост в воркер: единственное место, где интерфейс разговаривает с ядром.
 *
 * Comlink убирает ручной `postMessage`-протокол: вызов в воркере выглядит как
 * обычный `await`. Взамен он требует помнить две вещи, и обе здесь учтены.
 *
 * 1. **Функции через границу не летят.** Обратный вызов (прогресс загрузки
 *    словаря) заворачивается в `Comlink.proxy`.
 * 2. **Буферы летят с копией, если не сказать иначе.** Файл книги уходит
 *    `Comlink.transfer` — пять мегабайт копировать незачем.
 *
 * Воркер один на вкладку и создаётся лениво: пока читатель не открыл ничего,
 * ядро не нужно.
 */

import * as Comlink from 'comlink'

import type { Boot, CoreApi, OpenedBook } from '../../workers/core.worker'
import type {
  AppSettings,
  Chapter,
  Command,
  DictionaryEntry,
  Exercises,
  Grammar,
  LibraryState,
  Outcome,
  Reference,
  TokenizedText,
  WordAnalysis,
} from './types'

let worker: Worker | null = null
let remote: Comlink.Remote<CoreApi> | null = null

function core(): Comlink.Remote<CoreApi> {
  if (!remote) {
    worker = new Worker(new URL('../../workers/core.worker.ts', import.meta.url), {
      type: 'module',
      name: 'wolfy-core',
    })
    remote = Comlink.wrap<CoreApi>(worker)
  }
  return remote
}

/** Поднимает ядро. Зовётся один раз при старте приложения. */
export function boot(): Promise<Boot> {
  return core().boot()
}

export function loadLexicon(): Promise<boolean> {
  return core().loadLexicon()
}

export function loadDictionary(
  onProgress: (loaded: number, total: number) => void,
): Promise<boolean> {
  return core().loadDictionary(Comlink.proxy(onProgress))
}

export function cancelDictionary(): Promise<void> {
  return core().cancelDictionary()
}

export function restoreDictionary(): Promise<boolean> {
  return core().restoreDictionary()
}

/** Выполняет команду сессии. Всё изменение состояния идёт только отсюда. */
export function command(cmd: Command): Promise<Outcome> {
  return core().command(cmd)
}

export function library(): Promise<LibraryState> {
  return core().library()
}

export function settings(): Promise<AppSettings> {
  return core().settings()
}

export function analyzeWord(word: string): Promise<WordAnalysis> {
  return core().analyzeWord(word)
}

export function tokenize(text: string): Promise<TokenizedText> {
  return core().tokenize(text)
}

export function explain(sentence: string): Promise<Grammar> {
  return core().explain(sentence)
}

export function grammarReference(): Promise<Reference> {
  return core().grammarReference()
}

export function grammarExercises(): Promise<Exercises> {
  return core().grammarExercises()
}

/** Подстрочник фразы: начальная форма и короткий перевод каждого слова. */
export function gloss(
  words: string[],
): Promise<{ lemma: string; translation: string; pos: string }[]> {
  return core().gloss(words)
}

export function define(word: string): Promise<{
  entry: DictionaryEntry | null
  available: boolean
}> {
  return core().define(word)
}

/**
 * Отдаёт файл книги ядру.
 *
 * Буфер уходит `transfer`, то есть переезжает в воркер целиком и здесь
 * становится пустым. Это намеренно: копировать пятимегабайтный EPUB ради
 * того, чтобы главный поток подержал его вторую копию, незачем.
 */
export function importBook(
  id: string,
  fileName: string,
  bytes: ArrayBuffer,
): Promise<OpenedBook> {
  return core().importBook(id, fileName, Comlink.transfer(bytes, [bytes]))
}

export function importPages(
  id: string,
  title: string,
  pages: string[],
): Promise<OpenedBook> {
  return core().importPages(id, title, pages)
}

export function openBook(
  id: string,
  path: string,
  title: string | null,
): Promise<OpenedBook> {
  return core().openBook(id, path, title)
}

export function chapter(id: string, index: number): Promise<Chapter> {
  return core().chapter(id, index)
}

export function resource(id: string, path: string): Promise<Uint8Array | null> {
  return core().resource(id, path)
}

/** Байты обложки книги из хранилища. `null` — обложки нет. */
export function cover(path: string): Promise<Uint8Array | null> {
  return core().cover(path)
}

export function bookText(path: string): Promise<string> {
  return core().bookText(path)
}

export function closeBook(id: string): Promise<void> {
  return core().closeBook(id)
}

export function removeBookFile(id: string, path: string): Promise<void> {
  return core().removeBookFile(id, path)
}

export type { Boot, OpenedBook }

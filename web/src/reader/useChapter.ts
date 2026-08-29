/**
 * Глава: блоки, токены и предложения.
 *
 * Токенизация делается **одним вызовом на главу**, а не по абзацу. Причина
 * практическая: у ядра в воркере каждый вызов это сообщение туда и обратно,
 * а в главе шестьдесят абзацев — шестьдесят пересылок вместо одной.
 *
 * Склейка абзацев повторяет `Chapter::plain_text` в ядре — пустая строка
 * между блоками. Это не совпадение и не удобство: именно по пустой строке
 * детектор предложений не склеивает конец одного абзаца с началом
 * следующего, и токены, нарезанные здесь, обязаны быть теми же самыми, что
 * ядро нарежет из того же текста.
 */

import { useEffect, useMemo, useState } from 'react'

import * as bridge from '../core/bridge'
import type { Block, CompactChain, PreparedChapter, Sentence, Token } from '../core/types'

/** Абзац с уже нарезанными токенами. */
export interface TokenizedBlock {
  block: Block
  /** Номер блока в главе — по нему React отличает абзацы между перерисовками. */
  index: number
  /** Токены этого блока. Номера — общие по главе, а не местные. */
  tokens: Token[]
  /** Номер первого токена блока в общей нумерации главы. */
  offset: number
}

/**
 * Цепочка сказуемого в номерах токенов.
 *
 * Ядро отдаёт её в символах, а вся читалка живёт в номерах токенов — перевод
 * делается один раз при загрузке главы, а не на каждое касание.
 */
export interface TokenChain {
  start: number
  end: number
  /** Номер токена смыслового глагола. */
  main: number
}

export interface LoadedChapter {
  title: string | null
  blocks: TokenizedBlock[]
  /** Все токены главы подряд — по ним считается положение и прогресс. */
  tokens: Token[]
  sentences: Sentence[]
  /** Группы сказуемого: по ним тап по связке берёт всю цепочку. */
  chains: TokenChain[]
  /** Текст главы целиком: из него берётся контекст предложения для перевода. */
  text: string
}

const EMPTY: LoadedChapter = {
  title: null,
  blocks: [],
  tokens: [],
  sentences: [],
  chains: [],
  text: '',
}

/**
 * Символьные границы цепочки — в номера токенов.
 *
 * Токен считается принадлежащим цепочке, если целиком лежит внутри неё:
 * половинки на границе означали бы, что выделение начинается с середины слова.
 */
export function chainsOf(tokens: Token[], raw: CompactChain[] | undefined): TokenChain[] {
  if (!raw?.length) return []
  const out: TokenChain[] = []
  for (const chain of raw) {
    let start = -1
    let end = -1
    let main = -1
    tokens.forEach((token, index) => {
      if (token.start < chain.start || token.end > chain.end) return
      if (start < 0) start = index
      end = index + 1
      if (main < 0 && token.start >= chain.mainStart) main = index
    })
    if (start < 0 || end <= start) continue
    out.push({ start, end, main: main < 0 ? end : main })
  }
  return out
}

export interface ChapterLoad {
  chapter: LoadedChapter
  /** Идёт ли загрузка. Спиннера при этом нет: на экране остаётся прошлая глава. */
  loading: boolean
  error: string | null
}

export function useChapter(bookId: string, index: number, opened: boolean): ChapterLoad {
  const [prepared, setPrepared] = useState<{ key: string; data: PreparedChapter } | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [tokenized, setTokenized] = useState<{ key: string; loaded: LoadedChapter } | null>(
    null,
  )

  const key = `${bookId}:${index}`

  useEffect(() => {
    if (!opened) return
    let alive = true
    setLoading(true)
    setError(null)

    // §15: один тяжёлый переход вместо chapter + tokenize
    void bridge
      .preparedChapter(bookId, index)
      .then((data) => {
        if (alive) setPrepared({ key, data })
      })
      .catch(async (_problem: unknown) => {
        // Fallback для старой WASM без нового API
        try {
          const chapter = await bridge.chapter(bookId, index)
          const text = chapter.blocks
            .filter((b) => b.text)
            .map((b) => b.text)
            .join('\n\n')
          const parsed = await bridge.tokenize(text)
          const fallback: PreparedChapter = {
            title: chapter.title,
            blocks: chapter.blocks,
            tokens: parsed.tokens.map((t) => ({ kind: t.kind, start: t.start, end: t.end })),
            sentences: parsed.sentences.map((s) => ({
              start: s.start,
              end: s.end,
              firstToken: s.firstToken,
              lastToken: s.lastToken,
            })),
          }
          if (alive) setPrepared({ key, data: fallback })
        } catch (e: unknown) {
          if (!alive) return
          setError(e instanceof Error ? e.message : 'Главу не удалось прочитать')
          setLoading(false)
        }
      })

    return () => {
      alive = false
    }
  }, [bookId, index, key, opened])

  useEffect(() => {
    if (!prepared || prepared.key !== key) return
    let alive = true

    const data = prepared.data
    const texts = data.blocks.map((block) => block.text ?? '')
    const text = data.blocks
      .filter((block) => block.text)
      .map((block) => block.text)
      .join('\n\n')

    // Восстанавливаем текст токенов нарезанием строки главы (UTF-16)
    const tokens: Token[] = data.tokens.map((t) => ({
      kind: t.kind,
      start: t.start,
      end: t.end,
      text: text.slice(t.start, t.end),
    }))
    const sentences: Sentence[] = data.sentences.map((s) => ({
      start: s.start,
      end: s.end,
      firstToken: s.firstToken,
      lastToken: s.lastToken,
      text: text.slice(s.start, s.end),
    }))

    // assign остаётся идентичным — проверка регрессии token indexes
    const loaded: LoadedChapter = {
      title: data.title,
      blocks: assign(data.blocks, texts, tokens),
      tokens,
      sentences,
      chains: chainsOf(tokens, data.chains),
      text,
    }
    if (alive) {
      setTokenized({ key, loaded })
      setLoading(false)
    }

    return () => {
      alive = false
    }
  }, [prepared, key])

  const chapter = tokenized?.key === key ? tokenized.loaded : EMPTY
  return useMemo(() => ({ chapter, loading, error }), [chapter, loading, error])
}

/**
 * Раздаёт токены по блокам.
 *
 * Токены идут подряд и несут свои границы в исходной строке, поэтому
 * распределение — это один проход с указателем: пока токен помещается в
 * текущий блок, он его; кончился блок — переходим к следующему.
 */
function assign(blocks: Block[], texts: string[], tokens: Token[]): TokenizedBlock[] {
  const out: TokenizedBlock[] = []
  let at = 0 // позиция начала блока в склеенном тексте
  let cursor = 0 // сколько токенов уже разобрано

  blocks.forEach((block, index) => {
    const text = texts[index] ?? ''
    if (!block.text) {
      // Разделитель и картинка текста не несут и токенов не получают.
      out.push({ block, index, tokens: [], offset: cursor })
      return
    }

    const end = at + text.length
    const first = cursor
    const mine: Token[] = []
    while (cursor < tokens.length && tokens[cursor]!.start < end) {
      mine.push(tokens[cursor]!)
      cursor += 1
    }

    out.push({ block, index, tokens: mine, offset: first })
    // Плюс два — та самая пустая строка между блоками.
    at = end + 2
  })

  return out
}

/**
 * Предложение, внутри которого стоит токен.
 *
 * Разбору грамматики нужно предложение целиком: он смотрит на соседей, и
 * обрывок фразы разберётся хуже, чем фраза целиком.
 */
export function sentenceAt(chapter: LoadedChapter, token: number): Sentence | null {
  for (const sentence of chapter.sentences) {
    if (token >= sentence.firstToken && token < sentence.lastToken) return sentence
  }
  return null
}

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
import type { Block, Chapter, Sentence, Token } from '../core/types'

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

export interface LoadedChapter {
  title: string | null
  blocks: TokenizedBlock[]
  /** Все токены главы подряд — по ним считается положение и прогресс. */
  tokens: Token[]
  sentences: Sentence[]
  /** Текст главы целиком: из него берётся контекст предложения для перевода. */
  text: string
}

const EMPTY: LoadedChapter = {
  title: null,
  blocks: [],
  tokens: [],
  sentences: [],
  text: '',
}

export interface ChapterLoad {
  chapter: LoadedChapter
  /** Идёт ли загрузка. Спиннера при этом нет: на экране остаётся прошлая глава. */
  loading: boolean
  error: string | null
}

export function useChapter(bookId: string, index: number, opened: boolean): ChapterLoad {
  const [raw, setRaw] = useState<{ key: string; chapter: Chapter } | null>(null)
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

    void bridge
      .chapter(bookId, index)
      .then((chapter) => {
        if (alive) setRaw({ key, chapter })
      })
      .catch((problem: unknown) => {
        if (!alive) return
        setError(problem instanceof Error ? problem.message : 'Главу не удалось прочитать')
        setLoading(false)
      })

    return () => {
      alive = false
    }
  }, [bookId, index, key, opened])

  useEffect(() => {
    if (!raw || raw.key !== key) return
    let alive = true

    const texts = raw.chapter.blocks.map((block) => block.text ?? '')
    const text = raw.chapter.blocks
      .filter((block) => block.text)
      .map((block) => block.text)
      .join('\n\n')

    void bridge.tokenize(text).then((parsed) => {
      if (!alive) return
      setTokenized({
        key,
        loaded: {
          title: raw.chapter.title,
          blocks: assign(raw.chapter.blocks, texts, parsed.tokens),
          tokens: parsed.tokens,
          sentences: parsed.sentences,
          text,
        },
      })
      setLoading(false)
    })

    return () => {
      alive = false
    }
  }, [raw, key])

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

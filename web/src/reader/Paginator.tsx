/**
 * Режим страниц: перелистывание вместо прокрутки.
 *
 * Страницы делает сам браузер — многоколоночная вёрстка с шириной колонки во
 * всю полосу. Своя разбивка на страницы означала бы измерять высоту каждой
 * строки и складывать их вручную, то есть переписать то, что движок и так
 * умеет, и получить дрожание строк при переносе.
 *
 * Перелистывание — сдвиг по горизонтали с инерцией бумаги: кривая `paper`
 * даёт быстрый старт и долгое успокоение, как у листа, ложащегося на место.
 */

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'

import { curveCss } from '../theme/motion'
import styles from './reader.module.css'

const GAP = 48

export interface PagerHandle {
  /** Всего страниц в главе. */
  pages: number
  page: number
  turn: (delta: number) => void
  go: (page: number) => void
  /** Открывает страницу, на которой стоит этот токен. */
  showToken: (token: number) => void
  /** Первый токен текущей страницы — по нему считается место в книге. */
  firstToken: () => number
}

interface PaginatorProps {
  children: React.ReactNode
  /** Пересобрать разбивку: сменилась глава, кегль или ширина окна. */
  resetKey: string
  onPage: (page: number, pages: number) => void
  /** Длительность перелистывания. Ноль — «меньше движения». */
  duration: number
  handle: React.MutableRefObject<PagerHandle | null>
}

export function Paginator({
  children,
  resetKey,
  onPage,
  duration,
  handle,
}: PaginatorProps) {
  const frame = useRef<HTMLDivElement>(null)
  const inner = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [pages, setPages] = useState(1)
  const [page, setPage] = useState(0)

  const step = width + GAP

  const measure = useCallback(() => {
    const box = frame.current
    const sheet = inner.current
    if (!box || !sheet) return
    const next = box.clientWidth
    setWidth(next)
    if (next > 0) {
      const total = Math.max(1, Math.round((sheet.scrollWidth + GAP) / (next + GAP)))
      setPages(total)
    }
  }, [])

  useLayoutEffect(() => {
    measure()
  }, [measure, resetKey, children])

  useEffect(() => {
    const box = frame.current
    if (!box || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => measure())
    observer.observe(box)
    return () => observer.disconnect()
  }, [measure])

  // Смена главы возвращает на первую страницу — но не смена кегля: там
  // читатель остаётся на месте, и место это задаётся токеном, а не номером
  // страницы.
  useEffect(() => {
    setPage(0)
  }, [resetKey])

  useEffect(() => {
    onPage(page, pages)
  }, [page, pages, onPage])

  const go = useCallback(
    (next: number) => setPage(clamp(next, 0, Math.max(0, pages - 1))),
    [pages],
  )

  const turn = useCallback(
    (delta: number) =>
      setPage((current) => clamp(current + delta, 0, Math.max(0, pages - 1))),
    [pages],
  )

  const showToken = useCallback(
    (token: number) => {
      const sheet = inner.current
      if (!sheet || step <= 0) return
      const element = sheet.querySelector<HTMLElement>(`[data-t="${token}"]`)
      if (!element) return
      // Смещение внутри многоколоночного потока и есть номер колонки.
      const left = element.offsetLeft - sheet.offsetLeft
      setPage(clamp(Math.round(left / step), 0, Math.max(0, pages - 1)))
    },
    [step, pages],
  )

  const firstToken = useCallback((): number => {
    const sheet = inner.current
    if (!sheet || step <= 0) return 0
    const edge = page * step - 4
    const all = sheet.querySelectorAll<HTMLElement>('[data-t]')
    for (const element of all) {
      if (element.offsetLeft - sheet.offsetLeft >= edge) {
        return Number(element.dataset.t)
      }
    }
    return 0
  }, [page, step])

  // Ручка отдаётся наружу в layout-эффекте, а не прямо в отрисовке: правка
  // чужого объекта во время рендера — тот самый побочный эффект, из-за
  // которого StrictMode и ругается, а данные разъезжаются в конкурентном
  // режиме.
  useLayoutEffect(() => {
    handle.current = { pages, page, turn, go, showToken, firstToken }
  }, [handle, pages, page, turn, go, showToken, firstToken])

  return (
    <div className={styles.pages} ref={frame}>
      <div
        className={styles.pages__inner}
        ref={inner}
        style={{
          columnWidth: width ? `${width}px` : undefined,
          columnGap: `${GAP}px`,
          transform: `translateX(${-page * step}px)`,
          transition: duration
            ? `transform ${duration}ms ${curveCss.paper}`
            : 'none',
        }}
      >
        {children}
      </div>
    </div>
  )
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(Math.max(value, low), high)
}

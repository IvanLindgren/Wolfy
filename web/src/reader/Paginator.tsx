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
  const onPageRef = useRef(onPage)
  const [width, setWidth] = useState(0)
  const [pages, setPages] = useState(1)
  const [page, setPage] = useState(0)
  const [pageFirst, setPageFirst] = useState<number[]>([0])

  const step = width + GAP

  const measure = useCallback(() => {
    const box = frame.current
    const sheet = inner.current
    if (!box || !sheet) return
    const next = box.clientWidth
    // Не трогаем состояние если ширина не изменилась — избегаем лишних layout
    setWidth((prev) => (prev === next ? prev : next))
    if (next > 0) {
      const stepNext = next + GAP
      const total = Math.max(1, Math.round((sheet.scrollWidth + GAP) / stepNext))
      setPages((prev) => (prev === total ? prev : total))
      // §21: строим один раз отображение page -> first token, чтобы firstToken() был O(1)
      const all = sheet.querySelectorAll<HTMLElement>('[data-t]')
      const map: number[] = new Array(total).fill(-1)
      for (const el of Array.from(all)) {
        const left = el.offsetLeft - sheet.offsetLeft
        // Некоторые браузеры дают offsetLeft 0 до конца layout — пропускаем такие
        if (total > 1 && left === 0 && el !== all[0]) {
          // fallback через getBoundingClientRect для колонной вёрстки
          // left из offsetLeft надёжнее, но если 0 — пробуем позже через rect
        }
        const p = clamp(Math.round(left / stepNext), 0, total - 1)
        if (map[p] === -1) map[p] = Number(el.dataset.t ?? 0)
      }
      // Если из-за offsetLeft=0 карта осталась пустой, пробуем rect-путь
      if (map.some((v) => v === -1)) {
        // второй проход через rect если первый оставил дыры
        const baseLeft = sheet.getBoundingClientRect().left
        for (const el of Array.from(all)) {
          const r = el.getBoundingClientRect()
          const left2 = r.left - baseLeft + (inner.current?.scrollLeft ?? 0)
          // только если offsetLeft путь не сработал
          const estPage = clamp(Math.round(left2 / stepNext), 0, total - 1)
          if (map[estPage] === -1) map[estPage] = Number(el.dataset.t ?? 0)
        }
      }
      for (let i = 1; i < map.length; i += 1) if (map[i] === -1) map[i] = map[i - 1] ?? 0
      if (map[0] === -1) map[0] = 0
      setPageFirst((prev) => (arraysEqual(prev, map) ? prev : map))
    }
  }, [])

  useLayoutEffect(() => {
    measure()
  }, [measure, resetKey])

  useEffect(() => {
    const box = frame.current
    const sheet = inner.current
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => measure())
    if (box) observer.observe(box)
    if (sheet) observer.observe(sheet)
    return () => observer.disconnect()
  }, [measure])

  // Смена главы возвращает на первую страницу — но не смена кегля: там
  // читатель остаётся на месте, и место это задаётся токеном, а не номером
  // страницы.
  useEffect(() => {
    setPage(0)
  }, [resetKey])

  useEffect(() => {
    onPageRef.current = onPage
  }, [onPage])

  // Сообщаем наружу только об изменении самой страницы. Родитель вправе
  // передать обычную inline-функцию: её новая ссылка после setState не должна
  // повторно запускать этот эффект и замыкать обновления по кругу.
  useEffect(() => {
    onPageRef.current(page, pages)
  }, [page, pages])

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
    // O(1) по заранее построенному отображению, а не сканированием всех токенов
    if (pageFirst.length > page && pageFirst[page] !== undefined) return pageFirst[page]!
    // fallback — старый скан, если карта ещё не готова (первый кадр)
    const sheet = inner.current
    if (!sheet || step <= 0) return 0
    const edge = page * step - 4
    const all = sheet.querySelectorAll<HTMLElement>('[data-t]')
    for (const element of Array.from(all)) {
      if (element.offsetLeft - sheet.offsetLeft >= edge) {
        return Number(element.dataset.t)
      }
    }
    return 0
  }, [page, step, pageFirst])

  // Ручка отдаётся наружу в layout-эффекте, а не прямо в отрисовке: правка
  // чужого объекта во время рендера — тот самый побочный эффект, из-за
  // которого StrictMode и ругается, а данные разъезжаются в конкурентном
  // режиме.
  useLayoutEffect(() => {
    handle.current = { pages, page, turn, go, showToken, firstToken }
  }, [handle, pages, page, turn, go, showToken, firstToken])

  // Сброс pageFirst при смене главы — карта старой главы не должна пережить
  useEffect(() => {
    setPageFirst([0])
  }, [resetKey])

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

function arraysEqual(a: number[], b: number[]): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i += 1) if (a[i] !== b[i]) return false
  return true
}

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from '@tanstack/react-router'

import { session, useSession } from '../core/session'
import type { Card, CardKind, Drill, FreshRule } from '../core/types'
import { Button } from '../widgets/Button'
import { CloseIcon } from '../widgets/icons'
import { WolfyCompanion } from '../widgets/Wolfy'
import styles from './decks.module.css'

type Verdict = { right: boolean; answer: string; explanation: string }

const NAMES: Record<CardKind, string> = {
  word: 'Слова',
  phrase: 'Фразы',
  rule: 'Грамматика',
}

export function TrainingScreen() {
  const rawKind = useParams({ strict: false }).kind
  const kind: CardKind = rawKind === 'phrase' || rawKind === 'rule' ? rawKind : 'word'
  const revision = useSession((state) => state.library.revision)
  const ready = useSession((state) => state.ready)
  const [keys, setKeys] = useState<string[]>([])
  const [rules, setRules] = useState<Record<string, FreshRule>>({})
  const [position, setPosition] = useState(0)
  const [drill, setDrill] = useState<Drill | null>(null)
  const [card, setCard] = useState<Card | null>(null)
  const [verdict, setVerdict] = useState<Verdict | null>(null)
  const [loading, setLoading] = useState(true)

  const show = useCallback(async (key: string, pending: Record<string, FreshRule>) => {
    const fresh = pending[key]
    if (fresh) {
      const outcome = await session.ruleCard(fresh.rule, fresh.title)
      const created = outcome.card ?? null
      setCard(created)
      setDrill(created ? (await session.ruleDrill(fresh.rule, created.id)) ?? null : null)
      return
    }
    const found = useSession.getState().library.cards.find((item) => item.id === key) ?? null
    setCard(found)
    setDrill((await session.drillFor(key)) ?? null)
  }, [])

  useEffect(() => {
    if (!ready) return
    let alive = true
    setLoading(true)
    void session.trainingQueue(kind).then(async (queue) => {
      if (!alive) return
      const nextKeys = queue?.keys ?? []
      const nextRules = Object.fromEntries((queue?.rules ?? []).map((rule) => [rule.rule, rule]))
      setKeys(nextKeys)
      setRules(nextRules)
      setPosition(0)
      setVerdict(null)
      if (nextKeys[0]) await show(nextKeys[0], nextRules)
      if (alive) setLoading(false)
    })
    return () => {
      alive = false
    }
  }, [kind, ready, show])

  const answer = useCallback(
    async (value: string) => {
      if (!drill || verdict) return
      const right = await session.sameText(value, drill.answer)
      await session.review(drill.cardId, right)
      setVerdict({ right, answer: drill.answer, explanation: drill.explanation })
    },
    [drill, verdict],
  )

  const next = useCallback(async () => {
    const index = position + 1
    setVerdict(null)
    if (index >= keys.length) {
      setPosition(index)
      setDrill(null)
      return
    }
    setPosition(index)
    await show(keys[index]!, rules)
  }, [keys, position, rules, show])

  useTrainingKeys(drill, verdict, answer, next)

  if (loading) {
    return <div className={styles.training}><p className={styles.subject}>Готовим сегодняшнюю порцию…</p></div>
  }

  if (keys.length === 0 || position >= keys.length) {
    return (
      <div className={styles.training}>
        <WolfyCompanion mood="glad" title={keys.length ? 'Порция закончилась' : 'На сегодня всё'}>
          <p className={styles.subject}>
            {keys.length ? 'Расписание карточек обновлено. Хорошая тихая работа.' : 'Новые карточки появятся по вашему графику забывания.'}
          </p>
          <Link to="/decks"><Button variant="primary">Вернуться к колодам</Button></Link>
        </WolfyCompanion>
      </div>
    )
  }

  return (
    <div className={styles.training}>
      <div className={styles.trainingBar}>
        <Link to="/decks" aria-label="Закрыть тренировку" title="Закрыть тренировку"><CloseIcon size={18} /></Link>
        <div className={styles.trainingProgress}>
          <div className={styles.trainingProgress__bar} style={{ width: `${((position + (verdict ? 1 : 0)) / keys.length) * 100}%` }} />
        </div>
        <span className={styles.trainingCount}>{position + 1} / {keys.length}</span>
      </div>

      {drill ? (
        <div className={styles.stage}>
          <article className={styles.card}>
            <div className={styles.question}>{drill.question}</div>
            {drill.subject && <div className={styles.subject}>{drill.subject}</div>}
            <DrillBody drill={drill} disabled={!!verdict} onAnswer={answer} />
            <div className={styles.hp}>
              <span>HP {card?.hp ?? 100}</span>
              <span className={styles.hp__track}><span className={styles.hp__bar} style={{ width: `${Math.max(0, Math.min(100, card?.hp ?? 100))}%` }} /></span>
              <span>{NAMES[kind]}</span>
            </div>
            {verdict && (
              <div className={styles.verdict}>
                <span aria-hidden="true">{verdict.right ? '✓' : '×'}</span>
                <div>
                  <strong>{verdict.right ? 'Верно' : `Ответ: ${verdict.answer}`}</strong>
                  {verdict.explanation && <p className={styles.explanation}>{verdict.explanation}</p>}
                </div>
                <Button variant="primary" onClick={() => void next()}>Дальше</Button>
              </div>
            )}
          </article>
        </div>
      ) : <p className={styles.subject}>Задание не удалось открыть.</p>}
      <span hidden>{revision}</span>
    </div>
  )
}

function DrillBody({ drill, disabled, onAnswer }: { drill: Drill; disabled: boolean; onAnswer: (answer: string) => void }) {
  if (drill.kind === 'Choice') {
    return <div className={styles.options}>{drill.pieces.map((piece, index) => <button key={`${piece}-${index}`} className={styles.option} disabled={disabled} onClick={() => onAnswer(piece)}><span className={styles.option__key}>{index + 1}</span>{piece}</button>)}</div>
  }
  if (drill.kind === 'Letters') return <Letters drill={drill} disabled={disabled} onAnswer={onAnswer} />
  if (drill.kind === 'Builder') return <Builder pieces={drill.pieces} disabled={disabled} onAnswer={onAnswer} />
  if (drill.kind === 'Gap') return <Gap drill={drill} disabled={disabled} onAnswer={onAnswer} />
  return <Typing disabled={disabled} onAnswer={onAnswer} />
}

function Typing({ disabled, onAnswer }: { disabled: boolean; onAnswer: (value: string) => void }) {
  const [value, setValue] = useState('')
  return <form onSubmit={(event) => { event.preventDefault(); if (value.trim()) onAnswer(value) }}><input autoFocus className={styles.typing} value={value} disabled={disabled} autoComplete="off" onChange={(event) => setValue(event.target.value)} aria-label="Ваш ответ" /><Button wide variant="primary" type="submit" disabled={disabled || !value.trim()}>Проверить</Button></form>
}

function Gap({ drill, disabled, onAnswer }: { drill: Drill; disabled: boolean; onAnswer: (value: string) => void }) {
  return <div className={styles.options}>{drill.pieces.map((piece, index) => <button key={`${piece}-${index}`} className={styles.option} disabled={disabled} onClick={() => onAnswer(piece)}><span className={styles.option__key}>{index + 1}</span>{piece}</button>)}</div>
}

function Letters({ drill, disabled, onAnswer }: { drill: Drill; disabled: boolean; onAnswer: (value: string) => void }) {
  const [picked, setPicked] = useState<number[]>([])
  const given = useMemo(() => new Set(drill.given), [drill.given])
  const open = drill.answer.split('').map((char, index) => given.has(index) ? char : null)
  let cursor = 0
  const assembled = open.map((char) => char ?? drill.pieces[picked[cursor++] ?? -1] ?? '').join('')
  return <>
    <div className={styles.slots}>{open.map((char, index) => <button type="button" key={index} className={styles.slot} data-given={char !== null} data-filled={char === null && !!assembled[index]} disabled={disabled || char !== null || !assembled[index]} onClick={() => { const before = open.slice(0, index).filter((item) => item === null).length; setPicked((items) => items.filter((_, i) => i !== before)) }}>{assembled[index] || '·'}</button>)}</div>
    <div className={styles.tiles}>{drill.pieces.map((piece, index) => <button type="button" key={`${piece}-${index}`} className={styles.tile} data-used={picked.includes(index)} disabled={disabled || picked.includes(index)} onClick={() => setPicked((items) => [...items, index])}>{piece}</button>)}</div>
    <Button wide variant="primary" disabled={disabled || assembled.length !== drill.answer.length} onClick={() => onAnswer(assembled)}>Проверить</Button>
  </>
}

function Builder({ pieces, disabled, onAnswer }: { pieces: string[]; disabled: boolean; onAnswer: (value: string) => void }) {
  const [picked, setPicked] = useState<number[]>([])
  return <>
    <div className={styles.assembled}>{picked.map((index, at) => <button type="button" className={styles.block} key={`${index}-${at}`} disabled={disabled} onClick={() => setPicked((items) => items.filter((_, i) => i !== at))}>{pieces[index]}</button>)}</div>
    <div className={styles.blocks}>{pieces.map((piece, index) => <button type="button" key={`${piece}-${index}`} className={styles.block} data-used={picked.includes(index)} disabled={disabled || picked.includes(index)} onClick={() => setPicked((items) => [...items, index])}>{piece}</button>)}</div>
    <Button wide variant="primary" disabled={disabled || picked.length !== pieces.length} onClick={() => onAnswer(picked.map((index) => pieces[index]).join(' '))}>Проверить</Button>
  </>
}

function useTrainingKeys(drill: Drill | null, verdict: Verdict | null, answer: (value: string) => void, next: () => void) {
  const answerRef = useRef(answer)
  answerRef.current = answer
  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target?.matches('input, textarea')) return
      if (verdict && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); next(); return }
      if (!verdict && drill && (drill.kind === 'Choice' || drill.kind === 'Gap') && /^[1-4]$/.test(event.key)) {
        const piece = drill.pieces[Number(event.key) - 1]
        if (piece) answerRef.current(piece)
      }
    }
    window.addEventListener('keydown', listener, true)
    return () => window.removeEventListener('keydown', listener, true)
  }, [drill, next, verdict])
}

/**
 * Компаньон в читалке веб-версии.
 *
 * В обычном состоянии у правого края виден только узкий газетный ярлычок.
 * Читатель тянет его влево или нажимает мышью, после чего появляется фигура.
 * Прокрутка снова освобождает страницу. Сеть трогают только явные действия
 * читателя.
 */
import { useEffect, useRef, useState } from 'react'

import * as api from '../api/client'
import { CompanionFigure } from './figure'
import { CompanionReactionEngine, analyzeMood, type Decision } from './engine'
import { characterLine, type CompanionProfile } from './model'
import { playCompanionSound } from './sound'
import motionStyles from './ReaderCompanion.module.css'

export interface ReaderCompanionProps {
  profile: CompanionProfile
  onProfileChange: (profile: CompanionProfile) => void
  persona: api.CompanionPersonaIn
  bookId: string
  bookTitle: string
  chapter: number
  offset: () => number
  pageText: () => string
  suppressed: boolean
  scrolling: boolean
  activeText: string
  soundsEnabled: boolean
  reduceMotion: boolean
  onRecap: () => void
  onEdit: () => void
}

interface AiSheet {
  kind: 'consent' | 'loading' | 'opinion' | 'question' | 'failed' | 'ask'
  opinion?: api.CompanionOpinion
  question?: api.CompanionQuestion
  message?: string
  retryable?: boolean
  retry?: () => void
}

export function ReaderCompanion(props: ReaderCompanionProps) {
  const { profile, onProfileChange, persona, bookId, bookTitle, chapter, offset, pageText, suppressed, scrolling, activeText } = props
  const [bubble, setBubble] = useState<string | null>(null)
  const [revealed, setRevealed] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [sheet, setSheet] = useState<AiSheet | null>(null)
  const [question, setQuestion] = useState('')
  const engineKey = `${profile.id}|${profile.phrasePack?.profileHash ?? 'fallback'}|${profile.phrasePack?.source ?? 'fallback'}`
  const engineRef = useRef<{ key: string; value: CompanionReactionEngine } | null>(null)
  if (!engineRef.current || engineRef.current.key !== engineKey) {
    engineRef.current = { key: engineKey, value: new CompanionReactionEngine(profile) }
  }
  const engine = engineRef.current.value
  const rests = useRef(0)
  const previousChapter = useRef(chapter)
  const compact = window.matchMedia('(max-width: 42rem)').matches
  const reducedMotion = props.reduceMotion || window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const requestRef = useRef<AbortController | null>(null)
  const pendingConsentRef = useRef<(() => void) | null>(null)
  const ribbonStartRef = useRef<number | null>(null)

  const revealCompanion = () => {
    if (!revealed) playCompanionSound('reveal', props.soundsEnabled)
    setRevealed(true)
  }

  const cancelRequest = () => {
    requestRef.current?.abort()
    requestRef.current = null
    setSheet(null)
  }

  useEffect(() => () => requestRef.current?.abort(), [])

  useEffect(() => {
    if (!suppressed) return
    requestRef.current?.abort()
    requestRef.current = null
    pendingConsentRef.current = null
    setBubble(null)
    setRevealed(false)
    setMenuOpen(false)
    setSheet(null)
  }, [suppressed])

  useEffect(() => {
    if (scrolling) setRevealed(false)
  }, [scrolling])

  useEffect(() => {
    if (!revealed || bubble || menuOpen || sheet) return
    const timer = window.setTimeout(() => setRevealed(false), 8_000)
    return () => window.clearTimeout(timer)
  }, [revealed, bubble, menuOpen, sheet])

  useEffect(() => {
    engine.newSession()
    if (profile.readerMode !== 'active' || !profile.reactionsEnabled) return
    const decision = engine.decide({ kind: 'session_start' }, { sessionMinutes: 0, overlayOpen: false, scrolling: false, reactionsEnabled: true })
    if (decision.phrase) setBubble(decision.phrase.text)
    // Старт сессии один на профиль: перерисовки не пересобирают решение.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.id])

  useEffect(() => {
    if (chapter > previousChapter.current) {
      const allowed = profile.readerMode === 'active' && profile.reactionsEnabled
      const decision = engine.decide(
        { kind: 'chapter_completed' },
        { sessionMinutes: rests.current / 2, overlayOpen: suppressed, scrolling, reactionsEnabled: allowed },
      )
      if (decision.phrase) setBubble(decision.phrase.text)
    }
    previousChapter.current = chapter
  }, [chapter, engine, profile.readerMode, profile.reactionsEnabled, scrolling, suppressed])

  // Покой после прокрутки: две секунды без движения дают событие страницы,
  // каждое восьмое добавляет настроение по локальной оценке текста.
  useEffect(() => {
    if (scrolling || suppressed || activeText === '') return
    const timer = window.setTimeout(() => {
      if (scrolling || suppressed) return
      rests.current += 1
      const allowed = profile.readerMode === 'active' && profile.reactionsEnabled
      const context = { sessionMinutes: rests.current / 2, overlayOpen: suppressed, scrolling: false, reactionsEnabled: allowed }
      let decision: Decision = engine.decide({ kind: 'page_completed' }, context)
      if (!decision.phrase && rests.current % 8 === 0) {
        const mood = analyzeMood(activeText)
        if (mood.mood !== 'neutral') {
          decision = engine.decide({ kind: 'mood', mood: mood.mood }, context)
        }
      }
      if (decision.phrase) setBubble(decision.phrase.text)
    }, 2_000)
    return () => window.clearTimeout(timer)
  }, [scrolling, suppressed, activeText, profile.readerMode, profile.reactionsEnabled, engine])

  useEffect(() => {
    if (!bubble) return
    const timer = window.setTimeout(() => setBubble(null), 6_000)
    return () => window.clearTimeout(timer)
  }, [bubble])

  if (profile.readerMode === 'off') return null

  const runOpinion = (consentGranted = false) => {
    if (!consentGranted && (profile.aiConsentAt ?? 0) <= 0) {
      pendingConsentRef.current = () => runOpinion(true)
      setSheet({ kind: 'consent' })
      return
    }
    requestRef.current?.abort()
    const controller = new AbortController()
    requestRef.current = controller
    engine.noteManualShow()
    setSheet({ kind: 'loading' })
    void api.companionOpinion({
      bookId, title: bookTitle, chapter, offset: offset(), pageText: pageText(), companion: persona, signal: controller.signal,
    }).then(
      (value) => { if (requestRef.current === controller) setSheet({ kind: 'opinion', opinion: value }) },
      (error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (requestRef.current !== controller) return
        const failure = error as api.CompanionAiFailure
        setSheet({ kind: 'failed', message: failure.message ?? 'Подсказки сейчас недоступны.', retryable: failure.code !== 'quota', retry: runOpinion })
      },
    )
  }

  const runQuestion = (consentGranted = false) => {
    const text = question.trim()
    if (text.length < 3 || text.length > 500) return
    if (!consentGranted && (profile.aiConsentAt ?? 0) <= 0) {
      pendingConsentRef.current = () => runQuestion(true)
      setSheet({ kind: 'consent' })
      return
    }
    requestRef.current?.abort()
    const controller = new AbortController()
    requestRef.current = controller
    engine.noteManualShow()
    setSheet({ kind: 'loading' })
    void api.companionQuestion({
      bookId, title: bookTitle, chapter, offset: offset(), question: text, context: pageText(), companion: persona, signal: controller.signal,
    }).then(
      (value) => { if (requestRef.current === controller) setSheet({ kind: 'question', question: value }) },
      (error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (requestRef.current !== controller) return
        const failure = error as api.CompanionAiFailure
        setSheet({ kind: 'failed', message: failure.message ?? 'Подсказки сейчас недоступны.', retryable: failure.code !== 'quota', retry: runQuestion })
      },
    )
  }

  const runRecap = (consentGranted = false) => {
    if (!consentGranted && (profile.aiConsentAt ?? 0) <= 0) {
      pendingConsentRef.current = () => runRecap(true)
      setSheet({ kind: 'consent' })
      return
    }
    engine.noteManualShow()
    props.onRecap()
  }

  return (
    <div style={{ position: 'fixed', right: 12, bottom: compact ? 84 : 12, zIndex: 30, display: 'grid', justifyItems: 'end', gap: 8 }}>
      {revealed && bubble && !suppressed && !menuOpen && !sheet && (
        <button
          type="button"
          onClick={() => { setMenuOpen(true); setBubble(null) }}
          style={{
            maxWidth: 260, textAlign: 'left', padding: '0.5rem 0.75rem', borderRadius: 12,
            background: 'var(--surface, #fff)', border: '1px solid rgba(0,0,0,.2)',
            fontFamily: 'inherit', fontSize: '0.85rem', cursor: 'pointer', color: 'inherit',
            marginBottom: compact ? 60 : 104,
          }}
        >
          {bubble}
        </button>
      )}

      {menuOpen && !suppressed && (
        <div
          role="menu"
          style={{
            maxWidth: compact ? 260 : 300, padding: '0.9rem', borderRadius: 12, display: 'grid', gap: '0.5rem',
            background: 'var(--paper, #F7F2E9)', border: '1px solid rgba(0,0,0,.2)', color: 'inherit',
          }}
        >
          <MenuRow label="Что думаешь об этой странице? · Beta" onClick={() => { setMenuOpen(false); runOpinion() }} />
          <MenuRow label="Задать вопрос о книге · Beta" onClick={() => { setMenuOpen(false); setQuestion(''); setSheet({ kind: 'ask' }) }} />
          <MenuRow label="Вспомнить сюжет · Beta" onClick={() => { setMenuOpen(false); runRecap() }} />
          <MenuRow
            label={profile.reactionsEnabled ? 'Помолчи пока' : 'Включить реплики'}
            onClick={() => { onProfileChange({ ...profile, reactionsEnabled: !profile.reactionsEnabled }); setMenuOpen(false) }}
          />
          <MenuRow label="Изменить компаньона" onClick={() => { setMenuOpen(false); props.onEdit() }} />
          <MenuRow label="Спрятать компаньона" onClick={() => { setMenuOpen(false); setBubble(null); setRevealed(false) }} />
          <hr style={{ border: 'none', borderTop: '1px solid rgba(0,0,0,.15)', width: '100%' }} />
          <small>ИИ может ошибаться. До 10 запросов в день.</small>
        </div>
      )}

      {sheet && !suppressed && (
        <div
          role="dialog"
          style={{
            maxWidth: compact ? 300 : 340, padding: '0.9rem', borderRadius: 12, display: 'grid', gap: '0.5rem',
            background: 'var(--paper, #F7F2E9)', border: '1px solid rgba(0,0,0,.2)', color: 'inherit',
          }}
        >
          {sheet.kind === 'consent' && (
            <>
              <strong>Передать фрагмент ИИ?</strong>
              <span>Фрагмент текущей или недавно прочитанной части книги будет отправлен серверному ИИ-провайдеру. Согласие можно отозвать в разделе компаньона.</span>
              <a href="/privacy" target="_blank" rel="noreferrer">Политика приватности</a>
              <div style={{ display: 'flex', gap: '1rem' }}>
                <MenuRow label="Разрешить" onClick={() => {
                  onProfileChange({ ...profile, aiConsentAt: Date.now() })
                  const pending = pendingConsentRef.current
                  pendingConsentRef.current = null
                  setSheet(null)
                  pending?.()
                }} />
                <MenuRow label="Не сейчас" onClick={() => { pendingConsentRef.current = null; setSheet(null) }} />
              </div>
            </>
          )}
          {sheet.kind === 'loading' && (
            <>
              <span>Думаю над страницей…</span>
              <MenuRow label="Отменить" onClick={cancelRequest} />
            </>
          )}
          {sheet.kind === 'opinion' && sheet.opinion && (
            <>
              <strong>{sheet.opinion.title}</strong>
              <span>{sheet.opinion.opinion}</span>
              {(sheet.opinion.details ?? []).map((detail) => (
                <small key={detail.label}>{detail.label}: {detail.text}</small>
              ))}
              {sheet.opinion.uncertainty && <small>{sheet.opinion.uncertainty}</small>}
              <small>Осталось запросов сегодня: {sheet.opinion.remaining}</small>
              <MenuRow label="Закрыть" onClick={() => setSheet(null)} />
            </>
          )}
          {sheet.kind === 'question' && sheet.question && (
            <>
              <strong>Ответ</strong>
              <span>{sheet.question.answer}</span>
              {(sheet.question.evidence ?? []).map((evidence) => (
                <small key={evidence.hint}>{evidence.hint}: {evidence.text}</small>
              ))}
              {sheet.question.uncertainty && <small>{sheet.question.uncertainty}</small>}
              <small>Осталось запросов сегодня: {sheet.question.remaining}</small>
              <MenuRow label="Закрыть" onClick={() => setSheet(null)} />
            </>
          )}
          {sheet.kind === 'failed' && (
            <>
              <span>{sheet.message}</span>
              <div style={{ display: 'flex', gap: '1rem' }}>
                {sheet.retryable && sheet.retry && <MenuRow label="Повторить" onClick={() => { const retry = sheet.retry; setSheet(null); retry?.() }} />}
                <MenuRow label="Закрыть" onClick={() => setSheet(null)} />
              </div>
            </>
          )}
          {sheet.kind === 'ask' && (
            <>
              <strong>Вопрос о прочитанном</strong>
              <textarea
                value={question}
                rows={2}
                maxLength={500}
                placeholder="Что уже случилось в книге?"
                onChange={(event) => setQuestion(event.target.value.slice(0, 500))}
                aria-label="Вопрос о книге"
              />
              <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                <button
                  type="button"
                  disabled={question.trim().length < 3}
                  onClick={() => { setMenuOpen(false); runQuestion() }}
                  style={{ fontFamily: 'inherit', cursor: 'pointer' }}
                >
                  Спросить
                </button>
                <MenuRow label="Отмена" onClick={() => setSheet(null)} />
              </div>
            </>
          )}
        </div>
      )}

      {!suppressed && !revealed && !menuOpen && !sheet && <button
        type="button"
        aria-label="Потяните влево, чтобы открыть компаньона"
        onClick={revealCompanion}
        onPointerDown={(event) => {
          ribbonStartRef.current = event.clientX
        }}
        onPointerMove={(event) => {
          const start = ribbonStartRef.current
          if (start !== null && start - event.clientX >= 22) {
            ribbonStartRef.current = null
            revealCompanion()
          }
        }}
        onPointerUp={(event) => {
          const start = ribbonStartRef.current
          ribbonStartRef.current = null
          if (start !== null && start - event.clientX >= 22) revealCompanion()
        }}
        onPointerLeave={(event) => {
          const start = ribbonStartRef.current
          if (start !== null && start - event.clientX >= 6 && event.buttons === 1) {
            ribbonStartRef.current = null
            revealCompanion()
          }
        }}
        onPointerCancel={() => { ribbonStartRef.current = null }}
        style={{
          width: 30, height: 68, marginRight: -12, borderRadius: '12px 0 0 12px',
          border: '1px solid rgba(0,0,0,.25)', background: 'var(--accent, #b43227)',
          color: 'var(--paper, #fff)', fontFamily: 'Georgia, serif', fontSize: '1.8rem',
          lineHeight: 1, cursor: 'ew-resize', touchAction: 'none',
          transform: 'translateX(0)', transition: reducedMotion ? 'none' : 'transform 240ms ease',
        }}
      >
        ‹
      </button>}

      {revealed && !suppressed && !menuOpen && !sheet && <button
        type="button"
        aria-label={profile.name ? `Компаньон ${profile.name}, ${characterLine(profile)}` : 'Компаньон'}
        onClick={() => { if (!suppressed) { setMenuOpen((open) => !open); setBubble(null) } }}
        className={`${motionStyles.figure} ${reducedMotion ? motionStyles.reduced : ''}`}
        style={{
          background: 'none', border: 'none', padding: 0, cursor: suppressed ? 'default' : 'pointer',
          width: compact ? 72 : 108, height: compact ? 72 : 108, overflow: 'visible',
        }}
      >
        <CompanionFigure appearance={profile.appearance} size={compact ? 72 : 108} />
      </button>}
    </div>
  )
}

function MenuRow({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onClick}
      style={{ background: 'none', border: 'none', padding: '0.15rem 0', textAlign: 'left', fontFamily: 'inherit', fontSize: '0.9rem', cursor: 'pointer', color: 'inherit' }}
    >
      {label}
    </button>
  )
}

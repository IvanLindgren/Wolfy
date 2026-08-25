/**
 * Карточка слова и фразы.
 *
 * Главное правило продукта живёт здесь: **карточка никогда не ждёт сеть,
 * чтобы появиться**. Всё, что можно посчитать локально — разбор формы, части
 * речи, частотность, грамматика предложения, толкование из офлайн-словаря, —
 * считается ядром и показывается сразу. Перевод уходит запросом параллельно и
 * доезжает в уже открытую карточку.
 *
 * Порядок обязателен. Обратный порядок — сначала сеть, потом показ — даёт
 * спиннер на месте разбора и превращает тап по слову в ожидание.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion as m } from 'motion/react'
import { Link } from '@tanstack/react-router'

import { motionFor } from '../app/theme'
import { useShortcuts } from '../app/shortcuts'
import * as bridge from '../core/bridge'
import { session, useSession } from '../core/session'
import type { Card, Grammar, PosTag, Token, WordAnalysis } from '../core/types'
import { seconds } from '../theme/motion'
import { Button } from '../widgets/Button'
import { CheckIcon, CloseIcon, SoundIcon } from '../widgets/icons'
import { flyToDeck } from '../widgets/Flight'
import { Wolfy } from '../widgets/Wolfy'
import styles from './card.module.css'
import {
  FAMILY_TITLES,
  POS_TITLES,
  familyOf,
} from './grammarColors'
import { useAnnotations, type Tone } from '../reader/annotations'
import { Highlighter } from './Highlighter'
import { PhraseText } from './PhraseText'
import { ColorLegend } from './ColorLegend'
import { SentenceGraph } from './SentenceGraph'
import { contextualPos, otherSenses, primarySense } from './cardEssentials'
import { canSpeak, onVoicesReady, speak } from './speech'
import { useDefinition } from './useDefinition'
import { useTranslation } from './useTranslation'

/** Что читатель выбрал в тексте. */
export interface CardTarget {
  kind: 'word' | 'phrase'
  bookId: string
  /** Слово так, как оно стоит в тексте. Для фразы — пусто. */
  surface: string
  /** Предложение вокруг слова или сама выделенная фраза. */
  sentence: string
  /** Токены предложения (или фразы) и номер первого из них в главе. */
  tokens: Token[]
  offset: number
  /** Индекс выбранного слова внутри `tokens`; у карточки фразы его нет. */
  selectedToken?: number
  /** Глава, из которой взят кусок: заметки и выделения живут по главам. */
  chapter: number
  /** Что именно выделено — полуинтервал номеров токенов **главы**. */
  range: { start: number; end: number }
  /** Цитата: ровно тот текст, что стоит в книге на этом месте. */
  quote: string
  /** Откуда стартует полёт слова в колоду. */
  origin: HTMLElement | null
}

interface WordCardProps {
  target: CardTarget | null
  onClose: () => void
}

export function WordCard({ target, onClose }: WordCardProps) {
  const settings = useSession((state) => state.settings)
  const timing = motionFor(settings)

  return (
    <AnimatePresence>
      {target && (
        <>
          <m.div
            className={styles.scrim}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: seconds(timing.quick) }}
            onClick={onClose}
          />
          <Sheet
            key={`${target.kind}:${target.sentence}:${target.surface}:${target.selectedToken ?? ''}`}
            target={target}
            onClose={onClose}
          />
        </>
      )}
    </AnimatePresence>
  )
}

function Sheet({ target, onClose }: { target: CardTarget; onClose: () => void }) {
  const settings = useSession((state) => state.settings)
  const timing = motionFor(settings)
  const cards = useSession((state) => state.library.cards)

  /*
   * Отметка на этом же куске, если она уже есть.
   *
   * Совпадение ищется по точным границам: выделение «the lazy dog» и
   * выделение «lazy dog» — две разные отметки, и склеивать их значило бы
   * молча стирать одну из них при попытке поставить вторую.
   */
  const annotations = useAnnotations((state) => state.annotations)
  const mark = useMemo(
    () =>
      annotations.find(
        (item) =>
          !item.deleted &&
          item.chapter === target.chapter &&
          item.start === target.range.start &&
          item.end === target.range.end,
      ),
    [annotations, target.chapter, target.range.start, target.range.end],
  )

  const setTone = async (tone: Tone | null) => {
    const store = useAnnotations.getState()
    if (!mark) {
      if (tone === null) return
      await store.add({
        chapter: target.chapter,
        start: target.range.start,
        end: target.range.end,
        tone,
        quote: target.quote,
        note: '',
      })
      return
    }
    // Снятая краска у отметки без текста не оставляет ничего, что стоило бы
    // хранить: пустая отметка невидима в книге и бессмысленна в списке.
    if (tone === null && mark.note === '') {
      await store.remove(mark.id)
      return
    }
    await store.update(mark.id, { tone })
  }

  const setNote = async () => {
    const store = useAnnotations.getState()
    if (!mark) {
      // Стикер клеится пустым: текст на нём появится в книге, когда читатель
      // нажмёт на сам стикер. Здесь — только жест «приклеить».
      await store.add({
        chapter: target.chapter,
        start: target.range.start,
        end: target.range.end,
        tone: null,
        quote: target.quote,
        note: '',
      })
      return
    }
    // Стикер уже на месте: текст правится на нём самом, а не в карточке.
  }

  const dropMark = async () => {
    if (mark) await useAnnotations.getState().remove(mark.id)
  }

  const [analysis, setAnalysis] = useState<WordAnalysis | null>(null)
  const [grammar, setGrammar] = useState<Grammar | null>(null)
  const [voices, setVoices] = useState(canSpeak())
  const sheet = useRef<HTMLDivElement>(null)

  // §16: один вызов inspectWord вместо четырёх. Карточка уже видна (shell),
  // разбор догоняет в фоне через воркер и не задерживает анимацию.
  useEffect(() => {
    let alive = true
    // Сброс при смене цели — иначе прошлый результат моргнёт
    setAnalysis(null)
    setGrammar(null)
    if (target.kind === 'word' && target.surface) {
      void bridge
        .inspectWord(target.surface, target.sentence)
        .then((res) => {
          if (!alive) return
          setAnalysis(res.word)
          setGrammar({
            findings: res.findings,
            chunks: res.chunks,
            markers: res.markers,
            parts: res.parts,
          } as Grammar)
        })
        .catch(() => {
          // Fallback для старой WASM без inspectWord
          void bridge.analyzeWord(target.surface).then((found) => {
            if (alive) setAnalysis(found)
          })
          if (target.sentence) {
            void bridge.explain(target.sentence).then((found) => {
              if (alive) setGrammar(found)
            })
          }
        })
    } else if (target.sentence) {
      void bridge.explain(target.sentence).then((found) => {
        if (alive) setGrammar(found)
      })
    }
    return () => {
      alive = false
    }
  }, [target.kind, target.surface, target.sentence])

  useEffect(() => onVoicesReady(() => setVoices(true)), [])

  const lemma = analysis?.lemma ?? target.surface
  const definition = useDefinition(target.kind === 'word' ? lemma : '')
  const translation = useTranslation(
    target.kind === 'word' ? target.surface : '',
    target.sentence,
  )
  const wordPos = target.kind === 'word'
    ? contextualPos(grammar, target.selectedToken) ??
      analysis?.matchedPos ??
      analysis?.dominantPos ??
      analysis?.pos[0]
    : undefined

  const existing = useMemo(
    () =>
      cards.find(
        (card) =>
          !card.deleted &&
          ((target.kind === 'word' &&
            card.kind === 'word' &&
            card.lemma.toLowerCase() === lemma.toLowerCase()) ||
            (target.kind === 'phrase' &&
              card.kind === 'phrase' &&
              card.surface.trim() === target.sentence.trim())),
      ),
    [cards, lemma, target.kind, target.sentence],
  )

  const wordTranslation =
    translation.state === 'ready' ? translation.word : existing?.translation ?? ''

  const save = useCallback(async () => {
    if (existing) return
    if (target.kind === 'phrase') {
      await session.savePhrase(
        target.bookId,
        target.sentence,
        translation.state === 'ready' ? translation.sentence : '',
      )
    } else {
      await session.saveWord({
        bookId: target.bookId,
        surface: target.surface,
        lemma,
        translation: wordTranslation,
        context: target.sentence,
        pos: wordPos ?? '',
        cefr: analysis?.cefr ?? '',
      })
    }
    // Действие обязано быть видимым: слово улетает по дуге в раздел карточек,
    // и на значке раздела загорается отметка. Иначе читатель нажмёт кнопку
    // второй раз.
    flyToDeck(target.kind === 'phrase' ? 'фраза' : target.surface, target.origin)
    onClose()
  }, [
    existing,
    target,
    translation,
    lemma,
    wordTranslation,
    analysis,
    wordPos,
    onClose,
  ])

  useShortcuts(
    useMemo(
      () => [
        { key: 'Escape', run: onClose },
        { key: 'Enter', run: () => void save() },
        { key: 's', run: () => speak(target.kind === 'phrase' ? target.sentence : target.surface) },
      ],
      [onClose, save, target],
    ),
  )

  return (
    <m.div
      ref={sheet}
      className={styles.sheet}
      role="dialog"
      aria-modal="true"
      aria-label={target.kind === 'phrase' ? 'Разбор фразы' : `Слово ${target.surface}`}
      initial={{ y: '100%' }}
      animate={{ y: 0 }}
      exit={{ y: '100%' }}
      transition={
        timing.calm
          ? { type: 'spring', stiffness: 380, damping: 34, mass: 0.9 }
          : { duration: 0 }
      }
      drag="y"
      dragDirectionLock
      // Вверх карточка не тянется — только «резинка» на границе; вниз тянется
      // свободно и закрывается броском.
      dragConstraints={{ top: 0, bottom: 0 }}
      dragElastic={{ top: 0.02, bottom: 0.6 }}
      onDragEnd={(_, info) => {
        if (info.offset.y > 110 || info.velocity.y > 620) onClose()
      }}
    >
      <div className={styles.grip} aria-hidden="true" />

      <header className={styles.head}>
        <div className={styles.headline}>
          {target.kind === 'word' ? (
            <>
              <div>
                <span className={styles.lemma} lang="en">
                  {lemma}
                </span>
                {analysis && analysis.surface.toLowerCase() !== lemma.toLowerCase() && (
                  <span className={styles.surface} lang="en">
                    в тексте: {analysis.surface}
                  </span>
                )}
              </div>
              {definition.state === 'ready' && definition.entry.pronunciation && (
                <div className={styles.pronunciation}>
                  [{definition.entry.pronunciation}]
                </div>
              )}
            </>
          ) : (
            <span className={styles.lemma}>Разбор фразы</span>
          )}
        </div>

        <div className={styles.headActions}>
          <button
            type="button"
            className={styles.iconButton}
            onClick={() => speak(target.kind === 'phrase' ? target.sentence : target.surface)}
            disabled={!voices}
            title="Произнести · S"
            aria-label="Произнести"
          >
            <SoundIcon />
          </button>
          <button
            type="button"
            className={styles.iconButton}
            onClick={onClose}
            title="Закрыть · Esc"
            aria-label="Закрыть"
          >
            <CloseIcon />
          </button>
        </div>
      </header>

      <div className={styles.body}>
        {target.kind === 'word' ? (
          <WordBody
            target={target}
            analysis={analysis}
            grammar={grammar}
            definition={definition}
            translation={translation}
            existing={existing}
            mainPos={wordPos}
            duration={timing.calm}
          />
        ) : (
          <PhraseBody
            target={target}
            grammar={grammar}
            translation={translation}
            stagger={timing.stagger}
            duration={timing.calm}
          />
        )}
      </div>

      <Highlighter
        existing={mark}
        quote={target.quote}
        onHighlight={(tone) => void setTone(tone)}
        onSticker={() => void setNote()}
        onRemove={() => void dropMark()}
      />

      <footer className={styles.footer}>
        <Button
          variant={existing ? 'secondary' : 'primary'}
          onClick={() => void save()}
          disabled={!!existing}
          wide
        >
          {existing ? (
            <>
              <CheckIcon size={16} /> Уже в колоде
            </>
          ) : target.kind === 'phrase' ? (
            'В колоду фраз'
          ) : (
            'В колоду книги'
          )}
        </Button>
      </footer>
    </m.div>
  )
}

// --- Режим «слово» ----------------------------------------------------------

function WordBody({
  target,
  analysis,
  grammar,
  definition,
  translation,
  existing,
  mainPos,
  duration,
}: {
  target: CardTarget
  analysis: WordAnalysis | null
  grammar: Grammar | null
  definition: ReturnType<typeof useDefinition>
  translation: ReturnType<typeof useTranslation>
  existing: Card | undefined
  mainPos: PosTag | undefined
  duration: number
}) {
  const mainSense = definition.state === 'ready'
    ? primarySense(definition.entry.senses, mainPos)
    : undefined
  const remainingSenses = definition.state === 'ready'
    ? otherSenses(definition.entry.senses, mainSense)
    : []

  return (
    <>
      <div className={styles.primaryGrid}>
        <section className={styles.primaryCard} data-tone="translation">
          <h3 className={styles.primaryCard__title}>Перевод в контексте</h3>
          {translation.state === 'ready' && translation.word ? (
            <p className={styles.primaryCard__value}>{translation.word}</p>
          ) : existing?.translation ? (
            <p className={styles.primaryCard__value}>{existing.translation}</p>
          ) : translation.state === 'failed' ? (
            <p className={styles.primaryCard__pending}>{translation.message}</p>
          ) : (
            <p className={styles.primaryCard__pending}>Перевожу…</p>
          )}
          {translation.state === 'ready' && translation.sentence ? (
            <p className={styles.primaryCard__context}>{translation.sentence}</p>
          ) : null}
        </section>

        <section className={styles.primaryCard} data-tone="definition">
          <h3 className={styles.primaryCard__title}>Толкование</h3>
          {mainSense ? (
            <>
              <p className={styles.primaryCard__value} lang="en">
                {mainSense.definition}
              </p>
              <span className={styles.primaryCard__meta}>
                {POS_TITLES[mainSense.pos] ?? mainSense.pos}
              </span>
            </>
          ) : definition.state === 'missing' || definition.state === 'ready' ? (
            <p className={styles.primaryCard__pending}>Толкование пока не найдено</p>
          ) : (
            <p className={styles.primaryCard__pending}>Ищу толкование…</p>
          )}
        </section>

        <section className={styles.primaryCard} data-tone="form">
          <h3 className={styles.primaryCard__title}>Форма в этом контексте</h3>
          {analysis ? (
            <>
              <p className={styles.wordForm} lang="en">
                <strong>{analysis.surface}</strong>
                {analysis.surface.toLowerCase() !== analysis.lemma.toLowerCase() && (
                  <>
                    <span aria-hidden="true"> → </span>
                    <span className={styles.wordForm__lemma}>{analysis.lemma}</span>
                  </>
                )}
              </p>
              <div className={styles.formSummary}>
                {mainPos && <span>{POS_TITLES[mainPos] ?? mainPos}</span>}
                {analysis.facts.slice(0, 2).map((fact, index) => (
                  <span key={`${fact.label}:${index}`}>
                    {fact.label}: {fact.value}
                  </span>
                ))}
                {analysis.facts.length === 0 && <span>{formTitle(analysis.form)}</span>}
              </div>
            </>
          ) : (
            <p className={styles.primaryCard__pending}>Разбираю форму…</p>
          )}
        </section>
      </div>

      <WolfyDisclosure
        label="Подробнее о слове"
        hint="Грамматика, другие значения и конструкции"
        duration={duration}
      >
        <div className={styles.tags}>
          {mainPos && (
            <span className={styles.tag}>
              в контексте: {POS_TITLES[mainPos] ?? mainPos}
            </span>
          )}
          {analysis?.cefr && <span className={styles.tag}>уровень {analysis.cefr}</span>}
          {analysis && analysis.zipf > 0 && (
            <span className={styles.tag} title="Частотность по шкале Zipf: 6 — «the», 4 — обычное книжное слово">
              Zipf {analysis.zipf.toFixed(1)}
            </span>
          )}
          {analysis && (
            <span className={styles.tag}>
              {analysis.surface.length} букв · {syllables(analysis.surface)} слог.
            </span>
          )}
          {analysis && !analysis.known && <span className={styles.tag}>нет в словаре форм</span>}
        </div>

        <RecallQuiz target={target} existing={existing} />

        {analysis && analysis.facts.length > 2 && (
          <section className={styles.section}>
            <h3 className={styles.section__title}>Полный разбор формы</h3>
            <div className={styles.facts}>
              {analysis.facts.map((fact, index) => (
                <div key={index} className={styles.fact}>
                  <span className={styles.fact__label}>{fact.label}</span>
                  <span>{fact.value}</span>
                </div>
              ))}
            </div>
          </section>
        )}

        {remainingSenses.length > 0 && (
          <section className={styles.section}>
            <h3 className={styles.section__title}>Другие толкования</h3>
            <div className={styles.senses}>
              {remainingSenses.map((sense, index) => (
                <div key={index} className={styles.sense}>
                  <span className={styles.sense__pos}>{POS_TITLES[sense.pos] ?? sense.pos}</span>
                  <span className={styles.sense__text} lang="en">{sense.definition}</span>
                </div>
              ))}
            </div>
          </section>
        )}

        {definition.state === 'ready' && definition.entry.translations.length > 1 && (
          <section className={styles.section}>
            <h3 className={styles.section__title}>Словарные значения</h3>
            <p>{definition.entry.translations.join(' · ')}</p>
          </section>
        )}

        {definition.state === 'missing' && (
          <p className={styles.pending}>
            Офлайн-словарь можно установить в настройках — с ним толкование и
            произношение доступны без сети.
          </p>
        )}

        <Constructions grammar={grammar} tokens={target.tokens} word={target.surface} />
      </WolfyDisclosure>
    </>
  )
}

/**
 * Умная проверка знакомства.
 *
 * Перевод теперь всегда остаётся в главном блоке. Уже встречавшееся слово —
 * то, что лежит в колоде, — дополнительно спрашивается мини-квизом внутри
 * подробностей: верный ответ двигает карточку вперёд, ошибка возвращает её в
 * оборот.
 *
 * Квиз возможен, только если в колоде есть чужие переводы: придумывать
 * правдоподобно неверный вариант приложению нечем, а «дом / стол / бегать»
 * рядом с «библиотека» не проверяют ничего. Нет вариантов — показываем
 * перевод, как незнакомому.
 */
function RecallQuiz({
  target,
  existing,
}: {
  target: CardTarget
  existing: Card | undefined
}) {
  const cards = useSession((state) => state.library.cards)
  const [answered, setAnswered] = useState<number | null>(null)

  const answer = existing?.translation?.trim() ?? ''
  const options = useMemo(() => {
    if (!existing || !answer) return []
    const others = cards
      .filter(
        (card) =>
          !card.deleted &&
          card.kind === 'word' &&
          card.id !== existing.id &&
          card.translation.trim() &&
          card.translation.trim() !== answer,
      )
      .map((card) => card.translation.trim())
    const unique = Array.from(new Set(others))
    if (unique.length < 3) return []
    // Порядок постоянен внутри одной карточки: перемешивание при каждой
    // перерисовке заставляло бы читателя искать вариант заново.
    const picked = shuffle(unique, existing.id).slice(0, 3)
    return shuffle([answer, ...picked], existing.id + answer)
  }, [cards, existing, answer])

  if (!existing || options.length === 0) return null

  const right = answered !== null && options[answered] === answer

  return (
    <section className={styles.section}>
      <h3 className={styles.section__title}>Вы это уже встречали</h3>
      <p className={styles.quiz__question}>
        Что значит <strong lang="en">{target.surface}</strong>?
      </p>
      <div className={styles.quiz}>
        {options.map((option, index) => (
          <button
            key={option}
            type="button"
            className={styles.quiz__option}
            disabled={answered !== null}
            data-state={
              answered === null
                ? undefined
                : option === answer
                  ? 'right'
                  : answered === index
                    ? 'wrong'
                    : undefined
            }
            onClick={() => {
              setAnswered(index)
              // Ответ уходит в ядро: расписание карточки и серия дней — одно
              // событие, и считает их планировщик, а не экран.
              void session.review(existing.id, option === answer)
            }}
          >
            <span className={styles.quiz__key}>{index + 1}</span>
            {option}
          </button>
        ))}
      </div>
      {answered !== null && (
        <div className={styles.quiz__verdict}>
          <Wolfy mood={right ? 'glad' : 'kind'} size={34} />
          <span>
            {right
              ? 'Верно — карточка окрепла.'
              : `Ничего страшного: ${answer}. Слово вернётся к повторению.`}
          </span>
        </div>
      )}
    </section>
  )
}

/**
 * Сочетания: фразовые глаголы, обороты и предлоги, в которых стоит это слово.
 *
 * Отдельного словаря коллокаций у Wolfy нет ни на одной платформе, и заводить
 * его только в вебе значило бы показать в браузере то, чего нет на телефоне.
 * Равноценная замена — то, что ядро действительно знает: конструкции,
 * найденные в **этом** предложении и захватывающие это слово, вместе с
 * маркерами-предлогами и частицами при нём. Это честно и это проверяемо.
 */
function Constructions({
  grammar,
  tokens,
  word,
}: {
  grammar: Grammar | null
  tokens: Token[]
  word: string
}) {
  const local = useMemo(() => {
    if (!grammar) return []
    const position = tokens.findIndex(
      (token) => token.kind === 'word' && token.text.toLowerCase() === word.toLowerCase(),
    )
    if (position < 0) return grammar.findings
    return grammar.findings.filter(
      (finding) => position >= finding.start && position < finding.end,
    )
  }, [grammar, tokens, word])

  if (!local.length) return null

  return (
    <section className={styles.section}>
      <h3 className={styles.section__title}>Сочетания в этой фразе</h3>
      {local.map((finding, index) => (
        <div
          key={index}
          className={styles.finding}
        >
          <div className={styles.finding__head}>
            <span className={styles.finding__title}>{finding.title}</span>
            <span className={styles.finding__formula}>{finding.formula}</span>
          </div>
          <p className={styles.finding__explanation}>{finding.explanation}</p>
          <Link to="/grammar/$rule" params={{ rule: finding.rule }} className={styles.finding__link}>
            Правило целиком
          </Link>
        </div>
      ))}
    </section>
  )
}

// --- Режим «фраза» ----------------------------------------------------------

function PhraseBody({
  target,
  grammar,
  translation,
  stagger,
  duration,
}: {
  target: CardTarget
  grammar: Grammar | null
  translation: ReturnType<typeof useTranslation>
  stagger: number
  duration: number
}) {
  return (
    <>
      {/*
        Перевод стоит первым, и это не вопрос вкуса. Читатель выделил фразу
        потому, что не понял её; разбор по членам предложения объясняет, как
        она устроена, но ответить на «что здесь сказано» может только перевод.
        Показывать сначала устройство фразы, а смысл — третьим экраном значит
        заставить искать ответ на свой же вопрос.
      */}
      <section className={styles.phrasePrimary} data-tone="translation">
        <h3 className={styles.primaryCard__title}>Перевод фразы</h3>
        {translation.state === 'ready' && translation.sentence ? (
          <p className={styles.phraseTranslation}>{translation.sentence}</p>
        ) : translation.state === 'failed' ? (
          <p className={styles.primaryCard__pending}>{translation.message}</p>
        ) : (
          <p className={styles.primaryCard__pending}>Перевожу фразу…</p>
        )}
      </section>

      <section className={styles.phrasePrimary} data-tone="parts">
        <h3 className={styles.primaryCard__title}>Фраза и части речи</h3>
        <PhraseText
          tokens={target.tokens}
          markers={grammar?.markers ?? []}
          parts={grammar?.parts}
          interlinear={false}
          showParts
        />
        <ColorLegend parts={grammar?.parts ?? []} markers={grammar?.markers ?? []} />
      </section>

      {/*
        Разбор по членам спрятан нарочно. Он занимает больше места, чем всё
        остальное в карточке вместе взятое, а нужен далеко не каждому и далеко
        не каждый раз: чаще всего фразу выделяют, чтобы понять смысл, и ответ
        на это стоит выше. Тот, кому интересно устройство, раскроет.
      */}
      <WolfyDisclosure
        label="Разбор по членам предложения"
        hint="Кто подлежащее, что сказуемое, а остальное к чему"
        duration={duration}
      >
        <SentenceGraph
          tokens={target.tokens}
          chunks={grammar?.chunks ?? []}
          stagger={stagger}
          duration={duration}
        />
      </WolfyDisclosure>

      <WolfyDisclosure
        label="Подробнее о фразе"
        hint="Подстрочник и найденные конструкции"
        duration={duration}
      >
        <section className={styles.section}>
          <h3 className={styles.section__title}>Подстрочный перевод</h3>
          <PhraseText
            tokens={target.tokens}
            markers={grammar?.markers ?? []}
            parts={grammar?.parts}
            interlinear
          />
        </section>

        {grammar && grammar.findings.length > 0 && (
          <section className={styles.section}>
            <h3 className={styles.section__title}>Найденные конструкции</h3>
            {grammar.findings.map((finding, index) => (
              <div
                key={index}
                className={styles.finding}
              >
                <div className={styles.finding__head}>
                  <span className={styles.family}>
                    {FAMILY_TITLES[familyOf(finding.rule)]}
                  </span>
                  <span className={styles.finding__title}>{finding.title}</span>
                  <span className={styles.finding__formula}>{finding.formula}</span>
                </div>
                <p className={styles.finding__explanation}>{finding.explanation}</p>
                <p className={styles.finding__explanation} style={{ opacity: 0.75 }}>
                  Слова {finding.start + 1}–{finding.end} во фразе
                </p>
                <Link
                  to="/grammar/$rule"
                  params={{ rule: finding.rule }}
                  className={styles.finding__link}
                >
                  Правило целиком
                </Link>
              </div>
            ))}
          </section>
        )}
      </WolfyDisclosure>
    </>
  )
}

function WolfyDisclosure({
  label,
  hint,
  duration,
  children,
}: {
  label: string
  hint: string
  duration: number
  children: React.ReactNode
}) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const transition = { duration: seconds(duration) }

  return (
    <section className={styles.disclosure} data-open={open}>
      <button
        type="button"
        className={styles.disclosure__button}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((current) => !current)}
      >
        <span className={styles.disclosure__copy}>
          <strong>{open ? 'Скрыть подробности' : label}</strong>
          <span>{open ? 'Оставить только главное' : hint}</span>
        </span>
        <m.span
          className={styles.disclosure__wolfy}
          aria-hidden="true"
          animate={open ? { x: -8, y: 7, rotate: -7 } : { x: 0, y: 0, rotate: 0 }}
          transition={transition}
        >
          <Wolfy mood={open ? 'proud' : 'kind'} size={58} />
        </m.span>
        <span className={styles.disclosure__handle} aria-hidden="true" />
      </button>

      <AnimatePresence initial={false}>
        {open && (
          <m.div
            id={panelId}
            className={styles.disclosure__reveal}
            role="region"
            aria-label={label}
            initial={{ height: 0, opacity: 0, y: -8 }}
            animate={{ height: 'auto', opacity: 1, y: 0 }}
            exit={{ height: 0, opacity: 0, y: -8 }}
            transition={transition}
          >
            <div className={styles.disclosure__content}>{children}</div>
          </m.div>
        )}
      </AnimatePresence>
    </section>
  )
}

function formTitle(form: WordAnalysis['form']): string {
  switch (form) {
    case 'lemma':
      return 'начальная форма'
    case 'regular':
      return 'регулярная форма'
    case 'irregular':
      return 'неправильная форма'
    default:
      return 'форма не определена'
  }
}

// --- Мелочи -----------------------------------------------------------------

/**
 * Оценка числа слогов.
 *
 * Именно оценка: точный подсчёт требует словаря произношений на каждое слово,
 * а разница между «примерно три» и «ровно три» читателю не нужна — ему нужно
 * понять, длинное слово или короткое.
 */
function syllables(word: string): number {
  const clean = word.toLowerCase().replace(/[^a-z]/g, '')
  if (!clean) return 0
  const groups = clean.match(/[aeiouy]+/g)?.length ?? 0
  // Немое «e» на конце слога не образует: «make» — один слог, а не два.
  const silent = /[^aeiouy]e$/.test(clean) ? 1 : 0
  return Math.max(1, groups - silent)
}

/**
 * Перемешивание с постоянным зерном.
 *
 * Постоянным, потому что порядок вариантов не должен меняться при каждой
 * перерисовке: читатель, потянувшийся к третьему варианту, обязан найти его
 * там же, где увидел.
 */
function shuffle<T>(items: T[], seed: string): T[] {
  let hash = 0
  for (const symbol of seed) hash = (hash * 31 + symbol.charCodeAt(0)) | 0

  const out = [...items]
  for (let index = out.length - 1; index > 0; index -= 1) {
    hash = (hash * 1103515245 + 12345) & 0x7fffffff
    const pick = hash % (index + 1)
    const swap = out[index]!
    out[index] = out[pick]!
    out[pick] = swap
  }
  return out
}

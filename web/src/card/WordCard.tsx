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

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion as m } from 'motion/react'
import { Link } from '@tanstack/react-router'

import { motionFor } from '../app/theme'
import { useShortcuts } from '../app/shortcuts'
import * as bridge from '../core/bridge'
import { session, useSession } from '../core/session'
import type { Card, Grammar, Token, WordAnalysis } from '../core/types'
import { seconds } from '../theme/motion'
import { Button } from '../widgets/Button'
import { CheckIcon, CloseIcon, GraphIcon, SoundIcon, TreeIcon } from '../widgets/icons'
import { flyToDeck } from '../widgets/Flight'
import { Wolfy } from '../widgets/Wolfy'
import styles from './card.module.css'
import {
  FAMILY_TITLES,
  POS_TITLES,
  familyColor,
  familyOf,
} from './grammarColors'
import { PhraseText } from './PhraseText'
import { SentenceGraph } from './SentenceGraph'
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
          <Sheet key={`${target.kind}:${target.sentence}:${target.surface}`} target={target} onClose={onClose} />
        </>
      )}
    </AnimatePresence>
  )
}

function Sheet({ target, onClose }: { target: CardTarget; onClose: () => void }) {
  const settings = useSession((state) => state.settings)
  const timing = motionFor(settings)
  const cards = useSession((state) => state.library.cards)

  const [analysis, setAnalysis] = useState<WordAnalysis | null>(null)
  const [grammar, setGrammar] = useState<Grammar | null>(null)
  const [graphMode, setGraphMode] = useState<'graph' | 'tree'>('graph')
  const [voices, setVoices] = useState(canSpeak())
  const sheet = useRef<HTMLDivElement>(null)

  // Разбор и грамматика — локальные, и потому приезжают почти мгновенно.
  useEffect(() => {
    let alive = true
    if (target.kind === 'word' && target.surface) {
      void bridge.analyzeWord(target.surface).then((found) => {
        if (alive) setAnalysis(found)
      })
    }
    if (target.sentence) {
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
        pos: analysis?.matchedPos ?? analysis?.dominantPos ?? analysis?.pos[0] ?? '',
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
          />
        ) : (
          <PhraseBody
            target={target}
            grammar={grammar}
            translation={translation}
            graphMode={graphMode}
            setGraphMode={setGraphMode}
            stagger={timing.stagger}
            duration={timing.calm}
          />
        )}
      </div>

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
}: {
  target: CardTarget
  analysis: WordAnalysis | null
  grammar: Grammar | null
  definition: ReturnType<typeof useDefinition>
  translation: ReturnType<typeof useTranslation>
  existing: Card | undefined
}) {
  return (
    <>
      <div className={styles.tags}>
        {analysis?.pos.map((tag) => (
          <span key={tag} className={styles.tag}>
            {POS_TITLES[tag] ?? tag}
          </span>
        ))}
        {analysis && <span className={styles.tag}>уровень {analysis.cefr}</span>}
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
        {analysis && !analysis.known && (
          <span className={styles.tag}>нет в словаре форм</span>
        )}
      </div>

      <Familiarity
        target={target}
        existing={existing}
        translation={translation}
        analysis={analysis}
      />

      {target.sentence && (
        <section className={styles.section}>
          <h3 className={styles.section__title}>Предложение</h3>
          {translation.state === 'ready' && translation.sentence ? (
            <p className={styles.context}>{translation.sentence}</p>
          ) : translation.state === 'failed' ? (
            <p className={styles.pending}>{translation.message}</p>
          ) : (
            <p className={styles.pending}>Перевод предложения едет…</p>
          )}
        </section>
      )}

      {analysis && analysis.facts.length > 0 && (
        <section className={styles.section}>
          <h3 className={styles.section__title}>Разбор формы</h3>
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

      {definition.state === 'ready' && definition.entry.senses.length > 0 && (
        <section className={styles.section}>
          <h3 className={styles.section__title}>Толкование</h3>
          <div className={styles.senses}>
            {definition.entry.senses.slice(0, 6).map((sense, index) => (
              <div key={index} className={styles.sense}>
                <span className={styles.sense__pos}>
                  {POS_TITLES[sense.pos] ?? sense.pos}
                </span>
                <span className={styles.sense__text} lang="en">
                  {sense.definition}
                </span>
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
          Словарной статьи нет. Офлайн-словарь ставится в настройках — с ним
          толкование и произношение появляются без сети.
        </p>
      )}

      <Constructions grammar={grammar} tokens={target.tokens} word={target.surface} />
    </>
  )
}

/**
 * Умная проверка знакомства.
 *
 * Незнакомое слово показывает перевод сразу: читатель тапнул потому, что не
 * знает, и заставлять его угадывать — издевательство. Уже встречавшееся —
 * то, что лежит в колоде, — спрашивается мини-квизом: верный ответ отмечается
 * похвалой и двигает карточку вперёд, ошибка возвращает её в оборот.
 *
 * Квиз возможен, только если в колоде есть чужие переводы: придумывать
 * правдоподобно неверный вариант приложению нечем, а «дом / стол / бегать»
 * рядом с «библиотека» не проверяют ничего. Нет вариантов — показываем
 * перевод, как незнакомому.
 */
function Familiarity({
  target,
  existing,
  translation,
  analysis,
}: {
  target: CardTarget
  existing: Card | undefined
  translation: ReturnType<typeof useTranslation>
  analysis: WordAnalysis | null
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

  if (!existing || options.length === 0) {
    return (
      <section className={styles.section}>
        <h3 className={styles.section__title}>Перевод</h3>
        {translation.state === 'ready' && translation.word ? (
          <p className={styles.translation}>{translation.word}</p>
        ) : existing?.translation ? (
          <p className={styles.translation}>{existing.translation}</p>
        ) : translation.state === 'failed' ? (
          <p className={styles.pending}>{translation.message}</p>
        ) : (
          <p className={styles.pending}>Перевод едет…</p>
        )}
        {analysis && !analysis.known && (
          <p className={styles.pending}>
            Слова нет в словаре форм — возможно, это имя собственное.
          </p>
        )}
      </section>
    )
  }

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
          style={{
            background: familyColor(finding.rule),
            borderLeftColor: 'color-mix(in srgb, currentColor 25%, transparent)',
          }}
        >
          <div className={styles.finding__head}>
            <span className={styles.finding__title} style={{ color: 'var(--family-ink)' }}>
              {finding.title}
            </span>
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
  graphMode,
  setGraphMode,
  stagger,
  duration,
}: {
  target: CardTarget
  grammar: Grammar | null
  translation: ReturnType<typeof useTranslation>
  graphMode: 'graph' | 'tree'
  setGraphMode: (mode: 'graph' | 'tree') => void
  stagger: number
  duration: number
}) {
  const [interlinear, setInterlinear] = useState(true)

  return (
    <>
      <PhraseText
        tokens={target.tokens}
        markers={grammar?.markers ?? []}
        offset={target.offset}
        interlinear={interlinear}
      />

      <section className={styles.section}>
        <h3 className={styles.section__title}>
          Перевод
          <span className={styles.toggle} style={{ textTransform: 'none', letterSpacing: 0 }}>
            <button
              type="button"
              data-active={interlinear}
              onClick={() => setInterlinear(true)}
            >
              с подстрочником
            </button>
            <button
              type="button"
              data-active={!interlinear}
              onClick={() => setInterlinear(false)}
            >
              без
            </button>
          </span>
        </h3>
        {translation.state === 'ready' && translation.sentence ? (
          <p className={styles.translation}>{translation.sentence}</p>
        ) : translation.state === 'failed' ? (
          <p className={styles.pending}>{translation.message}</p>
        ) : (
          <p className={styles.pending}>Литературный перевод едет…</p>
        )}
      </section>

      {grammar && grammar.findings.length > 0 && (
        <section className={styles.section}>
          <h3 className={styles.section__title}>Найденные конструкции</h3>
          {grammar.findings.map((finding, index) => (
            <div
              key={index}
              className={styles.finding}
              style={{ background: familyColor(finding.rule) }}
            >
              <div className={styles.finding__head}>
                <span className={styles.family}>
                  {FAMILY_TITLES[familyOf(finding.rule)]}
                </span>
                <span className={styles.finding__title} style={{ color: 'var(--family-ink)' }}>
                  {finding.title}
                </span>
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

      <section className={styles.section}>
        <div className={styles.graphHead}>
          <h3 className={styles.section__title} style={{ margin: 0 }}>
            Связи
          </h3>
          <div className={styles.toggle}>
            <button
              type="button"
              data-active={graphMode === 'graph'}
              onClick={() => setGraphMode('graph')}
              title="Граф связей"
            >
              <GraphIcon size={15} />
            </button>
            <button
              type="button"
              data-active={graphMode === 'tree'}
              onClick={() => setGraphMode('tree')}
              title="Дерево зависимостей"
            >
              <TreeIcon size={15} />
            </button>
          </div>
        </div>
        <SentenceGraph
          tokens={target.tokens}
          chunks={grammar?.chunks ?? []}
          offset={target.offset}
          mode={graphMode}
          stagger={stagger}
          duration={duration}
        />
      </section>
    </>
  )
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

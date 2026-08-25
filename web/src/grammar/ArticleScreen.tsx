/**
 * Статья справочника.
 *
 * Порядок на странице — порядок вопросов, которые задаёт человек, открывший
 * правило: как оно выглядит, что значат его знаки, как выглядит живая фраза,
 * что правило значит и когда его брать. Упражнение стоит последним: проверять
 * себя имеет смысл после объяснения, а не вместо него.
 *
 * Ничего из этого не написано здесь руками. Название, формула, объяснение и
 * пример приходят из ядра — из тех же детекторов, которые разбирают книгу,
 * поэтому справочник не может разойтись с разбором.
 */

import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from '@tanstack/react-router'

import { grammarExercises, grammarReference } from '../core/bridge'
import type { Article, Exercise } from '../core/types'
import { Button } from '../widgets/Button'
import { BackIcon } from '../widgets/icons'
import page from '../widgets/Page.module.css'
import { Formula, FormulaLegend } from './Formula'
import styles from './grammar.module.css'

export function ArticleScreen() {
  const rule = useParams({ strict: false }).rule ?? ''
  const [article, setArticle] = useState<Article | null>(null)
  const [pool, setPool] = useState<Exercise[]>([])
  const [missing, setMissing] = useState(false)

  useEffect(() => {
    let alive = true
    void Promise.all([grammarReference(), grammarExercises()]).then(
      ([reference, exercises]) => {
        if (!alive) return
        const found = reference.articles.find((item) => item.rule === rule) ?? null
        setArticle(found)
        setMissing(found === null)
        setPool(exercises.exercises.filter((item) => item.rule === rule))
      },
    )
    return () => {
      alive = false
    }
  }, [rule])

  if (!article) {
    return (
      <div className={page.page}>
        <p className={page.muted}>
          {missing ? 'Такого правила в справочнике нет.' : 'Ищем статью в справочнике…'}
        </p>
        {missing && (
          <Link className={styles.back} to="/grammar">
            <BackIcon size={15} /> Все правила
          </Link>
        )}
      </div>
    )
  }

  return (
    <div className={`${page.page} ${styles.article}`}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>{article.topicTitle}</div>
          <h1 className={page.title}>{article.title}</h1>
        </div>
        <div className={page.headActions}>
          <Link className={styles.back} to="/grammar">
            <BackIcon size={15} /> Все правила
          </Link>
        </div>
      </header>

      <section className={styles.build}>
        <h2 className={styles.build__title}>Как выглядит</h2>
        <Formula formula={article.formula} size="large" />
        <FormulaLegend formula={article.formula} />
      </section>

      <figure className={styles.example}>
        <blockquote className={styles.example__sentence} lang="en">
          {article.example}
        </blockquote>
        <figcaption className={styles.example__translation}>{article.translation}</figcaption>
      </figure>

      <section className={page.section}>
        <div className={page.sectionHead}>
          <h2 className={page.sectionTitle}>Что это значит</h2>
          <span className={page.sectionRule} />
        </div>
        <p className={styles.prose}>{article.explanation}</p>
      </section>

      <section className={page.section}>
        <div className={page.sectionHead}>
          <h2 className={page.sectionTitle}>Когда его брать</h2>
          <span className={page.sectionRule} />
        </div>
        <p className={styles.prose}>{article.usage}</p>
      </section>

      <ExerciseCard pool={pool} />
    </div>
  )
}

/**
 * Проверка себя на одном примере.
 *
 * Предложение стоит выше вариантов и крупнее их: выбирают форму для фразы, а
 * не строку из списка. Пропуск в предложении показан отдельным знаком, чтобы
 * его было видно с расстояния строки.
 */
function ExerciseCard({ pool }: { pool: Exercise[] }) {
  const [turn, setTurn] = useState(0)
  const [picked, setPicked] = useState<number | null>(null)
  const exercise = useMemo(() => (pool.length ? pool[turn % pool.length] : null), [pool, turn])
  if (!exercise) return null

  const right = picked === exercise.answer

  return (
    <section className={styles.exercise}>
      <div className={styles.exercise__head}>
        <span className={page.kicker}>Проверить себя</span>
        {pool.length > 1 && (
          <span className={styles.count}>
            {(turn % pool.length) + 1} из {pool.length}
          </span>
        )}
      </div>

      <p className={styles.exercise__sentence} lang="en">
        {gapped(exercise.sentence)}
      </p>
      {exercise.translation && (
        <p className={styles.exercise__translation}>{exercise.translation}</p>
      )}

      <p className={styles.question}>{exercise.question}</p>

      <div className={styles.options}>
        {exercise.options.map((option, index) => (
          <button
            type="button"
            className={styles.option}
            key={`${option}-${index}`}
            lang="en"
            data-state={
              picked === null
                ? undefined
                : index === exercise.answer
                  ? 'right'
                  : picked === index
                    ? 'wrong'
                    : undefined
            }
            disabled={picked !== null}
            onClick={() => setPicked(index)}
          >
            <span className={styles.option__key} aria-hidden="true">
              {index + 1}
            </span>
            {option}
          </button>
        ))}
      </div>

      {picked !== null && (
        <div className={styles.verdict} data-right={right}>
          <strong>
            {right ? 'Верно.' : `Верный ответ: ${exercise.options[exercise.answer]}.`}
          </strong>{' '}
          {exercise.explanation}
        </div>
      )}

      <Button
        variant="primary"
        disabled={picked === null}
        onClick={() => {
          setTurn((value) => value + 1)
          setPicked(null)
        }}
      >
        Ещё пример
      </Button>
    </section>
  )
}

/** Показывает пропуск в предложении отдельным знаком, а не тремя чёрточками. */
function gapped(sentence: string): React.ReactNode {
  const parts = sentence.split('___')
  if (parts.length === 1) return sentence
  return parts.map((part, index) => (
    <span key={index}>
      {index > 0 && <span className={styles.exercise__gap} aria-label="пропуск" />}
      {part}
    </span>
  ))
}

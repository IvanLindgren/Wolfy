import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from '@tanstack/react-router'

import { grammarExercises, grammarReference } from '../core/bridge'
import type { Article, Exercise } from '../core/types'
import { Button } from '../widgets/Button'
import page from '../widgets/Page.module.css'
import styles from './grammar.module.css'

export function ArticleScreen() {
  const rule = useParams({ strict: false }).rule ?? ''
  const [article, setArticle] = useState<Article | null>(null)
  const [pool, setPool] = useState<Exercise[]>([])
  useEffect(() => { void Promise.all([grammarReference(), grammarExercises()]).then(([reference, exercises]) => { setArticle(reference.articles.find((item) => item.rule === rule) ?? null); setPool(exercises.exercises.filter((item) => item.rule === rule)) }) }, [rule])
  if (!article) return <div className={page.page}><p className={page.muted}>Ищем статью в справочнике…</p></div>
  return <div className={`${page.page} ${styles.article}`}>
    <header className={page.head}><div><div className={page.kicker}>{article.topicTitle}</div><h1 className={page.title}>{article.title}</h1></div><div className={page.headActions}><Link to="/grammar">← Все правила</Link></div></header>
    <div className={styles.formula}>{article.formula}</div>
    <p className={styles.prose}>{article.explanation}</p>
    <div className={styles.example}><strong>{article.example}</strong><span>{article.translation}</span></div>
    <section className={page.section}><div className={page.sectionHead}><h2 className={page.sectionTitle}>Как строить и когда использовать</h2><span className={page.sectionRule} /></div><p className={styles.prose}>{article.usage}</p></section>
    <ExerciseCard pool={pool} />
  </div>
}

function ExerciseCard({ pool }: { pool: Exercise[] }) {
  const [turn, setTurn] = useState(0)
  const [picked, setPicked] = useState<number | null>(null)
  const exercise = useMemo(() => pool.length ? pool[turn % pool.length] : null, [pool, turn])
  if (!exercise) return null
  return <section className={styles.exercise}><div className={page.kicker}>Микро‑упражнение</div><div className={styles.question}>{exercise.question}</div><p className={page.muted}>{exercise.sentence}</p><div className={styles.options}>{exercise.options.map((option, index) => <button className={styles.option} key={`${option}-${index}`} data-state={picked === null ? undefined : index === exercise.answer ? 'right' : picked === index ? 'wrong' : undefined} disabled={picked !== null} onClick={() => setPicked(index)}>{index + 1}. {option}</button>)}</div>{picked !== null && <div className={page.notice} style={{ marginTop: '1rem' }}>{picked === exercise.answer ? 'Верно. ' : `Верный ответ: ${exercise.options[exercise.answer]}. `}{exercise.explanation}</div>}<Button variant="primary" disabled={picked === null} onClick={() => { setTurn((value) => value + 1); setPicked(null) }}>Ещё пример</Button></section>
}

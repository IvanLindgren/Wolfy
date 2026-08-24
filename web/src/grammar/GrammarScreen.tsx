import { useEffect, useMemo, useState } from 'react'
import { Link } from '@tanstack/react-router'

import { grammarReference } from '../core/bridge'
import type { Article, Reference } from '../core/types'
import page from '../widgets/Page.module.css'
import styles from './grammar.module.css'

export function GrammarScreen() {
  const [reference, setReference] = useState<Reference>({ articles: [] })
  const [query, setQuery] = useState('')
  useEffect(() => { void grammarReference().then(setReference) }, [])
  const groups = useMemo(() => {
    const found = reference.articles.filter((article) => `${article.title} ${article.explanation} ${article.topicTitle}`.toLowerCase().includes(query.toLowerCase()))
    return found.reduce<Record<string, Article[]>>((all, article) => { (all[article.topicTitle] ??= []).push(article); return all }, {})
  }, [query, reference])
  return <div className={page.page}>
    <header className={page.head}><div><div className={page.kicker}>Справочник</div><h1 className={page.title}>Грамматика без тумана</h1></div><div className={page.headActions}><input className={`${page.input} ${styles.filter}`} value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Найти правило" aria-label="Поиск по грамматике" /></div></header>
    <div className={styles.catalog}>{Object.entries(groups).map(([title, articles]) => <section className={styles.topic} key={title}><h2 className={styles.topicTitle}>{title}</h2>{articles.map((article) => <Link className={styles.articleLink} key={article.rule} to="/grammar/$rule" params={{ rule: article.rule }}>{article.title}<span>{article.formula}</span></Link>)}</section>)}</div>
    {!reference.articles.length && <p className={page.muted}>Справочник поднимается вместе с ядром…</p>}
  </div>
}

/**
 * Справочник грамматики: список правил по темам.
 *
 * Раньше здесь был список ссылок, у каждой из которых под названием стояла
 * формула моноширинным шрифтом. По такому списку нельзя выбрать, что читать:
 * «Present Perfect · have/has + V3» — это оглавление учебника, а не ответ на
 * вопрос «а мне это зачем».
 *
 * Поэтому карточка правила показывает три вещи и ровно в этом порядке:
 * название, разобранную на кирпичики формулу и живой пример с переводом. Про
 * пример читатель понимает правило раньше, чем прочитает объяснение, — а
 * объяснение стоит следом, одной строкой.
 */

import { useEffect, useMemo, useState } from 'react'
import { Link } from '@tanstack/react-router'

import { grammarReference } from '../core/bridge'
import type { Article, Reference } from '../core/types'
import { Appear } from '../widgets/Appear'
import page from '../widgets/Page.module.css'
import { SearchField } from '../widgets/SearchField'
import { Formula } from './Formula'
import styles from './grammar.module.css'

export function GrammarScreen() {
  const [reference, setReference] = useState<Reference>({ articles: [] })
  const [query, setQuery] = useState('')

  useEffect(() => {
    void grammarReference().then(setReference)
  }, [])

  /*
   * Порядок тем — порядок ядра, а не алфавит: справочник выстроен по тому,
   * в каком порядке правила осваивают. `Map` сохраняет порядок вставки, и
   * этого достаточно, чтобы не заводить отдельную таблицу тем.
   */
  const topics = useMemo(() => {
    const needle = query.trim().toLowerCase()
    const found = needle
      ? reference.articles.filter((article) =>
          `${article.title} ${article.formula} ${article.explanation} ${article.topicTitle}`
            .toLowerCase()
            .includes(needle),
        )
      : reference.articles

    const grouped = new Map<string, Article[]>()
    for (const article of found) {
      const list = grouped.get(article.topicTitle)
      if (list) list.push(article)
      else grouped.set(article.topicTitle, [article])
    }
    return [...grouped]
  }, [query, reference])

  const total = reference.articles.length

  return (
    <div className={page.page}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Справочник</div>
          <h1 className={page.title}>Грамматика без тумана</h1>
          <p className={page.subtitle}>
            Каждое правило — формула, пример и объяснение теми же словами,
            какими его объясняет разбор в книге.
          </p>
        </div>
        <div className={page.headActions}>
          <SearchField
            value={query}
            onChange={setQuery}
            label="Поиск по грамматике"
            placeholder="Найти правило"
          />
        </div>
      </header>

      {!total && <p className={page.muted}>Справочник поднимается вместе с ядром…</p>}

      {total > 0 && !topics.length && (
        <p className={page.muted}>
          По запросу «{query.trim()}» ничего не нашлось. Попробуйте название
          правила или слово из формулы — например, «will» или «-ing».
        </p>
      )}

      {topics.map(([title, articles], topicIndex) => (
        <section className={page.section} key={title}>
          <div className={page.sectionHead}>
            <h2 className={page.sectionTitle}>{title}</h2>
            <span className={page.sectionRule} />
            <span className={styles.count}>{countLabel(articles.length)}</span>
          </div>
          <div className={styles.catalog}>
            {articles.map((article, index) => (
              <Appear
                as="article"
                key={article.rule}
                index={topicIndex === 0 ? index : 0}
                className={styles.rule}
              >
                <Link
                  className={styles.rule__link}
                  to="/grammar/$rule"
                  params={{ rule: article.rule }}
                >
                  <h3 className={styles.rule__title}>{article.title}</h3>
                  <Formula formula={article.formula} />
                  <p className={styles.rule__example} lang="en">
                    {article.example}
                  </p>
                  <p className={styles.rule__translation}>{article.translation}</p>
                </Link>
              </Appear>
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}

function countLabel(count: number): string {
  const tail = count % 10
  const teen = count % 100
  if (teen >= 11 && teen <= 14) return `${count} правил`
  if (tail === 1) return `${count} правило`
  if (tail >= 2 && tail <= 4) return `${count} правила`
  return `${count} правил`
}

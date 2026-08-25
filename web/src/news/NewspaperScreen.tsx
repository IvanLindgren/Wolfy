/**
 * Газета: свежие заметки на английском, свёрстанные полосой.
 *
 * Зачем она в приложении для чтения. Глава романа по силам не всегда, а две
 * заметки — почти всегда; при этом язык в них живой, сегодняшний, какого в
 * классике из Открытой библиотеки нет вовсе. Слова из заметки идут в ту же
 * колоду, что слова из книги, и разбираются тем же ядром.
 *
 * Почему именно полоса, а не список карточек. Список новостей читают
 * по диагонали — он и сделан, чтобы его пролистывали. Полоса устроена
 * наоборот: у неё есть первая заметка, к которой ведёт вся вёрстка, есть
 * колонки, есть подвал, и глаз идёт по ней так, как ходит по бумаге. Ради
 * этого здесь настоящие газетные приёмы: название антиквой во всю ширину,
 * линейка под датой, колонки в несколько столбцов, врезка первой заметки.
 *
 * «Читать целиком» не открывает чужой сайт. Заметка приезжает текстом,
 * ложится в библиотеку обычной книгой и открывается в читалке — со словарём,
 * разбором и колодой. В этом вся мысль: газета не уводит из приложения,
 * а приводит в читалку.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'

import {
  ApiError,
  newspaper,
  newspaperArticle,
  OfflineError,
  type NewsArticle,
  type NewsIssue,
} from '../api/client'
import { session, useSession } from '../core/session'
import { addDownloaded } from '../library/import'
import { Appear } from '../widgets/Appear'
import { Button } from '../widgets/Button'
import { BackIcon, ForwardIcon } from '../widgets/icons'
import page from '../widgets/Page.module.css'
import { WolfyCompanion } from '../widgets/Wolfy'
import styles from './news.module.css'

type Load =
  | { state: 'loading' }
  | { state: 'ready'; issue: NewsIssue }
  | { state: 'failed'; message: string }

export function NewspaperScreen() {
  const navigate = useNavigate()
  const settings = useSession((state) => state.settings)
  const topics = settings.newspaperTopics

  const [load, setLoad] = useState<Load>({ state: 'loading' })
  const [sheet, setSheet] = useState(0)
  const [opening, setOpening] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const running = useRef<AbortController | null>(null)

  useEffect(() => {
    running.current?.abort()
    const controller = new AbortController()
    running.current = controller

    setLoad({ state: 'loading' })
    void newspaper(topics, 7, controller.signal)
      .then((issue) => {
        if (controller.signal.aborted) return
        setLoad({ state: 'ready', issue })
        setSheet(0)
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setLoad({ state: 'failed', message: reason(error) })
      })

    return () => controller.abort()
  }, [topics])

  /**
   * Открывает заметку в читалке.
   *
   * Текст приезжает с сервера и становится обычной книгой библиотеки: у неё
   * есть имя, автор и `sourceKey` по адресу заметки — по нему та же заметка,
   * открытая второй раз, не заведёт второй книги.
   */
  const read = useCallback(
    async (article: NewsArticle) => {
      setOpening(article.id)
      setProblem(null)
      try {
        const reading = await newspaperArticle(article.link)
        const title = reading.title || article.title
        const author = reading.author || article.source || reading.source

        // Заголовок первой строкой, дальше абзацы через пустую строку — тот
        // самый вид, который разбирает парсер TXT ядра.
        const text = [title, '', ...reading.paragraphs.flatMap((item) => [item, ''])]
          .join('\n')
          .trimEnd()
        const bytes = new TextEncoder().encode(text)

        const result = await addDownloaded(
          bytes.buffer as ArrayBuffer,
          `${safeName(title)}.txt`,
          title,
          author,
          `newspaper:${article.link}`,
        )
        if (result.kind === 'refused') {
          setProblem(result.message)
          return
        }
        await navigate({ to: '/reader/$bookId', params: { bookId: result.book.id } })
      } catch (error) {
        setProblem(reason(error))
      } finally {
        setOpening(null)
      }
    },
    [navigate],
  )

  const sections = load.state === 'ready' ? load.issue.sections : []
  const current = sections[Math.min(sheet, Math.max(0, sections.length - 1))]

  const chosen = useMemo(() => new Set(topics), [topics])
  const toggle = (code: string) => {
    const next = chosen.has(code)
      ? topics.filter((item) => item !== code)
      : [...topics, code]
    void session.setNewspaperTopics(next)
  }

  return (
    <div className={`${page.page} ${page['page--wide']} ${styles.paper}`}>
      <header className={styles.masthead}>
        <div className={styles.masthead__rule} aria-hidden="true" />
        <h1 className={styles.masthead__name}>The Wolfy Times</h1>
        <div className={styles.masthead__line}>
          <span>Свежие заметки на английском</span>
          <span className={styles.masthead__date}>
            {load.state === 'ready' ? readableDate(load.issue.date) : '…'}
          </span>
          <span>Слова идут в вашу колоду</span>
        </div>
        <div className={styles.masthead__rule} data-thin="true" aria-hidden="true" />
      </header>

      {load.state === 'ready' && load.issue.topics.length > 0 && (
        <nav className={styles.topics} aria-label="Разделы газеты">
          <button
            type="button"
            className={styles.topic}
            data-active={chosen.size === 0}
            onClick={() => void session.setNewspaperTopics([])}
          >
            Весь номер
          </button>
          {load.issue.topics.map((item) => (
            <button
              key={item.code}
              type="button"
              className={styles.topic}
              data-active={chosen.has(item.code)}
              onClick={() => toggle(item.code)}
            >
              {item.title}
            </button>
          ))}
        </nav>
      )}

      {problem && <p className={page.notice}>{problem}</p>}

      {load.state === 'loading' && (
        <p className={page.muted}>Свежий номер печатается…</p>
      )}

      {load.state === 'failed' && (
        <WolfyCompanion mood="kind" title="Номер не вышел">
          <p style={{ color: 'var(--ink-muted)', maxWidth: '32rem' }}>{load.message}</p>
        </WolfyCompanion>
      )}

      {load.state === 'ready' && sections.length === 0 && (
        <WolfyCompanion mood="calm" title="В этих разделах сегодня пусто">
          <p style={{ color: 'var(--ink-muted)' }}>
            Попробуйте выбрать другие разделы или взять весь номер.
          </p>
        </WolfyCompanion>
      )}

      {current && (
        <>
          <Sheet
            key={current.topic}
            section={current}
            opening={opening}
            onRead={(article) => void read(article)}
          />

          {sections.length > 1 && (
            <div className={styles.turn}>
              <Button
                small
                disabled={sheet === 0}
                onClick={() => setSheet((at) => Math.max(0, at - 1))}
              >
                <BackIcon size={15} /> Назад
              </Button>
              <span className={styles.turn__count}>
                Полоса {sheet + 1} из {sections.length} · {current.title}
              </span>
              <Button
                small
                disabled={sheet >= sections.length - 1}
                onClick={() => setSheet((at) => Math.min(sections.length - 1, at + 1))}
              >
                Дальше <ForwardIcon size={15} />
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

/**
 * Полоса: одна врезка и колонки под ней.
 *
 * Первая заметка стоит во всю ширину и набрана крупно — так полоса получает
 * точку входа. Остальные идут колонками: глаз, дочитав столбец, сам знает,
 * куда перейти, и это единственное, ради чего колонки вообще нужны.
 */
function Sheet({
  section,
  opening,
  onRead,
}: {
  section: NewsIssue['sections'][number]
  opening: string | null
  onRead: (article: NewsArticle) => void
}) {
  const [lead, ...rest] = section.articles
  if (!lead) return null

  return (
    <section className={styles.sheet}>
      <div className={styles.sheet__head}>
        <h2 className={styles.sheet__title}>{section.title}</h2>
        <span className={styles.sheet__rule} aria-hidden="true" />
      </div>

      <Appear as="article" className={styles.lead}>
        <h3 className={styles.lead__title} lang="en">
          {lead.title}
        </h3>
        <p className={styles.byline}>{byline(lead)}</p>
        {lead.summary && (
          <p className={styles.lead__text} lang="en">
            {lead.summary}
          </p>
        )}
        <ReadButton article={lead} opening={opening} onRead={onRead} />
      </Appear>

      {rest.length > 0 && (
        <div className={styles.columns}>
          {rest.map((article, index) => (
            <Appear
              as="article"
              key={article.id}
              index={index}
              className={styles.story}
            >
              <h3 className={styles.story__title} lang="en">
                {article.title}
              </h3>
              <p className={styles.byline}>{byline(article)}</p>
              {article.summary && (
                <p className={styles.story__text} lang="en">
                  {article.summary}
                </p>
              )}
              <ReadButton article={article} opening={opening} onRead={onRead} />
            </Appear>
          ))}
        </div>
      )}
    </section>
  )
}

function ReadButton({
  article,
  opening,
  onRead,
}: {
  article: NewsArticle
  opening: string | null
  onRead: (article: NewsArticle) => void
}) {
  const busy = opening === article.id
  return (
    <button
      type="button"
      className={styles.read}
      disabled={opening !== null}
      onClick={() => onRead(article)}
    >
      {busy ? 'Набираем…' : 'Читать целиком'}
    </button>
  )
}

/** Строка под заголовком: издание, автор и время. */
function byline(article: NewsArticle): string {
  const parts = [article.source, article.author, timeOf(article.published)]
  return parts.filter(Boolean).join(' · ')
}

function timeOf(published: number): string {
  if (!published) return ''
  const at = new Date(published)
  const hours = Math.round((Date.now() - published) / 3_600_000)
  if (hours < 1) return 'только что'
  if (hours < 24) return `${hours} ч назад`
  return at.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })
}

function readableDate(iso: string): string {
  const parsed = new Date(`${iso}T00:00:00`)
  if (Number.isNaN(parsed.getTime())) return iso
  return parsed.toLocaleDateString('ru-RU', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  })
}

/** Имя файла книги: без знаков, на которых спотыкается файловая система. */
function safeName(title: string): string {
  return (
    title
      .replace(/[\\/:*?"<>|]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 80) || 'Заметка'
  )
}

function reason(error: unknown): string {
  if (error instanceof OfflineError) return 'Сети нет. Номер подождёт до связи.'
  if (error instanceof ApiError) return error.message
  return 'Газета сейчас недоступна.'
}

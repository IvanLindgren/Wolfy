import { useEffect, useState } from 'react'
import { Link } from '@tanstack/react-router'

import page from '../widgets/Page.module.css'
import styles from './legal.module.css'
import {
  EDITIONS,
  PLAY_URL,
  SOURCE_URL,
  TELEGRAM_URL,
  fileSize,
  latestRelease,
  shortSum,
  type Release,
} from './downloads'

/** Что известно про сборку платформы: ещё спрашиваем, есть, нет, не ответил. */
type Slot =
  | { state: 'loading' }
  | { state: 'ready'; release: Release }
  | { state: 'none' }
  | { state: 'failed' }

/**
 * Страница загрузок — полосой газеты.
 *
 * Не потому, что так нарядно, а потому, что задача у полосы та же: четыре
 * равноправных колонки, которые читают не подряд, а выбирают глазами одну
 * свою. Список карточек предложил бы порядок и главный вариант, которого тут
 * нет: у читателя ровно одна машина, и она либо в первой колонке, либо в
 * четвёртой.
 *
 * Версии и размеры спрашиваются у сервера обновлений — того же, который
 * обслуживает автообновление приложений. Страница показывает то, что на
 * сервере действительно лежит, и молчит про то, чего там нет: ссылка в 404
 * хуже честного «пока нет сборки».
 */
export function DownloadsScreen() {
  const [slots, setSlots] = useState<Record<string, Slot>>(() =>
    Object.fromEntries(EDITIONS.map((item) => [item.platform, { state: 'loading' } as Slot])),
  )

  useEffect(() => {
    const abort = new AbortController()
    for (const edition of EDITIONS) {
      void latestRelease(edition.platform, abort.signal)
        .then((release) => {
          setSlots((current) => ({
            ...current,
            [edition.platform]: release ? { state: 'ready', release } : { state: 'none' },
          }))
        })
        .catch((error: unknown) => {
          if (abort.signal.aborted) return
          void error
          setSlots((current) => ({ ...current, [edition.platform]: { state: 'failed' } }))
        })
    }
    return () => abort.abort()
  }, [])

  // Номер выпуска в шапке — версия любой готовой сборки: они выходят одним
  // прогоном и различаться не могут. Если ни одной нет, строки просто не будет.
  const issue = EDITIONS.map((item) => slots[item.platform])
    .find((slot): slot is { state: 'ready'; release: Release } => slot?.state === 'ready')
    ?.release.version

  return (
    <main className={page.page}>
      <div className={styles.masthead}>
        <span>Wolfy · английский через чтение</span>
        <span>{issue ? `Выпуск ${issue}` : 'Выпуски'}</span>
      </div>

      <h1 className={styles.headline}>Скачать Wolfy</h1>
      <p className={styles.standfirst}>
        Одно приложение на четыре платформы. Работает без сети; дальше
        обновляется само.
      </p>

      <div className={styles.editions}>
        {EDITIONS.map((edition) => {
          const slot = slots[edition.platform] ?? { state: 'loading' }
          return (
            <section className={styles.edition} key={edition.platform}>
              <h2 className={styles.editionTitle}>{edition.title}</h2>
              <p className={styles.editionWho}>{edition.who}</p>
              <Availability slot={slot} kind={edition.kind} />
              <p className={styles.editionInstall}>{edition.install}</p>
            </section>
          )
        })}
      </div>

      <aside className={styles.classified}>
        <div className={styles.classifiedText}>
          <h2>Android — из магазина</h2>
          <p>То же приложение, что в APK, но без установки из неизвестного источника.</p>
        </div>
        <a
          className={styles.playBadge}
          href={PLAY_URL}
          target="_blank"
          rel="noreferrer"
          aria-label="Wolfy в Google Play"
        >
          <img src="/img/google-play-ru.png" alt="Доступно в Google Play" />
        </a>
      </aside>

      <p className={styles.colophon}>
        Рядом с каждым пакетом — отпечаток SHA-256, тот самый, что приложение
        сверяет при обновлении. Не встало —{' '}
        <a href={TELEGRAM_URL} target="_blank" rel="noreferrer">
          канал
        </a>{' '}
        или <Link to="/about">автор</Link>. ·{' '}
        <a href={SOURCE_URL} target="_blank" rel="noreferrer">
          Исходники
        </a>{' '}
        · <Link to="/privacy">Конфиденциальность</Link>
      </p>
    </main>
  )
}

/**
 * Кнопка или честная строка вместо неё.
 *
 * Четыре состояния, и три из них — не кнопка. Сборки может не быть (платформа
 * ещё не выпускалась), сервер может не ответить (тогда виновата связь, а не
 * отсутствие сборки), и пока ответ едет, показывать «скачать» нельзя: адрес
 * файла ещё неизвестен.
 */
function Availability({ slot, kind }: { slot: Slot; kind: string }) {
  switch (slot.state) {
    case 'ready': {
      const size = fileSize(slot.release.size)
      const sum = shortSum(slot.release.sha256)
      return (
        <>
          <a className={styles.get} href={slot.release.url} download>
            Скачать {kind} <small>{size}</small>
          </a>
          <p className={styles.editionMeta}>
            Версия {slot.release.version}
            {sum ? ` · SHA-256 ${sum}` : ''}
          </p>
        </>
      )
    }
    case 'none':
      return <p className={styles.pending}>Сборка ещё не выпускалась</p>
    case 'failed':
      return <p className={styles.pending}>Сервер не ответил — попробуйте позже</p>
    default:
      return <p className={styles.pending}>Спрашиваем сервер…</p>
  }
}

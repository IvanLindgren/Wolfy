/**
 * Оболочка приложения: шапка, разделы, глобальные клавиши.
 *
 * Здесь же живут три вещи, которым нужно быть выше любого экрана: полёт слова
 * в колоду, перетаскивание книги в окно и короткие сообщения. Если положить
 * их в экран, они исчезнут вместе с ним — а книга, брошенная в окно поверх
 * тренировки, должна открыться так же, как брошенная поверх библиотеки.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion as m } from 'motion/react'
import { Link, Outlet, useNavigate, useRouterState } from '@tanstack/react-router'

import { useSession } from '../core/session'
import { addFile } from '../library/import'
import { onLaunchFiles } from '../library/launch'
import { curveCss, motion, seconds } from '../theme/motion'
import { FlightLayer, useFlight } from '../widgets/Flight'
import {
  AccountIcon,
  BooksIcon,
  DecksIcon,
  DiscoveryIcon,
  GrammarIcon,
  NewspaperIcon,
  ReaderIcon,
  SettingsIcon,
} from '../widgets/icons'
import { Wolfy } from '../widgets/Wolfy'
import styles from './Shell.module.css'
import { CHEAT_SHEET, useShortcuts } from './shortcuts'
import { applySettings, motionFor } from './theme'
import { useToasts } from './toasts'
import { useDueCount } from '../decks/useDeckStatus'
import { useAccount } from '../account/useAccount'
import { SyncController } from '../sync/sync'
import { ReminderController } from '../decks/notifications'

/**
 * Разделы в том же порядке, в каком их выбирает `Ctrl+1…6`.
 *
 * Порядок не алфавитный и не по частоте: он повторяет путь читателя —
 * библиотека, чтение, повторение, объяснение, сегодняшний язык, поиск нового.
 */
const SECTIONS = [
  { to: '/library', title: 'Книги', Icon: BooksIcon },
  { to: '/reader', title: 'Читалка', Icon: ReaderIcon },
  { to: '/decks', title: 'Колоды', Icon: DecksIcon },
  { to: '/grammar', title: 'Грамматика', Icon: GrammarIcon },
  { to: '/newspaper', title: 'Газета', Icon: NewspaperIcon },
  { to: '/discovery', title: 'Лента', Icon: DiscoveryIcon },
] as const

export function Shell() {
  const navigate = useNavigate()
  const path = useRouterState({ select: (state) => state.location.pathname })
  /*
   * Открытая книга — единственный экран без подвала.
   *
   * Подвал стоит под содержимым и прокручивается вместе с ним. В читалке
   * «вместе с ним» означает «через триста страниц»: до него нельзя дойти, а
   * дойдя — оказаться в конце главы среди ссылок на политику. Список книг
   * (`/reader` без номера) подвал сохраняет: это обычная страница.
   */
  const immersive = path.startsWith('/reader/')
  const settings = useSession((state) => state.settings)
  const ready = useSession((state) => state.ready)
  const bootError = useSession((state) => state.bootError)
  const due = useDueCount()
  const account = useAccount()

  // P12: повреждённое состояние не должно молча становиться пустой библиотекой.
  // Если и primary, и backup битые — показываем явную ошибку восстановления,
  // а не пустой экран. Клиент после ошибки не сохраняет пустое состояние
  // поверх повреждённого.
  if (bootError) {
    return (
      <div className={styles.shell}>
        <div style={{ padding: 32, maxWidth: 560, margin: '40px auto' }}>
          <h1 style={{ fontSize: 24, marginBottom: 12 }}>Библиотека повреждена</h1>
          <p style={{ whiteSpace: 'pre-wrap', marginBottom: 16, opacity: 0.8 }}>
            Состояние не прочиталось и не восстановилось из бэкапа: {bootError}
            {'\n\n'}Книги на диске остались, но список и колоды не загрузились.
            Попробуйте перезагрузить вкладку. Если не помогает — очистите состояние
            и добавьте книги заново (книги сами не удалятся, удалится только список).
          </p>
          <div style={{ display: 'flex', gap: 12 }}>
            <button
              type="button"
              onClick={() => location.reload()}
              style={{ padding: '8px 16px' }}
            >
              Перезагрузить
            </button>
            <button
              type="button"
              onClick={async () => {
                if (!confirm('Очистить список книг, колоды и настройки в этом браузере? Книги-файлы останутся, но их придётся добавить заново.')) return
                const { clearEverything } = await import('../storage/opfs')
                const { clearAssets } = await import('../storage/assets')
                const { clearCaches } = await import('../storage/idb')
                await clearEverything()
                await clearAssets()
                await clearCaches()
                location.reload()
              }}
              style={{ padding: '8px 16px', background: '#d32', color: '#fff', border: 'none' }}
            >
              Очистить состояние
            </button>
          </div>
        </div>
      </div>
    )
  }

  const [cheatSheet, setCheatSheet] = useState(false)
  const [dropping, setDropping] = useState(false)
  const [online, setOnline] = useState(() => navigator.onLine)
  const timing = motionFor(settings)

  // Настройки ядра переносятся на документ одним местом: тема, кегль,
  // интерлиньяж и движение. Экраны об этом не знают и знать не должны.
  useEffect(() => {
    if (ready) applySettings(settings)
  }, [ready, settings])

  useEffect(() => {
    const update = () => setOnline(navigator.onLine)
    window.addEventListener('online', update)
    window.addEventListener('offline', update)
    return () => {
      window.removeEventListener('online', update)
      window.removeEventListener('offline', update)
    }
  }, [])

  // Книга, открытая из системы: двойной щелчок по файлу в проводнике или
  // «Поделиться» на телефоне.
  useEffect(
    () =>
      onLaunchFiles(async (files) => {
        for (const file of files) {
          const result = await addFile(file)
          if (result.kind === 'refused') {
            useToasts.getState().show(result.message)
          } else {
            void navigate({ to: '/reader/$bookId', params: { bookId: result.book.id } })
          }
        }
      }),
    [navigate],
  )

  const sectionShortcuts = useMemo(
    () =>
      SECTIONS.map((section, index) => ({
        key: String(index + 1),
        ctrl: true,
        run: () => void navigate({ to: section.to }),
      })),
    [navigate],
  )

  useShortcuts(
    useMemo(
      () => [
        ...sectionShortcuts,
        { key: '?', run: () => setCheatSheet((open) => !open) },
        { key: '/', shift: true, run: () => setCheatSheet((open) => !open) },
      ],
      [sectionShortcuts],
    ),
  )

  return (
    <div
      className={styles.shell}
      style={
        {
          '--quick': `${timing.quick}ms`,
          '--calm': `${timing.calm}ms`,
          '--paper-curve': curveCss.paper,
        } as React.CSSProperties
      }
      onDragOver={(event) => {
        // Перетаскивание книги в окно: реагируем только на файлы, чтобы не
        // мигать рамкой при обычном выделении текста.
        if (!Array.from(event.dataTransfer.types).includes('Files')) return
        event.preventDefault()
        setDropping(true)
      }}
      onDragLeave={(event) => {
        if (event.currentTarget === event.target) setDropping(false)
      }}
      onDrop={async (event) => {
        event.preventDefault()
        setDropping(false)
        for (const file of Array.from(event.dataTransfer.files)) {
          const result = await addFile(file)
          if (result.kind === 'refused') {
            useToasts.getState().show(result.message)
          } else {
            void navigate({ to: '/reader/$bookId', params: { bookId: result.book.id } })
          }
        }
      }}
    >
      <SyncController />
      <ReminderController />
      <header className={styles.masthead}>
        <Link to="/library" className={styles.wordmark}>
          <span className={styles.wordmark__name}>Wolfy</span>
        </Link>
        <div className={styles.spacer} />
        <div className={styles.mastheadActions}>
          <Link
            to="/account"
            className={styles.iconButton}
            data-active={path.startsWith('/account')}
            aria-label={account.data ? `Аккаунт: ${account.data.email}` : 'Войти'}
            title={account.data ? account.data.email : 'Войти'}
          >
            <AccountIcon />
          </Link>
          <Link
            to="/settings"
            className={styles.iconButton}
            data-active={path.startsWith('/settings')}
            aria-label="Настройки"
          >
            <SettingsIcon />
          </Link>
        </div>
      </header>

      {!online && (
        <div className={styles.offline} role="status">
          Сети нет. Книги, разбор и колоды работают; перевод, лента и
          синхронизация подождут.
        </div>
      )}

      <div className={styles.body}>
        <nav className={styles.rail} aria-label="Разделы">
          {SECTIONS.map((section, index) => (
            <Tab
              key={section.to}
              to={section.to}
              title={section.title}
              Icon={section.Icon}
              index={index}
              active={path.startsWith(section.to)}
              badge={section.to === '/decks' ? due : 0}
            />
          ))}
        </nav>

        <main className={styles.content}>
          <Outlet />
          {!immersive && <SiteFooter />}
        </main>
      </div>

      <FlightLayer quiet={timing.flight === 0} />
      <Toasts />

      <AnimatePresence>
        {dropping && (
          <m.div
            className={styles.dropZone}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: seconds(timing.quick) }}
          >
            <div className={styles.dropCard}>
              <Wolfy mood="glad" size={92} />
              <p className={styles.dropCard__title}>Отпустите книгу здесь</p>
              <p className={styles.dropCard__hint}>
                EPUB, TXT или PDF. Книга останется на этом устройстве.
              </p>
            </div>
          </m.div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {cheatSheet && <CheatSheet onClose={() => setCheatSheet(false)} quick={timing.quick} />}
      </AnimatePresence>
    </div>
  )
}

/*
 * Адреса наружу продублированы здесь, а не взяты из `legal/downloads`.
 *
 * Оболочка едет в каждую сессию чтения и уложена в бюджет 200 КБ gzip. Импорт
 * ради трёх строк втащил бы в неё весь список изданий с описаниями платформ —
 * шесть килобайт за четыре ссылки в подвале.
 */
const PLAY_URL = 'https://play.google.com/store/apps/details?id=com.wolfy.reader'
const TELEGRAM_URL = 'https://t.me/citavuk'
const SOURCE_URL = 'https://github.com/IvanLindgren/Wolfy'

/**
 * Подвал сайта.
 *
 * Всё, что не помещается в разделы приложения и при этом обязано быть
 * найдено: где скачать, кто автор, что с данными, куда написать. До этой
 * правки такие страницы существовали только по прямой ссылке — из
 * приложения на них не вело ничего.
 *
 * Ссылки наружу отмечены `rel="noreferrer"`: адрес страницы читалки не
 * обязан уезжать чужому сайту вместе с переходом.
 */
function SiteFooter() {
  return (
    <footer className={styles.footer}>
      <div className={styles.footer__brand}>
        <span className={styles.footer__name}>Wolfy</span>
        <p className={styles.footer__line}>Английский через чтение.</p>
      </div>

      <nav className={styles.footer__group} aria-label="Приложение">
        <h2 className={styles.footer__title}>Приложение</h2>
        <Link to="/downloads">Скачать</Link>
        <a href={PLAY_URL} target="_blank" rel="noreferrer">
          Google Play
        </a>
        <Link to="/library">Библиотека</Link>
        <Link to="/settings">Настройки</Link>
      </nav>

      <nav className={styles.footer__group} aria-label="Проект">
        <h2 className={styles.footer__title}>Проект</h2>
        <Link to="/about">Об авторе</Link>
        <Link to="/privacy">Политика конфиденциальности</Link>
        <a href={TELEGRAM_URL} target="_blank" rel="noreferrer">
          Телеграм-канал
        </a>
        <a href={SOURCE_URL} target="_blank" rel="noreferrer">
          Исходный код
        </a>
      </nav>

      <p className={styles.footer__note}>
        Денис Корнилов ·{' '}
        <a href="https://t.me/ivanlindgren" target="_blank" rel="noreferrer">
          @ivanlindgren
        </a>{' '}
        · книги остаются на вашем устройстве
      </p>
    </footer>
  )
}

function Tab({
  to,
  title,
  Icon,
  index,
  active,
  badge,
}: {
  to: string
  title: string
  Icon: (props: { size?: number }) => React.ReactElement
  index: number
  active: boolean
  badge: number
}) {
  const glyph = useRef<HTMLSpanElement>(null)
  const setTarget = useFlight((state) => state.setTarget)

  // Значок колод — цель полёта слова. Координата пересчитывается при каждой
  // перерисовке раздела: панель переезжает вниз на узком экране, и ловить её
  // один раз при монтировании нельзя.
  const measure = useCallback(() => {
    if (to !== '/decks' || !glyph.current) return
    const box = glyph.current.getBoundingClientRect()
    setTarget({ x: box.left + box.width / 2, y: box.top + box.height / 2 })
  }, [to, setTarget])

  useEffect(() => {
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [measure])

  return (
    <Link
      to={to}
      className={styles.tab}
      data-active={active}
      aria-current={active ? 'page' : undefined}
      title={`${title} · Ctrl+${index + 1}`}
    >
      <span className={styles.tab__glyph} ref={glyph}>
        <Icon size={21} />
        {badge > 0 && (
          <span className={styles.badge} aria-label={`${badge} к повторению`}>
            {badge > 99 ? '99+' : badge}
          </span>
        )}
      </span>
      {title}
    </Link>
  )
}

function Toasts() {
  const toasts = useToasts((state) => state.toasts)
  const dismiss = useToasts((state) => state.dismiss)

  return (
    <div className={styles.toasts} role="status" aria-live="polite">
      <AnimatePresence initial={false}>
        {toasts.map((item) => (
          <m.div
            key={item.id}
            className={styles.toast}
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 8 }}
            transition={{ duration: seconds(motion.quick) }}
          >
            <span>{item.text}</span>
            {item.action && (
              <button
                type="button"
                className={styles.toast__action}
                onClick={() => {
                  item.action?.run()
                  dismiss(item.id)
                }}
              >
                {item.action.label}
              </button>
            )}
          </m.div>
        ))}
      </AnimatePresence>
    </div>
  )
}

function CheatSheet({ onClose, quick }: { onClose: () => void; quick: number }) {
  useShortcuts(useMemo(() => [{ key: 'Escape', run: onClose }], [onClose]))

  return (
    <m.div
      className={styles.sheetScrim}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: seconds(quick) }}
      onClick={onClose}
    >
      <m.div
        className={styles.sheet}
        role="dialog"
        aria-label="Клавиши"
        initial={{ opacity: 0, y: 14 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: 10 }}
        transition={{ duration: seconds(quick) }}
        onClick={(event) => event.stopPropagation()}
      >
        <h2 className={styles.sheet__title}>Клавиши</h2>
        {CHEAT_SHEET.map((row) => (
          <div key={row.keys} className={styles.sheet__row}>
            <span className={styles.sheet__keys}>{row.keys}</span>
            <span className={styles.sheet__action}>{row.action}</span>
          </div>
        ))}
      </m.div>
    </m.div>
  )
}

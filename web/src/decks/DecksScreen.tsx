/**
 * Хаб повторений: три колоды и серия дней.
 *
 * Числа — от ядра. Сколько созрело, сколько всего и сколько выучено, считает
 * `srs/training.rs`, а не этот экран: колода грамматики наполняется сама, и
 * повторить это правило в интерфейсе значит однажды показать в браузере и на
 * телефоне разное «к повторению» на одних и тех же данных.
 */

import { useEffect, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { motion as m } from 'motion/react'

import { motionFor } from '../app/theme'
import { clockTime, plural } from '../core/clock'
import { session, useSession } from '../core/session'
import { seconds } from '../theme/motion'
import { Appear } from '../widgets/Appear'
import { Button } from '../widgets/Button'
import { FlameIcon } from '../widgets/icons'
import page from '../widgets/Page.module.css'
import { WolfyCompanion } from '../widgets/Wolfy'
import styles from './decks.module.css'
import { DECKS, useDeckStatuses } from './useDeckStatus'

export function DecksScreen() {
  const statuses = useDeckStatuses()
  const settings = useSession((state) => state.settings)
  const ready = useSession((state) => state.ready)
  const [reminder, setReminder] = useState<number | null>(null)

  useEffect(() => {
    if (!ready) return
    void session.reminderAt().then(setReminder)
  }, [ready, settings.trainedOn, settings.intensity])

  const total = statuses.reduce((sum, status) => sum + status.total, 0)

  return (
    <div className={page.page}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Повторения</div>
          <h1 className={page.title}>Колоды</h1>
        </div>
      </header>

      <Streak
        days={settings.streakDays}
        best={settings.bestStreak}
        answers={settings.answers}
        right={settings.right}
      />

      {total === 0 && ready ? (
        <WolfyCompanion mood="calm" title="Колода пока пуста">
          <p className={page.muted} style={{ maxWidth: '32rem' }}>
            Отмечайте слова при чтении — они сами появятся здесь.
            Грамматика добавляется <strong>небольшими порциями</strong>.
          </p>
          <Link to="/library">
            <Button variant="primary">К книгам</Button>
          </Link>
        </WolfyCompanion>
      ) : (
        <div className={styles.decks}>
          {DECKS.map((deck, index) => {
            const status = statuses.find((item) => item.kind === deck.kind)
            const due = status?.due ?? 0
            return (
              <Appear key={deck.kind} index={index}>
                <Link
                  to="/decks/$kind"
                  params={{ kind: deck.kind }}
                  className={styles.deck}
                >
                  <span className={styles.deck__title}>{deck.title}</span>
                  <span className={styles.deck__hint}>{deck.hint}</span>
                  <span className={styles.deck__numbers}>
                    <span className={styles.deck__due} data-empty={due === 0}>
                      {due}
                    </span>
                    <span className={styles.deck__meta}>
                      {due === 0
                        ? 'сегодня всё повторено'
                        : `к повторению ${plural(due, 'карточка', 'карточки', 'карточек')}`}
                      <br />
                      всего {status?.total ?? 0} · выучено {status?.learned ?? 0}
                    </span>
                  </span>
                </Link>
              </Appear>
            )
          })}
        </div>
      )}

      {reminder && (
        <p className={page.muted} style={{ marginTop: '1.5rem' }}>
          Следующее напоминание — около {clockTime(reminder)}. Время считает
          планировщик по вашему графику забывания, а не будильник по расписанию.
        </p>
      )}
    </div>
  )
}

/**
 * Серия дней.
 *
 * Пламя разгорается при продлении серии — одна анимация на весь экран, и она
 * же единственное здесь украшение. История последних четырнадцати дней рядом:
 * серия без истории — это число, которому нечем себя подтвердить.
 */
function Streak({
  days,
  best,
  answers,
  right,
}: {
  days: number
  best: number
  answers: number
  right: number
}) {
  const settings = useSession((state) => state.settings)
  const timing = motionFor(settings)
  const accuracy = answers > 0 ? Math.round((right / answers) * 100) : 0

  return (
    <div className={styles.streak}>
      <m.span
        className={styles.streak__flame}
        key={days}
        initial={{ scale: 0.8, opacity: 0.6 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{
          duration: seconds(timing.flight),
          type: timing.flight ? 'spring' : 'tween',
          stiffness: 260,
          damping: 14,
        }}
      >
        <FlameIcon size={34} />
      </m.span>

      <div>
        <div className={styles.streak__days}>
          {days} {plural(days, 'день', 'дня', 'дней')} подряд
        </div>
        <div className={styles.streak__note}>
          Лучшая серия — {best} · верных ответов {accuracy}% из {answers}
        </div>
      </div>

      <div className={styles.streak__history} aria-hidden="true">
        {Array.from({ length: 14 }, (_, index) => (
          <span
            key={index}
            className={styles.streak__day}
            data-on={index >= 14 - Math.min(days, 14)}
          />
        ))}
      </div>
    </div>
  )
}

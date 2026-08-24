import calmSticker from '../assets/wolfy/calm.webp'
import gladSticker from '../assets/wolfy/glad.webp'
import kindSticker from '../assets/wolfy/kind.webp'
import proudSticker from '../assets/wolfy/proud.webp'

import styles from './Wolfy.module.css'

export type WolfyMood = 'calm' | 'glad' | 'kind' | 'proud'

const STICKERS: Record<WolfyMood, string> = {
  calm: calmSticker,
  glad: gladSticker,
  kind: kindSticker,
  proud: proudSticker,
}

interface WolfyProps {
  mood?: WolfyMood
  size?: number
  className?: string
  /** Подпись для программ чтения с экрана. Пусто — картинка декоративная. */
  label?: string
}

/** Готовый Вульфи из общего набора ассетов Android, Windows и веб-клиента. */
export function Wolfy({ mood = 'calm', size = 120, className, label }: WolfyProps) {
  const classes = [styles.wolfy, className ?? ''].filter(Boolean).join(' ')

  return (
    <img
      className={classes}
      src={STICKERS[mood]}
      width={size}
      height={size}
      alt={label ?? ''}
      aria-hidden={label ? undefined : true}
      draggable={false}
      decoding="async"
      style={{ objectFit: 'contain' }}
    />
  )
}

/** Вульфи с репликой — пустое состояние и понятный следующий шаг. */
export function WolfyCompanion({
  mood = 'calm',
  title,
  children,
  size = 132,
}: {
  mood?: WolfyMood
  title: string
  children?: React.ReactNode
  size?: number
}) {
  return (
    <div className={styles.companion}>
      <div className={styles.companion__art}>
        <Wolfy mood={mood} size={size} />
      </div>
      <div className={styles.companion__body}>
        <h2 className={styles.companion__title}>{title}</h2>
        {children}
      </div>
    </div>
  )
}

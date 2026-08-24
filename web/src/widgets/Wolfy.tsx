import calmSticker from '../../../client/assets/wolfy_stickers/vulfie_sticker_04_scroll.png'
import gladSticker from '../../../client/assets/wolfy_stickers/vulfie_sticker_07_happywave.png'
import kindSticker from '../../../client/assets/wolfy_stickers/vulfie_sticker_03_thinking.png'
import proudSticker from '../../../client/assets/wolfy_stickers/vulfie_sticker_10_celebrate.png'

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
  return (
    <img
      className={className}
      src={STICKERS[mood]}
      width={size}
      height={size}
      alt={label ?? ''}
      aria-hidden={label ? undefined : true}
      draggable={false}
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
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '1rem',
        textAlign: 'center',
        padding: '2.5rem 1rem',
      }}
    >
      <Wolfy mood={mood} size={size} />
      <h2 style={{ fontSize: '1.35rem' }}>{title}</h2>
      {children}
    </div>
  )
}

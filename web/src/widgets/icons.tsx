/**
 * Значки — свои, а не из набора.
 *
 * Чужой набор приводит за собой чужую пластику: скруглённые концы, толщину
 * штриха и пропорции, подобранные под чужой гротеск. Здесь всё нарисовано
 * одним штрихом в 1,6 единицы под тот же ритм, что и линейки вёрстки.
 */

interface IconProps {
  size?: number
  className?: string
}

function icon(path: React.ReactNode) {
  return function Icon({ size = 20, className }: IconProps) {
    return (
      <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
        aria-hidden="true"
      >
        {path}
      </svg>
    )
  }
}

export const BooksIcon = icon(
  <>
    <path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H10v16H5.5A1.5 1.5 0 0 1 4 18.5z" />
    <path d="M10 4h4.5A1.5 1.5 0 0 1 16 5.5v13a1.5 1.5 0 0 1-1.5 1.5H10" />
    <path d="m17.5 5.6 2 .5a1.5 1.5 0 0 1 1.05 1.84l-3 11.6" />
  </>,
)

export const ReaderIcon = icon(
  <>
    <path d="M3 5.5c3-1.2 6-1.2 9 0v13c-3-1.2-6-1.2-9 0z" />
    <path d="M12 5.5c3-1.2 6-1.2 9 0v13c-3-1.2-6-1.2-9 0z" />
  </>,
)

export const DecksIcon = icon(
  <>
    <rect x="3" y="6.5" width="12" height="14" rx="1.6" />
    <path d="M7.5 3.5h9A1.5 1.5 0 0 1 18 5v11.5" />
    <path d="M6.5 11h5M6.5 14.5h7" />
  </>,
)

export const GrammarIcon = icon(
  <>
    <path d="M5 20V4h9.5L19 8.5V20z" />
    <path d="M14 4v5h5" />
    <path d="M8.5 13h7M8.5 16.5h4.5" />
  </>,
)

export const DiscoveryIcon = icon(
  <>
    <circle cx="12" cy="12" r="8.5" />
    <path d="m15.5 8.5-2.1 5.2-5.2 2.1 2.1-5.2z" />
  </>,
)

export const SettingsIcon = icon(
  <>
    <circle cx="12" cy="12" r="3" />
    <path d="M12 2.8v2.4M12 18.8v2.4M4.5 12H2.1M21.9 12h-2.4M6.7 6.7 5 5M19 19l-1.7-1.7M6.7 17.3 5 19M19 5l-1.7 1.7" />
  </>,
)

export const AccountIcon = icon(
  <>
    <circle cx="12" cy="8.5" r="3.6" />
    <path d="M4.8 20a7.2 7.2 0 0 1 14.4 0" />
  </>,
)

export const CloseIcon = icon(<path d="M6 6l12 12M18 6 6 18" />)

export const BackIcon = icon(<path d="M15 5l-7 7 7 7" />)

export const ForwardIcon = icon(<path d="m9 5 7 7-7 7" />)

export const ContentsIcon = icon(
  <>
    <path d="M4 6h16M4 12h16M4 18h10" />
  </>,
)

export const TuneIcon = icon(
  <>
    <path d="M5 20V13M5 9V4M12 20v-9M12 7V4M19 20v-5M19 11V4" />
    <path d="M2.5 13h5M9.5 11h5M16.5 15h5" />
  </>,
)

export const SoundIcon = icon(
  <>
    <path d="M5 9.5h3l4-3.5v12l-4-3.5H5z" />
    <path d="M15.5 9.2a4 4 0 0 1 0 5.6M18 6.7a7.5 7.5 0 0 1 0 10.6" />
  </>,
)

export const PlusIcon = icon(<path d="M12 5v14M5 12h14" />)

export const CheckIcon = icon(<path d="m5 12.5 4.5 4.5L19 7" />)

export const TrashIcon = icon(
  <>
    <path d="M4 7h16M9.5 7V4.5h5V7M6.5 7l1 12.5h9L17.5 7" />
  </>,
)

export const CameraIcon = icon(
  <>
    <path d="M3 8.5h3.5L8 6h8l1.5 2.5H21v11H3z" />
    <circle cx="12" cy="14" r="3.4" />
  </>,
)

export const SyncIcon = icon(
  <>
    <path d="M20 11a8 8 0 0 0-14.3-4.6M4 13a8 8 0 0 0 14.3 4.6" />
    <path d="M20 4.5V11h-6.5M4 19.5V13h6.5" />
  </>,
)

export const FlameIcon = icon(
  <path d="M12 3s4.5 3.8 4.5 8a4.5 4.5 0 0 1-9 0c0-1.4.7-2.6 1.5-3.4 0 1.6.9 2.6 1.8 2.6.9 0 1.2-1.1 1.2-2.4 0-2-.5-3.6-1-4.8z" />,
)

export const ImageIcon = icon(
  <>
    <rect x="3" y="4.5" width="18" height="15" rx="2" />
    <circle cx="8.5" cy="10" r="1.6" />
    <path d="m3.5 17 4.8-4.6 3.4 3.2 3.3-3.4L20.5 16" />
  </>,
)

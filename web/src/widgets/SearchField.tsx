/**
 * Поле поиска.
 *
 * Три причины, по которым оно переписано, и все три видны глазом.
 *
 * Первая: `type="search"` заставляет браузер рисовать поверх поля свой крестик
 * очистки и свою подсветку — чужой системный виджет посреди набранной
 * страницы. Нативные украшения сняты, крестик нарисован свой и появляется
 * только тогда, когда есть что очищать.
 *
 * Вторая: рамка со всех сторон и заливка делали из поля кнопку — оно весило
 * больше, чем заголовок раздела, рядом с которым стояло. Осталась строка с
 * тонкой линейкой снизу: столько веса, сколько у поля смысла.
 *
 * Третья: значок. Пустое поле без значка не отличить от поля ввода чего
 * угодно, и подсказка внутри пропадает, как только начинаешь печатать, —
 * лупа остаётся.
 *
 * Фокус виден по линейке: она темнеет и удваивается. Убрать системное кольцо,
 * не дав взамен ничего, значит оставить без ориентира того, кто ходит по
 * странице с клавиатуры.
 */

import { CloseIcon, SearchIcon } from './icons'
import page from './Page.module.css'

interface SearchFieldProps {
  value: string
  onChange: (value: string) => void
  /** Подпись для программ чтения с экрана: у поля нет видимого заголовка. */
  label: string
  placeholder?: string
}

export function SearchField({ value, onChange, label, placeholder }: SearchFieldProps) {
  return (
    <div className={page.search}>
      <SearchIcon size={15} />
      <input
        className={page.search__input}
        type="search"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        aria-label={label}
      />
      {value !== '' && (
        <button
          type="button"
          className={page.search__clear}
          onClick={() => onChange('')}
          aria-label="Очистить поиск"
          title="Очистить поиск"
        >
          <CloseIcon size={13} />
        </button>
      )}
    </div>
  )
}

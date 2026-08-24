import { Link } from '@tanstack/react-router'

import { WolfyCompanion } from '../widgets/Wolfy'
import { Button } from '../widgets/Button'

/** Адреса, которого нет. Тупик без выхода — плохой тупик. */
export function NotFound() {
  return (
    <WolfyCompanion mood="kind" title="Такой страницы нет">
      <p style={{ color: 'var(--ink-muted)', maxWidth: '28rem' }}>
        Возможно, ссылка устарела или книгу удалили с этого устройства.
      </p>
      <Link to="/library">
        <Button variant="primary">К библиотеке</Button>
      </Link>
    </WolfyCompanion>
  )
}

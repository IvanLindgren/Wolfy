/**
 * Маршруты.
 *
 * Описаны кодом, а не файловой генерацией: маршрутов полтора десятка, они
 * почти не меняются, а генератор потребовал бы отдельного шага сборки и
 * своего кэша ради экономии сорока строк.
 *
 * Каждый раздел уезжает отдельным куском (`lazyRouteComponent`). Это не
 * микрооптимизация: у оболочки бюджет в 200 КБ gzip, а справочник грамматики,
 * тренировка и лента вместе весят больше неё самой — и читателю, открывшему
 * книгу, не нужны вовсе.
 */

import {
  createRootRoute,
  createRoute,
  createRouter,
  lazyRouteComponent,
} from '@tanstack/react-router'

import { Shell } from './Shell'
import { NotFound } from './NotFound'

const rootRoute = createRootRoute({
  component: Shell,
  notFoundComponent: NotFound,
})

function screen(load: () => Promise<Record<string, unknown>>, name: string) {
  return lazyRouteComponent(load as never, name as never)
}

const homeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: screen(() => import('../account/Onboarding'), 'Home'),
})

const libraryRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/library',
  component: screen(() => import('../library/LibraryScreen'), 'LibraryScreen'),
})

const catalogRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/library/catalogue',
  component: screen(() => import('../library/CatalogScreen'), 'CatalogScreen'),
})

const allWordsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/library/words',
  component: screen(() => import('../library/AllWordsScreen'), 'AllWordsScreen'),
})

const bookWordsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/library/$bookId/words',
  component: screen(() => import('../library/BookWordsScreen'), 'BookWordsScreen'),
})

const bookNotesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/library/$bookId/notes',
  component: screen(() => import('../library/BookNotesScreen'), 'BookNotesScreen'),
})

const readerIndexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/reader',
  component: screen(() => import('../reader/ReaderIndex'), 'ReaderIndex'),
})

const readerRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/reader/$bookId',
  component: screen(() => import('../reader/ReaderScreen'), 'ReaderScreen'),
  /*
   * Место в книге приходит адресом, когда читатель пришёл из списка заметок.
   * Разбирается терпимо: чужая или испорченная ссылка не должна мешать книге
   * открыться — она просто откроется там, где её закрыли.
   */
  validateSearch: (search: Record<string, unknown>) => {
    const chapter = Number(search.chapter)
    const token = Number(search.token)
    return Number.isInteger(chapter) && chapter >= 0 && Number.isInteger(token) && token >= 0
      ? { chapter, token }
      : {}
  },
})

const decksRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/decks',
  component: screen(() => import('../decks/DecksScreen'), 'DecksScreen'),
})

const trainingRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/decks/$kind',
  component: screen(() => import('../decks/TrainingScreen'), 'TrainingScreen'),
})

const grammarRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/grammar',
  component: screen(() => import('../grammar/GrammarScreen'), 'GrammarScreen'),
})

const articleRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/grammar/$rule',
  component: screen(() => import('../grammar/ArticleScreen'), 'ArticleScreen'),
})

const newspaperRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/newspaper',
  component: screen(() => import('../news/NewspaperScreen'), 'NewspaperScreen'),
})

const discoveryRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/discovery',
  component: screen(() => import('../discovery/DiscoveryScreen'), 'DiscoveryScreen'),
})

const settingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings',
  component: screen(() => import('../settings/SettingsScreen'), 'SettingsScreen'),
})

const accountRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/account',
  component: screen(() => import('../account/AccountScreen'), 'AccountScreen'),
})

/**
 * Куда возвращается браузер после провайдера.
 *
 * Схема с loopback `127.0.0.1` — десктопная. В вебе провайдер возвращает
 * браузер сюда: сервер уже поставил куку, а экран только разбирает исход и
 * уводит читателя туда, откуда он начинал.
 */
const authReturnRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/auth/return',
  component: screen(() => import('../account/AuthReturn'), 'AuthReturn'),
})

const photoRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/photo',
  component: screen(() => import('../library/PhotoScreen'), 'PhotoScreen'),
})

const routeTree = rootRoute.addChildren([
  homeRoute,
  libraryRoute,
  catalogRoute,
  allWordsRoute,
  bookWordsRoute,
  bookNotesRoute,
  readerIndexRoute,
  readerRoute,
  decksRoute,
  trainingRoute,
  grammarRoute,
  articleRoute,
  newspaperRoute,
  discoveryRoute,
  settingsRoute,
  accountRoute,
  authReturnRoute,
  photoRoute,
])

export const router = createRouter({
  routeTree,
  defaultPreload: 'intent',
  // Спиннер вместо содержимого запрещён: пока кусок раздела едет, на экране
  // остаётся предыдущий. Ноль означает «не мигать вообще».
  defaultPendingMs: 0,
  defaultPendingMinMs: 0,
  scrollRestoration: true,
})

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

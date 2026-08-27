import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

/**
 * Сборка веб-версии.
 *
 * Три вещи здесь не по умолчанию, и каждая из них — требование задания.
 *
 * 1. **Прокси на сервер.** В разработке `/v1` и `/healthz` уходят на Go-сервер
 *    так, будто он на том же origin. Это не удобство, а воспроизведение
 *    боевой схемы: сессия живёт в httpOnly-куке `SameSite=Lax`, и она
 *    доедет только если API и приложение на одном origin.
 * 2. **Ручное разбиение бандла.** Оболочка обязана уложиться в 200 КБ gzip,
 *    поэтому читалка, колоды, справочник и лента уезжают в отдельные куски
 *    (это делает ленивый импорт маршрутов), а тяжёлые общие библиотеки —
 *    в предсказуемые чанки, чтобы их не тянуло в оболочку транзитом.
 * 3. **`.wasm` не инлайнится.** Полтора мегабайта в base64 внутри JS — это
 *    полтора мегабайта, которые парсит главный поток.
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  worker: {
    // Воркер ядра грузит `.wasm` динамическим импортом, а тот требует модулей:
    // классический воркер такого не умеет.
    format: 'es',
  },

  // `.wasm` и лексикон отдаются файлами, а не base64 внутри бандла.
  assetsInclude: ['**/*.wasm'],

  build: {
    target: 'es2022',
    // Не инлайнить ничего крупнее четырёх килобайт: остальное лучше отдать
    // отдельным запросом, который кэшируется и не задерживает разбор JS.
    assetsInlineLimit: 4096,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('pdfjs-dist')) return 'pdf'
          if (id.includes('@dnd-kit')) return 'dnd'
          if (id.includes('motion') || id.includes('framer')) return 'motion'
          if (id.includes('@tanstack')) return 'tanstack'
          // React, ReactDOM, scheduler и зависящие от React небольшие пакеты
          // должны оставаться вместе. Разделение только по подстроке `react`
          // создавало цикл vendor → react → vendor; production-бандл загружал
          // scheduler раньше React и падал ещё до первого экрана.
          return 'vendor'
        },
      },
    },
  },

  server: {
    port: 5180,
    proxy: {
      '/v1': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/healthz': { target: 'http://127.0.0.1:8080', changeOrigin: false },
    },
  },

  plugins: [
    react(),
    VitePWA({
      // Обновление должно активироваться само. Если старый JS падает до
      // отрисовки кнопки «обновить», prompt-режим оставляет пользователя в
      // сломанном precache навсегда: новый worker уже скачан, но ждёт клика,
      // которого интерфейс не способен показать.
      registerType: 'autoUpdate',
      // Оболочка, шрифты и `.wasm` — в предкэш: повторный запуск обязан быть
      // мгновенным и работать без сети.
      workbox: {
        skipWaiting: true,
        clientsClaim: true,
        globPatterns: ['**/*.{js,css,html,woff2,svg,wasm,json}'],
        // §29: тяжёлые необязательные чанки не попадают в precache — они скачаются по требованию
        globIgnores: ['**/pdf*.js', '**/pdf.worker*.js', '**/dnd*.js', '**/Discovery*.js', '**/Training*.js', '**/Grammar*.js'],
        // Лексикон и словарь заведомо больше умолчания в 2 МБ.
        maximumFileSizeToCacheInBytes: 8 * 1024 * 1024,
        navigateFallback: 'index.html',
        // Запросы к API в предкэш не попадают никогда: ответ «из кэша» на
        // синхронизации — это потерянные данные, а не офлайн-режим.
        navigateFallbackDenylist: [/^\/v1\//, /^\/healthz/],
        runtimeCaching: [
          {
            // Ленивые тяжёлые чанки и роут-чанки — кэшируем при первом использовании
            urlPattern: /\/assets\/(pdf|dnd|motion|tanstack|ReaderScreen|LibraryScreen|DecksScreen|TrainingScreen|Grammar|Discovery|Book|AllWords|Photo|Account|Onboarding|Settings).*\.js$/,
            handler: 'CacheFirst',
            options: {
              cacheName: 'wolfy-lazy',
              expiration: { maxEntries: 32, maxAgeSeconds: 60 * 60 * 24 * 30 },
            },
          },
          {
            // Остальные ассеты (CSS, мелкие чанки) — тоже кэш после первого запроса
            urlPattern: /\/assets\/.*\.(js|css)$/,
            handler: 'StaleWhileRevalidate',
            options: {
              cacheName: 'wolfy-assets-runtime',
              expiration: { maxEntries: 64, maxAgeSeconds: 60 * 60 * 24 * 7 },
            },
          },
          {
            // Лексикон и словарь неизменяемы: один раз скачали — больше
            // никогда не спрашиваем.
            urlPattern: /\/(lexicon|dictionary)\//,
            handler: 'CacheFirst',
            options: {
              cacheName: 'wolfy-data',
              expiration: { maxEntries: 8 },
            },
          },
          {
            urlPattern: /^https:\/\/fonts\.(googleapis|gstatic)\.com\//,
            handler: 'CacheFirst',
            options: {
              cacheName: 'wolfy-fonts',
              expiration: { maxEntries: 24, maxAgeSeconds: 60 * 60 * 24 * 365 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
        ],
      },
      manifest: false,
      includeAssets: ['favicon.png'],
      devOptions: { enabled: false },
    }),
  ],

  test: {
    environment: 'jsdom',
    include: ['tests/unit/**/*.test.ts', 'tests/unit/**/*.test.tsx'],
    globals: true,
  },
})

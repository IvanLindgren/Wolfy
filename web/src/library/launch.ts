/**
 * Открытие файла книги из системы.
 *
 * Установленный PWA заявлен в манифесте обработчиком EPUB, TXT и PDF, и
 * двойной щелчок по книге в проводнике открывает Wolfy. Файл приходит
 * событием `launchQueue` — **один раз и до того**, как приложение успевает
 * отрисоваться. Поэтому очередь перехватывается в самом начале `main.tsx`, а
 * не в компоненте: компонент к этому моменту ещё не смонтирован, и книга,
 * которую читатель уже открыл, потерялась бы молча.
 *
 * Тем же путём приходит книга, отправленная в приложение через «Поделиться»
 * (`share_target` в манифесте).
 */

export type LaunchListener = (files: File[]) => void

/** Файлы, пришедшие до того, как экран успел подписаться. */
let waiting: File[] = []
let listener: LaunchListener | null = null

function deliver(files: File[]): void {
  if (!files.length) return
  if (listener) {
    listener(files)
  } else {
    waiting = [...waiting, ...files]
  }
}

/** Перехватывает очередь запуска. Зовётся один раз, до первой отрисовки. */
export function claimLaunchFiles(): void {
  const queue = (window as unknown as { launchQueue?: LaunchQueue }).launchQueue
  queue?.setConsumer(async (params) => {
    const files: File[] = []
    for (const handle of params.files ?? []) {
      try {
        files.push(await handle.getFile())
      } catch {
        // Читатель мог отозвать доступ к файлу. Остальные всё равно откроем.
      }
    }
    deliver(files)
  })
}

/**
 * Подписывается на открытие файлов из системы.
 *
 * Возвращает функцию отписки. Файлы, пришедшие раньше подписки, отдаются
 * сразу — иначе первое же открытие из проводника пропало бы.
 */
export function onLaunchFiles(next: LaunchListener): () => void {
  listener = next
  if (waiting.length) {
    const pending = waiting
    waiting = []
    next(pending)
  }
  return () => {
    if (listener === next) listener = null
  }
}

interface LaunchParams {
  files?: FileSystemFileHandle[]
}

interface LaunchQueue {
  setConsumer(consumer: (params: LaunchParams) => void): void
}

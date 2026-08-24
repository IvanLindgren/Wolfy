/**
 * Описание устройства для сервера.
 *
 * Нужно заметкам — подписью писателя, — и Читавуку — именем сессии. Поэтому
 * живёт здесь, а не в account: заметки читаются из модулей данных, которым
 * react-query ни к чему.
 */

import { newId } from './clock'

const DEVICE_KEY = 'wolfy.device'

export interface DeviceInfo {
  id: string
  name: string
  platform: string
}

/**
 * Номер устройства держится в `localStorage` — в отличие от токена, это не
 * секрет: он нужен, чтобы список сессий в аккаунте не превращался в десять
 * одинаковых «Браузер» после десяти входов.
 *
 * Номер обязан быть стабильным в пределах одного браузера: по нему
 * подписываются правки заметок, и новое значение после очистки сайтовых
 * данных означало бы второго писателя с нулевым счётчиком.
 */
export function deviceInfo(): DeviceInfo {
  let id = ''
  try {
    id = localStorage.getItem(DEVICE_KEY) ?? ''
    if (!id) {
      id = newId()
      localStorage.setItem(DEVICE_KEY, id)
    }
  } catch {
    id = newId()
  }
  return { id, name: browserName(), platform: 'web' }
}

function browserName(): string {
  const agent = navigator.userAgent
  const engine = /Firefox\//.test(agent)
    ? 'Firefox'
    : /Edg\//.test(agent)
      ? 'Edge'
      : /Chrome\//.test(agent)
        ? 'Chrome'
        : /Safari\//.test(agent)
          ? 'Safari'
          : 'Браузер'
  const system = /Android/.test(agent)
    ? 'Android'
    : /iPhone|iPad/.test(agent)
      ? 'iOS'
      : /Mac OS X/.test(agent)
        ? 'macOS'
        : /Windows/.test(agent)
          ? 'Windows'
          : /Linux/.test(agent)
            ? 'Linux'
            : ''
  return system ? `${engine} на ${system}` : engine
}

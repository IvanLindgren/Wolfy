/**
 * Произношение.
 *
 * Не уходит в сеть: `SpeechSynthesis` — то же, чем на Android служит
 * `TextToSpeech`, а на Windows системный `SpeechSynthesizer`. Голос выбирается
 * английский; русский голос, читающий «library», звучит как «либрари» и учит
 * ровно неверному.
 *
 * Голоса приезжают асинхронно и в первый вызов список часто пуст — браузер
 * подгружает их лениво. Поэтому список запрашивается заново перед каждой
 * репликой, а не запоминается один раз при старте.
 */

let preferred: SpeechSynthesisVoice | null = null

function english(): SpeechSynthesisVoice | null {
  if (preferred) return preferred
  const voices = window.speechSynthesis?.getVoices?.() ?? []
  if (!voices.length) return null

  // Порядок предпочтений: британский, американский, любой английский.
  // Британский первым потому, что офлайн-словарь даёт британскую МФА, и
  // расхождение звука с записанной транскрипцией сбивает больше, чем помогает.
  preferred =
    voices.find((voice) => voice.lang === 'en-GB') ??
    voices.find((voice) => voice.lang === 'en-US') ??
    voices.find((voice) => voice.lang.startsWith('en')) ??
    null
  return preferred
}

/** Умеет ли этот браузер говорить по-английски вообще. */
export function canSpeak(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window
}

export function speak(text: string): void {
  if (!canSpeak() || !text.trim()) return
  const synthesis = window.speechSynthesis

  // Прошлая реплика обрывается: читатель, тапнувший три слова подряд, хочет
  // услышать третье, а не очередь из трёх.
  synthesis.cancel()

  const utterance = new SpeechSynthesisUtterance(text)
  const voice = english()
  if (voice) {
    utterance.voice = voice
    utterance.lang = voice.lang
  } else {
    utterance.lang = 'en-GB'
  }
  // Чуть медленнее обычного: слово разбирают на слух, а не слушают дикцию.
  utterance.rate = 0.92
  synthesis.speak(utterance)
}

/**
 * Подписывается на появление голосов.
 *
 * Нужно ровно затем, чтобы кнопка «произнести» не была активной там, где
 * говорить нечем: кнопка, которая молчит, хуже её отсутствия.
 */
export function onVoicesReady(next: () => void): () => void {
  if (!canSpeak()) return () => {}
  const synthesis = window.speechSynthesis
  const handle = () => {
    preferred = null
    next()
  }
  synthesis.addEventListener('voiceschanged', handle)
  return () => synthesis.removeEventListener('voiceschanged', handle)
}

export type CompanionSound = 'reveal' | 'reaction' | 'ready'

let audio: AudioContext | null = null

/** Короткий газетный «щелчок» без внешних файлов и сетевой загрузки. */
export function playCompanionSound(cue: CompanionSound, enabled: boolean): void {
  if (!enabled || typeof AudioContext === 'undefined') return
  try {
    audio ??= new AudioContext()
    const context = audio
    if (context.state === 'suspended') void context.resume()
    const start = context.currentTime + 0.005
    const notes = cue === 'ready' ? [392, 523] : cue === 'reaction' ? [330, 440] : [392]
    notes.forEach((frequency, index) => {
      const oscillator = context.createOscillator()
      const gain = context.createGain()
      const at = start + index * 0.06
      oscillator.type = 'sine'
      oscillator.frequency.value = frequency
      gain.gain.setValueAtTime(0.0001, at)
      gain.gain.exponentialRampToValueAtTime(0.035, at + 0.012)
      gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.085)
      oscillator.connect(gain).connect(context.destination)
      oscillator.start(at)
      oscillator.stop(at + 0.09)
    })
  } catch {
    // Звук — украшение: запрет браузера не должен мешать чтению.
  }
}

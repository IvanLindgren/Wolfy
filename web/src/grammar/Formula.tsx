/**
 * Формула правила: `have/has + V3`.
 *
 * Раньше формула была строкой моноширинным шрифтом — то есть выглядела как
 * кусок кода посреди книжной страницы и ничего не объясняла: `V3` читателю,
 * который и пришёл узнать, что такое `V3`, не говорит ничего.
 *
 * Здесь формула разбирается на части и собирается обратно кирпичиками, в том
 * же порядке, в каком слова стоят в предложении. Каждый кирпичик знает, что
 * он такое:
 *
 * - **слово** (`have/has`, `will`, `to`) — то, что пишется буквально; варианты
 *   через косую черту разводятся, потому что «have/has» — это выбор одного из
 *   двух, а не слово с чертой посередине;
 * - **знак** (`V`, `V-ing`, `V3`) — место глагола. У него есть расшифровка, и
 *   она стоит рядом с формулой, а не в голове у того, кто её писал;
 * - **место** («объект», «подлежащее») — пропуск, который читатель заполняет
 *   сам; набран по-русски и нарочно выглядит как пустая строка.
 *
 * Запятая в формуле (`if + Present, will + V`) разделяет части сложного
 * предложения — она и остаётся запятой, разрывая строку кирпичиков.
 */

import styles from './grammar.module.css'

/** Расшифровка знаков. Порядок важен: `V-ing` обязан проверяться раньше `V`. */
const SYMBOLS: { code: string; title: string; hint: string }[] = [
  { code: 'V-ing', title: 'глагол с окончанием -ing', hint: 'reading, going, being' },
  { code: 'V3', title: 'третья форма глагола (причастие)', hint: 'read, gone, written' },
  { code: 'V2', title: 'вторая форма глагола (прошедшее время)', hint: 'read, went, wrote' },
  { code: 'V', title: 'глагол в словарной форме', hint: 'read, go, be' },
]

function symbolOf(piece: string) {
  return SYMBOLS.find((symbol) => symbol.code === piece)
}

/** Пропуск, который читатель заполняет своим словом: набран по-русски. */
function isSlot(piece: string): boolean {
  return /[а-яё]/i.test(piece)
}

interface Piece {
  text: string
  kind: 'word' | 'symbol' | 'slot'
  title?: string
}

/** Разбирает формулу на части предложения и кирпичики внутри них. */
export function parseFormula(formula: string): Piece[][] {
  return formula
    .split(',')
    .map((clause) =>
      clause
        .split('+')
        .map((piece) => piece.trim())
        .filter(Boolean)
        .map((text): Piece => {
          const symbol = symbolOf(text)
          if (symbol) return { text, kind: 'symbol', title: `${symbol.title}: ${symbol.hint}` }
          if (isSlot(text)) return { text, kind: 'slot' }
          return { text, kind: 'word' }
        }),
    )
    .filter((clause) => clause.length > 0)
}

/** Знаки, встретившиеся в формуле, — для расшифровки под ней. */
export function symbolsOf(formula: string) {
  return SYMBOLS.filter((symbol) =>
    parseFormula(formula).some((clause) =>
      clause.some((piece) => piece.kind === 'symbol' && piece.text === symbol.code),
    ),
  )
}

export function Formula({ formula, size = 'small' }: { formula: string; size?: 'small' | 'large' }) {
  const clauses = parseFormula(formula)
  if (!clauses.length) return null

  return (
    <p className={styles.formula} data-size={size} aria-label={`Формула: ${formula}`}>
      {clauses.map((clause, clauseIndex) => (
        <span className={styles.formula__clause} key={clauseIndex}>
          {clauseIndex > 0 && (
            <span className={styles.formula__comma} aria-hidden="true">
              ,
            </span>
          )}
          {clause.map((piece, index) => (
            <span className={styles.formula__step} key={index}>
              {index > 0 && (
                <span className={styles.formula__plus} aria-hidden="true">
                  +
                </span>
              )}
              <span
                className={styles.formula__piece}
                data-kind={piece.kind}
                title={piece.title}
                lang={piece.kind === 'slot' ? 'ru' : 'en'}
              >
                {piece.text.split('/').map((variant, variantIndex) => (
                  <span key={variantIndex}>
                    {variantIndex > 0 && (
                      <span className={styles.formula__or} aria-hidden="true">
                        или
                      </span>
                    )}
                    {variant.trim()}
                  </span>
                ))}
              </span>
            </span>
          ))}
        </span>
      ))}
    </p>
  )
}

/** Расшифровка знаков формулы. Стоит рядом с ней, а не в справке. */
export function FormulaLegend({ formula }: { formula: string }) {
  const symbols = symbolsOf(formula)
  if (!symbols.length) return null

  return (
    <dl className={styles.legend}>
      {symbols.map((symbol) => (
        <div className={styles.legend__row} key={symbol.code}>
          <dt className={styles.legend__code} lang="en">
            {symbol.code}
          </dt>
          <dd className={styles.legend__text}>
            {symbol.title}
            <span className={styles.legend__hint} lang="en">
              {symbol.hint}
            </span>
          </dd>
        </div>
      ))}
    </dl>
  )
}

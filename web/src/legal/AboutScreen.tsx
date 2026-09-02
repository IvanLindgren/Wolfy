import { Link } from '@tanstack/react-router'

import page from '../widgets/Page.module.css'
import styles from './legal.module.css'
import { SOURCE_URL, TELEGRAM_URL } from './downloads'

/**
 * Об авторе.
 *
 * Кто написал и как ему сказать, что сломалось. Коротко: страницу открывают
 * один раз, и длинная биография на ней читается ровно до второго абзаца.
 */
export function AboutScreen() {
  return (
    <main className={page.page}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Wolfy</div>
          <h1 className={page.title}>О разработчике</h1>
        </div>
      </header>

      <div className={styles.author}>
        <img
          className={styles.portrait}
          src="/img/denis.webp"
          width={272}
          height={272}
          alt="Денис Корнилов"
        />
        <div>
          <p className={styles.authorName}>Денис Корнилов</p>
          <p className={styles.authorRole}>Независимый разработчик</p>
          <dl className={styles.contacts}>
            <div className={styles.contact}>
              <dt>Телеграм</dt>
              <dd>
                <a href="https://t.me/ivanlindgren" target="_blank" rel="noreferrer">
                  @ivanlindgren
                </a>
              </dd>
            </div>
            <div className={styles.contact}>
              <dt>ВКонтакте</dt>
              <dd>
                <a href="https://vk.com/denkorni" target="_blank" rel="noreferrer">
                  vk.com/denkorni
                </a>
              </dd>
            </div>
            <div className={styles.contact}>
              <dt>Почта</dt>
              <dd>
                <a href="mailto:denis.kornilov12@yandex.ru">denis.kornilov12@yandex.ru</a>
              </dd>
            </div>
            <div className={styles.contact}>
              <dt>Канал</dt>
              <dd>
                <a href={TELEGRAM_URL} target="_blank" rel="noreferrer">
                  t.me/citavuk
                </a>
              </dd>
            </div>
          </dl>
        </div>
      </div>

      <div className={styles.prose}>
        <p className={styles.lead}>
          Привет! Я независимый разработчик всего, что может оказаться людям
          хоть немного полезным.
        </p>
        <p>
          Читать в оригинале мешало одно: каждое второе слово уходило в
          переводчик, и книга кончалась раньше терпения. Так появился Wolfy —
          нажимаете слово и видите перевод в этом предложении, разбор формы и
          правило. Что оставили себе, само становится карточкой.
        </p>
        <p>
          Нашли ошибку или чего-то не хватает — напишите, быстрее всего доходит
          телеграм. Исходники открыты:{' '}
          <a href={SOURCE_URL} target="_blank" rel="noreferrer">
            github.com/IvanLindgren/Wolfy
          </a>
          .
        </p>
        <p>
          <Link to="/downloads">Скачать</Link> ·{' '}
          <Link to="/privacy">Конфиденциальность</Link> ·{' '}
          <a href={TELEGRAM_URL} target="_blank" rel="noreferrer">
            Телеграм-канал
          </a>
        </p>
      </div>
    </main>
  )
}

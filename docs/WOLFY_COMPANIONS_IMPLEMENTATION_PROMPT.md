# Wolfy Companions: полный промпт реализации

Ниже находится самостоятельный промпт для coding agent. Он описывает продукт, архитектуру, интерфейс, серверные контракты, ассеты, безопасность, производительность, тесты и порядок выпуска. Выполняй его целиком. Не заменяй реализацию макетами, заглушками или описанием будущей работы.

---

## Роль и результат

Ты работаешь как ведущий продуктовый инженер Wolfy. Твоя задача: реализовать необязательную систему книжных компаньонов во всех актуальных клиентах Wolfy и на сервере.

Компаньон это персонаж, которого читатель создаёт, настраивает и наряжает. Книгу можно читать без него или вместе с ним. В обычном чтении компаньон работает локально и редко произносит заранее подготовленные короткие реплики. По явному запросу читателя он может высказать мнение о текущей странице, ответить на вопрос по уже прочитанной части книги или пересказать недавний сюжет через существующую серверную ИИ-инфраструктуру.

Результат должен быть пригоден для production:

1. Android, JVM desktop и web имеют одинаковую модель данных и одинаковые основные возможности.
2. Обычное чтение с компаньоном не вызывает ИИ и не делает сетевых запросов.
3. Ответы ИИ имеют строгие серверные контракты, проверяются до отправки клиенту и честно показывают ошибки.
4. Состояние компаньона синхронизируется между устройствами и работает offline-first.
5. Компаньон никогда не перекрывает текст, карточку слова, выделение фразы, настройки чтения и системную навигацию.
6. Ассеты имеют проверенное происхождение, нормализованы, не ломают размер сборок и не содержат запечённый псевдопрозрачный фон.
7. Режим «Исследования» полностью отсутствует в финальных продуктах, API, конфигурации, БД и документации.

Не останавливайся после составления плана. После анализа реализуй, протестируй, собери и подготовь изменения к выпуску. Не коммить и не пушь без явного запроса владельца репозитория.

## Контекст репозитория

Перед изменениями прочитай `docs/agents` и локальные инструкции репозитория. Используй graph/code index, если он доступен, для поиска связей и вызовов. Не делай массовые догадки по именам файлов.

Ожидаемая архитектура:

- `client/shared/src/commonMain/kotlin`: Kotlin Multiplatform, Compose UI, общая модель и логика Android/JVM desktop.
- `client/shared/src/androidMain` и platform source sets: платформенные детали Android.
- `client/shared/src/jvmMain` или JVM-related source sets: desktop.
- `web/src`: React, TypeScript, Vite, PWA.
- `server`: Go API, PostgreSQL, миграции и интеграция с Gemini-совместимым провайдером.
- ядро библиотеки и настроек использует Rust и сериализованные команды. Если профиль компаньона помещается в ядро, обнови схему, миграцию состояния, команды и wasm/JNI bindings согласованно.

Существующая ИИ-инфраструктура включает `/v1/ai/phrase` и `/v1/ai/recap`, дневной лимит 10 запросов, серверную проверку JSON, резервирование квоты и подробные коды ошибок. Не обходи её прямым вызовом Gemini из клиента. Секретный ключ остаётся только на сервере.

Синхронизация уже передаёт книги, карточки, сведения о файлах и настройки чтения. Расширяй версионируемый контракт совместимо со старыми клиентами.

## Нулевая стадия: окончательно удалить «Исследования»

В текущей рабочей ветке удаление уже могло быть сделано. Сначала проверь результат, затем исправь остатки. Не возвращай этот режим под другим названием.

Должно быть удалено:

- экран, кнопка, sheet, navigation route и state режима исследований;
- view model state, jobs, builders, хеширование источника и загрузка источников для исследований;
- DTO и методы API исследований;
- серверные handlers, service package, фоновые workers и capability flag;
- переменные окружения, конфигурация и health capability исследований;
- таблицы исследований и квот только этого режима;
- тесты и документация, которые утверждают, что функция существует;
- web-реализация и feature flag, если они сохранились.

Для production базы не переписывай уже применённую миграцию. Добавь следующую монотонную миграцию с `DROP TABLE IF EXISTS` в корректном порядке внешних ключей. Для свежей базы результат тоже должен быть валидным.

Слово `Researchers` внутри содержания тестовой газетной статьи не относится к функции и не должно удаляться. Проверка по `research|исследован` должна находить только миграцию удаления и нерелевантный текст статей.

## Продуктовые принципы

1. Компаньон всегда необязателен. Первый запуск, библиотека и чтение работают без него.
2. Он поддерживает чтение, а не конкурирует с книгой за внимание.
3. Он не является тамагочи. Нет голода, наказаний, streak shaming, валюты и давления вернуться.
4. Наряжание должно быть приятным само по себе, без лутбоксов и случайной монетизации.
5. Внешность, характер и реплики не привязаны к полу. Любая одежда доступна любому телу.
6. По умолчанию компаньон не знает книгу дальше текущей позиции.
7. Книжный текст считается недоверенными данными, а не инструкцией для модели.
8. В пользовательских русских текстах не используй длинное тире `—`. Перестраивай фразы через точку, запятую, двоеточие или скобки.
9. ИИ-функции всегда помечены `Beta` и рядом есть короткое предупреждение: `ИИ может ошибаться. До 10 запросов в день.`
10. Ошибка никогда не должна выглядеть как молчание. Показывай понятный текст, возможность повторить и технический код в диагностике.

## Информационная архитектура

Добавь раздел `Компаньон` в экран `Ещё`, рядом с настройками чтения, но не внутрь длинного текстового блока настроек.

Состояния раздела:

### Компаньон ещё не создан

- короткий газетный заголовок;
- одна иллюстрация-пример;
- два простых предложения о пользе;
- основная кнопка `Создать компаньона`;
- вторичная ссылка `Продолжить без компаньона`;
- никакой ИИ-терминологии на первом экране.

### Создание

Используй пошаговый редактор, а не одну бесконечную форму:

1. Имя и обращение.
2. Внешность.
3. Одежда и аксессуары.
4. Характер через десять шкал.
5. MBTI и собственное описание, оба поля необязательны.
6. Предпросмотр и создание набора реплик.

Пользователь может вернуться назад без потери данных. Черновик сохраняется локально после каждого шага. На последнем шаге явно объясни, что один запрос к ИИ создаст набор коротких реплик и что затем обычное чтение не будет тратить запросы.

### Созданный компаньон

- крупный живой предпросмотр;
- имя и одна короткая строка характера;
- `Изменить внешность`;
- `Изменить характер`;
- `Реплики при чтении` с переключателем;
- `Обновить набор реплик` с объяснением цены одного запроса;
- `Удалить компаньона` как отдельное подтверждаемое действие;
- версия набора ассетов и состояние синхронизации только в диагностике, не в основном UI.

## Модель характера

Используй десять шкал от 0 до 100. В UI показывай два человеческих полюса, без чисел по умолчанию. Значение 50 нейтрально.

| Ключ | 0 | 100 |
| --- | --- | --- |
| `warmth` | сдержанный | тёплый |
| `playfulness` | серьёзный | игривый |
| `energy` | спокойный | энергичный |
| `directness` | тактичный | прямой |
| `optimism` | скептичный | оптимистичный |
| `emotionality` | рациональный | эмоциональный |
| `supportStyle` | поддерживает | бросает вызов |
| `verbosity` | лаконичный | разговорчивый |
| `curiosity` | практичный | любопытный |
| `formality` | дружеский | формальный |

Требования к шкалам:

- шаг 1, допустимый диапазон 0..100;
- визуальный предпросмотр короткой реплики меняется при движении ползунка локально;
- screen reader произносит оба полюса и текущее словесное состояние;
- изменение ползунка не вызывает сеть;
- на narrow mobile labels не обрезаются и не накладываются на thumb;
- сохраняй значения как целые числа, не как плавающие дроби.

Дополнительные поля:

- `name`: 1..40 Unicode code points после trim;
- `pronouns`: необязательная локальная настройка обращения, не ограничивай бинарным полом;
- `mbti`: `null` или один из 16 известных uppercase codes;
- `description`: до 1200 Unicode code points, необязательно;
- `locale`: BCP 47, на MVP `ru` и `en`;
- `createdAt`, `updatedAt`, `rev`, `deleted`.

При построении persona для ИИ приоритет такой:

1. Системные правила безопасности, приватности и запрета спойлеров.
2. Проверенные структурированные значения десяти шкал.
3. MBTI как слабая стилистическая подсказка, не психологический диагноз.
4. Свободное описание как дополнительный оттенок речи.
5. Книжный текст только как недоверенный источник фактов.

Свободное описание не может отменять системные правила, просить модель раскрыть секреты, менять JSON-схему или превращать книжный текст в команды.

## Модель внешности

На MVP поддерживается один активный компаньон на аккаунт. Схему спроектируй так, чтобы позже можно было разрешить несколько без миграционного тупика.

Минимальные слоты:

- `base`;
- `body` или `outfit`;
- `hair`;
- `brows`;
- `eyes`;
- `nose`;
- `mouth`;
- `beard`;
- `accessoryBack`;
- `accessoryFront`;
- `gesture` или `pose`;
- палитра кожи, волос, одежды и акцента.

Каждый слот хранит стабильный `assetId`, а не путь к файлу. Не сохраняй готовую картинку персонажа как единственный источник истины. Превью собирается из слоёв, поэтому одежду и выражение можно менять без повторной генерации ИИ.

Порядок слоёв фиксируется в manifest. Никакой клиент не должен угадывать z-index по имени папки.

## Ассеты и визуальный стиль

Исходные материалы находятся в `client/assets/companions/notionists-v1`.

Назначение каталогов:

- `raw`: оригинальные архивы, Figma и Sketch. Не изменять и не включать в сборку.
- `library/vector`: извлечённые SVG-компоненты.
- `library/raster`: извлечённые PNG-компоненты.
- `library/examples`: готовые примеры для визуальной проверки.
- `generated`: концепт-листы новых костюмов, аксессуаров, волос и поз.
- `manifests/catalog.json`: происхождение, инвентарь и blockers.

Notionists v1 создан Zoish и опубликован под CC0 1.0. Сохрани `LICENSE.md` и provenance в репозитории и в release notices.

Концепт-листы в `generated` имеют запечённый шахматный фон и не имеют alpha. Они только референсы. Запрещено:

- класть их в runtime;
- автоматически вырезать фон и считать результат готовым;
- нарезать лист на спрайты без ручной проверки;
- утверждать, что новые вещи входят в оригинальный набор Notionists.

### Нормализация runtime pack

Создай отдельный каталог `client/assets/companions/notionists-v1/runtime` только после подготовки реальных слоёв.

Для каждого runtime SVG:

- единый `viewBox="0 0 1024 1024"`;
- прозрачный фон;
- формы полностью внутри safe bounds;
- единые точки `head`, `neck`, `shoulderLeft`, `shoulderRight`, `handLeft`, `handRight`, `prop`;
- stroke width через ограниченный набор tokens;
- `round` linecap и linejoin, если исходная иллюстрация не требует другого;
- только разрешённые цвета через заменяемые tokens;
- отсутствие embedded raster, external URLs, scripts, filters и fonts;
- уникальные IDs после namespacing;
- разумно упрощённые paths;
- визуальная проверка на светлом, сепия и тёмном фоне.

Runtime manifest должен содержать:

```json
{
  "schemaVersion": 1,
  "packId": "notionists-wolfy-v1",
  "canvas": {"width": 1024, "height": 1024},
  "layerOrder": ["accessoryBack", "base", "body", "hair", "brows", "eyes", "nose", "mouth", "beard", "gesture", "accessoryFront"],
  "assets": [
    {
      "id": "hair.short.wavy.01",
      "slot": "hair",
      "file": "hair/short-wavy-01.svg",
      "tags": ["short", "wavy"],
      "incompatibleWith": [],
      "anchorsVersion": 1
    }
  ]
}
```

Добавь валидатор asset pack, который падает на CI при неверном viewBox, опасных SVG-элементах, неизвестном слоте, повторяющемся ID, отсутствующем файле, неправильном anchor version и слишком большом файле.

### Редактор внешности

- компаньон виден в верхней части экрана;
- категории представлены понятными пиктограммами и подписями;
- варианты листаются горизонтально на телефоне и сеткой на desktop/web;
- выбранный предмет обведён кирпично-красным;
- есть явный вариант `Без аксессуара`;
- есть undo/redo минимум на текущей сессии редактора;
- смена предмета анимируется коротким fade/scale, но при `reduceMotion` применяется мгновенно;
- не используй случайную генерацию как единственный путь, но добавь кнопку `Удивить меня` с локальным seed;
- один и тот же seed и pack version дают одинаковую внешность на всех клиентах.

## Данные и синхронизация

Введи общий версионированный DTO. Названия можно адаптировать к стилю проекта, но семантика обязательна.

```kotlin
@Serializable
data class CompanionProfile(
    val id: String,
    val name: String,
    val pronouns: String? = null,
    val locale: String = "ru",
    val personality: CompanionPersonality,
    val mbti: String? = null,
    val description: String = "",
    val appearance: CompanionAppearance,
    val phrasePack: CompanionPhrasePack? = null,
    val rev: Long = 0,
    val deleted: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)
```

`CompanionPersonality` содержит ровно десять целочисленных шкал. `CompanionAppearance` содержит `packId`, `packVersion`, asset IDs по слотам, palette IDs и seed. `CompanionPhrasePack` содержит schema version, profile hash, locale, generatedAt и ровно 100 валидных реплик.

Добавь `companions` как отдельную коллекцию в sync payload. Старые клиенты игнорируют неизвестное поле, новые корректно принимают payload без него. Не вкладывай компаньона в JSON настроек чтения: у него независимая ревизия, tombstone и более крупный phrase pack.

Conflict policy:

- сервер назначает монотонную `rev`;
- профиль синхронизируется как LWW record по серверной ревизии;
- удаление является tombstone и не оживляется старым offline клиентом;
- локальный черновик редактора не синхронизируется, пока пользователь не нажал `Сохранить`;
- готовый profile и phrase pack пишутся атомарно;
- неизвестный asset ID не ломает профиль: renderer использует безопасный default и показывает действие `Исправить внешний вид` в редакторе;
- если нужной версии pack нет локально, сначала показывается default silhouette, затем pack загружается или берётся из приложения.

На сервере создай следующую миграцию после существующих. Не переименовывай старые применённые миграции. Предпочтительная таблица:

```sql
CREATE TABLE wolfy.companions (
    user_id uuid PRIMARY KEY REFERENCES wolfy.users(id) ON DELETE CASCADE,
    companion_id uuid NOT NULL,
    profile jsonb NOT NULL,
    phrase_pack jsonb,
    profile_hash text NOT NULL,
    rev bigint NOT NULL,
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

Если принятые conventions store требуют нормализованных колонок, следуй им. В любом случае добавь JSON schema validation в Go, ограничения размера и индексы для sync cursor. Не доверяй JSON от клиента.

Offline behavior:

- создание и редактирование внешности работают offline;
- локальный встроенный fallback phrase pack доступен offline;
- генерация персональных 100 реплик и вопросы к книге требуют сеть и вход;
- сетевой статус показывается до нажатия, а не после молчаливого таймаута;
- после восстановления сети dirty profile синхронизируется обычным механизмом.

## Набор из 100 реплик

При первом сохранении профиля предложи один серверный запрос, который создаёт ровно 100 коротких реплик. Не вызывай модель отдельно для каждой реплики.

Если пользователь пропускает генерацию или провайдер недоступен, используй встроенный нейтральный набор из 100 локализованных реплик. Приложение остаётся полностью работоспособным.

### События реплик

Распредели ровно 100 элементов по фиксированным сценариям:

- `session_start`: 10;
- `session_resume`: 8;
- `steady_reading`: 18;
- `page_completed`: 10;
- `chapter_completed`: 10;
- `long_session`: 8;
- `return_after_break`: 8;
- `difficult_page`: 8;
- `mood_joy`: 4;
- `mood_sadness`: 4;
- `mood_tension`: 4;
- `mood_mystery`: 4;
- `session_end`: 4.

Итого 100. Изменение распределения требует новой версии schema.

Контракт элемента:

```json
{
  "id": "session_start.01",
  "scenario": "session_start",
  "text": "Ну что, почитаем немного?",
  "minMinutes": 0,
  "cooldownMinutes": 20,
  "weight": 1,
  "moods": [],
  "motion": "wave"
}
```

Server validator обязан проверить:

- JSON содержит только ожидаемые поля;
- ровно 100 уникальных IDs;
- точное распределение сценариев;
- каждый текст 2..120 Unicode code points;
- нет управляющих символов, markdown, URL, HTML и переносов строки;
- нет длинного тире `—`;
- нет цитат, выдаваемых за текст книги;
- нет фактов о сюжете, персонажах или авторе;
- нет оскорблений, давления, романтической зависимости и guilt language;
- `cooldownMinutes` находится в безопасном диапазоне;
- `weight` конечный и положительный;
- `motion` входит в allowlist;
- locale совпадает с запросом;
- `profileHash` соответствует canonical profile.

Если первая генерация не проходит контракт, выполни один repair request с ошибками validator. Если repair тоже невалиден, освободи квоту и верни fallback marker. Никогда не сохраняй частичный pack.

Идемпотентность:

- клиент отправляет idempotency key;
- сервер canonical-сериализует личностные поля и вычисляет profile hash;
- повтор с тем же hash возвращает сохранённый pack без нового вызова модели;
- изменение только одежды не меняет hash и не пересоздаёт реплики;
- изменение характера предлагает обновить pack, но не делает это без согласия;
- одновременные запросы для одного hash объединяются или один получает conflict/retry, но не создают две оплаты.

Не храни provider prompt и полный свободный текст в обычных логах. Логи содержат request ID, user ID в принятом безопасном формате, model, latency, result code и validator reason без содержимого книги или persona.

## Локальный выбор реплик

Обычное чтение не обращается к серверу. Реализуй детерминированный локальный `CompanionReactionEngine`.

Входы:

- профиль и phrase pack;
- тип события чтения;
- длительность текущей сессии;
- количество прочитанных страниц или смысловых сегментов;
- локальная оценка сложности и настроения текущей страницы;
- история последних 20 показанных IDs;
- timestamp последней реплики;
- `reduceMotion`, foreground/background, открытые overlays;
- seed, полученный из profile ID и номера дня.

Правила:

- не чаще одной непрошенной реплики в 7 минут;
- максимум 5 непрошенных реплик за часовую сессию;
- никогда не показывать во время активного scroll, selection, long press, drag, double click, phrase card, word card, settings preview, dialog или system permission;
- после scroll ждать минимум 2 секунды покоя;
- не повторять один ID в последних 20;
- не повторять нормализованный текст в последних 10;
- `session_start`, `chapter_completed` и ручные действия имеют отдельные cooldown;
- если нет подходящей реплики, ничего не показывать;
- пользователь может отключить реплики, оставив самого персонажа и ручные вопросы;
- все решения engine покрываются тестами с fake clock и fixed seed.

## Лёгкий локальный анализ настроения

Для выбора одной из заготовленных реплик не используй Gemini. Реализуй небольшой локальный классификатор или lexicon scorer для английского текста, при необходимости с русским fallback.

MVP labels:

- `neutral`;
- `joy`;
- `sadness`;
- `tension`;
- `mystery`;
- `calm`;
- `difficulty` как независимый score.

Требования:

- анализ только текущего видимого/страничного фрагмента;
- ограничение входа, например последние 1200 слов;
- нормализация case и punctuation;
- отрицание меняет локальный score, если это реализуемо без тяжёлой NLP-модели;
- восклицания, вопросы, длина предложений, редкая лексика и плотность диалога могут быть дополнительными признаками;
- confidence ниже порога возвращает `neutral`;
- вычисление выполняется off UI thread;
- результат кешируется по `bookId + chapter + page/segment hash`;
- никакой текст не сохраняется в analytics;
- модель или словарь поставляется локально и имеет задокументированную лицензию;
- размер компонента минимален, предпочтителен простой проверяемый scorer;
- одинаковый текст даёт одинаковый результат на Android, desktop и web.

Компаньон не должен утверждать, что знает эмоции читателя. Допустимо: `Здесь стало тревожнее.` Недопустимо: `Тебе сейчас страшно.`

## Компаньон в читалке

### Режимы

Для каждой книги или устройства поддержи:

- `off`: обычное чтение, персонажа нет;
- `quiet`: персонаж виден, непрошенные реплики выключены;
- `active`: персонаж виден и может редко реагировать.

Переход находится в компактном меню чтения: `Читать с компаньоном`. Не возвращай отдельный перегруженный research mode.

### Размещение на телефоне

- компактная фигура в нижнем безопасном углу рядом со страницей, но не поверх текста;
- выдели для неё реальную layout area или внешний gutter;
- при нехватке высоты показывай только маленькую голову/таб;
- во время scroll персонаж плавно уходит в край, после покоя возвращается;
- bubble ограничен двумя короткими строками и закрывается сам;
- tap открывает actions sheet;
- long press по тексту всегда начинает выделение фразы и не перехватывается персонажем;
- bottom navigation, IME и системные insets учтены;
- карточка слова/фразы имеет приоритет и полностью скрывает companion bubble.

### Размещение на desktop и web

- фигура живёт в боковом поле страницы;
- если ширины нет, сворачивается в compact tab;
- double click по тексту всегда запускает выделение фразы;
- mouse selection, context menu, keyboard navigation и copy не перехватываются;
- bubble не меняет ширину текста и не вызывает layout shift после первого кадра;
- окно при resize остаётся стабильным.

### Действия по tap/click

Покажи компактное меню:

1. `Что думаешь об этой странице?` с меткой `Beta`.
2. `Задать вопрос о книге` с меткой `Beta`.
3. `Вспомнить сюжет` с меткой `Beta`.
4. `Помолчи пока` или `Включить реплики`.
5. `Изменить компаньона`.

Под действиями ИИ один раз показывай: `ИИ может ошибаться. До 10 запросов в день.` Не дублируй предупреждение под каждой кнопкой.

## ИИ: мнение о странице

Добавь серверный endpoint, например `POST /v1/ai/companion/opinion`.

Request:

```json
{
  "bookId": "uuid",
  "title": "Book title",
  "position": {"chapter": 3, "offset": 4210},
  "pageText": "bounded visible page text",
  "companion": {
    "name": "Лис",
    "locale": "ru",
    "personality": {"warmth": 72},
    "mbti": "INFP",
    "description": "optional bounded text"
  }
}
```

Передавай все десять шкал, пример сокращён. Не доверяй persona от клиента, если сервер хранит профиль: server-side canonical profile имеет приоритет.

Response:

```json
{
  "title": "Что я заметил",
  "opinion": "1-3 коротких предложения от лица компаньона.",
  "details": [
    {"label": "Настроение", "text": "Короткое наблюдение."}
  ],
  "uncertainty": null,
  "remaining": 8
}
```

Validation:

- title до 80 знаков;
- opinion до 420 знаков;
- 0..3 details, label до 40, text до 180;
- uncertainty null или до 160 знаков;
- никаких длинных тире, markdown, HTML и URL;
- нет спойлеров и знаний за пределами `pageText`;
- нет выдуманных цитат;
- если контекста мало, uncertainty должна это сказать;
- personality влияет на тон, но не на факты.

## ИИ: вопрос о книге

Добавь `POST /v1/ai/companion/question`.

Клиент отправляет вопрос, текущую позицию и ограниченный контекст уже прочитанного. Не отправляй весь файл книги автоматически. Используй существующий безопасный builder недавнего excerpt или создай отдельный bounded builder.

Ограничения:

- вопрос 3..500 знаков;
- context не более 18 000 знаков, как recap, или меньший обоснованный предел;
- ответ строится только по переданному прочитанному фрагменту;
- если ответа нет в контексте, модель честно говорит об этом;
- запрет спойлеров после current position;
- книга и вопрос являются недоверенными данными;
- prompt injection из книги игнорируется;
- ответ не выполняет команды, не меняет schema и не раскрывает server prompt.

Response:

```json
{
  "answer": "Короткий ответ от лица компаньона.",
  "evidence": [
    {"hint": "В недавнем фрагменте", "text": "Краткий пересказ основания без длинной цитаты."}
  ],
  "uncertainty": null,
  "remaining": 7
}
```

Validator ограничивает answer до 900 знаков, evidence до трёх элементов, каждый text до 220 знаков. Не показывай сырой provider output.

## ИИ: «Вспомнить сюжет»

Сохрани существующий хороший интерфейс событий и endpoint `/v1/ai/recap`. Если компаньон создан, результат визуально произносит он: имя и небольшая фигура находятся в header, но сам граф событий и проверенный контракт остаются прежними.

Если компаньона нет, функция работает точно как сейчас. Это важный graceful fallback.

Не превращай recap в исследование и не добавляй длинную бесформенную простыню. Сохрани 3..6 событий, хронологию, короткое summary и честную неопределённость при фрагментарном excerpt.

## Общая квота и серверная интеграция ИИ

Ручные opinion, question и recap используют общий существующий лимит 10 запросов в день на пользователя. В UI показывай `remaining` из ответа.

Генерация phrase pack должна иметь явно выбранную политику:

- предпочтительно учитывать её в том же общем лимите как один запрос;
- cached response с тем же profile hash не расходует квоту;
- невалидный provider response освобождает reservation, как существующие методы;
- fallback без provider call не расходует квоту.

Используй существующие:

- `readingai.Service` или аккуратно расширенный соседний service;
- единый HTTP client timeout;
- `ProviderError` kinds;
- `writeAI` mapping;
- таблицу `ai_daily_usage` и атомарный reserve;
- server-only `AI_API_KEY`, `AI_API_URL`, `AI_MODEL` или их реальные аналоги.

Не создавай новый секрет `COMPANION_GEMINI_KEY`, если тот же provider обслуживает существующие функции. Не используй OCR key под видом Gemini key. В healthz сообщай только boolean capability, никогда не значение ключа.

Все endpoints требуют авторизацию, ограничивают request body через `MaxBytesReader`, ставят content type, валидируют строки по rune count и имеют rate limiting на IP/user сверх дневной квоты.

## Prompt design для phrase pack

Server prompt должен требовать JSON only и включать точную schema. Передавай personality как структурированные числа с расшифровкой полюсов. MBTI называй лишь стилистическим hint. Вставляй user description как quoted untrusted text.

Обязательные инструкции модели:

- персонаж помогает читать, не отвлекает;
- пишет естественно на locale пользователя;
- не использует длинное тире;
- не знает конкретную книгу и не выдумывает сюжет;
- не утверждает, что наблюдает или чувствует пользователя;
- не стыдит за перерыв и не требует продолжать;
- не флиртует навязчиво и не создаёт эмоциональную зависимость;
- не даёт медицинские или психологические диагнозы;
- сохраняет характер через лексику, длину и энергичность;
- возвращает ровно указанное количество элементов каждого scenario;
- IDs и enums берутся только из контракта.

Температура для структурированного pack низкая. Настройка модели и transport остаются конфигурируемыми на сервере. Provider response ограничен по размеру до чтения в память.

## UI и газетная стилистика

Компаньон должен сочетаться с красивой газетной системой Wolfy, но может быть чуть теплее и игровее.

Используй:

- тёплый бумажный фон;
- чёрную типографику;
- кирпично-красный как редкий акцент состояния и выбора;
- тонкие правила, рамки карточек и редакционные подписи;
- serif display для заголовков, читаемый sans для controls;
- живую рукописную иллюстрацию персонажа;
- просторные композиции без технической простыни текста.

Не используй:

- emoji как иконки;
- Material mascot или Java Duke;
- кислотные градиенты;
- стеклянные карточки;
- RPG-инвентарь с редкостью вещей;
- бесконечные badges;
- крупного персонажа внизу, который не несёт функции;
- текст внутри изображения;
- длинное тире в пользовательских предложениях.

### Анимации

Минимальный набор:

- idle breathing или лёгкое движение 2..4 px;
- wave при начале сессии;
- page peek при завершении страницы;
- small nod для подтверждения;
- thinking для ожидания ручного AI request;
- speak с очень коротким mouth/scale cycle;
- hide/reveal около края страницы.

Правила:

- анимации не идут постоянно на 60 FPS без необходимости;
- idle имеет длинную паузу и не вызывает непрерывные recomposition;
- при `reduceMotion` остаётся статичный кадр или opacity change;
- loading не обещает успех и имеет cancel;
- персонаж не дёргает layout;
- все animations lifecycle-aware и останавливаются в background;
- не запускать несколько animation jobs после recomposition или повторного открытия.

## Доступность

- каждый предмет имеет локализованное имя, не только картинку;
- персонаж как декор исключается из accessibility tree, а его button имеет ясную label;
- controls имеют минимум 44x44 CSS px или эквивалент;
- contrast соответствует WCAG AA;
- focus order на web/desktop логичен;
- редактор полностью доступен с клавиатуры;
- sliders имеют `aria-valuemin`, `aria-valuemax`, `aria-valuenow`, человеческий value text;
- screen reader не объявляет каждую idle animation;
- bubbles не крадут фокус;
- text scaling 200% не обрезает controls;
- dark mode и темы чтения не делают чёрную иллюстрацию невидимой: используй подложку или theme-aware ink token.

## Производительность и размер

Поставь измеримые budgets и добавь проверки там, где возможно:

- ноль сетевых запросов от companion engine во время обычного чтения;
- не декодировать весь каталог PNG при открытии редактора;
- thumbnail grid ленивый и кешируемый;
- runtime pack с основным MVP набором не более 8 MB compressed, либо обоснуй иной бюджет измерением;
- один собранный персонаж в reader не увеличивает steady-state память более чем на 20 MB;
- animation frame p95 укладывается в 16.7 ms на согласованном reference device при 60 Hz;
- открытие reader не ждёт companion assets;
- web bundle budget не нарушается, assets грузятся отдельными cacheable chunks;
- Compose painter/vector caches имеют bounded keys;
- analysis выполняется вне main thread и отменяется при смене страницы;
- никакой full-book analysis;
- benchmark быстрого scroll с активным компаньоном не показывает утечки jobs, images и DOM nodes.

Если бюджет не достигнут, выключи необязательную анимацию или уменьши runtime pack. Не скрывай регрессию увеличением лимита без замера и объяснения.

## Безопасность и приватность

Перед первым ручным ИИ-действием покажи короткое согласие: фрагмент текущей или недавней прочитанной части будет отправлен серверному ИИ-провайдеру. Запомни согласие в настройках аккаунта, предоставь ссылку на приватность и возможность отозвать.

Никогда не отправляй:

- весь файл книги без отдельной необходимости и согласия;
- путь локального файла;
- email в provider prompt;
- access token;
- сырые annotations, не относящиеся к вопросу;
- содержимое других книг;
- историю чтения целиком.

Book excerpt, question и description оборачиваются как недоверенные данные. В логах нет книжного текста и provider key. Ошибки клиента не содержат provider body.

Удаление компаньона удаляет профиль и phrase pack после sync tombstone. В privacy controls должна быть возможность удалить эти данные с сервера.

## KMP реализация

Следуй существующим паттернам StateFlow, ViewModel, repository/API и structured concurrency.

- введи отдельный `CompanionRepository`;
- UI state является immutable data class;
- не держи networking прямо в Composable;
- запросы отменяются при закрытии sheet;
- restore state не дублирует запрос;
- pending idempotency key переживает rotation/process recreation там, где это нужно;
- renderer принимает appearance и pose как данные;
- source assets не копируются автоматически в Android resources;
- platform-specific SVG support оборачивается в ожидаемый abstraction;
- long press selection на Android и double click на desktop имеют regression tests;
- настройки чтения остаются доступны со страницы книги.

## Web реализация

Обеспечь parity, а не пустую карточку с надписью `скоро`.

- shared TypeScript contracts совпадают с server JSON;
- runtime validation внешних responses;
- React Query используется для server state, local editor draft не смешивается с cache;
- renderer не использует dangerouslySetInnerHTML с непроверенным SVG;
- SVG assets импортируются/build-time sanitization или безопасным img/object pipeline;
- PWA offline cache включает только runtime pack и fallback phrases;
- keyboard, pointer и touch interactions не конфликтуют с selection;
- layout устойчив на 320 px, tablet и wide desktop;
- reduced motion определяется также через `prefers-reduced-motion`;
- AI errors показывают те же человеческие сообщения и коды, что KMP.

## Серверные контракты и совместимость

Добавь OpenAPI или существующий эквивалент contract documentation. Для каждого нового endpoint создай request/response types, max sizes, error codes и примеры.

Общие error codes:

- `auth`;
- `invalid_request`;
- `quota`;
- `key`;
- `model`;
- `limit`;
- `timeout`;
- `badjson`;
- `provider`;
- `profile_conflict`;
- `asset_pack_unknown`.

Клиент обязан иметь default message для неизвестного будущего code. HTTP status соответствует смыслу. 4xx не ретраятся бесконечно. 429 уважает `Retry-After`, если он есть. Timeout имеет ручной retry.

Добавь capability flags в health/API только для новых AI действий, если этот механизм уже используется. Нельзя прятать UI без объяснения: если capability false, действие disabled с коротким текстом `Подсказки сейчас недоступны`.

## Аналитика без вторжения

Если в проекте уже есть разрешённая продуктовая аналитика, допустимы только события без текста книги, вопроса, описания и имени:

- companion creation started/completed;
- reader mode off/quiet/active;
- outfit category opened;
- local reaction shown/dismissed;
- AI action requested/succeeded/failed с error code;
- phrase pack source generated/fallback/cache.

Не добавляй новый analytics SDK ради этой функции. Не записывай конкретные asset choices, MBTI и personality values без отдельного согласия.

## Тесты

### Unit

- personality bounds и MBTI allowlist;
- Unicode limits;
- canonical profile hash стабилен и не зависит от порядка JSON keys;
- одежда не меняет phrase profile hash;
- изменение personality меняет hash;
- phrase pack ровно 100 и имеет точное распределение;
- validator отклоняет неизвестные поля, markdown, URL, long dash, duplicate IDs и неверные enums;
- local engine cooldown, caps, anti-repeat и overlay suppression;
- fixed seed determinism;
- mood scorer golden corpus на нейтральных, радостных, грустных, напряжённых и загадочных фрагментах;
- uncertainty threshold;
- sync merge, tombstone и unknown asset fallback;
- migration fresh/upgrade;
- AI reserve/release и idempotency concurrency;
- prompt injection fixtures в book text, question и description;
- provider errors map в честные public codes.

### API integration

- unauthenticated requests rejected;
- oversized bodies rejected;
- quota общая для opinion/question/recap/pack по выбранной политике;
- invalid provider JSON never reaches client;
- second same-hash pack request returns cache;
- simultaneous same-hash requests do not double charge;
- server restart preserves pack;
- deleted profile is absent after sync but tombstone prevents resurrection;
- legacy sync payload without companions succeeds;
- legacy client can ignore companions field.

### KMP UI

- creation wizard restore;
- all ten sliders fit and announce values;
- appearance selection and no-accessory option;
- reader off/quiet/active;
- Android long press selects a phrase and opens phrase graph/card, not a single-word card;
- desktop double click starts phrase selection as specified by current product behavior;
- companion does not intercept selection;
- reading settings remain reachable from the book page;
- word/phrase card hides companion bubble;
- AI loading, cancel, each error type and retry;
- reduce motion;
- font scale and small screen;
- process recreation does not submit twice.

### Web UI and e2e

- create/edit/delete and offline draft;
- keyboard-only editor;
- touch selection and desktop selection;
- responsive snapshots at representative widths;
- PWA offline opens reader with fallback companion;
- AI endpoints mocked for valid, invalid, timeout, quota and capability false;
- no console errors and no layout shift from late asset load.

### Visual regression

Создай golden screenshots:

- empty companion screen;
- each wizard step;
- three diverse appearances using only permitted assets;
- reader mobile active/quiet;
- reader desktop sidebar;
- speech bubble one and two lines;
- action menu;
- opinion response;
- recap with companion;
- loading and all error states;
- light, sepia and dark reading themes;
- 200% font scale and reduce motion state.

Проверь, что тексты не обрезаны, персонаж не перекрывает страницу, нет emoji и нет длинного тире.

### Performance

- Compose macrobenchmark или доступный эквивалент: reader startup, rapid scroll, repeated open/close action sheet;
- desktop profiler smoke: CPU idle, heap after 20 reader opens, animation jobs;
- web Lighthouse/bundle budget и trace scroll;
- asset decode/cache benchmark;
- mood analysis on long allowed page under a documented target;
- network assertion that local reactions cause zero requests.

## Наблюдаемость

Добавь server metrics без содержимого пользователя:

- request count и latency по AI action;
- provider error kind;
- validator reject reason category;
- quota rejects;
- phrase pack cache hit;
- fallback rate;
- sync companion conflict count.

Healthz не вызывает provider и не расходует деньги. Он сообщает только локальную конфигурационную готовность.

Client diagnostics может показать:

- companion profile revision;
- asset pack version;
- phrase pack schema/source;
- last sync result;
- last AI public error code.

Не показывай эти технические поля в обычных настройках.

## Порядок реализации

Выполняй вертикальными срезами, сохраняя собираемость после каждого логического этапа.

### Этап 1. Аудит и удаление исследований

- проверь все платформы и конфигурацию;
- закончи удаления;
- добавь безопасную migration cleanup;
- запусти текущие тесты.

### Этап 2. Asset pipeline

- проверь provenance и manifest;
- выбери ограниченный MVP набор исходных Notionists компонентов;
- вручную подготовь и нормализуй approved runtime SVG;
- не используй concept PNG напрямую;
- добавь validator и CI;
- собери три visual fixtures.

### Этап 3. Общая модель и локальный профиль

- schema/DTO/core commands;
- repository и local persistence;
- wizard и renderer в KMP;
- wizard и renderer в web;
- offline draft;
- unit и screenshot tests.

### Этап 4. Sync

- server migration/store;
- sync contract;
- KMP/web adapters;
- conflict/tombstone tests;
- compatibility tests.

### Этап 5. Fallback pack и локальные реакции

- встроенные 100 фраз на поддерживаемую locale;
- mood scorer;
- deterministic reaction engine;
- reader placement;
- performance и interaction regression tests.

### Этап 6. Генерация персонального pack

- endpoint, strict schema, validator;
- idempotency/cache/quota;
- client progress/error/fallback UX;
- security tests.

### Этап 7. Ручные действия ИИ

- opinion;
- question;
- recap presentation with optional persona;
- common errors and disclosure;
- no-spoiler and prompt injection tests.

### Этап 8. Полировка и release audit

- accessibility;
- reduced motion;
- localization;
- performance budgets;
- full builds;
- install smoke Android/Windows/Linux/web там, где поддерживается CI;
- migration rehearsal against copy of production schema;
- docs and operator runbook.

## Acceptance criteria

Функция принята только если выполнено всё:

1. Поиск не находит доступного пользователю режима «Исследования» и его endpoints.
2. Старые базы безопасно очищаются следующей миграцией, свежие поднимаются с нуля.
3. Пользователь может не создавать компаньона и не видит деградаций.
4. Компаньон создаётся offline с fallback pack.
5. Все десять шкал, MBTI и description сохраняются и синхронизируются.
6. Внешность идентична на KMP и web для одного profile.
7. Обычная часовая сессия не делает ни одного AI/network request из-за реакций.
8. Непрошенные реплики соблюдают cooldown, caps и anti-repeat.
9. Android long press и desktop double click продолжают корректно разбирать фразу.
10. Настройки чтения доступны со страницы книги.
11. Opinion, question и recap возвращают только server-validated JSON.
12. Все ошибки видимы и понятны. Нет silent failure.
13. Общая квота 10 в день не обходится конкурентными запросами.
14. Ключ ИИ отсутствует в APK, desktop bundle, web bundle, логах и sync data.
15. Компаньон не знает текст после текущей позиции и не выдумывает цитаты.
16. Concept sheets с checkerboard не входят ни в одну production сборку.
17. Runtime assets проходят automated geometry/security validation.
18. UI не содержит emoji-иконок, бесполезного большого Wolfy и длинного тире.
19. reduce motion, keyboard, screen reader и 200% text scale проверены.
20. Go tests, KMP compilation/tests, web tests/build и relevant e2e проходят.
21. Performance budgets измерены, результаты записаны в release audit.
22. Git diff не содержит секретов, generated build output и случайных пользовательских файлов.

## Не входит в MVP

- несколько активных компаньонов;
- marketplace и пользовательская торговля одеждой;
- платная валюта, rarity и loot boxes;
- голосовой разговор;
- постоянный облачный чат и долговременная память обо всех книгах;
- social feed компаньонов;
- генерация внешности по фотографии;
- синхронная мультиплеерная читалка;
- автоматическое чтение всей библиотеки моделью;
- возврат режима исследований;
- background AI requests без явного действия пользователя.

Архитектура может учитывать будущие расширения, но не добавляй их UI, таблицы и сложность сейчас.

## Финальный отчёт coding agent

По завершении дай владельцу короткий, проверяемый отчёт:

- что реализовано по платформам;
- что удалено из исследований;
- какие миграции добавлены;
- какие новые endpoints и schemas появились;
- какие assets реально runtime-ready, а какие остались concept-only;
- как хранится и синхронизируется профиль;
- когда расходуется ИИ-квота;
- какие тесты и сборки запущены с точными результатами;
- измеренные performance numbers;
- известные ограничения;
- где лежат APK/desktop/web artifacts, если сборка запрошена;
- список изменённых файлов и отсутствие секретов.

Не называй работу готовой, если хотя бы один acceptance criterion не проверен. Если окружение не позволяет подписать или развернуть сборку, честно отдели реализованный код от непроверенного выпуска.


/*
 * Ядро Wolfy — интерфейс для клиента.
 *
 * Заголовок написан руками, а не сгенерирован: он маленький, меняется редко и
 * читается как документация границы. Реализация — `core/src/ffi/mod.rs`, и
 * любое расхождение между этими двумя файлами считается багом.
 *
 * Общие правила
 * -------------
 * 1. Каждая строка, возвращённая ядром, освобождается `wolfy_string_free`.
 *    Освобождать её аллокатором вызывающей стороны нельзя.
 * 2. Ошибка возвращается как NULL (или 0 для номера книги); её описание —
 *    в `wolfy_last_error` для того же потока.
 * 3. Паника наружу не выпускается: любая внутренняя ошибка ядра превращается
 *    в NULL с описанием.
 * 4. Все строки — UTF-8 с нулевым байтом на конце.
 *
 * Ответы приходят в JSON. Формат описан в `core/src/ffi/dto.rs` и совпадает
 * со схемами в `proto/`.
 */

#ifndef WOLFY_CORE_H
#define WOLFY_CORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Версия ядра, например "0.1.0". Освободить wolfy_string_free. */
char *wolfy_version(void);

/*
 * Описание последней ошибки в текущем потоке или NULL.
 * Строка принадлежит ядру и живёт до следующего вызова из этого потока —
 * копируйте её, а не храните указатель. Освобождать её не нужно.
 */
const char *wolfy_last_error(void);

/* Освобождает строку, выданную ядром. NULL допустим. */
void wolfy_string_free(char *text);

/*
 * Разбор одного слова: начальная форма, части речи, объяснение формы,
 * частотность и уровень. Работает без сети и без открытой книги.
 *
 * Ответ:
 *   {"surface":"children","lemma":"child","pos":["NOUN"],"form":"irregular",
 *    "facts":[{"label":"Форма","value":"неправильная, от «child»"}],
 *    "zipf":5.3,"cefr":"A2","known":true}
 */
char *wolfy_analyze_word(const char *word);

/*
 * Разбивка текста на токены и предложения.
 *
 * Смещения — в единицах UTF-16, то есть ровно в тех индексах, которыми
 * оперируют строки Kotlin и Java.
 *
 * Ответ:
 *   {"tokens":[{"kind":"word","start":0,"end":3,"text":"The"}, ...],
 *    "sentences":[{"start":0,"end":16,"firstToken":0,"lastToken":5,
 *                  "text":"The door opened."}, ...]}
 */
char *wolfy_tokenize(const char *text);

/*
 * Открывает книгу. Формат определяется по расширению: .epub, .txt, .pdf.
 * Возвращает номер книги (>0) либо 0 при ошибке.
 *
 * Пока книга открыта, ядро держит её файл — закрывайте wolfy_book_close.
 */
int64_t wolfy_book_open(const char *path);

/*
 * Метаданные и оглавление открытой книги.
 *
 * Ответ:
 *   {"title":"The Old Library","author":"Evelyn Hart","language":"en",
 *    "cover":"OEBPS/images/cover.jpg",
 *    "chapters":[{"title":"The Old Library"}, ...]}
 */
char *wolfy_book_metadata(int64_t handle);

/*
 * Читает главу по её номеру в оглавлении, начиная с нуля.
 *
 * Это единственная тяжёлая операция ядра — вызывайте её из фонового потока.
 *
 * Ответ:
 *   {"title":"The Old Library",
 *    "blocks":[{"kind":"heading","level":2,"text":"The Old Library"},
 *              {"kind":"paragraph","text":"The library smelled of dust."},
 *              {"kind":"image","path":"OEBPS/images/lamp.jpg","alt":"A lamp"},
 *              {"kind":"divider"}]}
 *
 * Возможные kind: heading, paragraph, quote, listItem, image, divider.
 */
char *wolfy_book_chapter(int64_t handle, size_t index);

/* Закрывает книгу и отпускает её файл. Неизвестный номер безопасен. */
void wolfy_book_close(int64_t handle);

/* --- Сессия: библиотека и настройки читателя --- */

/*
 * Открывает сессию на сохранённом состоянии.
 *
 * library и settings — записи, прочитанные клиентом с диска, или NULL, если
 * их ещё нет. Битую запись ядро молча заменяет пустой: падение на старте не
 * оставило бы читателю ничего.
 *
 * Состояние держит ядро, а не клиент. Библиотека это десятки килобайт, а
 * прогресс чтения пишется при каждой прокрутке: гонять весь список книг через
 * границу по десять раз в секунду нельзя.
 *
 * Возвращает номер сессии или 0 при ошибке.
 */
int64_t wolfy_session_open(const char *library, const char *settings);

/*
 * Выполняет команду над библиотекой или настройками.
 *
 * Одна функция вместо двадцати намеренно: каждая функция границы описывается
 * трижды — в ядре, здесь и в привязках клиента, — и двадцать операций это
 * шестьдесят мест, которые обязаны сойтись. Незнакомая команда возвращает
 * NULL, а её имя попадает в wolfy_last_error.
 *
 * Запрос:
 *   {"op":"define","word":"library","path":".../wolfy_dictionary.tsv"}
 *   {"op":"saveWord","bookId":"...","surface":"libraries","lemma":"library",
 *    "translation":"библиотека","id":"<uuid>","now":1700000000000}
 *   {"op":"rememberProgress","id":"...","chapter":3,"withinChapter":0.4,
 *    "now":1700000000000}
 *   {"op":"review","cardId":"...","right":true,"now":...,"offsetMinutes":180}
 *   {"op":"applyServer","cursor":9,"books":[...],"cards":[...],"now":...}
 *
 * Ответ:
 *   {"changed":true,"card":{...}}
 *
 * Поле changed говорит, изменилось ли состояние: по нему клиент решает,
 * писать ли на диск. Остальные поля зависят от команды.
 *
 * Часовой пояс и «сейчас» приходят числами: своих часов у ядра нет, и базы
 * часовых поясов оно не носит — offsetMinutes задан в минутах, потому что
 * Индия живёт на +5:30, а Непал на +5:45.
 */
char *wolfy_session_run(int64_t handle, const char *command);

/* Библиотека целиком — то, что клиент пишет на диск. */
char *wolfy_session_library(int64_t handle);

/* Настройки целиком. */
char *wolfy_session_settings(int64_t handle);

/*
 * Что изменилось с последней записи на диск.
 *
 * Ответ: {"library":true,"settings":false}
 */
char *wolfy_session_dirty(int64_t handle);

/*
 * Отмечает, что состояние записано на диск.
 *
 * Отдельным вызовом, а не внутри чтения: между «отдай мне библиотеку» и «файл
 * лёг на диск» запись может не удаться, и снимать пометку до того, как это
 * подтвердилось, значит однажды потерять главу.
 */
void wolfy_session_saved(int64_t handle, bool library, bool settings);

/* Закрывает сессию. Несохранённое теряется. */
void wolfy_session_close(int64_t handle);

#endif /* WOLFY_CORE_H */

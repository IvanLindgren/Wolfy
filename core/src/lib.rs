//! Ядро Wolfy: разбор книг, токенизация и морфология английского.
//!
//! Всё, что здесь есть, считается на устройстве и не ждёт сети. Граница
//! проведена по одной линии: если для ответа нужен интернет — это не ядро, а
//! сервер. Поэтому здесь живут парсеры форматов, токенизатор, словарь и
//! грамматические правила, а перевода и синхронизации нет вовсе.
//!
//! Наружу ядро выходит только через [`ffi`]: клиент на Kotlin грузит его как
//! `.so` на Android и `.dll` на Windows.
//!
//! Правила слоя — в `rules/rust_core.md`.

// Паника в продакшн-коде запрещена правилами слоя: битая книга обязана
// вернуться ошибкой, а не уронить читалку. В тестах `expect` наоборот уместен —
// упавший тест должен сказать, что именно он ждал.
#![cfg_attr(not(test), deny(clippy::unwrap_used, clippy::expect_used))]

mod clock;
pub use clock::{at_local_hour, local_day, local_hour};

mod error;
pub mod ffi;
pub mod grammar;
pub mod library;
pub mod lexicon;
pub mod parser;
pub mod settings;
pub mod srs;
pub mod tagger;
pub mod tokenizer;

pub use error::{CoreError, Result};

/// Версия ядра — клиент показывает её в диагностике и сверяет с ожидаемой.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

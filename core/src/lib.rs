//! Ядро Wolfy: разбор книг, токенизация и морфология английского.
//!
//! Всё, что здесь есть, считается на устройстве и не ждёт сети. Граница
//! проведена по одной линии: если для ответа нужен интернет — это не ядро, а
//! сервер. Поэтому здесь живут парсеры форматов, токенизатор, словарь и
//! грамматические правила, а перевода и синхронизации нет вовсе.
//!
//! Наружу ядро выходит двумя дверями в одну и ту же логику: [`ffi`] — C-ABI,
//! которым клиент на Kotlin грузит `.so` на Android и `.dll` на Windows, и
//! [`wasm`] — `wasm-bindgen` для браузера. Обе ведут в один
//! [`ffi::session::Command`]: дублируется способ передать строку, а не
//! правила.
//!
//! Правила слоя — в `rules/rust_core.md`.

// Паника в продакшн-коде запрещена правилами слоя: битая книга обязана
// вернуться ошибкой, а не уронить читалку. В тестах `expect` наоборот уместен —
// упавший тест должен сказать, что именно он ждал.
#![cfg_attr(not(test), deny(clippy::unwrap_used, clippy::expect_used))]

mod clock;
pub use clock::{at_local_hour, local_day, local_hour};

pub mod dictionary;

mod error;
pub mod ffi;
pub mod grammar;
pub mod lexicon;
pub mod library;
pub mod parser;
pub mod settings;
pub mod srs;
pub mod tagger;
pub mod tokenizer;

/// Вторая дверь в то же ядро — для браузера.
#[cfg(all(feature = "wasm", target_arch = "wasm32"))]
pub mod wasm;

pub use error::{CoreError, Result};

/// Версия ядра — клиент показывает её в диагностике и сверяет с ожидаемой.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

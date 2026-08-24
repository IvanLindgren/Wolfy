//! Тренировочная статистика как отдельный CRDT.
//!
//! Вынесена из `AppSettings` по §6: обычные настройки (`theme`, `fontScale`, …)
//! — это LWW, а тренировка — это счётчики и множество дней, которые нельзя
//! терять при гонке `ReplaceSettings`.
//!
//! ## Семантика
//! - `days` — множество местных дней, когда была хоть одна тренировка (`local_day`).
//!   Merge — объединение множества (коммутативно, ассоциативно, идемпотентно).
//! - `counters` — G-Counter по устройствам: `device_id` приходит снаружи, Rust
//!   его не генерирует. На каждом устройстве свой компонент монотонно растёт,
//!   merge берёт `max` по каждому `device_id` (как `max`, а не `+`, потому что
//!   повторная доставка того же состояния не должна удваивать ответы).
//!   Totals — суммы по всем компонентам.
//! - `best_floor` — сохранённый лучший стрик из старой схемы (т.к. из
//!   `trained_on/streak_days/best_streak` нельзя восстановить всю историю).
//!   Итоговый `best_streak = max(best_floor, longest_run(days))`.
//! - `current_streak` считается по множеству дней: если сегодня есть — старт
//!   сегодня, иначе если вчера есть — старт вчера (серия ещё жива), иначе 0,
//!   затем идти назад пока дни подряд.
//! - Ядро остаётся детерминированным: `now_ms`, `offset_minutes`, `device_id`
//!   приходят параметрами, часов/таймзоны/RNG внутри нет.
//! - `G-counter` корректен только если один `device_id` не имеет двух
//!   независимых писателей. Для web нельзя двум вкладкам одновременно мутировать
//!   один и тот же `device_id` без координации (Web Locks / single-writer).
//!   В Rust платформенного хака для этого нет — это обязанность клиента.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

use crate::clock::local_day;
use crate::srs::scheduler;

/// Ключ для миграции старых счётчиков — один стабильный компонент.
pub const LEGACY_DEVICE_ID: &str = "legacy";

/// Счётчики одного устройства/реплики.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct PracticeCounter {
    #[serde(default)]
    pub answers: u64,
    #[serde(default)]
    pub right: u64,
}

impl PracticeCounter {
    pub fn is_valid(&self) -> bool {
        self.right <= self.answers
    }

    /// Поправить инвариант `right <= answers` (на случай битого входа).
    pub fn normalize(&mut self) {
        if self.right > self.answers {
            self.right = self.answers;
        }
    }
}

/// Состояние тренировок — отдельный CRDT.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct PracticeState {
    /// Множество местных дней (`local_day`), когда была тренировка.
    #[serde(default)]
    pub days: BTreeSet<i64>,
    /// По-устройству G-Counter.
    #[serde(default)]
    pub counters: BTreeMap<String, PracticeCounter>,
    /// Нижняя граница лучшего стрика (из миграции `best_streak`).
    #[serde(default)]
    pub best_floor: u32,
}

impl PracticeState {
    /// Пустое состояние.
    pub fn new() -> Self {
        Self::default()
    }

    /// Засчитывает ответ тренировки.
    ///
    /// `device_id` — постоянный ID устройства/реплики, приходит снаружи.
    /// Пустой `device_id` считается `"legacy"` для совместимости со старыми вызывающими.
    pub fn record_answer(
        &mut self,
        device_id: &str,
        right: bool,
        now_ms: i64,
        offset_minutes: i32,
    ) {
        let day = local_day(now_ms, offset_minutes);
        self.days.insert(day);
        let key = if device_id.is_empty() {
            LEGACY_DEVICE_ID
        } else {
            device_id
        };
        let entry = self.counters.entry(key.to_string()).or_default();
        entry.answers = entry.answers.saturating_add(1);
        if right {
            entry.right = entry.right.saturating_add(1);
        }
        // После инкремента инвариант сохраняется: right <= answers
        debug_assert!(entry.right <= entry.answers);
    }

    /// Слияние двух состояний (коммутативно, ассоциативно, идемпотентно).
    pub fn merge(&self, other: &Self) -> Self {
        let mut out = self.clone();
        out.merge_inplace(other);
        out
    }

    /// Слияние на месте.
    pub fn merge_inplace(&mut self, other: &Self) {
        // days = union
        for &d in &other.days {
            self.days.insert(d);
        }
        // counters = max per device
        for (k, v) in &other.counters {
            let entry = self.counters.entry(k.clone()).or_default();
            entry.answers = entry.answers.max(v.answers);
            entry.right = entry.right.max(v.right);
            // нормализация на случай битого входа где right > answers в одной из реплик
            if entry.right > entry.answers {
                entry.right = entry.answers;
            }
        }
        self.best_floor = self.best_floor.max(other.best_floor);
    }

    /// Суммарные `answers` / `right` по всем устройствам.
    pub fn totals(&self) -> (u64, u64) {
        let mut answers = 0u64;
        let mut right = 0u64;
        for c in self.counters.values() {
            answers = answers.saturating_add(c.answers);
            right = right.saturating_add(c.right);
        }
        // Инвариант: right <= answers (если каждый компонент валиден)
        debug_assert!(right <= answers || self.counters.values().any(|c| !c.is_valid()));
        // Если суммарно right > answers из-за битого входа — clamp к answers
        if right > answers {
            right = answers;
        }
        (answers, right)
    }

    pub fn total_answers(&self) -> u64 {
        self.totals().0
    }

    pub fn total_right(&self) -> u64 {
        self.totals().1
    }

    /// Текущая серия по календарю читателя.
    ///
    /// Правило: если сегодня есть — старт сегодня, иначе если вчера есть —
    /// старт вчера (серия ещё жива), иначе 0; затем идти назад пока дни подряд.
    pub fn current_streak(&self, now_ms: i64, offset_minutes: i32) -> u32 {
        if self.days.is_empty() {
            return 0;
        }
        let today = local_day(now_ms, offset_minutes);
        let start = if self.days.contains(&today) {
            today
        } else if self.days.contains(&(today - 1)) {
            today - 1
        } else {
            return 0;
        };
        let mut streak = 1u32;
        let mut day = start - 1;
        while self.days.contains(&day) {
            streak = streak.saturating_add(1);
            // защита от бесконечного цикла при i64::MIN (недостижимо на практике)
            if day == i64::MIN {
                break;
            }
            day -= 1;
        }
        streak
    }

    /// Самая длинная последовательная серия в `days`.
    pub fn longest_run(&self) -> u32 {
        if self.days.is_empty() {
            return 0;
        }
        let mut best = 0u32;
        let mut cur = 0u32;
        let mut prev: Option<i64> = None;
        for &d in &self.days {
            if let Some(p) = prev {
                if d == p + 1 {
                    cur = cur.saturating_add(1);
                } else {
                    cur = 1;
                }
            } else {
                cur = 1;
            }
            best = best.max(cur);
            prev = Some(d);
        }
        best
    }

    /// Лучшая серия: `max(best_floor, longest_run(days))`.
    pub fn best_streak(&self) -> u32 {
        self.best_floor.max(self.longest_run())
    }

    /// Поправка расписания из суммарной точности (как `scheduler::ease`).
    pub fn ease(&self) -> f32 {
        let (answers, right) = self.totals();
        // scheduler::ease ждёт i32, но totals могут превысить i32::MAX.
        // Clamp к i32::MAX сохраняет смысл поправки (>30 уже влияет).
        let a = answers.min(i32::MAX as u64) as i32;
        let r = right.min(i32::MAX as u64) as i32;
        scheduler::ease(a, r)
    }

    /// Пустое ли состояние (нет ни дней, ни счётчиков, ни best_floor).
    pub fn is_empty(&self) -> bool {
        self.days.is_empty() && self.counters.is_empty() && self.best_floor == 0
    }

    /// Миграция из старых полей `AppSettings`.
    ///
    /// - `trained_on = D`, `streak_days = N` => `D-N+1 .. D` как минимально
    ///   известный диапазон дней (set union — идемпотентно).
    /// - `best_streak` => `best_floor` (max — идемпотентно).
    /// - `answers/right` => компонент `"legacy"` (max — идемпотентно).
    /// Повторный вызов не удваивает ответы и не расширяет `days` за пределами
    /// уже известного.
    pub fn migrate_from_legacy(&mut self, settings: &crate::settings::AppSettings) {
        // days
        if settings.trained_on != 0 && settings.streak_days > 0 {
            let d = settings.trained_on;
            let n = settings.streak_days as i64;
            // D-N+1 .. D inclusive. Защита от переполнения.
            let start = d.saturating_sub(n - 1);
            // Если диапазон слишком большой из-за битого N — ограничим разумным.
            // На практике N — дни серии, десятки, не миллионы.
            // Если N > 10000, всё равно вставим, но BTreeSet справится; это защита от OOM.
            // Более 36500 (~100 лет) — явно битое, обрежем.
            let capped_start = if n > 36_500 {
                d.saturating_sub(36_500 - 1)
            } else {
                start
            };
            for day in capped_start..=d {
                self.days.insert(day);
            }
        } else if settings.trained_on != 0 && settings.streak_days == 0 {
            // Старый код мог иметь trained_on без streak? Тогда это один день.
            // Не добавлять лишнего если streak 0 — это означает "нет серии", но
            // сам факт тренировки в D известен, поэтому можно добавить D?
            // По спеку N==0 => диапазон пуст, но для сохранности факта тренировки
            // лучше не гадать. Оставляем как есть: только если N>0.
            // Если нужно было бы добавить одиночный день, раскомментируй:
            // self.days.insert(settings.trained_on);
        }

        // best_floor
        if settings.best_streak > 0 {
            let bf = settings.best_streak as u32;
            self.best_floor = self.best_floor.max(bf);
        }

        // counters -> legacy
        if settings.answers > 0 {
            let answers = settings.answers as u64;
            let right = (settings.right as u64).min(answers); // clamp
            let entry = self
                .counters
                .entry(LEGACY_DEVICE_ID.to_string())
                .or_default();
            entry.answers = entry.answers.max(answers);
            entry.right = entry.right.max(right);
            if entry.right > entry.answers {
                entry.right = entry.answers;
            }
        }
    }

    /// Нужна ли миграция (есть ли legacy данные, которых ещё нет в practice).
    pub fn needs_migration(&self, settings: &crate::settings::AppSettings) -> bool {
        // Если в settings есть legacy не-ноль, а в practice этого ещё нет — нужно.
        let has_legacy_days = settings.trained_on != 0 && settings.streak_days > 0;
        let has_legacy_best = settings.best_streak > 0;
        let has_legacy_counters = settings.answers > 0;

        if !has_legacy_days && !has_legacy_best && !has_legacy_counters {
            return false;
        }
        // Проверяем, уже ли мигрировано (идемпотентность):
        // - days: содержит ли уже диапазон?
        // - best_floor: >= legacy best?
        // - counters: legacy компонент >= legacy answers?
        if has_legacy_days {
            let d = settings.trained_on;
            let n = settings.streak_days as i64;
            let start = d.saturating_sub(n - 1);
            // Если хотя бы один день из диапазона отсутствует — нужна миграция.
            for day in start..=d {
                if !self.days.contains(&day) {
                    return true;
                }
                // ограничение по 36500 как выше — для needs_migration тоже.
                if day - start > 36_500 {
                    break;
                }
            }
        }
        if has_legacy_best && (self.best_floor < settings.best_streak as u32) {
            return true;
        }
        if has_legacy_counters {
            let entry = self.counters.get(LEGACY_DEVICE_ID);
            let answers = settings.answers as u64;
            if entry.map(|e| e.answers).unwrap_or(0) < answers {
                return true;
            }
            let right = (settings.right as u64).min(answers);
            if entry.map(|e| e.right).unwrap_or(0) < right {
                return true;
            }
        }
        false
    }

    /// Нормализовать инварианты (right <= answers для каждого компонента).
    pub fn normalize(&mut self) {
        for c in self.counters.values_mut() {
            c.normalize();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::DAY_MS;

    const NOON: i64 = 1_700_000_000_000;

    fn day(n: i64) -> i64 {
        crate::clock::local_day(NOON + n * DAY_MS, 0)
    }

    #[test]
    fn merge_days_union() {
        let mut a = PracticeState::default();
        a.days.insert(10);
        a.days.insert(11);
        let mut b = PracticeState::default();
        b.days.insert(11);
        b.days.insert(12);
        let c = a.merge(&b);
        assert_eq!(c.days.len(), 3);
        assert!(c.days.contains(&10));
        assert!(c.days.contains(&11));
        assert!(c.days.contains(&12));
        // коммутативно
        assert_eq!(a.merge(&b), b.merge(&a));
        // идемпотентно
        assert_eq!(a.merge(&a), a);
        // ассоциативно
        let mut d = PracticeState::default();
        d.days.insert(13);
        assert_eq!(a.merge(&b).merge(&d), a.merge(&b.merge(&d)));
    }

    #[test]
    fn merge_counters_max() {
        let mut a = PracticeState::default();
        a.counters.insert(
            "phone".to_string(),
            PracticeCounter {
                answers: 100,
                right: 90,
            },
        );
        let mut b = PracticeState::default();
        b.counters.insert(
            "phone".to_string(),
            PracticeCounter {
                answers: 80,
                right: 70,
            },
        );
        b.counters.insert(
            "laptop".to_string(),
            PracticeCounter {
                answers: 30,
                right: 25,
            },
        );
        let c = a.merge(&b);
        assert_eq!(c.counters["phone"].answers, 100);
        assert_eq!(c.counters["phone"].right, 90);
        assert_eq!(c.counters["laptop"].answers, 30);
        assert_eq!(c.totals(), (130, 115));
        // коммутативно
        assert_eq!(a.merge(&b).totals(), b.merge(&a).totals());
        // идемпотентно
        assert_eq!(a.merge(&a), a);
    }

    #[test]
    fn totals_sum() {
        let mut s = PracticeState::default();
        s.counters.insert(
            "a".to_string(),
            PracticeCounter {
                answers: 5,
                right: 3,
            },
        );
        s.counters.insert(
            "b".to_string(),
            PracticeCounter {
                answers: 10,
                right: 8,
            },
        );
        assert_eq!(s.totals(), (15, 11));
    }

    #[test]
    fn current_streak_today_present() {
        let mut s = PracticeState::default();
        let today = day(0);
        s.days.insert(today);
        s.days.insert(today - 1);
        s.days.insert(today - 2);
        // gap before
        s.days.insert(today - 4);
        assert_eq!(s.current_streak(NOON, 0), 3);
        // longest is still 3
        assert_eq!(s.longest_run(), 3);
    }

    #[test]
    fn current_streak_yesterday_alive() {
        let mut s = PracticeState::default();
        let today = day(0);
        // no today, but yesterday + before
        s.days.insert(today - 1);
        s.days.insert(today - 2);
        s.days.insert(today - 3);
        assert_eq!(s.current_streak(NOON, 0), 3, "streak should stay alive if yesterday present");
        // if also today present, streak extends
        s.days.insert(today);
        assert_eq!(s.current_streak(NOON, 0), 4);
    }

    #[test]
    fn current_streak_zero_if_gap() {
        let mut s = PracticeState::default();
        let today = day(0);
        s.days.insert(today - 2);
        s.days.insert(today - 3);
        // today missing, yesterday missing => 0
        assert_eq!(s.current_streak(NOON, 0), 0);
    }

    #[test]
    fn best_streak_max_floor_and_run() {
        let mut s = PracticeState::default();
        s.best_floor = 10;
        // longest run 3, best should be 10
        let today = day(0);
        s.days.insert(today);
        s.days.insert(today - 1);
        s.days.insert(today - 2);
        assert_eq!(s.best_streak(), 10);
        // extend run to 12
        for i in 3..12 {
            s.days.insert(today - i);
        }
        assert_eq!(s.longest_run(), 12);
        assert_eq!(s.best_streak(), 12);
    }

    #[test]
    fn counters_right_le_answers() {
        let mut s = PracticeState::default();
        s.record_answer("phone", true, NOON, 0);
        s.record_answer("phone", false, NOON, 0);
        assert_eq!(s.counters["phone"].answers, 2);
        assert_eq!(s.counters["phone"].right, 1);
        assert!(s.counters["phone"].is_valid());
        // merge with invalid payload should clamp
        let mut bad = PracticeState::default();
        bad.counters.insert(
            "phone".to_string(),
            PracticeCounter {
                answers: 5,
                right: 10,
            },
        );
        let merged = s.merge(&bad);
        assert!(merged.counters["phone"].is_valid());
        assert_eq!(merged.counters["phone"].answers, 5);
        // right = max(1,10)=10 clamped to answers 5
        assert_eq!(merged.counters["phone"].right, 5);
        assert!(merged.totals().1 <= merged.totals().0);
    }

    #[test]
    fn record_answer_inserts_day_and_counter() {
        let mut s = PracticeState::default();
        assert!(s.days.is_empty());
        s.record_answer("device1", true, NOON, 0);
        let today = crate::clock::local_day(NOON, 0);
        assert!(s.days.contains(&today));
        assert_eq!(s.total_answers(), 1);
        assert_eq!(s.total_right(), 1);
        // same day second answer doesn't create new day (same offset)
        s.record_answer("device1", false, NOON, 0);
        assert_eq!(s.days.len(), 1);
        assert_eq!(s.total_answers(), 2);
        assert_eq!(s.total_right(), 1);
        // different device
        s.record_answer("device2", true, NOON, 0);
        assert_eq!(s.total_answers(), 3);
        assert_eq!(s.counters.len(), 2);
    }

    #[test]
    fn migration_restore_days() {
        let mut s = PracticeState::default();
        let settings = crate::settings::AppSettings {
            trained_on: 20000,
            streak_days: 7,
            best_streak: 21,
            answers: 300,
            right: 270,
            ..Default::default()
        };
        s.migrate_from_legacy(&settings);
        // D-N+1..D => 19994..20000 inclusive = 7 days
        assert_eq!(s.days.len(), 7);
        assert!(s.days.contains(&20000));
        assert!(s.days.contains(&19994));
        assert_eq!(s.best_floor, 21);
        assert_eq!(s.counters[LEGACY_DEVICE_ID].answers, 300);
        assert_eq!(s.counters[LEGACY_DEVICE_ID].right, 270);
        // idempotent
        let before = s.clone();
        s.migrate_from_legacy(&settings);
        assert_eq!(s, before, "migration must be idempotent");
    }

    #[test]
    fn migration_zero_streak_no_days() {
        let mut s = PracticeState::default();
        let settings = crate::settings::AppSettings {
            trained_on: 20000,
            streak_days: 0,
            ..Default::default()
        };
        s.migrate_from_legacy(&settings);
        assert!(s.days.is_empty(), "N==0 should not inject days");
    }

    #[test]
    fn migration_right_clamped() {
        let mut s = PracticeState::default();
        let settings = crate::settings::AppSettings {
            answers: 10,
            right: 20, // invalid: right > answers
            ..Default::default()
        };
        s.migrate_from_legacy(&settings);
        assert_eq!(s.counters[LEGACY_DEVICE_ID].answers, 10);
        assert_eq!(s.counters[LEGACY_DEVICE_ID].right, 10);
    }

    #[test]
    fn merge_associative_three() {
        let mut a = PracticeState::default();
        a.record_answer("phone", true, NOON, 0);
        a.days.insert(100);
        a.best_floor = 5;
        let mut b = PracticeState::default();
        b.record_answer("laptop", false, NOON + DAY_MS, 0);
        b.days.insert(101);
        b.best_floor = 7;
        let mut c = PracticeState::default();
        c.record_answer("tablet", true, NOON + 2 * DAY_MS, 0);
        c.days.insert(102);
        c.best_floor = 3;
        let left = a.merge(&b).merge(&c);
        let right = a.merge(&b.merge(&c));
        assert_eq!(left, right);
        assert_eq!(left.best_streak(), right.best_streak());
    }

    #[test]
    fn old_laptop_not_overwrite_new_answers() {
        // Bug scenario: answers 100 -> 80 due to LWW. With CRDT, 100 stays.
        let mut phone = PracticeState::default();
        for _ in 0..100 {
            phone.record_answer("phone", true, NOON, 0);
        }
        let mut laptop = PracticeState::default();
        for _ in 0..80 {
            laptop.record_answer("laptop", true, NOON, 0);
        }
        let merged = phone.merge(&laptop);
        assert_eq!(merged.total_answers(), 180);
        assert_eq!(merged.total_right(), 180);
        // laptop old state arriving last should not reduce totals
        let merged2 = laptop.merge(&phone);
        assert_eq!(merged2, merged);
    }

    #[test]
    fn today_training_disappeared_bug() {
        // Today done on phone, old laptop sync without today shouldn't delete today
        let today = crate::clock::local_day(NOON, 0);
        let mut phone = PracticeState::default();
        phone.days.insert(today);
        phone.days.insert(today - 1);
        let mut laptop = PracticeState::default();
        laptop.days.insert(today - 1);
        laptop.days.insert(today - 2);
        // merge should union => today preserved
        let merged = phone.merge(&laptop);
        assert!(merged.days.contains(&today), "today's training disappeared");
        assert_eq!(merged.current_streak(NOON, 0), 3); // today, yesterday, day before
    }

    #[test]
    fn serde_roundtrip() {
        let mut s = PracticeState::default();
        s.record_answer("phone", true, NOON, 0);
        s.best_floor = 42;
        let json = serde_json::to_string(&s).expect("serialize");
        assert!(json.contains("bestFloor") || json.contains("best_floor"));
        let de: PracticeState = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(s, de);
        // missing fields default
        let empty: PracticeState = serde_json::from_str("{}").expect("empty deserialize");
        assert!(empty.is_empty());
    }
}

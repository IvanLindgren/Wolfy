//! Осторожный разбор фразы на смысловые блоки и видимые маркеры правила.
//!
//! Это не полный синтаксический парсер. Если личная форма глагола не
//! найдена, роли не выдаются вовсе: пустой ответ честнее уверенной ошибки.

use std::ops::Range;

use crate::grammar::Finding;
use crate::lexicon::{analyze, FormKind, Lexicon, Pos};
use crate::srs::chunks::blocks;
use crate::tagger::{tag, Aux, Word};
use crate::tokenizer::Token;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    Subject,
    Predicate,
    Object,
    Complement,
    Adverbial,
    Connector,
}

impl Role {
    pub fn short(self) -> &'static str {
        match self {
            Role::Subject => "подл.",
            Role::Predicate => "сказ.",
            Role::Object => "доп.",
            Role::Complement => "часть",
            Role::Adverbial => "обст.",
            Role::Connector => "связь",
        }
    }

    pub fn title(self) -> &'static str {
        match self {
            Role::Subject => "подлежащее",
            Role::Predicate => "сказуемое",
            Role::Object => "дополнение",
            Role::Complement => "дополнение сказуемого",
            Role::Adverbial => "обстоятельство",
            Role::Connector => "связка",
        }
    }

    pub fn tint(self) -> Pos {
        match self {
            Role::Subject | Role::Object | Role::Complement => Pos::Noun,
            Role::Predicate => Pos::Verb,
            Role::Adverbial => Pos::Adverb,
            Role::Connector => Pos::Pronoun,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Chunk {
    pub role: Role,
    pub words: Range<usize>,
    pub tokens: Range<usize>,
    pub head: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MarkerKind {
    Auxiliary,
    Ending,
    Particle,
    Preposition,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Marker {
    pub token: usize,
    pub inside: Range<usize>,
    pub kind: MarkerKind,
    pub rule: &'static str,
    pub note: &'static str,
}

pub fn chunks(lexicon: &Lexicon, tokens: &[Token], findings: &[Finding]) -> Vec<Chunk> {
    let words = tag(lexicon, tokens);
    if words.is_empty() {
        return Vec::new();
    }
    let ranges = blocks(tokens, findings);
    let Some(predicate) = ranges.iter().position(|range| {
        words[range.clone()]
            .iter()
            .any(|word| word.pos == Pos::Verb && word.is_finite_verb())
    }) else {
        return Vec::new();
    };

    let subject = (0..predicate)
        .rev()
        .find(|&index| nominal(&words[ranges[index].clone()]));
    let mut object_used = false;

    ranges
        .into_iter()
        .enumerate()
        .map(|(index, range)| {
            let slice = &words[range.clone()];
            let first = slice.first().map(|word| word.lower.as_str()).unwrap_or("");
            let connector = matches!(
                first,
                "that" | "which" | "who" | "whom" | "whose" | "if" | "unless"
            );
            let adverbial = slice
                .first()
                .is_some_and(|word| word.pos == Pos::Preposition)
                || slice.iter().any(|word| word.pos == Pos::Adverb)
                || time_expression(slice);

            let role = if index == predicate {
                Role::Predicate
            } else if Some(index) == subject {
                Role::Subject
            } else if connector {
                Role::Connector
            } else if adverbial {
                Role::Adverbial
            } else if index > predicate && !object_used && nominal(slice) {
                object_used = true;
                Role::Object
            } else {
                Role::Complement
            };

            let head_word = if role == Role::Predicate {
                slice.iter().rposition(|word| word.pos == Pos::Verb)
            } else {
                slice.iter().rposition(|word| meaningful(word.pos))
            }
            .unwrap_or(slice.len().saturating_sub(1));
            let head = slice.get(head_word).map(|word| word.token).unwrap_or(0);
            let start = slice.first().map(|word| word.token).unwrap_or(0);
            let end = slice.last().map(|word| word.token + 1).unwrap_or(start);

            Chunk {
                role,
                words: range,
                tokens: start..end,
                head,
            }
        })
        .collect()
}

pub fn markers(lexicon: &Lexicon, tokens: &[Token], findings: &[Finding]) -> Vec<Marker> {
    let words = tag(lexicon, tokens);
    let parsed_chunks = chunks(lexicon, tokens, findings);
    let mut out = Vec::new();

    for word in &words {
        let finding = findings
            .iter()
            .find(|finding| finding.tokens.contains(&word.token));
        let rule = finding.map(|finding| finding.rule).unwrap_or("word-form");

        if word
            .aux
            .is_some_and(|aux| !matches!(aux, Aux::To | Aux::Not))
        {
            push_unique(
                &mut out,
                Marker {
                    token: word.token,
                    inside: 0..word.text.len(),
                    kind: MarkerKind::Auxiliary,
                    rule,
                    note: "вспомогательный глагол",
                },
            );
        }

        if matches!(word.lower.as_str(), "to" | "not" | "n't" | "if" | "unless") {
            push_unique(
                &mut out,
                Marker {
                    token: word.token,
                    inside: 0..word.text.len(),
                    kind: MarkerKind::Particle,
                    rule,
                    note: if matches!(word.lower.as_str(), "if" | "unless") {
                        "условие"
                    } else {
                        "частица"
                    },
                },
            );
        }

        if word.pos == Pos::Preposition
            && (matches!(word.lower.as_str(), "by" | "than")
                || parsed_chunks
                    .iter()
                    .any(|chunk| chunk.role == Role::Adverbial && chunk.head == word.token)
                || parsed_chunks
                    .iter()
                    .any(|chunk| chunk.role == Role::Adverbial && chunk.tokens.start == word.token))
        {
            push_unique(
                &mut out,
                Marker {
                    token: word.token,
                    inside: 0..word.text.len(),
                    kind: MarkerKind::Preposition,
                    rule,
                    note: "связь обстоятельства",
                },
            );
        }

        let analysis = analyze(lexicon, &word.text);
        if analysis.form == FormKind::Regular {
            if let Some(suffix) = regular_suffix(&word.lower) {
                push_unique(
                    &mut out,
                    Marker {
                        token: word.token,
                        inside: word.text.len().saturating_sub(suffix.len())..word.text.len(),
                        kind: MarkerKind::Ending,
                        rule,
                        note: ending_note(suffix),
                    },
                );
            }
        }
    }
    out
}

fn push_unique(out: &mut Vec<Marker>, marker: Marker) {
    if !out.iter().any(|old| {
        old.token == marker.token && old.inside == marker.inside && old.kind == marker.kind
    }) {
        out.push(marker);
    }
}

fn nominal(words: &[Word]) -> bool {
    words
        .iter()
        .any(|word| matches!(word.pos, Pos::Noun | Pos::Pronoun))
}

fn meaningful(pos: Pos) -> bool {
    matches!(
        pos,
        Pos::Noun | Pos::Pronoun | Pos::Verb | Pos::Adjective | Pos::Adverb
    )
}

fn time_expression(words: &[Word]) -> bool {
    words.iter().any(|word| {
        matches!(
            word.lower.as_str(),
            "today"
                | "tomorrow"
                | "yesterday"
                | "week"
                | "month"
                | "year"
                | "morning"
                | "evening"
                | "night"
        )
    })
}

fn regular_suffix(word: &str) -> Option<&'static str> {
    ["ing", "est", "ed", "er", "ly", "s"]
        .into_iter()
        .find(|suffix| word.ends_with(suffix) && word.len() > suffix.len())
}

fn ending_note(suffix: &str) -> &'static str {
    match suffix {
        "ing" => "признак длительности",
        "ed" => "форма прошедшего времени",
        "s" => "окончание формы",
        "er" | "est" => "степень сравнения",
        "ly" => "признак наречия",
        _ => "окончание",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grammar::analyze;
    use crate::tokenizer::tokenize;

    fn parsed(sentence: &str) -> (Vec<Chunk>, Vec<Marker>) {
        let tokens = tokenize(sentence);
        let findings = analyze(Lexicon::embedded(), &tokens);
        (
            chunks(Lexicon::embedded(), &tokens, &findings),
            markers(Lexicon::embedded(), &tokens, &findings),
        )
    }

    #[test]
    fn perfect_continuous_has_roles_and_three_markers() {
        let (chunks, markers) = parsed("I have been reading this book for a month.");
        assert!(chunks.iter().any(|chunk| chunk.role == Role::Subject));
        assert!(chunks.iter().any(|chunk| chunk.role == Role::Predicate));
        assert!(chunks.iter().any(|chunk| chunk.role == Role::Object));
        assert!(chunks.iter().any(|chunk| chunk.role == Role::Adverbial));
        assert!(
            markers
                .iter()
                .filter(|marker| marker.kind == MarkerKind::Auxiliary)
                .count()
                >= 2
        );
        assert!(markers
            .iter()
            .any(|marker| marker.kind == MarkerKind::Ending));
    }

    #[test]
    fn passive_marks_by_and_conditional_marks_if() {
        let (_, passive) = parsed("The book was written by Orwell.");
        assert!(passive
            .iter()
            .any(|marker| marker.kind == MarkerKind::Preposition));
        let (_, conditional) = parsed("If it rains, we will stay home.");
        assert!(conditional
            .iter()
            .any(|marker| marker.kind == MarkerKind::Particle));
    }

    #[test]
    fn no_finite_verb_has_no_roles_and_single_verb_is_predicate() {
        assert!(parsed("The old house.").0.is_empty());
        assert_eq!(
            parsed("Run.").0.first().map(|chunk| chunk.role),
            Some(Role::Predicate)
        );
    }
}

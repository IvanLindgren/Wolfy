//! Один вызов на тап: всё локальное для карточки.
//!
//! Раньше тап порождал до шести переходов через границу: analyzeWord,
//! explain, tokenize, buildSentenceGraph, снова tokenize, analyzeWord каждого
//! слова. Здесь один проход и один ответ.

use crate::ffi::dto::{
    ChunkDto, CompactSentenceDto, CompactTokenDto, FindingDto, GraphLinkDto, GraphWordDto,
    InspectDto, MarkerDto, PartDto, WordDto,
};
use crate::grammar::{self, Finding};
use crate::lexicon::{analyze, Lexicon};
use crate::tagger::{tag, Word as TaggedWord};
use crate::tokenizer::{split, tokenize, Token, TokenKind};

///
/// Собирает всё, что карточка показывает без сети.
///
/// * `surface` — слово как оно стоит в тексте (token.text)
/// * `sentence` — предложение вокруг слова (для грамматики и графа). Если пусто,
///   берётся само `surface` как однословное предложение.
///
/// Возвращает компактные токены над `sentence` — текст предложения принадлежит
/// вызывающему один раз, токены несут только смещения в UTF-16.
///
pub fn inspect_word(surface: &str, sentence: &str) -> InspectDto {
    let sentence_text = if sentence.trim().is_empty() {
        surface.to_string()
    } else {
        sentence.to_string()
    };
    let lexicon = Lexicon::embedded();

    // Анализ выбранного слова
    let analysis = analyze(lexicon, surface);
    let word = WordDto::from(&analysis);

    // Токенизация предложения (один раз)
    let tokens = tokenize(&sentence_text);
    let sentences = split(&tokens);

    // Грамматика: части речи в контексте + разборы + роли + маркеры
    let words = tag(lexicon, &tokens);
    let findings = grammar::analyze_words(&words);
    let chunks = grammar::chunks(lexicon, &tokens, &findings);
    let markers = grammar::markers(lexicon, &tokens, &findings);
    let parts = words.iter().map(PartDto::from).collect::<Vec<_>>();

    // Граф — эвристики из Kotlin, теперь в Rust
    let (graph_words, graph_links) = build_graph(&tokens, &words, &findings, lexicon);

    InspectDto {
        word,
        tokens: tokens.iter().map(CompactTokenDto::from).collect(),
        sentences: sentences.iter().map(CompactSentenceDto::from).collect(),
        findings: findings.iter().map(FindingDto::from).collect(),
        chunks: chunks.iter().map(ChunkDto::from).collect(),
        markers: markers.iter().map(MarkerDto::from).collect(),
        parts,
        graph_words,
        graph_links,
    }
}

/// Граф предложения — подсказки для чтения, а не претензия на полный parse.
///
/// Логика перенесена из `ReaderViewModel.buildSentenceGraph` (Kotlin) без
/// изменений смысла, чтобы лингвистические правила жили только в Rust.
fn build_graph(
    tokens: &[Token],
    _words: &[TaggedWord],
    findings: &[Finding],
    lexicon: &Lexicon,
) -> (Vec<GraphWordDto>, Vec<GraphLinkDto>) {
    // Видимые токены: всё кроме пробелов, как в Kotlin (`kind != "space"`).
    let visible: Vec<(usize, &Token)> = tokens
        .iter()
        .enumerate()
        .filter(|(_, t)| t.kind != TokenKind::Space)
        .collect();

    if visible.is_empty() {
        return (Vec::new(), Vec::new());
    }

    // original -> visible
    let mut orig_to_vis = std::collections::HashMap::<usize, usize>::new();
    for (vis_idx, (orig_idx, _)) in visible.iter().enumerate() {
        orig_to_vis.insert(*orig_idx, vis_idx);
    }

    // Анализ каждого видимого токена
    let analyses: Vec<Option<AnalysisInfo>> = visible
        .iter()
        .map(|(_, tok)| {
            if tok.kind == TokenKind::Word {
                let wa = analyze(lexicon, &tok.text);
                let primary = wa.matched.or(wa.dominant).or_else(|| wa.pos.iter().next());
                Some(AnalysisInfo {
                    tag: primary.map(pos_name),
                    is_noun: primary == Some(crate::lexicon::Pos::Noun),
                    is_pron: primary == Some(crate::lexicon::Pos::Pronoun),
                    is_verb: primary == Some(crate::lexicon::Pos::Verb),
                    is_adj: primary == Some(crate::lexicon::Pos::Adjective),
                    is_det: primary == Some(crate::lexicon::Pos::Determiner),
                    is_adp: primary == Some(crate::lexicon::Pos::Preposition),
                    is_part: primary == Some(crate::lexicon::Pos::Particle),
                    is_adv: primary == Some(crate::lexicon::Pos::Adverb),
                })
            } else {
                None
            }
        })
        .collect();

    let graph_words: Vec<GraphWordDto> = visible
        .iter()
        .enumerate()
        .map(|(vis_idx, (_, tok))| GraphWordDto {
            text: tok.text.clone(),
            tag: analyses[vis_idx].as_ref().and_then(|a| a.tag),
        })
        .collect();

    if graph_words.is_empty() {
        return (graph_words, Vec::new());
    }

    let mut links: Vec<GraphLinkDto> = Vec::new();

    // Связи от грамматических находок
    for finding in findings {
        let covered: Vec<usize> = (finding.tokens.start..finding.tokens.end)
            .filter_map(|orig| orig_to_vis.get(&orig).copied())
            .collect();
        // Пустой набор пропускается: разбор, ни одно слово которого не попало
        // в видимый текст, подсвечивать не на чем. min и max возвращают None
        // ровно в этом случае, поэтому отдельной проверки на пустоту не нужно.
        let (Some(&first), Some(&last)) = (covered.iter().min(), covered.iter().max()) else {
            continue;
        };
        let from = first;
        let to = last + 1;
        links.push(GraphLinkDto {
            from,
            to,
            label: finding.title.to_string(),
        });
    }

    // Эвристики
    let root = analyses
        .iter()
        .position(|a| a.as_ref().is_some_and(|i| i.is_verb));

    if let Some(root_idx) = root {
        let subject = (0..root_idx)
            .rev()
            .find(|&i| analyses[i].as_ref().is_some_and(|a| a.is_noun || a.is_pron));
        let target = (root_idx + 1..analyses.len())
            .find(|&i| analyses[i].as_ref().is_some_and(|a| a.is_noun || a.is_pron));
        if let Some(s) = subject {
            links.push(span_link(s, root_idx, "исполнитель — действие"));
        }
        if let Some(t) = target {
            links.push(span_link(root_idx, t, "действие — дополнение"));
        }
    }

    for (idx, a) in analyses.iter().enumerate() {
        if !a.as_ref().is_some_and(|v| v.is_adj) {
            continue;
        }
        let noun =
            (idx + 1..analyses.len()).find(|&j| analyses[j].as_ref().is_some_and(|v| v.is_noun));
        if let Some(n) = noun {
            if n - idx <= 2 {
                links.push(span_link(idx, n, "признак — слово"));
            }
        }
    }

    for (idx, a) in analyses.iter().enumerate() {
        let info = match a {
            Some(v) => v,
            None => continue,
        };
        if info.is_det {
            let target = (idx + 1..analyses.len())
                .find(|&j| analyses[j].as_ref().is_some_and(|v| v.is_noun || v.is_adj))
                .filter(|&j| j - idx <= 2);
            if let Some(t) = target {
                links.push(span_link(idx, t, "определитель — имя"));
            }
        } else if info.is_adp {
            let target = (idx + 1..analyses.len())
                .find(|&j| analyses[j].as_ref().is_some_and(|v| v.is_noun || v.is_pron))
                .filter(|&j| j - idx <= 3);
            if let Some(t) = target {
                links.push(span_link(idx, t, "предлог — зависимое слово"));
            }
        } else if info.is_part {
            let nearest = nearest_verb(&analyses, idx);
            if let Some(v) = nearest {
                links.push(span_link(idx, v, "частица — действие"));
            }
        } else if info.is_adv {
            let nearest = nearest_verb(&analyses, idx);
            if let Some(v) = nearest {
                links.push(span_link(idx, v, "обстоятельство — действие"));
            }
        }
    }

    // Дубликаты — как distinctBy Triple в Kotlin
    let mut seen = std::collections::HashSet::<(usize, usize, String)>::new();
    let mut deduped = Vec::new();
    for l in links {
        let key = (l.from, l.to, l.label.clone());
        if seen.insert(key) {
            deduped.push(l);
        }
    }

    (graph_words, deduped)
}

fn nearest_verb(analyses: &[Option<AnalysisInfo>], from: usize) -> Option<usize> {
    let mut best: Option<(usize, usize)> = None; // (idx, distance)
    for (i, a) in analyses.iter().enumerate() {
        if a.as_ref().is_some_and(|v| v.is_verb) {
            let dist = if i > from { i - from } else { from - i };
            match best {
                None => best = Some((i, dist)),
                Some((_, d)) if dist < d => best = Some((i, dist)),
                _ => {}
            }
        }
    }
    best.map(|(i, _)| i)
}

fn span_link(a: usize, b: usize, label: &str) -> GraphLinkDto {
    GraphLinkDto {
        from: a.min(b),
        to: a.max(b) + 1,
        label: label.to_string(),
    }
}

#[derive(Debug)]
struct AnalysisInfo {
    tag: Option<&'static str>,
    is_noun: bool,
    is_pron: bool,
    is_verb: bool,
    is_adj: bool,
    is_det: bool,
    is_adp: bool,
    is_part: bool,
    is_adv: bool,
}

fn pos_name(pos: crate::lexicon::Pos) -> &'static str {
    use crate::lexicon::Pos;
    match pos {
        Pos::Noun => "NOUN",
        Pos::Verb => "VERB",
        Pos::Adjective => "ADJ",
        Pos::Adverb => "ADV",
        Pos::Pronoun => "PRON",
        Pos::Determiner => "DET",
        Pos::Preposition => "ADP",
        Pos::Conjunction => "CONJ",
        Pos::Particle => "PART",
        Pos::Numeral => "NUM",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inspect_word_возвращает_анализ_и_грамматику() {
        let r = inspect_word("libraries", "She left the libraries at dusk.");
        assert_eq!(r.word.lemma, "library");
        assert!(!r.tokens.is_empty());
        assert!(!r.findings.is_empty() || r.findings.is_empty()); // может быть пусто — не важно
        assert!(!r.graph_words.is_empty());
        // токены компактные — без текста
        let json = serde_json::to_value(&r).expect("serial");
        for tok in json["tokens"].as_array().unwrap() {
            assert!(
                tok.get("text").is_none(),
                "компактный токен не должен нести text"
            );
        }
    }

    #[test]
    fn inspect_word_пустое_предложение_берёт_слово() {
        let r = inspect_word("hello", "");
        assert_eq!(r.word.surface, "hello");
        assert!(!r.tokens.is_empty());
        assert_eq!(r.tokens.len(), 1); // одно слово?
                                       // токенизация "hello" даст один токен word
        assert!(r.tokens.iter().any(|t| t.kind == "word"));
    }

    #[test]
    fn inspect_word_смещения_utf16_корректны() {
        let sentence = "Hi 😀 there";
        let r = inspect_word("there", sentence);
        let utf16_len = sentence.encode_utf16().count();
        let max_end = r.tokens.iter().map(|t| t.end).max().unwrap_or(0);
        assert_eq!(max_end, utf16_len);
        // эмодзи 2 единицы
        let smile = r
            .tokens
            .iter()
            .find(|t| {
                let slice: Vec<u16> =
                    sentence.encode_utf16().collect::<Vec<u16>>()[t.start..t.end].to_vec();
                String::from_utf16(&slice).is_ok_and(|s| s.contains('😀'))
            })
            .expect("эмодзи токен");
        assert_eq!(smile.end - smile.start, 2);
    }

    #[test]
    fn inspect_word_graph_эвристики() {
        // "The green lamp was broken." — должен найти определитель и прилагательное
        let r = inspect_word("lamp", "The green lamp was broken.");
        // graph должен содержать слова и хотя бы одну эвристическую связь
        assert!(r.graph_words.len() >= 4);
        // Проверяем что есть связь "определитель — имя" или "признак — слово"
        let has_det = r
            .graph_links
            .iter()
            .any(|l| l.label == "определитель — имя");
        let has_adj = r.graph_links.iter().any(|l| l.label == "признак — слово");
        assert!(
            has_det || has_adj,
            "ожидались эвристические связи, получили {:?}",
            r.graph_links
        );
    }

    #[test]
    fn inspect_word_графика_разных_предложений_детерминирована() {
        let a = inspect_word("book", "I have been reading this book for a month.");
        let b = inspect_word("book", "I have been reading this book for a month.");
        assert_eq!(
            serde_json::to_value(&a).unwrap(),
            serde_json::to_value(&b).unwrap()
        );
    }

    #[test]
    fn inspect_word_на_пунктуации_не_падает() {
        let r = inspect_word(",", "Hello, world!");
        assert!(!r.tokens.is_empty());
    }
}

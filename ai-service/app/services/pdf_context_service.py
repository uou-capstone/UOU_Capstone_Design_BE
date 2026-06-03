from __future__ import annotations

from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
import re
from typing import Any

from pypdf import PdfReader


@dataclass(frozen=True)
class PageContext:
    page: int
    page_text: str
    prev_text: str = ""
    next_text: str = ""
    page_count: int = 0

    @property
    def has_page_text(self) -> bool:
        return bool(self.page_text.strip())


@dataclass(frozen=True)
class RelevantPage:
    page: int
    text: str
    score: float
    matched_terms: tuple[str, ...] = ()


class PdfContextService:
    """
    Lightweight page text index for v3 learning agents.

    The reference MergeEduAgentFull flow sends current/prev/next page text to
    the explainer instead of asking Gemini to infer the page from the full PDF.
    This service keeps that behavior local and falls back gracefully when text
    extraction fails.
    """

    def __init__(self) -> None:
        self._cache: dict[str, dict[str, Any]] = {}

    def read_page_context(self, pdf_path: str | None, page: int) -> PageContext | None:
        if not pdf_path:
            return None

        path = Path(pdf_path)
        if not path.exists():
            return None

        try:
            pages = self._load_pages(path)
        except Exception:
            return None

        if not pages:
            return None

        target = max(1, int(page or 1))
        target = min(target, len(pages))
        index = target - 1

        return PageContext(
            page=target,
            page_text=pages[index],
            prev_text=pages[index - 1] if index > 0 else "",
            next_text=pages[index + 1] if index + 1 < len(pages) else "",
            page_count=len(pages),
        )

    def read_all_pages(self, pdf_path: str | None) -> list[str]:
        if not pdf_path:
            return []

        path = Path(pdf_path)
        if not path.exists():
            return []

        try:
            return self._load_pages(path)
        except Exception:
            return []

    def search_relevant_pages(
        self,
        pdf_path: str | None,
        query: str,
        *,
        current_page: int | None = None,
        limit: int = 3,
        include_current: bool = False,
    ) -> list[RelevantPage]:
        pages = self.read_all_pages(pdf_path)
        if not pages or not query.strip():
            return []

        terms = _expand_query_terms(query)
        if not terms:
            return []

        current = int(current_page or 0)
        candidates: list[RelevantPage] = []
        for index, text in enumerate(pages):
            page_number = index + 1
            if not include_current and current == page_number:
                continue
            score, matched = _score_page(text, terms)
            if score <= 0:
                continue
            candidates.append(RelevantPage(
                page=page_number,
                text=text,
                score=score,
                matched_terms=tuple(matched[:8]),
            ))

        candidates.sort(key=lambda page: (-page.score, abs(page.page - current) if current else 0, page.page))
        return candidates[:max(0, limit)]

    def _load_pages(self, path: Path) -> list[str]:
        stat = path.stat()
        key = str(path.resolve())
        cached = self._cache.get(key)
        if (
            cached
            and cached.get("mtime_ns") == stat.st_mtime_ns
            and cached.get("size") == stat.st_size
        ):
            return list(cached["pages"])

        reader = PdfReader(str(path))
        pages = [(page.extract_text() or "").strip() for page in reader.pages]
        self._cache[key] = {
            "mtime_ns": stat.st_mtime_ns,
            "size": stat.st_size,
            "pages": pages,
        }
        return pages


_WORD_RE = re.compile(r"[a-z0-9가-힣]+")

_DOMAIN_SYNONYMS: dict[str, tuple[str, ...]] = {
    "흐름제어": ("흐름 제어", "flow control", "receive window", "receiver window", "rcvwindow", "rwnd", "수신 윈도우", "수신 버퍼", "buffer overflow"),
    "흐름": ("흐름 제어", "flow control"),
    "flow": ("flow control", "receive window", "rwnd"),
    "control": ("flow control", "congestion control"),
    "tcp": ("transmission control protocol", "reliable data transfer", "ack", "sequence number", "retransmission", "checksum", "timeout", "rtt"),
    "신뢰성": ("신뢰할 수 있는 데이터 전송", "reliable data transfer", "rdt", "ack", "sequence number", "retransmission", "checksum", "timeout", "fast retransmit", "duplicate ack"),
    "신뢰": ("신뢰할 수 있는 데이터 전송", "reliable data transfer", "ack", "retransmission", "checksum"),
    "reliable": ("reliable data transfer", "rdt", "ack", "sequence number", "retransmission"),
    "ack": ("ack", "acknowledgement", "acknowledgment", "duplicate ack", "cumulative ack"),
    "재전송": ("retransmission", "timeout", "fast retransmit", "duplicate ack"),
    "혼잡제어": ("혼잡 제어", "congestion control", "congestion window", "cwnd"),
    "혼잡": ("congestion control", "congestion window", "cwnd"),
    "다중화": ("multiplexing", "demultiplexing", "port number", "socket"),
    "역다중화": ("demultiplexing", "multiplexing", "port number", "socket"),
    "udp": ("user datagram protocol", "connectionless", "best effort"),
}

_PHONETIC_TERM_PREFIX = "__phonetic__:"
_KOREAN_QUERY_STOPWORDS = {
    "그거", "내용", "대해", "대한", "설명", "설명해", "설명해줘", "알려", "알려줘",
    "어디", "어느", "몇", "페이지", "페이지에", "있어", "있나", "있나요", "있는데",
    "나와", "나오", "나오는", "보여", "보여줘", "찾아", "찾아줘", "현재", "이전",
    "다음", "내가", "제대로", "이해", "했는지", "같아", "봐줘", "관련",
}
_KOREAN_PARTICLES = (
    "으로부터", "로부터", "에서는", "에게는", "한테는", "이라는", "라는", "에서는",
    "에서", "에게", "한테", "으로", "부터", "까지", "처럼", "보다", "이나", "거나",
    "은", "는", "이", "가", "을", "를", "에", "도", "만", "와", "과", "로", "요",
)
_ENGLISH_STOPWORDS = {
    "the", "and", "for", "with", "this", "that", "from", "page", "pages", "lecture",
    "overview", "chapter", "current", "next", "previous", "between", "about", "into",
}

_INITIAL_VARIANTS: tuple[tuple[str, ...], ...] = (
    ("g", "k"), ("kk",), ("n",), ("d", "t"), ("tt",), ("r", "l"), ("m",), ("b", "p"),
    ("pp",), ("s",), ("ss",), ("",), ("j", "z"), ("jj",), ("ch",), ("k",), ("t",),
    ("p", "f"), ("h",),
)
_MEDIAL_VARIANTS: tuple[tuple[str, ...], ...] = (
    ("a",), ("ae", "e"), ("ya",), ("yae", "ye"), ("eo", "e", "er", "u", "o"), ("e",),
    ("yeo", "yu", "yo"), ("ye",), ("o",), ("wa",), ("wae", "we"), ("oe", "we"),
    ("yo",), ("u", "oo", "ou"), ("wo", "weo"), ("we",), ("wi",), ("yu",),
    ("eu", "u"), ("ui", "y"), ("i", "ee", "y"),
)
_FINAL_VARIANTS: tuple[tuple[str, ...], ...] = (
    ("",), ("k", "g"), ("k",), ("ks",), ("n",), ("nj",), ("nh",), ("t", "d"),
    ("l", "r"), ("lk",), ("lm",), ("lb",), ("ls",), ("lt",), ("lp",), ("lh",),
    ("m",), ("p", "b"), ("ps",), ("t", "s"), ("t", "ss"), ("ng",), ("t", "j"),
    ("t", "ch"), ("k",), ("t",), ("p",), ("h",),
)


def _normalize_text(value: str) -> str:
    return " ".join(_WORD_RE.findall(value.lower()))


def _expand_query_terms(query: str) -> list[str]:
    normalized = _normalize_text(query)
    terms: set[str] = set()
    for token in _WORD_RE.findall(query.lower()):
        if len(token) >= 2:
            terms.add(token)
        terms.update(_build_phonetic_query_terms(token))
        collapsed = token.replace(" ", "")
        for key, values in _DOMAIN_SYNONYMS.items():
            if key in collapsed:
                terms.add(key)
                terms.update(values)
    if "흐름" in normalized and "제어" in normalized:
        terms.add("흐름제어")
        terms.update(_DOMAIN_SYNONYMS["흐름제어"])
    if "혼잡" in normalized and "제어" in normalized:
        terms.add("혼잡제어")
        terms.update(_DOMAIN_SYNONYMS["혼잡제어"])
    if "신뢰" in normalized:
        terms.update(_DOMAIN_SYNONYMS["신뢰"])
    return sorted({term.strip().lower() for term in terms if term.strip()}, key=_term_sort_key)


def _score_page(text: str, terms: list[str]) -> tuple[float, list[str]]:
    normalized = _normalize_text(text)
    if not normalized:
        return 0.0, []

    score = 0.0
    matched: list[str] = []
    english_tokens = _english_tokens(text)
    for term in terms:
        if term.startswith(_PHONETIC_TERM_PREFIX):
            match, ratio = _best_phonetic_match(term, english_tokens)
            if match:
                matched.append(match)
                score += 5.0 + (ratio * 5.0)
            continue

        normalized_term = _normalize_text(term)
        if not normalized_term:
            continue
        count = normalized.count(normalized_term)
        if count <= 0:
            continue
        matched.append(term)
        weight = 3.0 if " " in normalized_term else 1.0
        if len(normalized_term) >= 8:
            weight += 1.0
        score += min(count, 6) * weight

    # Prefer pages that match several distinct question-specific concepts over
    # overview pages that repeat one broad term many times.
    if len(matched) >= 2:
        score += (len(matched) - 1) * 4.0
    if any(term in {"rcvwindow", "rwnd", "receive window", "수신 윈도우"} for term in matched):
        score += 4.0

    if matched and len(normalized) < 350:
        score *= 0.85
    return score, matched


def _term_sort_key(term: str) -> tuple[int, int, str]:
    if term.startswith(_PHONETIC_TERM_PREFIX):
        return (0, -len(term), term)
    return (1, -len(term), term)


def _build_phonetic_query_terms(token: str) -> set[str]:
    stripped = _strip_korean_query_token(token)
    if not stripped or stripped in _KOREAN_QUERY_STOPWORDS or not _contains_hangul(stripped):
        return set()
    if len(stripped) < 2 or len(stripped) > 7:
        return set()

    variants = _romanize_hangul_variants(stripped, limit=24)
    if not variants:
        return set()
    return {_PHONETIC_TERM_PREFIX + stripped + ":" + "|".join(sorted(variants)[:24])}


def _strip_korean_query_token(token: str) -> str:
    stripped = re.sub(r"[^a-z0-9가-힣]", "", token.lower())
    for particle in sorted(_KOREAN_PARTICLES, key=len, reverse=True):
        if stripped.endswith(particle) and len(stripped) > len(particle) + 1:
            stripped = stripped[: -len(particle)]
            break
    return stripped


def _contains_hangul(value: str) -> bool:
    return any("가" <= char <= "힣" for char in value)


def _romanize_hangul_variants(value: str, *, limit: int = 24) -> set[str]:
    variants = {""}
    for char in value:
        syllable_variants = _romanize_syllable_variants(char)
        next_variants: set[str] = set()
        for prefix in variants:
            for suffix in syllable_variants:
                next_variants.add(prefix + suffix)
                if len(next_variants) >= limit:
                    break
            if len(next_variants) >= limit:
                break
        variants = next_variants or variants
    expanded = set(variants)
    for variant in variants:
        expanded.add(_smooth_romanized_variant(variant))
    return {variant for variant in expanded if len(variant) >= 3}


def _romanize_syllable_variants(char: str) -> tuple[str, ...]:
    code = ord(char)
    if not 0xAC00 <= code <= 0xD7A3:
        return (char.lower(),)

    offset = code - 0xAC00
    initial = offset // 588
    medial = (offset % 588) // 28
    final = offset % 28

    variants: list[str] = []
    for initial_text in _INITIAL_VARIANTS[initial][:2]:
        for medial_text in _MEDIAL_VARIANTS[medial][:3]:
            for final_text in _FINAL_VARIANTS[final][:2]:
                variants.append(initial_text + medial_text + final_text)
    return tuple(dict.fromkeys(variants[:8]))


def _english_tokens(text: str) -> set[str]:
    return {
        token
        for token in re.findall(r"[a-z][a-z0-9]{2,}", text.lower())
        if token not in _ENGLISH_STOPWORDS
    }


def _best_phonetic_match(term: str, english_tokens: set[str]) -> tuple[str, float]:
    try:
        original, variants_text = term[len(_PHONETIC_TERM_PREFIX):].split(":", 1)
    except ValueError:
        return "", 0.0

    variants = [variant for variant in variants_text.split("|") if len(variant) >= 3]
    best_token = ""
    best_ratio = 0.0
    for variant in variants:
        normalized_variant = _squeeze_repeated_ascii(variant)
        for token in english_tokens:
            normalized_token = _squeeze_repeated_ascii(token)
            ratio = max(
                SequenceMatcher(None, normalized_variant, normalized_token).ratio(),
                SequenceMatcher(None, variant, token).ratio(),
            )
            if normalized_variant and (
                normalized_variant in normalized_token or normalized_token in normalized_variant
            ):
                ratio = max(ratio, 0.92)
            if ratio > best_ratio:
                best_ratio = ratio
                best_token = token

    if best_ratio >= 0.78:
        return best_token, best_ratio
    return original if best_ratio >= 0.9 else "", best_ratio


def _squeeze_repeated_ascii(value: str) -> str:
    return re.sub(r"([a-z0-9])\1+", r"\1", value.lower())


def _smooth_romanized_variant(value: str) -> str:
    return (
        value.lower()
        .replace("aou", "ou")
        .replace("aoo", "ou")
        .replace("teo", "ter")
        .replace("deo", "der")
    )


pdf_context_service = PdfContextService()

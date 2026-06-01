"""
NavigationIntentService

사용자가 채팅에서 페이지 이동을 요청했을 때 FE가 PDF 뷰어를 움직일 수 있도록
navigation directive를 만든다. 명시 페이지/다음·이전은 deterministic하게 처리하고,
의미 기반 이동은 로컬 PDF page index 텍스트 검색으로 best-effort 처리한다.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Iterable

from ai_agent.types.domain import AppEvent, AppEventType, SessionState
from ai_agent.v3.engine.PageCommandIntent import get_page_command_intent
from app.services.pdf_context_service import pdf_context_service


@dataclass(frozen=True)
class NavigationDirective:
    target_page: int
    reason: str
    confidence: float
    source: str
    explain_after_navigation: bool = True


_EXPLICIT_PAGE_RE = re.compile(r"(\d{1,4})\s*(?:페이지|쪽|page|p\b)", re.IGNORECASE)
_NAVIGATION_VERB_RE = re.compile(
    r"(이동|보여|찾아|가줘|가자|넘어|넘겨|열어|돌아가|다시|설명)",
    re.IGNORECASE,
)
_SEMANTIC_HINT_RE = re.compile(
    r"(나오는\s*페이지|있는\s*페이지|부분|개념|앞에서|이전에|전에|다시|보여|찾아|이동)",
    re.IGNORECASE,
)
_CLEAN_PATTERNS = [
    r"\d{1,4}\s*(?:페이지|쪽|page|p\b)",
    r"앞에서\s*설명한",
    r"이전에\s*설명한",
    r"전에\s*설명한",
    r"나오는\s*페이지",
    r"있는\s*페이지",
    r"부분",
    r"개념",
    r"다시",
    r"보여\s*줘?",
    r"찾아\s*줘?",
    r"이동\s*해?\s*줘?",
    r"가\s*줘?",
    r"설명\s*해?\s*줘?",
    r"알려\s*줘?",
    r"페이지",
]
_STOPWORDS = {
    "이",
    "그",
    "저",
    "것",
    "내용",
    "부분",
    "개념",
    "페이지",
    "설명",
    "이동",
    "다시",
    "보여",
    "찾아",
    "해줘",
    "해주세요",
    "where",
    "page",
    "show",
    "move",
    "explain",
    "about",
    "again",
}
_GLOSSARY = {
    "요구공학": ["requirements engineering", "requirements", "requirement"],
    "요구 공학": ["requirements engineering", "requirements", "requirement"],
    "요구사항": ["requirements", "requirement", "system requirements"],
    "분석": ["analysis"],
    "검증": ["validation", "validate"],
    "찾아내": ["elicitation", "identification"],
}


class NavigationIntentService:
    def resolve(self, event: AppEvent, state: SessionState) -> NavigationDirective | None:
        if event.type != AppEventType.USER_MESSAGE:
            return None

        text = _event_text(event)
        if not text:
            return None

        page_count = self._page_count(state)
        command_intent = get_page_command_intent(text)
        if command_intent:
            target = _clamp_page(state.current_page, page_count)
            return NavigationDirective(
                target_page=target,
                reason="다음 페이지로 이동합니다." if command_intent == "NEXT" else "이전 페이지로 이동합니다.",
                confidence=1.0,
                source="page_command",
                explain_after_navigation=True,
            )

        explicit_page = self._explicit_page(text, page_count)
        if explicit_page is not None and _NAVIGATION_VERB_RE.search(text):
            return NavigationDirective(
                target_page=explicit_page,
                reason=f"{explicit_page}페이지 요청으로 이동합니다.",
                confidence=1.0,
                source="explicit_page",
                explain_after_navigation=True,
            )

        if not self._looks_like_semantic_navigation(text):
            return None

        query = self._extract_query(text)
        if not _has_meaningful_query(query):
            return None

        result = self._search_pages(state, query, original_text=text)
        if result is None:
            return None

        target, score, _best_phrase = result
        confidence = max(0.35, min(0.95, score / 18.0))
        reason = f"{query}와 가장 관련 높은 페이지입니다."
        return NavigationDirective(
            target_page=target,
            reason=reason,
            confidence=round(confidence, 2),
            source="page_index_search",
            explain_after_navigation=True,
        )

    def _page_count(self, state: SessionState) -> int:
        pdf_path = _state_pdf_path(state)
        pages = pdf_context_service.read_all_pages(pdf_path)
        if pages:
            return len(pages)
        return 0

    def _explicit_page(self, text: str, page_count: int) -> int | None:
        match = _EXPLICIT_PAGE_RE.search(text)
        if not match:
            return None
        return _clamp_page(int(match.group(1)), page_count)

    def _looks_like_semantic_navigation(self, text: str) -> bool:
        return bool(_NAVIGATION_VERB_RE.search(text) and _SEMANTIC_HINT_RE.search(text))

    def _extract_query(self, text: str) -> str:
        cleaned = text
        for pattern in _CLEAN_PATTERNS:
            cleaned = re.sub(pattern, " ", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"[^\w가-힣A-Za-z0-9\s]", " ", cleaned)
        cleaned = re.sub(r"\s+", " ", cleaned).strip()
        tokens = [token for token in cleaned.split() if token.lower() not in _STOPWORDS]
        return " ".join(tokens).strip()

    def _search_pages(
        self,
        state: SessionState,
        query: str,
        *,
        original_text: str,
    ) -> tuple[int, float, str] | None:
        pdf_path = _state_pdf_path(state)
        pages = pdf_context_service.read_all_pages(pdf_path)
        if not pages:
            return None

        query_terms = _build_query_terms(query)
        if not query_terms:
            return None

        best: tuple[int, float, str] | None = None
        prefer_before = bool(re.search(r"(앞에서|이전에|전에|다시)", original_text))
        for index, page_text in enumerate(pages, start=1):
            score, best_phrase = _score_page(page_text, query_terms)
            if prefer_before and index < state.current_page:
                score += 1.0
            if index == state.current_page:
                score -= 0.5
            if score <= 0:
                continue
            if best is None or score > best[1]:
                best = (index, score, best_phrase)

        if best is None or best[1] < 3.0:
            return None
        return best


def _event_text(event: AppEvent) -> str:
    return str(event.get("text", event.get("message", event.get("question", "")))).strip()


def _state_pdf_path(state: SessionState) -> str:
    page_state = state.pages.get(state.current_page)
    return (page_state.pdf_path if page_state else None) or state.pdf_path or ""


def _clamp_page(page: int, page_count: int) -> int:
    page = max(1, int(page))
    if page_count and page_count > 0:
        return min(max(1, int(page_count)), page)
    return page


def _has_meaningful_query(query: str) -> bool:
    compact = re.sub(r"\s+", "", query)
    return len(compact) >= 2 and compact not in {"이개념", "그개념", "이내용", "그내용"}


def _build_query_terms(query: str) -> list[str]:
    terms: list[str] = []
    normalized = _normalize(query)
    if normalized:
        terms.append(normalized)
    compact = normalized.replace(" ", "")
    if compact and compact != normalized:
        terms.append(compact)

    for token in re.findall(r"[가-힣A-Za-z0-9]+", query):
        token_norm = _normalize(token)
        if len(token_norm) >= 2 and token_norm not in _STOPWORDS:
            terms.append(token_norm)
        for expanded in _GLOSSARY.get(token, []):
            terms.append(_normalize(expanded))

    for key, expansions in _GLOSSARY.items():
        if key in query:
            terms.extend(_normalize(item) for item in expansions)

    return _dedupe(term for term in terms if term)


def _score_page(page_text: str, query_terms: Iterable[str]) -> tuple[float, str]:
    text = _normalize(page_text)
    compact_text = text.replace(" ", "")
    score = 0.0
    best_phrase = ""

    for term in query_terms:
        compact_term = term.replace(" ", "")
        if len(term) < 2:
            continue
        term_score = 0.0
        if term in text:
            term_score += 6.0 + math.log(len(term) + 1)
        if compact_term and compact_term in compact_text:
            term_score += 4.0
        words = [word for word in term.split() if len(word) >= 3]
        if words:
            matched = sum(1 for word in words if word in text)
            term_score += matched * 1.5
        if term_score > score:
            best_phrase = term
        score += term_score

    return score, best_phrase


def _normalize(text: str) -> str:
    lowered = text.lower()
    lowered = re.sub(r"[^0-9a-z가-힣\s]", " ", lowered)
    return re.sub(r"\s+", " ", lowered).strip()


def _dedupe(items: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        result.append(item)
    return result


navigation_intent_service = NavigationIntentService()

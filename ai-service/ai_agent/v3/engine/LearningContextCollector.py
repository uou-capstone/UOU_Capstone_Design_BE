from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Optional

from ai_agent.types.domain import PageState, SessionState
from ai_agent.v3.engine.QaThreadService import qa_thread_service
from app.services.pdf_context_service import RelevantPage, pdf_context_service

_MAX_CURRENT_PAGE_CHARS = 8000
_MAX_NEIGHBOR_PAGE_CHARS = 2500
_MAX_EXPLANATION_CHARS = 2500
_VAGUE_CONFUSION_RE = re.compile(
    r"(이해\s*(가\s*)?(잘\s*)?(안|않)|모르겠|몰라|헷갈|어려워|다시\s*설명|"
    r"잘\s*안\s*돼|잘\s*안돼|무슨\s*말|뭔\s*말)",
    re.IGNORECASE,
)
_QUESTION_WORD_RE = re.compile(r"[a-zA-Z가-힣0-9]+")


def _trim(text: str | None, limit: int) -> str:
    value = (text or "").strip()
    if len(value) <= limit:
        return value
    return value[:limit].rstrip() + "\n...(중략)"


@dataclass(frozen=True)
class LearningContext:
    page_number: int
    pdf_path: str
    chapter_title: Optional[str] = None
    page_text: str = ""
    prev_text: str = ""
    next_text: str = ""
    page_count: int = 0
    learner_memory_digest: str = ""
    qa_thread_digest: str = ""
    related_pages_digest: str = ""

    @property
    def has_page_text(self) -> bool:
        return bool(self.page_text.strip())

    def build_quiz_context(self, explanation: str | None = None) -> str:
        """
        Build a page-scoped source text block for quiz/grading agents.

        The v2 quiz generator only accepts one lecture_content string, so this
        method folds page text, nearby-page hints, learner memory, and recent QA
        into a stable text contract.
        """
        blocks: list[str] = [
            f"[현재 페이지]\n{self.page_number}"
            + (f" / 전체 {self.page_count}페이지" if self.page_count else ""),
        ]
        if self.chapter_title:
            blocks.append(f"[챕터/제목]\n{self.chapter_title}")

        if self.page_text.strip():
            blocks.append(f"[현재 페이지 텍스트]\n{_trim(self.page_text, _MAX_CURRENT_PAGE_CHARS)}")
        elif explanation and explanation.strip():
            blocks.append(f"[현재 페이지 설명]\n{_trim(explanation, _MAX_EXPLANATION_CHARS)}")

        if self.prev_text.strip():
            blocks.append(f"[이전 페이지 흐름 참고]\n{_trim(self.prev_text, _MAX_NEIGHBOR_PAGE_CHARS)}")
        if self.next_text.strip():
            blocks.append(f"[다음 페이지 흐름 참고]\n{_trim(self.next_text, _MAX_NEIGHBOR_PAGE_CHARS)}")
        if self.learner_memory_digest.strip():
            blocks.append(f"[학습자 메모리]\n{self.learner_memory_digest.strip()}")
        if self.qa_thread_digest.strip():
            blocks.append(f"[최근 QA 흐름]\n{self.qa_thread_digest.strip()}")

        return "\n\n".join(blocks).strip()


class LearningContextCollector:
    """
    Collects the page-scoped context shared by Explainer, Qa, Quiz, and Grader.
    """

    def collect(self, state: SessionState, page_state: PageState | None = None) -> LearningContext:
        page_state = page_state or state.get_current_page_state()
        page_number = state.current_page or 1
        pdf_path = page_state.pdf_path or state.pdf_path or ""
        page_context = pdf_context_service.read_page_context(pdf_path, page_number)

        return LearningContext(
            page_number=page_context.page if page_context else page_number,
            pdf_path=pdf_path,
            chapter_title=page_state.chapter_title,
            page_text=page_context.page_text if page_context else "",
            prev_text=page_context.prev_text if page_context else "",
            next_text=page_context.next_text if page_context else "",
            page_count=page_context.page_count if page_context else 0,
            learner_memory_digest=self._build_learner_memory_digest(state),
            qa_thread_digest=qa_thread_service.build_digest(state, page_number=page_number),
        )

    def collect_for_question(
        self,
        state: SessionState,
        question: str,
        page_state: PageState | None = None,
    ) -> LearningContext:
        context = self.collect(state, page_state)
        retrieval_query = self._build_retrieval_query(state, question, context)
        related_pages = pdf_context_service.search_relevant_pages(
            context.pdf_path,
            retrieval_query,
            current_page=context.page_number,
            limit=4,
            include_current=False,
        )
        if not related_pages and retrieval_query.strip() != question.strip():
            related_pages = pdf_context_service.search_relevant_pages(
                context.pdf_path,
                question,
                current_page=context.page_number,
                limit=4,
                include_current=False,
            )
        return LearningContext(
            page_number=context.page_number,
            pdf_path=context.pdf_path,
            chapter_title=context.chapter_title,
            page_text=context.page_text,
            prev_text=context.prev_text,
            next_text=context.next_text,
            page_count=context.page_count,
            learner_memory_digest=context.learner_memory_digest,
            qa_thread_digest=context.qa_thread_digest,
            related_pages_digest=self._build_related_pages_digest(related_pages),
        )

    def _build_retrieval_query(self, state: SessionState, question: str, context: LearningContext) -> str:
        question_text = (question or "").strip()
        if not _is_vague_confusion(question_text):
            return question_text

        previous_question = self._latest_page_thread_question(state, context.page_number)
        if previous_question:
            return previous_question

        page_terms = _extract_page_key_terms(context.page_text)
        if page_terms:
            return page_terms

        return question_text

    @staticmethod
    def _latest_page_thread_question(state: SessionState, page_number: int) -> str:
        thread = state.qa_threads.get(qa_thread_service.page_key(page_number), [])
        for turn in reversed(thread):
            question = str(turn.get("question") or "").strip()
            if question and not _is_vague_confusion(question):
                return question
        return ""

    def _build_learner_memory_digest(self, state: SessionState) -> str:
        learner = state.learner
        weak = ", ".join(learner.weak_concepts[-5:]) if learner.weak_concepts else "없음"
        recent_scores = ", ".join(f"{score:.2f}" for score in learner.recent_scores[-5:]) or "없음"
        page_key = str(state.current_page)
        attempts = learner.quiz_attempt_counts.get(page_key, 0)
        return (
            f"수준: {learner.proficiency_level}\n"
            f"현재 페이지 퀴즈 시도: {attempts}\n"
            f"최근 점수: {recent_scores}\n"
            f"약점 개념: {weak}\n"
            f"최근 평균: {learner.average_recent_score:.2f}"
        )

    @staticmethod
    def _build_related_pages_digest(pages: list[RelevantPage]) -> str:
        if not pages:
            return ""
        blocks: list[str] = []
        for page in pages:
            matched = ", ".join(page.matched_terms[:6]) if page.matched_terms else "키워드 매칭"
            blocks.append(
                f"[관련 페이지 {page.page} | match: {matched} | score: {page.score:.1f}]\n"
                f"{_trim(page.text, 2200)}"
            )
        return "\n\n".join(blocks)


def _is_vague_confusion(question: str) -> bool:
    return bool(_VAGUE_CONFUSION_RE.search(question.strip()))


def _extract_page_key_terms(page_text: str) -> str:
    text = (page_text or "").lower()
    terms: list[str] = []
    patterns = (
        ("tcp", "tcp"),
        ("udp", "udp"),
        ("flow control", "flow control"),
        ("흐름 제어", "흐름 제어"),
        ("reliable data transfer", "reliable data transfer"),
        ("신뢰", "신뢰성"),
        ("congestion control", "congestion control"),
        ("혼잡", "혼잡 제어"),
        ("multiplex", "multiplexing demultiplexing"),
        ("network layer", "network layer"),
        ("transport layer", "transport layer"),
    )
    for needle, term in patterns:
        if needle in text and term not in terms:
            terms.append(term)
    if terms:
        return " ".join(terms[:6])

    tokens = [
        token for token in _QUESTION_WORD_RE.findall(page_text or "")
        if len(token) >= 3
    ]
    return " ".join(tokens[:8])

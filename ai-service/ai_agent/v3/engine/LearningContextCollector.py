from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from ai_agent.types.domain import PageState, SessionState
from ai_agent.v3.engine.QaThreadService import qa_thread_service
from app.services.pdf_context_service import pdf_context_service

_MAX_CURRENT_PAGE_CHARS = 8000
_MAX_NEIGHBOR_PAGE_CHARS = 2500
_MAX_EXPLANATION_CHARS = 2500


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

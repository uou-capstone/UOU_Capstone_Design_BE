from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from ai_agent.types.domain import SessionState

_MAX_TURNS_PER_PAGE = 6
_MAX_QUESTION_CHARS = 600
_MAX_ANSWER_CHARS = 1200


def _trim(text: str | None, limit: int) -> str:
    value = (text or "").strip()
    if len(value) <= limit:
        return value
    return value[:limit].rstrip() + "\n...(중략)"


class QaThreadService:
    """
    Maintains page-scoped QA follow-up threads.

    Reference workflow keeps QA memory tied to the currently visible page, not
    the whole session conversation. The session state stores a compact list of
    question/answer turns per 1-based page number.
    """

    def page_key(self, page_number: int) -> str:
        return str(max(int(page_number or 1), 1))

    def append_turn(
        self,
        state: SessionState,
        *,
        page_number: int,
        question: str,
        answer: str,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        question_text = _trim(question, _MAX_QUESTION_CHARS)
        answer_text = _trim(answer, _MAX_ANSWER_CHARS)
        if not question_text and not answer_text:
            return

        key = self.page_key(page_number)
        thread = list(state.qa_threads.get(key, []))
        turn: dict[str, Any] = {
            "page_number": max(int(page_number or 1), 1),
            "question": question_text,
            "answer": answer_text,
            "created_at": datetime.now(timezone.utc).isoformat(),
        }
        if metadata:
            turn["metadata"] = metadata
        thread.append(turn)
        state.qa_threads[key] = thread[-_MAX_TURNS_PER_PAGE:]

    def build_digest(self, state: SessionState, *, page_number: int) -> str:
        thread = state.qa_threads.get(self.page_key(page_number), [])
        lines: list[str] = []
        for turn in thread[-_MAX_TURNS_PER_PAGE:]:
            question = _trim(str(turn.get("question") or ""), _MAX_QUESTION_CHARS)
            answer = _trim(str(turn.get("answer") or ""), _MAX_ANSWER_CHARS)
            if question:
                lines.append(f"- 학생: {question}")
            if answer:
                lines.append(f"  AI: {answer}")
        return "\n".join(lines) if lines else "(현재 페이지 QA 흐름 없음)"


qa_thread_service = QaThreadService()

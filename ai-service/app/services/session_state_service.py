from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from ai_agent.types.domain import SessionState


def same_material_path(left: str | None, right: str | None) -> bool:
    if not left or not right:
        return left == right
    try:
        return Path(left).resolve() == Path(right).resolve()
    except Exception:
        return left == right


def fresh_state_for_pdf(state: SessionState, lecture_id: int, pdf_path: str) -> SessionState:
    now = datetime.now(timezone.utc).isoformat()
    return SessionState(
        session_id=state.session_id,
        lecture_id=lecture_id,
        current_page=1,
        pdf_path=pdf_path,
        ai_status_connected=True,
        created_at=state.created_at or now,
        updated_at=now,
    )

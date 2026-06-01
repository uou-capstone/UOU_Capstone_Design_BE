"""
PageCommandIntent

레퍼런스 MergeEduAgentFull의 페이지 이동 명령 감지 로직을 Python 엔진에 맞춘 버전.
일반 질문을 페이지 이동으로 오인하지 않도록 짧고 직접적인 명령만 허용한다.
"""
from __future__ import annotations

import re
from typing import Literal

PageCommandIntent = Literal["NEXT", "PREVIOUS"]

_CONDITIONAL_OR_META_RE = re.compile(
    r"(답변\s*중|스트리밍\s*중|누를\s*수도|클릭할\s*수도|"
    r"이동할\s*수도|넘어갈\s*수도|할\s*수도|수도\s*있|"
    r"앞에서\s*설명한|이전에\s*설명한|전에\s*설명한|"
    r"누르면|클릭하면|이동하면|넘어가면|넘기면|만약|경우|"
    r"어떻게|왜|무엇|뭐가|무슨|되나요|\?|？)",
    re.IGNORECASE,
)

_NEXT_ANCHOR_RE = re.compile(
    r"(다음\s*(페이지|슬라이드|장)?|다음으로|넘어가|넘겨|next\s*(page)?|next\b)",
    re.IGNORECASE,
)
_PREVIOUS_ANCHOR_RE = re.compile(
    r"(이전\s*(페이지|슬라이드|장)?|앞\s*(페이지|슬라이드|장)?|"
    r"앞으로\s*돌아가|돌아가|previous\s*(page)?|prev\b)",
    re.IGNORECASE,
)

_NEXT_DIRECT_RE = re.compile(
    r"(다음\s*(페이지|슬라이드|장)?(?:로|를|은|는)?\s*"
    r"(?:가|가자|갈게|이동|넘어|넘겨|보여|열어|진행|설명)|"
    r"다음으로\s*(?:가|가자|갈게|이동|넘어|넘겨|진행)?|"
    r"넘어가\s*(?:줘|주세요|자)?|넘겨\s*(?:줘|주세요)?|"
    r"next\s*(?:page)?(?:\s*please)?)",
    re.IGNORECASE,
)
_PREVIOUS_DIRECT_RE = re.compile(
    r"((?:이전|앞)\s*(페이지|슬라이드|장)?(?:로|를|은|는)?\s*"
    r"(?:가|가자|갈게|이동|돌아|넘어|넘겨|보여|열어|설명)|"
    r"앞으로\s*돌아가|돌아가\s*(?:줘|주세요)?|"
    r"previous\s*(?:page)?(?:\s*please)?|prev\b)",
    re.IGNORECASE,
)


def get_page_command_intent(text: str | None) -> PageCommandIntent | None:
    normalized = str(text or "").strip()
    if not normalized or _CONDITIONAL_OR_META_RE.search(normalized):
        return None

    next_match = _matches_command_intent(normalized, _NEXT_ANCHOR_RE, _NEXT_DIRECT_RE)
    previous_match = _matches_command_intent(
        normalized,
        _PREVIOUS_ANCHOR_RE,
        _PREVIOUS_DIRECT_RE,
    )
    if next_match == previous_match:
        return None
    return "NEXT" if next_match else "PREVIOUS"


def _matches_command_intent(text: str, anchor: re.Pattern[str], direct: re.Pattern[str]) -> bool:
    if not anchor.search(text):
        return False
    return _is_short_page_command(text) or bool(direct.search(text))


def _is_short_page_command(text: str) -> bool:
    token_count = len([token for token in re.split(r"\s+", text.strip()) if token])
    return token_count <= 6 and len(text) <= 48

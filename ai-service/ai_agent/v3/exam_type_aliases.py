# -*- coding: utf-8 -*-
"""
Normalizes exam/quiz type strings from Spring/Java Enum.name() to Bridge canonical values.

Bridge & LectureTestGenerator expect: Five_Choice, OX_Problem, Flash_Card, Short_Answer, Debate.
Java often sends: FIVE_CHOICE, OX_PROBLEM, ...
"""
from __future__ import annotations

JAVA_ENUM_NAME_TO_BRIDGE: dict[str, str] = {
    "FIVE_CHOICE": "Five_Choice",
    "OX_PROBLEM": "OX_Problem",
    "FLASH_CARD": "Flash_Card",
    "SHORT_ANSWER": "Short_Answer",
    "DEBATE": "Debate",
}


def normalize_exam_type_string(value: str | None) -> str:
    s = (value or "").strip()
    return JAVA_ENUM_NAME_TO_BRIDGE.get(s, s)

# -*- coding: utf-8 -*-
"""
Normalizes exam/quiz type strings from Spring/Java Enum.name() to Bridge canonical values.

Bridge & LectureTestGenerator expect: Five_Choice, OX_Problem, Flash_Card, Short_Answer, Debate.
Java often sends: FIVE_CHOICE, OX_PROBLEM, ...
"""
from __future__ import annotations

JAVA_ENUM_NAME_TO_BRIDGE: dict[str, str] = {
    "MCQ": "Five_Choice",
    "MULTIPLE_CHOICE": "Five_Choice",
    "FIVE_CHOICE": "Five_Choice",
    "FIVECHOICE": "Five_Choice",
    "OX": "OX_Problem",
    "O_X": "OX_Problem",
    "TRUE_FALSE": "OX_Problem",
    "OX_PROBLEM": "OX_Problem",
    "OXPROBLEM": "OX_Problem",
    "FLASH_CARD": "Flash_Card",
    "SHORT": "Short_Answer",
    "SHORT_ANSWER": "Short_Answer",
    "SHORTANSWER": "Short_Answer",
    "ESSAY": "Essay",
    "SUBJECTIVE": "Essay",
    "DEBATE": "Debate",
}


def normalize_exam_type_string(value: str | None) -> str:
    s = (value or "").strip()
    key = s.upper().replace("-", "_").replace(" ", "_")
    return JAVA_ENUM_NAME_TO_BRIDGE.get(key, s)

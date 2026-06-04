import os
from types import SimpleNamespace

import pytest
from google.genai import types

os.environ.setdefault("GEMINI_API_KEY", "dummy_api_key_for_testing")

from ai_agent.v2.test_gen.generators.five_choice import FiveChoiceGenerator
from ai_agent.v2.test_gen.profile import get_default_test_profile
from ai_agent.v2.test_gen.utils import (
    build_lecture_material_contents,
    load_lecture_material,
)


class FakeFiles:
    def upload(self, file):
        return SimpleNamespace(uri="gemini://files/lecture-pdf", mime_type="application/pdf")


class FakeClient:
    files = FakeFiles()


@pytest.mark.asyncio
async def test_load_lecture_material_returns_pdf_part(tmp_path):
    pdf_path = tmp_path / "lecture.pdf"
    pdf_path.write_bytes(b"%PDF-1.4\n")

    material = await load_lecture_material(str(pdf_path), FakeClient())

    assert isinstance(material, types.Part)


def test_build_lecture_material_contents_keeps_pdf_part_out_of_text():
    part = types.Part.from_uri(
        file_uri="gemini://files/lecture-pdf",
        mime_type="application/pdf",
    )

    contents = build_lecture_material_contents(part, text_limit=10000)

    assert contents[1] is part
    assert "Attached PDF lecture material" in contents[0]
    assert "gemini://files/lecture-pdf" not in contents[0]


def test_build_lecture_material_contents_truncates_inline_text():
    contents = build_lecture_material_contents("abcdef", text_limit=3)

    assert contents == ["[Lecture Material]\nabc"]


@pytest.mark.asyncio
async def test_five_choice_plan_passes_pdf_part_to_gemini_contents(monkeypatch):
    captured = {}
    generator = FiveChoiceGenerator(FakeClient())

    async def fake_call_gemini_async(*, contents, system_instruction, response_schema, model):
        captured["contents"] = contents
        return '{"planning_strategy":"test","planned_items":[{"id":1}]}'

    monkeypatch.setattr(generator, "_call_gemini_async", fake_call_gemini_async)
    part = types.Part.from_uri(
        file_uri="gemini://files/lecture-pdf",
        mime_type="application/pdf",
    )

    result = await generator._create_plan(part, get_default_test_profile(), 1)

    assert result["planned_items"] == [{"id": 1}]
    assert any(item is part for item in captured["contents"])
    assert not any(
        isinstance(item, str) and "gemini://files/lecture-pdf" in item
        for item in captured["contents"]
    )

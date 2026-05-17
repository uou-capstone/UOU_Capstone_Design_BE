"""
Orchestrator

설계서 §4: LLM 플래너
입력 이벤트(AppEvent)와 현재 상태를 바탕으로 LLM을 호출하여 JSON 플랜(OrchestratorPlan)을 생성한다.
규칙 기반 분기를 제거하고 Gemini의 thinking_config 및 JSON schema를 사용한다.
"""
from __future__ import annotations

import json
from typing import AsyncGenerator

from google.genai import types

from ai_agent.types.domain import (
    AppEvent,
    SessionState,
    OrchestratorPlan,
    NdjsonEvent,
    NdjsonEventType,
)
from ai_agent.bridge.GeminiBridgeClient import GeminiBridgeClient
from ai_agent.v3.engine.LearningContextCollector import LearningContextCollector
from ai_agent.v3.engine.QuizDiagnosisService import quiz_diagnosis_service


class Orchestrator:
    """
    LLM 기반 계획 수립기.
    """
    def __init__(self, bridge: GeminiBridgeClient):
        self._bridge = bridge
        self._context_collector = LearningContextCollector()

    def _extract_page_context(self, pdf_path: str | None, current_page: int) -> str:
        """현재 페이지와 전후 인접 페이지의 텍스트 컨텍스트를 PDF에서 추출한다."""
        if not pdf_path:
            return "PDF 정보가 연결되지 않았습니다."
        try:
            import pypdf
            reader = pypdf.PdfReader(pdf_path)
            total_pages = len(reader.pages)
            if total_pages == 0:
                return "PDF 텍스트를 읽을 수 없습니다."

            # UI 상의 1-indexed 페이지 번호를 0-indexed로 변환
            target_idx = max(0, current_page - 1)
            if target_idx >= total_pages:
                target_idx = total_pages - 1

            # 이전, 현재, 다음 페이지 정보를 조합
            pages_to_read = [
                idx for idx in [target_idx - 1, target_idx, target_idx + 1]
                if 0 <= idx < total_pages
            ]
            
            context_blocks = [f"전체 페이지 수: {total_pages}"]
            for idx in pages_to_read:
                text = reader.pages[idx].extract_text() or ""
                label = "현재 쪽" if idx == target_idx else ("이전 쪽" if idx < target_idx else "다음 쪽")
                context_blocks.append(f"[{label} (Page {idx+1})]\n{text.strip()[:1500]}")
            
            return "\n\n".join(context_blocks)
            
        except Exception as e:
            return f"페이지 텍스트 추출 중 요류 발생: {e}"

    def _build_prompt(self, event: AppEvent, state: SessionState) -> str:
        """시스템 프롬프트 생성"""
        learning_context = self._context_collector.collect(state)
        page_context = learning_context.build_quiz_context(state.get_current_page_state().explanation)

        learner_info = {
            "level": state.learner.proficiency_level,
            "recent_scores": state.learner.recent_scores,
            "weak_concepts": getattr(state.learner, "weak_concepts", []),
            "quiz_attempt_counts": state.learner.quiz_attempt_counts,
            "avg_score": state.learner.average_recent_score,
            "digest": learning_context.learner_memory_digest,
        }
        learner_memo = json.dumps(learner_info, ensure_ascii=False)
        assessment_digest = quiz_diagnosis_service.consume_pending_assessment_digest(
            state,
            page_number=state.current_page,
        )
        active_intervention = json.dumps(state.active_intervention or {}, ensure_ascii=False)
        
        event_str = f"Type: {event.type.value}, Payload: {json.dumps(event.payload, ensure_ascii=False)}"
        
        prompt = f"""당신은 "MergeEduAgent LLM 플래너" (학습 오케스트레이터)입니다.
당신의 절대적인 목표는 주어진 컨텍스트를 분석하여, 학생의 다음 학습을 위한 **도구 호출 계획(OrchestratorPlan)**을 확정하는 것입니다.

[현재 상황]
수신 이벤트: {event_str}
현재 페이지 번호: {state.current_page}

[페이지 컨텍스트 텍스트(요약)]
{page_context}

[학습자 성향 메모리]
{learner_memo}

[현재 페이지 QA 흐름 요약]
{learning_context.qa_thread_digest}

[최근 퀴즈 진단 artifact]
{assessment_digest or "(새 진단 artifact 없음)"}

[활성 오개념 교정 상태]
{active_intervention}

[사용 가능한 도구 (ActionType.CALL_TOOL 할당)]
- EXPLAIN_PAGE: 강의 설명 (매개변수: {{"detail": "NORMAL" | "DETAILED"}}) -> 강의 설명이 필요할 경우 호출.
- ANSWER_QUESTION: 질문에 대한 답변 (매개변수: {{"question": "..."}}) -> 사용자가 메시지로 질문한 내용의 답변 호출.
- GENERATE_QUIZ_FIVE_CHOICE: 객관식 퀴즈 (매개변수: {{"quiz_type": "Five_Choice"}})
- GENERATE_QUIZ_OX: OX 퀴즈 (매개변수: {{"quiz_type": "OX_Problem"}})
- AUTO_GRADE_MCQ_OX: 객관식 자동 채점 (매개변수: {{"quiz_type": "..."}})
- GRADE_SHORT_OR_ESSAY: 주관식 채점 (매개변수: {{"quiz_type": "..."}})
- REPAIR_MISCONCEPTION: 활성 오개념 교정 상태가 있을 때 학생 답변 기반으로 짧은 교정 설명을 생성 (매개변수: {{"student_message": "..."}})

UI 조절 도구 (ActionType.SET_UI_STATE 할당): 
  ui_state 필드에 넘길 수 있는 예시: {{"modal": "QUIZ_TYPE_PICKER"}}, {{"widget": "START_EXPLANATION_DECISION"}}, {{"widget": "NEXT_PAGE_DECISION"}}, {{"widget": "QUIZ_DECISION"}}

단순 UI 통지 메시지 표시 (ActionType.SEND_MESSAGE 할당): message 필드에 사용자에게 보여줄 안내문 작성.

[판단 지시 사항]
1. `thinking` 블록을 자유롭게 활용해서 상황을 파악하세요.
2. 당신의 응답은 추가 텍스트 없이 유효한 JSON 형식이어야 합니다(OrchestratorPlan 스키마 대응).
3. 일반 질문/답변의 경우 `ANSWER_QUESTION` 툴을 부릅니다.
4. 설명 직후에는 퀴즈 풀이를 제안하는 위젯(`QUIZ_DECISION`)을 노출시키거나, 이전 점수가 좋지 않다면 바로 해당 페이지 기반의 퀴즈를 생성하세요.
5. 시험 성적이 기준 이하면 재설명을 위해 `EXPLAIN_PAGE` 툴을 다시 부를 수 있습니다.
6. 활성 오개념 교정 상태가 있고 이벤트가 `USER_MESSAGE`이면 `REPAIR_MISCONCEPTION`을 우선 고려하세요. 일반 QA로 흐름을 분산시키지 마세요.
"""
        return prompt

    async def run_stream(self, event: AppEvent, state: SessionState) -> AsyncGenerator[NdjsonEvent, None]:
        contents = [self._build_prompt(event, state)]
        
        # NOTE: response_schema=OrchestratorPlan 은 Gemini 로부터
        # "additionalProperties is not supported in the Gemini API" 400 을 유발한다.
        # OrchestratorAction.params / ui_state 가 Dict[str, Any] 이므로 Pydantic 이
        # 생성한 JSON Schema 에 additionalProperties 가 포함되는데, Gemini 의
        # responseSchema 는 해당 키를 지원하지 않는다.
        # 프롬프트로 형식을 강제하고, DONE 시점의 OrchestratorPlan.model_validate_json()
        # 로 사후 검증하는 기존 로직에 의존한다.
        config = types.GenerateContentConfig(
            response_mime_type="application/json",
            thinking_config={"include_thoughts": True}
        )
        
        json_buffer = []
        async for ev in self._bridge.stream(contents, config=config, agent="orchestrator"):
            if ev.type == NdjsonEventType.AGENT_DELTA:
                if ev.channel == "thought":
                    yield ev  # UI로 생각 스트리밍 포워딩
                elif ev.channel == "main" and ev.delta:
                    # JSON 결과물 버퍼링 (클라이언트로는 보내지 않음)
                    json_buffer.append(ev.delta)
            elif ev.type == NdjsonEventType.ERROR:
                yield ev
            elif ev.type == NdjsonEventType.DONE:
                try:
                    json_str = "".join(json_buffer)
                    if not json_str.strip():
                        raise ValueError("LLM 응답에서 추출된 JSON 내용이 없습니다.")
                        
                    # pydantic 객체로 역직렬화 (검증)
                    plan = OrchestratorPlan.model_validate_json(json_str)
                    
                    # 딕셔너리로 형변환 후 바인딩 (이후 핸들러 측에서 필요시 다시 OrchestratorPlan으로 패킹)
                    ev.data = {"plan": plan.model_dump()}
                    ev.final = True
                    yield ev
                except Exception as e:
                    yield NdjsonEvent(
                        type=NdjsonEventType.ERROR, 
                        agent="orchestrator", 
                        message=f"JSON 파싱 오류: {e}"
                    )
            else:
                # 하트비트 포워딩
                yield ev

"""
Orchestrator

설계서 §4: LLM 플래너
입력 이벤트(AppEvent)와 현재 상태를 바탕으로 LLM을 호출하여 JSON 플랜(OrchestratorPlan)을 생성한다.
규칙 기반 분기를 제거하고 Gemini의 thinking_config 및 JSON schema를 사용한다.
"""
from __future__ import annotations

import json
import logging
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


logger = logging.getLogger(__name__)


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
당신은 직접 강의 내용을 길게 답하지 않습니다. 현재 턴에서 어떤 하위 에이전트를 실행할지만 결정합니다.

[한 턴 처리 흐름]
Learning Context Collection → Orchestrator Planner → Tool Dispatcher → Selected Sub-Agent → Execution Result → Session Memory Update.
이 프롬프트는 위 흐름 중 Orchestrator Planner 단계입니다.
따라서 최종 응답은 실행 계획 JSON이어야 하며, 학생에게 보일 설명/답변/채점 내용은 각 도구가 생성합니다.

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

[도구 호출 action]
도구를 실행하려면 반드시 다음 JSON literal 형식을 사용하세요:
{{"type": "CALL_TOOL", "tool": "EXPLAIN_PAGE", "params": {{"detail": "NORMAL"}}}}

`type` 허용값은 문자열 literal `"CALL_TOOL"`, `"SET_UI_STATE"`, `"SEND_MESSAGE"` 뿐입니다.
`tool` 허용값은 아래 도구 이름 문자열 literal 뿐입니다.
절대 enum class prefix나 클래스명과 점(.)을 붙인 형태를 쓰지 마세요.

[사용 가능한 도구]
- EXPLAIN_PAGE: 강의 설명 (매개변수: {{"detail": "NORMAL" | "DETAILED", "next_widget": "NEXT_PAGE_DECISION" | "QUIZ_DECISION"}}) -> 강의 설명이 필요할 경우 호출.
- ANSWER_QUESTION: 질문에 대한 답변 (매개변수: {{"question": "..."}}) -> 사용자가 메시지로 질문한 내용의 답변 호출.
- GENERATE_QUIZ_FIVE_CHOICE: 객관식 퀴즈 (매개변수: {{"quiz_type": "Five_Choice"}})
- GENERATE_QUIZ_OX: OX 퀴즈 (매개변수: {{"quiz_type": "OX_Problem"}})
- GENERATE_QUIZ_SHORT: 단답형 퀴즈 (매개변수: {{"quiz_type": "Short_Answer"}})
- GENERATE_QUIZ_ESSAY: 서술형 퀴즈 (매개변수: {{"quiz_type": "Essay"}})
- GENERATE_QUIZ_FLASH: 플래시카드 퀴즈 (매개변수: {{"quiz_type": "Flash_Card"}})
- AUTO_GRADE_MCQ_OX: 객관식 자동 채점 (매개변수: {{"quiz_type": "..."}})
- GRADE_SHORT_OR_ESSAY: 주관식 채점 (매개변수: {{"quiz_type": "..."}})
- REPAIR_MISCONCEPTION: 활성 오개념 교정 상태가 있을 때 학생 답변 기반으로 짧은 교정 설명을 생성 (매개변수: {{"student_message": "..."}})

UI 상태 action:
  반드시 {{"type": "SET_UI_STATE", "ui_state": {{"widget": "QUIZ_DECISION"}}}} 형식을 사용하세요.
  ui_state 필드에 넘길 수 있는 예시: {{"modal": "QUIZ_TYPE_PICKER"}}, {{"widget": "START_EXPLANATION_DECISION"}}, {{"widget": "NEXT_PAGE_DECISION"}}, {{"widget": "QUIZ_DECISION"}}

단순 UI 통지 메시지 action:
  반드시 {{"type": "SEND_MESSAGE", "message": "..."}} 형식을 사용하세요.

[판단 지시 사항]
1. `thinking` 블록을 자유롭게 활용해서 상황을 파악하세요.
2. 최종 응답은 추가 텍스트 없이 유효한 JSON 형식이어야 합니다(OrchestratorPlan 스키마 대응).
3. 최종 JSON에는 `thinking`, `thought`, `reasoning` 필드를 넣지 마세요. 사고 흐름은 스트리밍 thought 채널에서만 사용합니다.
4. 일반 질문/답변의 경우 반드시 `ANSWER_QUESTION` 툴을 부릅니다. `흐름제어가 뭐지`, `TCP가 뭐야`, `왜 이렇게 돼?`처럼 특정 개념을 묻는 말은 페이지 설명이 아니라 QA입니다.
5. 설명 직후에는 후속 UI를 분명히 정하세요. 처음 설명을 시작한 흐름은 `NEXT_PAGE_DECISION`, 페이지 변경 기반 흐름은 `QUIZ_DECISION`을 우선 사용합니다.
6. 시험 성적이 기준 이하면 재설명을 위해 `EXPLAIN_PAGE` 툴을 다시 부를 수 있습니다.
7. 활성 오개념 교정 상태가 있고 이벤트가 `USER_MESSAGE`이면 `REPAIR_MISCONCEPTION`을 우선 고려하세요. 일반 QA로 흐름을 분산시키지 마세요.
8. `SESSION_ENTERED`에서는 빈 actions를 반환하지 말고 `START_EXPLANATION_DECISION` 위젯을 표시하세요.
9. `START_EXPLANATION_DECISION`이 수락되었거나 payload가 비어 있으면 `EXPLAIN_PAGE` 도구를 호출하세요. params에는 {{"detail":"NORMAL","next_widget":"NEXT_PAGE_DECISION"}}를 넣으세요.
10. `PAGE_CHANGED` 이벤트는 PDF 뷰어가 이미 페이지를 바꾼 상태입니다. 현재 페이지에 대해 `EXPLAIN_PAGE`를 호출하고 params에는 {{"detail":"NORMAL","next_widget":"QUIZ_DECISION"}}를 넣으세요.
11. `NEXT_PAGE_DECISION`이 수락되면 현재 페이지가 이미 다음 페이지로 갱신된 상태이므로 `EXPLAIN_PAGE` 도구를 호출하세요. params에는 {{"detail":"NORMAL","next_widget":"NEXT_PAGE_DECISION"}}를 넣으세요.
12. `QUIZ_DECISION`이 수락되면 퀴즈 유형 선택 UI만 여세요: {{"type":"SET_UI_STATE","ui_state":{{"modal":"QUIZ_TYPE_PICKER"}}}}.
13. `RETEST_DECISION`이 수락되면 재시험 유형 선택 UI를 여세요: {{"type":"SET_UI_STATE","ui_state":{{"modal":"QUIZ_TYPE_PICKER","mode":"RETEST"}}}}.
14. `QUIZ_TYPE_SELECTED`가 `"Five_Choice"`/`"FIVE_CHOICE"`/`"MCQ"`이면 `GENERATE_QUIZ_FIVE_CHOICE`, `"OX_Problem"`/`"OX_PROBLEM"`/`"OX"`이면 `GENERATE_QUIZ_OX`, `"Short_Answer"`/`"SHORT"`이면 `GENERATE_QUIZ_SHORT`, `"Essay"`/`"ESSAY"`이면 `GENERATE_QUIZ_ESSAY`, `"Flash_Card"`/`"FLASH_CARD"`이면 `GENERATE_QUIZ_FLASH`를 호출하세요.
15. `QUIZ_SUBMITTED`에서 객관식/OX는 `AUTO_GRADE_MCQ_OX`, 단답형/서술형은 `GRADE_SHORT_OR_ESSAY`를 사용하세요. 기준 미달이면 진단/교정 흐름을 유지하고, 재시험 통과 후에도 다음 페이지 설명을 자동 시작하지 말고 사용자의 `PAGE_CHANGED`를 기다리세요.
16. 일반 `USER_MESSAGE` 질문에는 `ANSWER_QUESTION` 도구를 호출하고, 답변 후 다음 페이지 이동 여부를 묻는 흐름을 유지하세요. 단, 사용자가 "현재 페이지 전체 설명해줘", "이 페이지 설명해줘"처럼 페이지 설명을 명시적으로 요청한 경우에만 `EXPLAIN_PAGE`를 사용할 수 있습니다.
17. 사용자가 "이해가 잘 안 됨", "다시 설명해줘", "헷갈려"처럼 말하면 새 페이지 설명이 아니라 `ANSWER_QUESTION`으로 라우팅하세요. QA 에이전트가 현재 페이지/관련 페이지를 바탕으로 다른 방식의 설명을 제공합니다.
18. 사용자가 의미 기반 페이지 이동을 요청하면 navigation directive는 별도 intent layer가 처리하므로, 플래너는 현재 이벤트가 이미 `PAGE_CHANGED`로 정리된 경우에만 `EXPLAIN_PAGE`를 호출하세요.
19. 퀴즈 생성은 현재 페이지 컨텍스트가 확보된 경우에만 수행해야 합니다. 컨텍스트가 없으면 무리해서 퀴즈 생성 도구를 호출하지 마세요.
20. `USER_MESSAGE`가 "퀴즈 만들어줘", "OX 문제 2개 내줘", "객관식 시험 볼래", "복습 문제 만들어줘", "연습문제 내줘", "확인 문제 풀어볼래", "이 내용으로 문제풀이 해줘", "자가진단 테스트 해줘"처럼 퀴즈/시험/문제 풀이 의도를 명확히 담고 있으면 QA가 아니라 퀴즈 흐름입니다. "내가 이해했는지 확인해줘", "배운 내용 점검해줘"처럼 학습 확인 의도는 있지만 유형이 없는 요청은 직접 생성하지 말고 퀴즈 유형 선택 UI를 여세요. 유형이 명시되면 해당 `GENERATE_QUIZ_*` 도구를 호출하고, 유형이 없으면 `{{"type":"SET_UI_STATE","ui_state":{{"modal":"QUIZ_TYPE_PICKER","reason":"USER_QUIZ_REQUEST"}}}}`를 반환하세요.
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
                    logger.warning(
                        "Orchestrator plan validation failed: error_type=%s raw_length=%d",
                        type(e).__name__,
                        len("".join(json_buffer)),
                    )
                    yield NdjsonEvent(
                        type=NdjsonEventType.ERROR, 
                        agent="orchestrator", 
                        code="ORCHESTRATOR_PLAN_INVALID",
                        message="학습 계획을 생성하지 못했습니다. 다시 시도해 주세요.",
                        details=[{"errorType": "VALIDATION_ERROR"}],
                    )
            else:
                # 하트비트 포워딩
                yield ev

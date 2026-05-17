import json

import pytest
from fastapi.testclient import TestClient

from ai_agent.types.domain import (
    ActionType,
    AppEvent,
    AppEventType,
    NdjsonEvent,
    NdjsonEventType,
    OrchestratorAction,
    OrchestratorPlan,
    PedagogyPolicy,
    SessionState,
    QuizRecord,
    ToolName,
)
from ai_agent.v3.engine.Orchestrator import Orchestrator
from ai_agent.v3.engine.OrchestrationEngine import OrchestrationEngine
from ai_agent.v3.engine.PlanVerifier import PlanVerifier
from ai_agent.v3.engine.QuizDiagnosisService import QuizDiagnosisService
from ai_agent.v3.engine.ToolDispatcher import ToolDispatcher
from app.routers import bridge_agents
from app.routers.exam import ExamGradeRequest, ExamStudioChatRequest, _fallback_grade, run_exam_studio_chat
from app.services.exam_studio_operation_validator import validate_exam_studio_operations


def test_plan_verifier_caps_actions_and_records_state_warnings():
    state = SessionState(session_id=1, lecture_id=1)
    plan = OrchestratorPlan(
        actions=[
            OrchestratorAction(type=ActionType.SEND_MESSAGE, message=f"msg {index}")
            for index in range(10)
        ],
        pedagogy_policy=PedagogyPolicy(intervention_budget=8),
    )

    result = PlanVerifier().verify(plan, state)

    assert len(result.plan.actions) == 8
    assert result.warnings[-1]["code"] == "ACTION_HARD_CAP_TRIMMED"
    assert state.plan_verification_warnings == result.warnings


def test_plan_verifier_trims_intervention_budget_without_new_tool_injection():
    state = SessionState(session_id=1, lecture_id=1, active_intervention={"focusConcept": "분수"})
    plan = OrchestratorPlan(
        actions=[
            OrchestratorAction(type=ActionType.CALL_TOOL, tool=ToolName.EXPLAIN_PAGE),
            OrchestratorAction(type=ActionType.CALL_TOOL, tool=ToolName.GENERATE_QUIZ_OX),
            OrchestratorAction(type=ActionType.CALL_TOOL, tool=ToolName.ANSWER_QUESTION),
        ],
        pedagogy_policy=PedagogyPolicy(intervention_budget=1, allow_direct_answer=False),
    )

    result = PlanVerifier().verify(plan, state)

    assert [action.tool for action in result.plan.actions] == [
        ToolName.EXPLAIN_PAGE,
        ToolName.ANSWER_QUESTION,
    ]
    warning_codes = {warning["code"] for warning in result.warnings}
    assert "INTERVENTION_BUDGET_TRIMMED" in warning_codes
    assert "DIRECT_ANSWER_POLICY_REVIEW_REQUIRED" in warning_codes


def test_plan_verifier_injects_repair_for_active_intervention_user_turn():
    state = SessionState(
        session_id=1,
        lecture_id=1,
        active_intervention={
            "interventionId": "repair-1",
            "status": "AWAITING_USER_RESPONSE",
            "pageNumber": 1,
        },
    )
    plan = OrchestratorPlan(actions=[
        OrchestratorAction(
            type=ActionType.CALL_TOOL,
            tool=ToolName.ANSWER_QUESTION,
            params={"question": "왜 틀렸어?"},
        ),
    ])

    result = PlanVerifier().verify(
        plan,
        state,
        event_type=AppEventType.USER_MESSAGE.value,
        event_payload={"text": "분모는 그냥 더하면 되는 거 아닌가요?"},
    )

    assert [action.tool for action in result.plan.actions] == [ToolName.REPAIR_MISCONCEPTION]
    assert result.plan.actions[0].params["student_message"] == "분모는 그냥 더하면 되는 거 아닌가요?"
    assert result.warnings[-1]["code"] == "REPAIR_MISCONCEPTION_INJECTED"


@pytest.mark.asyncio
async def test_tool_dispatcher_runs_verified_plan(monkeypatch):
    dispatcher = ToolDispatcher(bridge=None)  # type: ignore[arg-type]
    state = SessionState(session_id=1, lecture_id=1)
    calls: list[OrchestratorAction] = []

    async def fake_execute_action(action, state, event_payload):
        calls.append(action)
        yield NdjsonEvent(type=NdjsonEventType.DONE, agent="test", final=True, data={})

    monkeypatch.setattr(dispatcher, "_execute_action", fake_execute_action)
    plan = OrchestratorPlan(
        actions=[
            OrchestratorAction(type=ActionType.SEND_MESSAGE, message=f"msg {index}")
            for index in range(10)
        ],
        pedagogy_policy=PedagogyPolicy(intervention_budget=8),
    )

    events = [event async for event in dispatcher.dispatch(plan, state, {})]

    assert len(events) == 8
    assert len(calls) == 8
    assert state.plan_verification_warnings[-1]["code"] == "ACTION_HARD_CAP_TRIMMED"


@pytest.mark.asyncio
async def test_tool_dispatcher_completes_repair_loop_with_retest_widget():
    class FakeBridge:
        async def stream(self, contents, agent: str, tool: str):
            self.contents = contents
            yield NdjsonEvent(
                type=NdjsonEventType.AGENT_DELTA,
                agent=agent,
                tool=tool,
                channel="main",
                delta="## 오개념 교정\n**분모**는 기준 단위라서 바로 더하지 않습니다.",
            )
            yield NdjsonEvent(type=NdjsonEventType.DONE, agent=agent, tool=tool, final=True, data={})

    state = SessionState(
        session_id=1,
        lecture_id=1,
        active_intervention={
            "interventionId": "repair-1",
            "status": "AWAITING_USER_RESPONSE",
            "pageNumber": 1,
            "focusConcept": "분수 덧셈",
            "focusConcepts": ["분수 덧셈"],
            "repairGoal": "분모의 의미를 설명한다.",
        },
    )
    dispatcher = ToolDispatcher(FakeBridge())  # type: ignore[arg-type]
    plan = OrchestratorPlan(actions=[
        OrchestratorAction(type=ActionType.CALL_TOOL, tool=ToolName.ANSWER_QUESTION),
    ])

    events = [
        event async for event in dispatcher.dispatch(
            plan,
            state,
            {"text": "분모끼리 더하면 되는 거 아닌가요?"},
            event_type=AppEventType.USER_MESSAGE.value,
        )
    ]

    assert any(event.agent == "repair" and event.channel == "main" for event in events)
    done_events = [event for event in events if event.type == NdjsonEventType.DONE]
    assert len(done_events) == 1
    done = done_events[-1]
    assert done.data["ui"] == {"widget": "RETEST_DECISION"}
    assert done.data["activeIntervention"]["status"] == "COMPLETED"
    assert state.active_intervention["status"] == "COMPLETED"


def test_quiz_diagnosis_creates_pending_assessment_and_active_intervention():
    service = QuizDiagnosisService()
    state = SessionState(session_id=1, lecture_id=1, current_page=1)
    record = QuizRecord(
        quiz_id="quiz-1",
        page_number=1,
        quiz_type="OX_Problem",
        questions=[
            {
                "prompt": "분모가 다른 분수는 분모끼리 바로 더한다.",
                "concepts": ["분수 덧셈"],
                "answer": {"value": "X"},
            }
        ],
    )

    assessment = service.record_assessment(
        state,
        record,
        {
            "total_score": 0.0,
            "results": [
                {
                    "question_index": 0,
                    "score": 0.0,
                    "passed": False,
                    "user_answer": "O",
                    "correct_answer": "X",
                    "feedback": "분모의 의미를 다시 확인해야 합니다.",
                }
            ],
        },
        [{"answer": "O"}],
    )

    assert assessment["status"] == "PENDING"
    assert assessment["focusConcepts"] == ["분수 덧셈"]
    assert state.active_intervention["status"] == "AWAITING_USER_RESPONSE"
    assert state.active_intervention["focusConcept"] == "분수 덧셈"


def test_orchestrator_prompt_consumes_pending_assessment_artifact():
    state = SessionState(session_id=1, lecture_id=1, current_page=1)
    state.quiz_assessments.append({
        "artifactId": "assessment-1",
        "quizId": "quiz-1",
        "pageNumber": 1,
        "status": "PENDING",
        "score": 0.0,
        "focusConcepts": ["분수 덧셈"],
        "missedQuestions": [{"question": "분모끼리 더한다"}],
        "repairGoal": "분모의 의미를 설명한다.",
    })
    state.active_intervention = {
        "interventionId": "repair-1",
        "status": "AWAITING_USER_RESPONSE",
        "pageNumber": 1,
        "focusConcept": "분수 덧셈",
    }

    prompt = Orchestrator(None)._build_prompt(  # type: ignore[arg-type]
        AppEvent(type=AppEventType.USER_MESSAGE, payload={"text": "왜 틀렸어?"}),
        state,
    )

    assert "최근 퀴즈 진단 artifact" in prompt
    assert "분수 덧셈" in prompt
    assert "활성 오개념 교정 상태" in prompt
    assert state.quiz_assessments[0]["status"] == "CONSUMED"


@pytest.mark.asyncio
async def test_orchestration_engine_fast_paths_mcq_ox_grading_without_planner():
    class FakeStore:
        def __init__(self, state):
            self.state = state
            self.saved = None

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.saved = state

    class ExplodingBridge:
        async def stream(self, *args, **kwargs):
            raise AssertionError("planner should not be called for MCQ/OX fast path")

    state = SessionState(session_id=1, lecture_id=1, current_page=1)
    state.quiz_history.append(QuizRecord(
        quiz_id="quiz-1",
        page_number=1,
        quiz_type="OX_Problem",
        questions=[{"answer": {"value": "O"}}, {"answer": {"value": "X"}}],
    ))
    store = FakeStore(state)
    engine = OrchestrationEngine(store, bridge=ExplodingBridge())  # type: ignore[arg-type]

    events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(
                type=AppEventType.QUIZ_SUBMITTED,
                payload={"answers": [{"answer": "O"}, {"answer": "O"}], "quiz_type": "OX_Problem"},
            ),
        )
    ]

    done = [event for event in events if event.type == NdjsonEventType.DONE][-1]
    assert done.data["grading"]["total_score"] == 0.5
    assert done.data["passed"] is False
    assert done.data["activeIntervention"]["status"] == "AWAITING_USER_RESPONSE"
    assert store.saved is state


@pytest.mark.asyncio
async def test_orchestration_engine_allows_verifier_repair_injection_for_empty_plan():
    class FakeStore:
        def __init__(self, state):
            self.state = state

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.state = state

    class FakeBridge:
        async def stream(self, contents, agent: str, tool: str):
            yield NdjsonEvent(
                type=NdjsonEventType.AGENT_DELTA,
                agent=agent,
                tool=tool,
                channel="main",
                delta="## 오개념 교정\n다시 연결해 봅시다.",
            )

    class EmptyPlanner:
        async def run_stream(self, event, state):
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="orchestrator",
                final=True,
                data={"plan": OrchestratorPlan(actions=[]).model_dump()},
            )

    state = SessionState(
        session_id=1,
        lecture_id=1,
        active_intervention={
            "interventionId": "repair-1",
            "status": "AWAITING_USER_RESPONSE",
            "pageNumber": 1,
            "focusConcept": "분수 덧셈",
        },
    )
    engine = OrchestrationEngine(FakeStore(state), bridge=FakeBridge())  # type: ignore[arg-type]
    engine._orchestrator = EmptyPlanner()  # type: ignore[assignment]

    events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.USER_MESSAGE, payload={"text": "아직 헷갈려요"}),
        )
    ]

    assert any(event.agent == "repair" and event.channel == "main" for event in events)
    done = [event for event in events if event.type == NdjsonEventType.DONE][-1]
    assert done.data["ui"] == {"widget": "RETEST_DECISION"}


def test_exam_studio_operation_validator_sanitizes_contract_shape():
    result = validate_exam_studio_operations([
        {"method": "patchExamSettings", "params": {"title": "중간고사", "availableFrom": "not-date"}},
        {"method": "appendQuestions", "params": {"questions": [{"id": "q1", "prompt": "문항", "type": "OX", "points": 200, "answer": {"value": "O"}}, "bad"]}},
        {"method": "replaceQuestion", "params": {"replaceQuestionId": "q1", "question": {"id": "q1-new", "prompt": "교체 문항", "type": "SHORT", "referenceAnswer": {"text": "정답"}}}},
        {"method": "removeQuestion", "params": {"id": "q2"}},
        {"method": "appendQuestions", "params": {"questions": []}},
    ])

    assert [operation["method"] for operation in result.operations] == [
        "patchExamSettings",
        "appendQuestions",
        "replaceQuestion",
    ]
    assert result.operations[0]["params"] == {"title": "중간고사"}
    assert result.operations[1]["params"]["questions"][0]["points"] == 100.0
    assert "unsupported_operation:removeQuestion" in result.warnings
    assert "append_questions_empty" in result.warnings
    assert "invalid_iso:availableFrom" in result.warnings
    assert "question_points_clamped" in result.warnings


def test_exam_studio_operation_validator_rejects_ungradable_questions():
    result = validate_exam_studio_operations([
        {
            "method": "appendQuestions",
            "params": {
                "questions": [
                    {"prompt": "정답 없는 객관식", "type": "MCQ", "choices": [{"id": "A"}, {"id": "B"}]},
                    {"prompt": "정답이 choices에 없는 객관식", "type": "MCQ", "choices": [{"id": "A"}, {"id": "B"}], "answer": {"choiceId": "C"}},
                    {"prompt": "정답 없는 OX", "type": "OX"},
                    {"prompt": "기준 없는 단답", "type": "SHORT"},
                    {"prompt": "정상 OX", "type": "OX", "answer": {"value": "true"}},
                ]
            },
        }
    ])

    assert len(result.operations) == 1
    questions = result.operations[0]["params"]["questions"]
    assert questions == [{"prompt": "정상 OX", "type": "OX", "answer": {"value": "O"}, "points": 1.0}]
    assert "mcq_answer_missing" in result.warnings
    assert "mcq_answer_choice_missing" in result.warnings
    assert "ox_answer_missing" in result.warnings
    assert "short_reference_missing" in result.warnings


def test_exam_grade_fallback_normalizes_object_answer_shapes():
    response = _fallback_grade(
        ExamGradeRequest(
            exam={
                "questions": [
                    {"id": "q1", "points": 2, "answer": {"choiceId": "A"}},
                    {"id": "q2", "points": 1, "answer": {"value": "O"}},
                ]
            },
            answers={
                "answers": [
                    {"answer": {"choiceId": "A"}},
                    {"answer": {"value": True}},
                ]
            },
        ),
        reason="AI_UNAVAILABLE",
    )

    assert response.totalScore == 3
    assert response.scoreRatio == 1


def test_discussion_result_sanitizer_removes_assistant_tone_and_redundant_heading():
    result = bridge_agents._normalize_discussion_result(
        bridge_agents.DiscussionAssistantRequest(topic="분수 질문", category="QUESTION"),
        {
            "title": "분수 질문",
            "contentMarkdown": "## 분수 질문\n안녕하세요 AI가 도와드리겠습니다.\n제가 궁금한 점은 **분수**입니다.",
            "source": "AI",
        },
        fallback_used=False,
        reason=None,
        default_source="AI",
    )

    assert result["title"] == "분수 질문"
    assert "안녕하세요" not in result["contentMarkdown"]
    assert "AI" not in result["contentMarkdown"]
    assert not result["contentMarkdown"].startswith("## 분수 질문")
    assert "DISCUSSION_BANNED_PHRASE_REMOVED" in result["warnings"]
    assert "DISCUSSION_REDUNDANT_TITLE_REMOVED" in result["warnings"]


@pytest.mark.asyncio
async def test_exam_studio_change_request_with_invalid_operations_returns_marked_fallback(monkeypatch):
    import app.routers.exam as exam_module

    async def fake_call_gemini_json(*, prompt: str, model: str, response_json_schema=None):
        return {
            "answerMarkdown": "변경했습니다.",
            "operations": [{"method": "removeQuestion", "params": {"id": "q1"}}],
        }

    monkeypatch.setattr(exam_module, "_call_gemini_json", fake_call_gemini_json)

    result = await run_exam_studio_chat(
        ExamStudioChatRequest(message="시험 제목을 바꿔줘", sourceText="문항 생성을 요청하지 않는 기존 시험 수정")
    )

    assert result.operations == []
    assert result.source == "FALLBACK"
    assert result.fallbackUsed is True
    assert result.reason == "VALIDATION_ERROR"
    assert "unsupported_operation:removeQuestion" in result.warnings


@pytest.mark.asyncio
async def test_criteria_suggestions_deduplicate_and_rebalance_weights(monkeypatch):
    async def fake_call_gemini_json(*, prompt: str, model: str):
        return {
            "suggestions": [
                {"label": "개념 이해도", "description": "기존 중복", "weight": 80},
                {"label": "분석력", "description": "근거를 분석한다", "weight": 10},
                {"label": "표현력", "description": "답변을 설명한다", "weight": 10},
            ]
        }

    monkeypatch.setattr(bridge_agents, "_call_gemini_json", fake_call_gemini_json)

    suggestions = await bridge_agents._criteria_suggestions(
        bridge_agents.CriteriaAssistantRequest(
            courseName="수학",
            existingCriteria=[
                bridge_agents.CriterionItem(label="개념 이해도", description="이미 있음", weight=40),
            ],
            desiredCount=3,
        )
    )
    labels = [item["label"] for item in suggestions]

    assert "개념 이해도" not in labels
    assert len(suggestions) == 3
    assert sum(item["weight"] for item in suggestions) == 100
    assert all("warnings" in item for item in suggestions)


@pytest.mark.asyncio
async def test_bridge_exam_studio_missing_context_stream_returns_done_fallback():
    response = await bridge_agents.exam_studio_chat_stream(
        bridge_agents.ExamStudioBridgeChatRequest(message="OX 문제 2개 만들어줘")
    )

    chunks = [chunk async for chunk in response.body_iterator]
    events = [
        json.loads(line)
        for chunk in chunks
        for line in chunk.decode("utf-8").splitlines()
        if line.strip()
    ]

    assert [event["type"] for event in events] == ["thought_delta", "answer_delta", "done"]
    done = events[-1]["data"]
    assert done["operations"] == []
    assert done["source"] == "FALLBACK"
    assert done["fallbackUsed"] is True
    assert done["reason"] == "MISSING_CONTEXT"
    assert done["confidence"] == "LOW"
    assert "MISSING_CONTEXT" in done["warnings"]


@pytest.mark.asyncio
async def test_tool_dispatcher_blocks_quiz_generation_without_page_context():
    class ExplodingBridge:
        async def stream(self, *args, **kwargs):
            raise AssertionError("quiz generation should not call LLM without context")

    dispatcher = ToolDispatcher(ExplodingBridge())  # type: ignore[arg-type]
    state = SessionState(session_id=1, lecture_id=1)
    plan = OrchestratorPlan(actions=[
        OrchestratorAction(
            type=ActionType.CALL_TOOL,
            tool=ToolName.GENERATE_QUIZ_OX,
            params={"quiz_type": "OX_Problem"},
        )
    ])

    events = [event async for event in dispatcher.dispatch(plan, state, {})]
    done = [event for event in events if event.type == NdjsonEventType.DONE][-1]

    assert done.data["quiz"] == []
    assert done.data["source"] == "FALLBACK"
    assert done.data["reason"] == "MISSING_CONTEXT"
    assert not state.quiz_history


@pytest.mark.asyncio
async def test_repair_failure_does_not_complete_intervention():
    class ErrorBridge:
        async def stream(self, contents, agent: str, tool: str):
            yield NdjsonEvent(
                type=NdjsonEventType.ERROR,
                agent=agent,
                tool=tool,
                code="AI_UNAVAILABLE",
                message="교정 생성 실패",
            )

    state = SessionState(
        session_id=1,
        lecture_id=1,
        active_intervention={
            "interventionId": "repair-1",
            "status": "AWAITING_USER_RESPONSE",
            "pageNumber": 1,
            "focusConcept": "분수",
        },
    )
    dispatcher = ToolDispatcher(ErrorBridge())  # type: ignore[arg-type]
    plan = OrchestratorPlan(actions=[
        OrchestratorAction(type=ActionType.CALL_TOOL, tool=ToolName.REPAIR_MISCONCEPTION)
    ])

    events = [
        event async for event in dispatcher.dispatch(
            plan,
            state,
            {"text": "모르겠어요"},
            event_type=AppEventType.USER_MESSAGE.value,
        )
    ]

    done = [event for event in events if event.type == NdjsonEventType.DONE][-1]
    assert done.data["activeIntervention"]["status"] == "AWAITING_USER_RESPONSE"
    assert done.data.get("ui") is None
    assert done.data["reason"] == "REPAIR_GENERATION_FAILED"


def _read_ndjson(response) -> list[dict]:
    return [json.loads(line) for line in response.text.splitlines() if line.strip()]


def test_bridge_auth_header_required_when_secret_configured(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    monkeypatch.setenv("AI_SECRET_KEY", "test-secret")
    from app.main import create_app

    client = TestClient(create_app())

    missing = client.post("/bridge/exam_studio/chat_stream", json={"message": "설정 확인"})
    wrong = client.post(
        "/bridge/exam_studio/chat_stream",
        json={"message": "설정 확인"},
        headers={"X-AI-SECRET-KEY": "wrong"},
    )
    ok = client.post(
        "/bridge/exam_studio/chat_stream",
        json={"message": "설정 확인"},
        headers={"X-AI-SECRET-KEY": "test-secret"},
    )

    assert missing.status_code == 401
    assert wrong.status_code == 401
    assert ok.status_code == 200
    assert _read_ndjson(ok)[-1]["type"] == "done"


def test_bridge_stream_endpoints_return_parseable_ndjson(monkeypatch):
    async def fake_discussion(req):
        return {
            "title": "토론",
            "contentMarkdown": "제가 궁금한 점은 **분수**입니다.",
            "source": "AI",
            "fallbackUsed": False,
            "reason": None,
            "confidence": "MEDIUM",
            "warnings": [],
        }

    async def fake_student_report_chat(req):
        return {
            "answer": "근거가 부족합니다.",
            "source": "FALLBACK",
            "fallbackUsed": True,
            "reason": "VALIDATION_ERROR",
            "confidence": "LOW",
            "warnings": ["VALIDATION_ERROR"],
        }

    async def fake_criteria(req):
        return [
            {
                "label": "개념 이해도",
                "description": "핵심 개념을 설명한다.",
                "weight": 100,
                "source": "AI",
                "fallbackUsed": False,
                "reason": None,
                "confidence": "MEDIUM",
                "warnings": [],
            }
        ]

    async def fake_classroom(req):
        return {
            "summaryMarkdown": "## 요약",
            "highlights": [],
            "risks": [],
            "coachingPriorities": [],
            "source": "AI",
            "fallbackUsed": False,
            "reason": None,
            "confidence": "MEDIUM",
            "warnings": [],
        }

    monkeypatch.delenv("AI_SECRET_KEY", raising=False)
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    monkeypatch.setattr(bridge_agents, "_discussion_result", fake_discussion)
    monkeypatch.setattr(bridge_agents, "answer_student_report_chat_result", fake_student_report_chat)
    monkeypatch.setattr(bridge_agents, "_criteria_suggestions", fake_criteria)
    monkeypatch.setattr(bridge_agents, "_classroom_report", fake_classroom)
    from app.main import create_app

    client = TestClient(create_app())

    cases = [
        ("/bridge/discussion_assistant_stream", {"topic": "분수 질문"}),
        ("/bridge/report/student_chat_stream", {"context": {}, "question": "약점은?"}),
        ("/bridge/report/criteria_assistant_stream", {"courseName": "수학"}),
        ("/bridge/report/classroom_analyze_stream", {"courseName": "수학", "studentReports": []}),
        ("/bridge/exam_studio/chat_stream", {"message": "OX 문제 2개 만들어줘"}),
    ]

    for path, payload in cases:
        response = client.post(path, json=payload)
        assert response.status_code == 200
        events = _read_ndjson(response)
        assert events
        assert events[-1]["type"] == "done"
        assert "data" in events[-1]

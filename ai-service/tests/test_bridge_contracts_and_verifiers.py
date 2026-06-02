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


def test_plan_verifier_drops_non_planner_tool_from_llm_plan():
    state = SessionState(session_id=1, lecture_id=1)
    plan = OrchestratorPlan(actions=[
        OrchestratorAction(
            type=ActionType.CALL_TOOL,
            tool=ToolName.WRITE_FEEDBACK_ENTRY,
            params={"feedback": "internal note"},
        ),
        OrchestratorAction(type=ActionType.CALL_TOOL, tool=ToolName.EXPLAIN_PAGE),
    ])

    result = PlanVerifier().verify(plan, state)

    assert [action.tool for action in result.plan.actions] == [ToolName.EXPLAIN_PAGE]
    assert result.warnings[0]["code"] == "UNALLOWED_TOOL_DROPPED"


def test_plan_verifier_patches_explain_followup_widget_for_event_flow():
    state = SessionState(session_id=1, lecture_id=1)
    plan = OrchestratorPlan(actions=[
        OrchestratorAction(type=ActionType.CALL_TOOL, tool=ToolName.EXPLAIN_PAGE, params={"detail": "NORMAL"}),
    ])

    page_changed = PlanVerifier().verify(
        plan,
        state,
        event_type=AppEventType.PAGE_CHANGED.value,
        event_payload={"page": 3},
    )
    assert page_changed.plan.actions[0].params["next_widget"] == "QUIZ_DECISION"

    start_decision = PlanVerifier().verify(
        plan,
        state,
        event_type=AppEventType.START_EXPLANATION_DECISION.value,
        event_payload={"accept": True},
    )
    assert start_decision.plan.actions[0].params["next_widget"] == "NEXT_PAGE_DECISION"


def test_plan_verifier_routes_general_user_message_to_qa():
    state = SessionState(session_id=1, lecture_id=1)
    plan = OrchestratorPlan(actions=[
        OrchestratorAction(
            type=ActionType.CALL_TOOL,
            tool=ToolName.EXPLAIN_PAGE,
            params={"detail": "NORMAL"},
        ),
    ])

    result = PlanVerifier().verify(
        plan,
        state,
        event_type=AppEventType.USER_MESSAGE.value,
        event_payload={"text": "tcp에서 신뢰성을 어떻게 주지?"},
    )

    assert [action.tool for action in result.plan.actions] == [ToolName.ANSWER_QUESTION]
    assert result.plan.actions[0].params["question"] == "tcp에서 신뢰성을 어떻게 주지?"
    assert result.warnings[-1]["code"] == "USER_MESSAGE_ROUTED_TO_QA"


def test_plan_verifier_allows_explicit_page_explanation_request():
    state = SessionState(session_id=1, lecture_id=1)
    plan = OrchestratorPlan(actions=[
        OrchestratorAction(
            type=ActionType.CALL_TOOL,
            tool=ToolName.EXPLAIN_PAGE,
            params={"detail": "NORMAL"},
        ),
    ])

    result = PlanVerifier().verify(
        plan,
        state,
        event_type=AppEventType.USER_MESSAGE.value,
        event_payload={"text": "현재 페이지 전체 설명해줘"},
    )

    assert [action.tool for action in result.plan.actions] == [ToolName.EXPLAIN_PAGE]


def test_plan_verifier_keeps_page_scoped_concept_explanation_as_qa():
    state = SessionState(session_id=1, lecture_id=1)
    plan = OrchestratorPlan(actions=[
        OrchestratorAction(type=ActionType.CALL_TOOL, tool=ToolName.EXPLAIN_PAGE),
    ])

    result = PlanVerifier().verify(
        plan,
        state,
        event_type=AppEventType.USER_MESSAGE.value,
        event_payload={"text": "이 페이지에서 흐름제어 설명해줘"},
    )

    assert [action.tool for action in result.plan.actions] == [ToolName.ANSWER_QUESTION]


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


def test_orchestrator_prompt_uses_json_literals_without_enum_prefixes():
    state = SessionState(session_id=1, lecture_id=1)

    prompt = Orchestrator(None)._build_prompt(  # type: ignore[arg-type]
        AppEvent(type=AppEventType.SESSION_ENTERED, payload={}),
        state,
    )

    assert "ActionType." not in prompt
    assert "ToolName." not in prompt
    assert "PedagogyMode." not in prompt
    assert '"CALL_TOOL"' in prompt
    assert '"EXPLAIN_PAGE"' in prompt


def test_orchestrator_plan_normalizes_exact_enum_prefixes_only():
    plan = OrchestratorPlan.model_validate({
        "actions": [
            {
                "type": "ActionType.CALL_TOOL",
                "tool": "ToolName.EXPLAIN_PAGE",
                "params": {"detail": "NORMAL"},
            },
            {
                "type": "ActionType.SET_UI_STATE",
                "ui_state": {"widget": "QUIZ_DECISION"},
            },
        ],
        "pedagogy_policy": {"mode": "PedagogyMode.ADVANCE"},
    })

    assert plan.actions[0].type == ActionType.CALL_TOOL
    assert plan.actions[0].tool == ToolName.EXPLAIN_PAGE
    assert plan.actions[1].type == ActionType.SET_UI_STATE
    assert plan.pedagogy_policy.mode.value == "ADVANCE"

    with pytest.raises(Exception):
        OrchestratorPlan.model_validate({
            "actions": [{"type": "Bad.CALL_TOOL", "tool": "ToolName.EXPLAIN_PAGE"}],
        })
    with pytest.raises(Exception):
        OrchestratorPlan.model_validate({
            "actions": [{"type": "ActionType.CALL_TOOL.extra", "tool": "ToolName.EXPLAIN_PAGE"}],
        })


@pytest.mark.asyncio
async def test_orchestrator_sanitizes_invalid_plan_error_and_keeps_thought():
    class FakeBridge:
        async def stream(self, contents, config, agent: str):
            yield NdjsonEvent(
                type=NdjsonEventType.AGENT_DELTA,
                agent=agent,
                channel="thought",
                delta="Analyzing the plan.",
            )
            yield NdjsonEvent(
                type=NdjsonEventType.AGENT_DELTA,
                agent=agent,
                channel="main",
                delta=json.dumps({"actions": [{"type": "Bad.CALL_TOOL", "tool": "EXPLAIN_PAGE"}]}),
            )
            yield NdjsonEvent(type=NdjsonEventType.DONE, agent=agent, final=True)

    state = SessionState(session_id=1, lecture_id=1)
    events = [
        event async for event in Orchestrator(FakeBridge()).run_stream(  # type: ignore[arg-type]
            AppEvent(type=AppEventType.SESSION_ENTERED, payload={}),
            state,
        )
    ]

    assert events[0].channel == "thought"
    assert events[0].delta == "Analyzing the plan."
    error = events[-1]
    assert error.type == NdjsonEventType.ERROR
    assert error.code == "ORCHESTRATOR_PLAN_INVALID"
    assert "Pydantic" not in error.message
    assert "Input should" not in error.message
    assert "Bad.CALL_TOOL" not in error.message


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
async def test_orchestration_engine_stops_after_planner_error_without_no_actions():
    class FakeStore:
        def __init__(self, state):
            self.state = state
            self.saved = None

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.saved = state

    class FakeBridge:
        pass

    class ErrorPlanner:
        async def run_stream(self, event, state):
            yield NdjsonEvent(
                type=NdjsonEventType.AGENT_DELTA,
                agent="orchestrator",
                channel="thought",
                delta="Checking the plan.",
            )
            yield NdjsonEvent(
                type=NdjsonEventType.ERROR,
                agent="orchestrator",
                code="ORCHESTRATOR_PLAN_INVALID",
                message="학습 계획을 생성하지 못했습니다. 다시 시도해 주세요.",
            )

    state = SessionState(session_id=1, lecture_id=1)
    store = FakeStore(state)
    engine = OrchestrationEngine(store, bridge=FakeBridge())  # type: ignore[arg-type]
    engine._orchestrator = ErrorPlanner()  # type: ignore[assignment]

    events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.SESSION_ENTERED, payload={}),
        )
    ]

    assert [event.type for event in events] == [
        NdjsonEventType.AGENT_DELTA,
        NdjsonEventType.ERROR,
    ]
    assert all(event.delta != "No actions to process." for event in events)
    assert store.saved is state


@pytest.mark.asyncio
async def test_orchestration_engine_falls_back_for_empty_initial_planner_plan():
    class FakeStore:
        def __init__(self, state):
            self.state = state

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.state = state

    class FakeBridge:
        pass

    class EmptyPlanner:
        async def run_stream(self, event, state):
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="orchestrator",
                final=True,
                data={"plan": OrchestratorPlan(actions=[]).model_dump()},
            )

    class CapturingDispatcher:
        def __init__(self):
            self.plans = []

        async def dispatch(self, plan, state, event_payload, event_type=None):
            self.plans.append(plan)
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="test",
                final=True,
                data={"actions": [action.model_dump() for action in plan.actions]},
            )

    state = SessionState(session_id=1, lecture_id=1)
    engine = OrchestrationEngine(FakeStore(state), bridge=FakeBridge())  # type: ignore[arg-type]
    engine._orchestrator = EmptyPlanner()  # type: ignore[assignment]
    dispatcher = CapturingDispatcher()
    engine._dispatcher = dispatcher  # type: ignore[assignment]

    events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.SESSION_ENTERED, payload={}),
        )
    ]

    assert not any(event.delta == "No actions to process." for event in events)
    assert dispatcher.plans[0].actions[0].type == ActionType.SET_UI_STATE
    assert dispatcher.plans[0].actions[0].ui_state == {"widget": "START_EXPLANATION_DECISION"}

    dispatcher.plans.clear()
    events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.START_EXPLANATION_DECISION, payload={}),
        )
    ]

    assert not any(event.delta == "No actions to process." for event in events)
    assert dispatcher.plans[0].actions[0].type == ActionType.CALL_TOOL
    assert dispatcher.plans[0].actions[0].tool == ToolName.EXPLAIN_PAGE


@pytest.mark.asyncio
async def test_initial_explanation_sequence_matches_reference_flow():
    class FakeStore:
        def __init__(self, state):
            self.state = state

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.state = state

    class FakeBridge:
        pass

    class EmptyPlanner:
        async def run_stream(self, event, state):
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="orchestrator",
                final=True,
                data={"plan": OrchestratorPlan(actions=[]).model_dump()},
            )

    class SequenceDispatcher:
        def __init__(self):
            self.calls = []

        async def dispatch(self, plan, state, event_payload, event_type=None):
            action = plan.actions[0]
            self.calls.append((event_type, state.current_page, action))
            if action.type == ActionType.SET_UI_STATE:
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="system",
                    final=True,
                    data={"ui": action.ui_state},
                )
                return
            page_state = state.get_current_page_state()
            page_state.explanation = f"{state.current_page}페이지 설명"
            yield NdjsonEvent(
                type=NdjsonEventType.AGENT_DELTA,
                agent="explainer",
                tool="EXPLAIN_PAGE",
                channel="main",
                delta=page_state.explanation,
            )
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="system",
                final=True,
                data={"ui": {"widget": "NEXT_PAGE_DECISION"}},
            )

    state = SessionState(session_id=1, lecture_id=1, current_page=1)
    engine = OrchestrationEngine(FakeStore(state), bridge=FakeBridge())  # type: ignore[arg-type]
    engine._orchestrator = EmptyPlanner()  # type: ignore[assignment]
    dispatcher = SequenceDispatcher()
    engine._dispatcher = dispatcher  # type: ignore[assignment]

    entered_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.SESSION_ENTERED, payload={}),
        )
    ]
    assert entered_events[-1].data["ui"] == {"widget": "START_EXPLANATION_DECISION"}

    first_page_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.START_EXPLANATION_DECISION, payload={"accept": True}),
        )
    ]
    assert any(event.delta == "1페이지 설명" for event in first_page_events)
    assert first_page_events[-1].data["ui"] == {"widget": "NEXT_PAGE_DECISION"}

    next_page_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.NEXT_PAGE_DECISION, payload={"accept": True}),
        )
    ]
    assert state.current_page == 2
    assert any(event.delta == "2페이지 설명" for event in next_page_events)
    assert next_page_events[-1].data["ui"] == {"widget": "NEXT_PAGE_DECISION"}

    assert [(event_type, page, action.type, action.tool) for event_type, page, action in dispatcher.calls] == [
        (AppEventType.SESSION_ENTERED.value, 1, ActionType.SET_UI_STATE, None),
        (AppEventType.START_EXPLANATION_DECISION.value, 1, ActionType.CALL_TOOL, ToolName.EXPLAIN_PAGE),
        (AppEventType.NEXT_PAGE_DECISION.value, 2, ActionType.CALL_TOOL, ToolName.EXPLAIN_PAGE),
    ]


@pytest.mark.asyncio
async def test_question_then_next_page_sequence_matches_reference_flow():
    class FakeStore:
        def __init__(self, state):
            self.state = state

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.state = state

    class FakeBridge:
        pass

    class EmptyPlanner:
        async def run_stream(self, event, state):
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="orchestrator",
                final=True,
                data={"plan": OrchestratorPlan(actions=[]).model_dump()},
            )

    class SequenceDispatcher:
        def __init__(self):
            self.calls = []

        async def dispatch(self, plan, state, event_payload, event_type=None):
            action = plan.actions[0]
            self.calls.append((event_type, state.current_page, action))
            if action.tool == ToolName.ANSWER_QUESTION:
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="qa",
                    tool="ANSWER_QUESTION",
                    channel="main",
                    delta="질문 답변",
                )
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="system",
                    final=True,
                    data={"ui": {"widget": "NEXT_PAGE_DECISION"}},
                )
                return
            if action.tool == ToolName.EXPLAIN_PAGE:
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="explainer",
                    tool="EXPLAIN_PAGE",
                    channel="main",
                    delta=f"{state.current_page}페이지 설명",
                )
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="system",
                    final=True,
                    data={"ui": {"widget": "NEXT_PAGE_DECISION"}},
                )

    state = SessionState(session_id=1, lecture_id=1, current_page=3)
    state.get_current_page_state().explanation = "3페이지 설명 완료"
    engine = OrchestrationEngine(FakeStore(state), bridge=FakeBridge())  # type: ignore[arg-type]
    engine._orchestrator = EmptyPlanner()  # type: ignore[assignment]
    dispatcher = SequenceDispatcher()
    engine._dispatcher = dispatcher  # type: ignore[assignment]

    qa_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.USER_MESSAGE, payload={"text": "이 개념이 뭐야?"}),
        )
    ]

    assert state.current_page == 3
    assert any(event.agent == "qa" and event.delta == "질문 답변" for event in qa_events)
    assert qa_events[-1].data["ui"] == {"widget": "NEXT_PAGE_DECISION"}

    next_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.NEXT_PAGE_DECISION, payload={"accept": True, "fromPage": 3}),
        )
    ]

    assert state.current_page == 4
    assert any(event.agent == "explainer" and event.delta == "4페이지 설명" for event in next_events)
    assert next_events[-1].data["ui"] == {"widget": "NEXT_PAGE_DECISION"}
    assert [(event_type, page, action.type, action.tool) for event_type, page, action in dispatcher.calls] == [
        (AppEventType.USER_MESSAGE.value, 3, ActionType.CALL_TOOL, ToolName.ANSWER_QUESTION),
        (AppEventType.NEXT_PAGE_DECISION.value, 4, ActionType.CALL_TOOL, ToolName.EXPLAIN_PAGE),
    ]


@pytest.mark.asyncio
async def test_chat_next_page_command_explains_next_page_without_llm_planner():
    class FakeStore:
        def __init__(self, state):
            self.state = state

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.state = state

    class FakeBridge:
        pass

    class PlannerShouldNotRun:
        async def run_stream(self, event, state):
            raise AssertionError("page navigation chat commands must bypass LLM planning")

    class CapturingDispatcher:
        def __init__(self):
            self.calls = []

        async def dispatch(self, plan, state, event_payload, event_type=None):
            action = plan.actions[0]
            self.calls.append((event_type, state.current_page, action))
            yield NdjsonEvent(
                type=NdjsonEventType.AGENT_DELTA,
                agent="explainer",
                tool="EXPLAIN_PAGE",
                channel="main",
                delta=f"{state.current_page}페이지 설명",
            )
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="system",
                final=True,
                data={"ui": {"widget": "NEXT_PAGE_DECISION"}},
            )

    state = SessionState(session_id=1, lecture_id=1, current_page=1)
    engine = OrchestrationEngine(FakeStore(state), bridge=FakeBridge())  # type: ignore[arg-type]
    engine._orchestrator = PlannerShouldNotRun()  # type: ignore[assignment]
    dispatcher = CapturingDispatcher()
    engine._dispatcher = dispatcher  # type: ignore[assignment]

    events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.USER_MESSAGE, payload={"text": "넘어가줘"}),
        )
    ]

    assert state.current_page == 2
    assert state.pages[1].status.value == "DONE"
    assert events[0].type == NdjsonEventType.NAVIGATION
    assert events[0].targetPage == 2
    assert events[0].source == "page_command"
    assert any(event.agent == "explainer" and event.delta == "2페이지 설명" for event in events)
    assert events[-1].data["ui"] == {"widget": "NEXT_PAGE_DECISION"}
    assert [(event_type, page, action.type, action.tool) for event_type, page, action in dispatcher.calls] == [
        (AppEventType.USER_MESSAGE.value, 2, ActionType.CALL_TOOL, ToolName.EXPLAIN_PAGE),
    ]


@pytest.mark.asyncio
async def test_semantic_navigation_emits_target_page_directive(monkeypatch):
    class FakeStore:
        def __init__(self, state):
            self.state = state

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.state = state

    class FakeBridge:
        pass

    class PlannerShouldNotRun:
        async def run_stream(self, event, state):
            raise AssertionError("semantic navigation must bypass LLM planning")

    class CapturingDispatcher:
        def __init__(self):
            self.calls = []

        async def dispatch(self, plan, state, event_payload, event_type=None):
            action = plan.actions[0]
            self.calls.append((event_type, state.current_page, action))
            yield NdjsonEvent(
                type=NdjsonEventType.AGENT_DELTA,
                agent="explainer",
                tool="EXPLAIN_PAGE",
                channel="main",
                delta=f"{state.current_page}페이지 설명",
            )
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="system",
                final=True,
                data={"ui": {"widget": "NEXT_PAGE_DECISION"}},
            )

    monkeypatch.setattr(
        "ai_agent.v3.engine.NavigationIntentService.pdf_context_service.read_all_pages",
        lambda pdf_path: [
            "Introduction to software engineering",
            "Requirements Engineering Process. Requirements elicitation, analysis, validation.",
            "System design and architecture",
        ],
    )

    state = SessionState(
        session_id=1,
        lecture_id=1,
        current_page=1,
        pdf_path="/tmp/lecture.pdf",
    )
    engine = OrchestrationEngine(FakeStore(state), bridge=FakeBridge())  # type: ignore[arg-type]
    engine._orchestrator = PlannerShouldNotRun()  # type: ignore[assignment]
    dispatcher = CapturingDispatcher()
    engine._dispatcher = dispatcher  # type: ignore[assignment]

    events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.USER_MESSAGE, payload={"text": "앞에서 설명한 요구공학 부분 다시 보여줘"}),
        )
    ]

    assert state.current_page == 2
    assert events[0].type == NdjsonEventType.NAVIGATION
    assert events[0].targetPage == 2
    assert events[0].source == "page_index_search"
    assert "요구공학" in events[0].reason
    assert any(event.agent == "explainer" and event.delta == "2페이지 설명" for event in events)
    assert dispatcher.calls[0][1] == 2


@pytest.mark.asyncio
async def test_user_message_page_number_updates_explanation_context_before_planning():
    class FakeStore:
        def __init__(self, state):
            self.state = state

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.state = state

    class FakeBridge:
        pass

    class PageExplainPlanner:
        def __init__(self):
            self.seen_pages = []

        async def run_stream(self, event, state):
            self.seen_pages.append(state.current_page)
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="orchestrator",
                final=True,
                data={
                    "plan": OrchestratorPlan(actions=[
                        OrchestratorAction(
                            type=ActionType.CALL_TOOL,
                            tool=ToolName.EXPLAIN_PAGE,
                            params={"detail": "NORMAL"},
                        )
                    ]).model_dump()
                },
            )

    class CapturingDispatcher:
        def __init__(self):
            self.calls = []

        async def dispatch(self, plan, state, event_payload, event_type=None):
            action = plan.actions[0]
            self.calls.append((event_type, state.current_page, action))
            yield NdjsonEvent(
                type=NdjsonEventType.AGENT_DELTA,
                agent="explainer",
                tool="EXPLAIN_PAGE",
                channel="main",
                delta=f"{state.current_page}페이지 설명",
            )
            yield NdjsonEvent(type=NdjsonEventType.DONE, agent="system", final=True, data={})

    state = SessionState(session_id=1, lecture_id=1, current_page=1)
    engine = OrchestrationEngine(FakeStore(state), bridge=FakeBridge())  # type: ignore[arg-type]
    planner = PageExplainPlanner()
    engine._orchestrator = planner  # type: ignore[assignment]
    dispatcher = CapturingDispatcher()
    engine._dispatcher = dispatcher  # type: ignore[assignment]

    events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.USER_MESSAGE, payload={"text": "3페이지 ㄱㄱㄹ", "pageNumber": 3}),
        )
    ]

    assert state.current_page == 3
    assert planner.seen_pages == [3]
    assert dispatcher.calls[0][1] == 3
    assert any(event.delta == "3페이지 설명" for event in events)


@pytest.mark.asyncio
async def test_quiz_generation_and_mcq_ox_grading_sequence_matches_reference_flow():
    class FakeStore:
        def __init__(self, state):
            self.state = state

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.state = state

    class FakeBridge:
        pass

    class EmptyPlanner:
        def __init__(self):
            self.calls = []

        async def run_stream(self, event, state):
            self.calls.append(event.type)
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="orchestrator",
                final=True,
                data={"plan": OrchestratorPlan(actions=[]).model_dump()},
            )

    class SequenceDispatcher:
        def __init__(self):
            self.calls = []

        async def dispatch(self, plan, state, event_payload, event_type=None):
            action = plan.actions[0]
            self.calls.append((event_type, state.current_page, action))
            if action.type == ActionType.SET_UI_STATE:
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="system",
                    final=True,
                    data={"ui": action.ui_state},
                )
                return
            if action.tool == ToolName.EXPLAIN_PAGE:
                page_state = state.get_current_page_state()
                page_state.explanation = f"{state.current_page}페이지 설명"
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="explainer",
                    tool="EXPLAIN_PAGE",
                    channel="main",
                    delta=page_state.explanation,
                )
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="system",
                    final=True,
                    data={"ui": {"widget": action.params.get("next_widget", "NEXT_PAGE_DECISION")}},
                )
                return
            if action.tool in {ToolName.GENERATE_QUIZ_FIVE_CHOICE, ToolName.GENERATE_QUIZ_OX}:
                quiz_type = action.params["quiz_type"]
                state.quiz_history.append(QuizRecord(
                    quiz_id="quiz-1",
                    page_number=state.current_page,
                    quiz_type=quiz_type,
                    questions=[{"prompt": "테스트 OX", "answer": {"value": "O"}}],
                ))
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="quiz",
                    tool=action.tool.value,
                    final=True,
                    data={"quiz": state.quiz_history[-1].questions, "quiz_type": quiz_type},
                )
                return
            if action.tool == ToolName.AUTO_GRADE_MCQ_OX:
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="grader",
                    tool="AUTO_GRADE_MCQ_OX",
                    final=True,
                    data={"grading": {"total_score": 1.0}, "passed": True},
                )

    state = SessionState(session_id=1, lecture_id=1, current_page=4)
    engine = OrchestrationEngine(FakeStore(state), bridge=FakeBridge())  # type: ignore[arg-type]
    planner = EmptyPlanner()
    engine._orchestrator = planner  # type: ignore[assignment]
    dispatcher = SequenceDispatcher()
    engine._dispatcher = dispatcher  # type: ignore[assignment]

    page_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.PAGE_CHANGED, payload={"page": 5}),
        )
    ]
    assert state.current_page == 5
    assert any(event.delta == "5페이지 설명" for event in page_events)
    assert page_events[-1].data["ui"] == {"widget": "QUIZ_DECISION"}

    decision_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.QUIZ_DECISION, payload={"accept": True}),
        )
    ]
    assert decision_events[-1].data["ui"] == {"modal": "QUIZ_TYPE_PICKER"}
    assert state.get_current_page_state().status.value == "QUIZ_TYPE_PENDING"

    quiz_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.QUIZ_TYPE_SELECTED, payload={"quizType": "OX"}),
        )
    ]
    assert quiz_events[-1].data["quiz_type"] == "OX_Problem"
    assert state.quiz_history[-1].quiz_type == "OX_Problem"
    assert state.get_current_page_state().status.value == "QUIZ_IN_PROGRESS"

    grade_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.QUIZ_SUBMITTED, payload={"quizType": "OX", "answers": [{"answer": "O"}]}),
        )
    ]
    assert grade_events[-1].data["passed"] is True
    assert state.current_page == 5
    assert state.get_current_page_state().status.value == "QUIZ_GRADED"

    calls_after_grading = list(dispatcher.calls)
    next_page_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.PAGE_CHANGED, payload={"page": 6}),
        )
    ]
    assert state.current_page == 6
    assert any(event.delta == "6페이지 설명" for event in next_page_events)

    assert [(event_type, page, action.type, action.tool) for event_type, page, action in calls_after_grading] == [
        (AppEventType.PAGE_CHANGED.value, 5, ActionType.CALL_TOOL, ToolName.EXPLAIN_PAGE),
        (AppEventType.QUIZ_DECISION.value, 5, ActionType.SET_UI_STATE, None),
        (AppEventType.QUIZ_TYPE_SELECTED.value, 5, ActionType.CALL_TOOL, ToolName.GENERATE_QUIZ_OX),
        (AppEventType.QUIZ_SUBMITTED.value, 5, ActionType.CALL_TOOL, ToolName.AUTO_GRADE_MCQ_OX),
    ]
    assert ToolName.EXPLAIN_PAGE not in [action.tool for _, _, action in calls_after_grading[-1:]]
    assert AppEventType.QUIZ_SUBMITTED not in planner.calls


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("selected_type", "canonical_type", "generation_tool"),
    [
        ("SHORT", "Short_Answer", ToolName.GENERATE_QUIZ_SHORT),
        ("ESSAY", "Essay", ToolName.GENERATE_QUIZ_ESSAY),
    ],
)
async def test_subjective_quiz_generation_and_grading_sequence_matches_reference_flow(
    selected_type,
    canonical_type,
    generation_tool,
):
    class FakeStore:
        def __init__(self, state):
            self.state = state

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.state = state

    class FakeBridge:
        pass

    class EmptyPlanner:
        def __init__(self):
            self.calls = []

        async def run_stream(self, event, state):
            self.calls.append(event.type)
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="orchestrator",
                final=True,
                data={"plan": OrchestratorPlan(actions=[]).model_dump()},
            )

    class SequenceDispatcher:
        def __init__(self):
            self.calls = []

        async def dispatch(self, plan, state, event_payload, event_type=None):
            action = plan.actions[0]
            self.calls.append((event_type, state.current_page, action))
            if action.type == ActionType.SET_UI_STATE:
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="system",
                    final=True,
                    data={"ui": action.ui_state},
                )
                return
            if action.tool == ToolName.EXPLAIN_PAGE:
                page_state = state.get_current_page_state()
                page_state.explanation = f"{state.current_page}페이지 설명"
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="explainer",
                    tool="EXPLAIN_PAGE",
                    channel="main",
                    delta=page_state.explanation,
                )
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="system",
                    final=True,
                    data={"ui": {"widget": action.params.get("next_widget", "NEXT_PAGE_DECISION")}},
                )
                return
            if action.tool in {ToolName.GENERATE_QUIZ_SHORT, ToolName.GENERATE_QUIZ_ESSAY}:
                quiz_type = action.params["quiz_type"]
                state.quiz_history.append(QuizRecord(
                    quiz_id="quiz-subjective-1",
                    page_number=state.current_page,
                    quiz_type=quiz_type,
                    questions=[
                        {
                            "question_content": "핵심 개념을 설명하세요.",
                            "best_answer": "핵심 개념 설명",
                            "evaluation_criteria": "강의 내용 근거 포함",
                        }
                    ],
                ))
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="quiz",
                    tool=action.tool.value,
                    final=True,
                    data={"quiz": state.quiz_history[-1].questions, "quiz_type": quiz_type},
                )
                return
            if action.tool == ToolName.GRADE_SHORT_OR_ESSAY:
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="grader",
                    tool="GRADE_SHORT_OR_ESSAY",
                    final=True,
                    data={
                        "grading": {"total_score": 0.8, "overall_feedback": "기준 점수 이상입니다."},
                        "passed": True,
                    },
                )

    state = SessionState(session_id=1, lecture_id=1, current_page=7)
    engine = OrchestrationEngine(FakeStore(state), bridge=FakeBridge())  # type: ignore[arg-type]
    planner = EmptyPlanner()
    engine._orchestrator = planner  # type: ignore[assignment]
    dispatcher = SequenceDispatcher()
    engine._dispatcher = dispatcher  # type: ignore[assignment]

    page_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.PAGE_CHANGED, payload={"page": 8}),
        )
    ]
    assert state.current_page == 8
    assert any(event.delta == "8페이지 설명" for event in page_events)
    assert page_events[-1].data["ui"] == {"widget": "QUIZ_DECISION"}

    decision_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.QUIZ_DECISION, payload={"accept": True}),
        )
    ]
    assert decision_events[-1].data["ui"] == {"modal": "QUIZ_TYPE_PICKER"}

    quiz_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.QUIZ_TYPE_SELECTED, payload={"quizType": selected_type}),
        )
    ]
    assert quiz_events[-1].data["quiz_type"] == canonical_type
    assert state.quiz_history[-1].quiz_type == canonical_type

    grade_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(
                type=AppEventType.QUIZ_SUBMITTED,
                payload={"quizType": selected_type, "answers": [{"answer": "학생 답변"}]},
            ),
        )
    ]
    assert grade_events[-1].data["passed"] is True
    assert state.current_page == 8

    calls_after_grading = list(dispatcher.calls)
    next_page_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.PAGE_CHANGED, payload={"page": 9}),
        )
    ]
    assert state.current_page == 9
    assert any(event.delta == "9페이지 설명" for event in next_page_events)

    assert [(event_type, page, action.type, action.tool) for event_type, page, action in calls_after_grading] == [
        (AppEventType.PAGE_CHANGED.value, 8, ActionType.CALL_TOOL, ToolName.EXPLAIN_PAGE),
        (AppEventType.QUIZ_DECISION.value, 8, ActionType.SET_UI_STATE, None),
        (AppEventType.QUIZ_TYPE_SELECTED.value, 8, ActionType.CALL_TOOL, generation_tool),
        (AppEventType.QUIZ_SUBMITTED.value, 8, ActionType.CALL_TOOL, ToolName.GRADE_SHORT_OR_ESSAY),
    ]
    assert ToolName.EXPLAIN_PAGE not in [action.tool for _, _, action in calls_after_grading[-1:]]
    assert AppEventType.QUIZ_SUBMITTED in planner.calls


@pytest.mark.asyncio
async def test_low_score_repair_and_retest_sequence_matches_reference_flow():
    class FakeStore:
        def __init__(self, state):
            self.state = state

        async def get_or_create(self, session_id: int, lecture_id: int):
            return self.state

        async def set(self, state):
            self.state = state

    class FakeBridge:
        pass

    class EmptyPlanner:
        def __init__(self):
            self.calls = []

        async def run_stream(self, event, state):
            self.calls.append(event.type)
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent="orchestrator",
                final=True,
                data={"plan": OrchestratorPlan(actions=[]).model_dump()},
            )

    class RepairRetestDispatcher:
        def __init__(self):
            self.calls = []
            self.grade_attempts = 0
            self.diagnosis = QuizDiagnosisService()

        async def dispatch(self, plan, state, event_payload, event_type=None):
            verification = PlanVerifier().verify(
                plan,
                state,
                event_type=event_type,
                event_payload=event_payload,
            )
            if not verification.plan.actions:
                return

            action = verification.plan.actions[0]
            self.calls.append((event_type, state.current_page, action))
            if action.type == ActionType.SET_UI_STATE:
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="system",
                    final=True,
                    data={"ui": action.ui_state},
                )
                return
            if action.tool == ToolName.AUTO_GRADE_MCQ_OX:
                record = state.quiz_history[-1]
                self.grade_attempts += 1
                if self.grade_attempts == 1:
                    user_answers = [{"answer": "X"}]
                    grading = {
                        "total_score": 0.0,
                        "results": [
                            {
                                "question_index": 0,
                                "score": 0.0,
                                "passed": False,
                                "user_answer": "X",
                                "correct_answer": "O",
                                "feedback": "전송 계층의 역할을 다시 확인해야 합니다.",
                            }
                        ],
                    }
                    assessment = self.diagnosis.record_assessment(state, record, grading, user_answers)
                    yield NdjsonEvent(
                        type=NdjsonEventType.DONE,
                        agent="grader",
                        tool="AUTO_GRADE_MCQ_OX",
                        final=True,
                        data={
                            "grading": grading,
                            "passed": False,
                            "quizAssessment": assessment,
                            "activeIntervention": state.active_intervention,
                            "ui": {"widget": "REVIEW_DECISION"},
                        },
                    )
                    return

                user_answers = [{"answer": "O"}]
                grading = {
                    "total_score": 1.0,
                    "results": [
                        {
                            "question_index": 0,
                            "score": 1.0,
                            "passed": True,
                            "user_answer": "O",
                            "correct_answer": "O",
                            "feedback": "정답입니다.",
                        }
                    ],
                }
                assessment = self.diagnosis.record_assessment(state, record, grading, user_answers)
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="grader",
                    tool="AUTO_GRADE_MCQ_OX",
                    final=True,
                    data={"grading": grading, "passed": True, "quizAssessment": assessment},
                )
                return
            if action.tool == ToolName.REPAIR_MISCONCEPTION:
                intervention = self.diagnosis.start_repair(
                    state,
                    action.params.get("student_message", ""),
                )
                completed = self.diagnosis.complete_repair(state, "전송 계층은 종단 간 데이터 전달을 담당합니다.")
                yield NdjsonEvent(
                    type=NdjsonEventType.AGENT_DELTA,
                    agent="repair",
                    tool="REPAIR_MISCONCEPTION",
                    channel="main",
                    delta="전송 계층은 종단 간 데이터 전달을 담당합니다.",
                )
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="repair",
                    tool="REPAIR_MISCONCEPTION",
                    final=True,
                    data={"activeIntervention": completed or intervention, "ui": {"widget": "RETEST_DECISION"}},
                )
                return
            if action.tool == ToolName.GENERATE_QUIZ_OX:
                quiz_type = action.params["quiz_type"]
                state.quiz_history.append(QuizRecord(
                    quiz_id="quiz-retest-1",
                    page_number=state.current_page,
                    quiz_type=quiz_type,
                    questions=[
                        {
                            "prompt": "전송 계층은 종단 간 데이터 전달을 담당한다.",
                            "answer": {"value": "O"},
                            "concepts": ["Transport Layer"],
                        }
                    ],
                ))
                yield NdjsonEvent(
                    type=NdjsonEventType.DONE,
                    agent="quiz",
                    tool="GENERATE_QUIZ_OX",
                    final=True,
                    data={"quiz": state.quiz_history[-1].questions, "quiz_type": quiz_type},
                )

    state = SessionState(session_id=1, lecture_id=1, current_page=3)
    state.get_current_page_state().explanation = "전송 계층 설명"
    state.quiz_history.append(QuizRecord(
        quiz_id="quiz-initial-1",
        page_number=3,
        quiz_type="OX_Problem",
        questions=[
            {
                "prompt": "전송 계층은 종단 간 데이터 전달을 담당한다.",
                "answer": {"value": "O"},
                "concepts": ["Transport Layer"],
            }
        ],
    ))
    engine = OrchestrationEngine(FakeStore(state), bridge=FakeBridge())  # type: ignore[arg-type]
    planner = EmptyPlanner()
    engine._orchestrator = planner  # type: ignore[assignment]
    dispatcher = RepairRetestDispatcher()
    engine._dispatcher = dispatcher  # type: ignore[assignment]

    failed_grade_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.QUIZ_SUBMITTED, payload={"quizType": "OX", "answers": [{"answer": "X"}]}),
        )
    ]
    failed_done = [event for event in failed_grade_events if event.type == NdjsonEventType.DONE][-1]
    assert failed_done.data["passed"] is False
    assert failed_done.data["ui"] == {"widget": "REVIEW_DECISION"}
    assert state.active_intervention["status"] == "AWAITING_USER_RESPONSE"
    source_artifact_id = state.active_intervention["sourceArtifactId"]

    repair_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.USER_MESSAGE, payload={"text": "어디가 틀렸는지 알려주세요"}),
        )
    ]
    assert any(event.agent == "repair" and event.channel == "main" for event in repair_events)
    repair_done = [event for event in repair_events if event.type == NdjsonEventType.DONE][-1]
    assert repair_done.data["ui"] == {"widget": "RETEST_DECISION"}
    assert state.active_intervention["status"] == "COMPLETED"
    source_assessment = next(item for item in state.quiz_assessments if item["artifactId"] == source_artifact_id)
    assert source_assessment["status"] == "REPAIR_COMPLETED"

    retest_decision_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.RETEST_DECISION, payload={"accept": True}),
        )
    ]
    assert retest_decision_events[-1].data["ui"] == {"modal": "QUIZ_TYPE_PICKER", "mode": "RETEST"}
    assert state.get_current_page_state().status.value == "QUIZ_TYPE_PENDING"

    retest_quiz_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.QUIZ_TYPE_SELECTED, payload={"quizType": "OX"}),
        )
    ]
    assert retest_quiz_events[-1].data["quiz_type"] == "OX_Problem"
    assert state.quiz_history[-1].quiz_id == "quiz-retest-1"

    passed_grade_events = [
        event async for event in engine.handle_event_stream(
            1,
            1,
            AppEvent(type=AppEventType.QUIZ_SUBMITTED, payload={"quizType": "OX", "answers": [{"answer": "O"}]}),
        )
    ]
    passed_done = [event for event in passed_grade_events if event.type == NdjsonEventType.DONE][-1]
    assert passed_done.data["passed"] is True
    assert state.active_intervention is None
    source_assessment = next(item for item in state.quiz_assessments if item["artifactId"] == source_artifact_id)
    assert source_assessment["status"] == "RESOLVED_BY_RETEST"

    assert [(event_type, page, action.type, action.tool) for event_type, page, action in dispatcher.calls] == [
        (AppEventType.QUIZ_SUBMITTED.value, 3, ActionType.CALL_TOOL, ToolName.AUTO_GRADE_MCQ_OX),
        (AppEventType.USER_MESSAGE.value, 3, ActionType.CALL_TOOL, ToolName.REPAIR_MISCONCEPTION),
        (AppEventType.RETEST_DECISION.value, 3, ActionType.SET_UI_STATE, None),
        (AppEventType.QUIZ_TYPE_SELECTED.value, 3, ActionType.CALL_TOOL, ToolName.GENERATE_QUIZ_OX),
        (AppEventType.QUIZ_SUBMITTED.value, 3, ActionType.CALL_TOOL, ToolName.AUTO_GRADE_MCQ_OX),
    ]
    assert planner.calls.count(AppEventType.QUIZ_SUBMITTED) == 0


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


@pytest.mark.asyncio
async def test_explain_page_stream_emits_single_final_done_with_next_widget():
    class FakeBridge:
        async def load_pdf_part(self, pdf_path):
            return "PDF_PART"

        async def stream(self, contents, agent="explainer", tool="EXPLAIN_PAGE", config=None):
            yield NdjsonEvent(
                type=NdjsonEventType.AGENT_DELTA,
                agent=agent,
                tool=tool,
                channel="main",
                delta="## 핵심 요지\n1페이지 설명",
            )
            yield NdjsonEvent(
                type=NdjsonEventType.DONE,
                agent=agent,
                tool=tool,
                final=True,
                data={},
            )

    dispatcher = ToolDispatcher(FakeBridge())  # type: ignore[arg-type]
    state = SessionState(session_id=1, lecture_id=1, pdf_path="/tmp/test.pdf")
    events = [
        event async for event in dispatcher.dispatch(
            OrchestratorPlan(actions=[
                OrchestratorAction(
                    type=ActionType.CALL_TOOL,
                    tool=ToolName.EXPLAIN_PAGE,
                    params={"next_widget": "NEXT_PAGE_DECISION"},
                )
            ]),
            state,
            {},
            event_type=AppEventType.START_EXPLANATION_DECISION.value,
        )
    ]

    done_events = [event for event in events if event.type == NdjsonEventType.DONE]
    assert len(done_events) == 1
    assert done_events[0].agent == "system"
    assert done_events[0].data == {"ui": {"widget": "NEXT_PAGE_DECISION"}}
    assert state.get_current_page_state().status.value == "EXPLAINED"


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
async def test_criteria_chat_returns_operation_and_protects_builtin(monkeypatch):
    async def fake_call_gemini_json(*, prompt: str, model: str, response_json_schema=None):
        return {
            "replyMarkdown": "기본 항목 삭제는 할 수 없습니다.",
            "operation": {
                "method": "deleteCriterion",
                "params": {"targetCriterionName": "개념 이해도"},
            },
            "source": "AI",
        }

    monkeypatch.setattr(bridge_agents, "_call_gemini_json", fake_call_gemini_json)

    result = await bridge_agents._criteria_chat_result(
        bridge_agents.CriteriaAssistantChatRequest(
            courseName="수학",
            message="개념 이해도 기준 삭제해줘",
            builtInCriteria=[
                bridge_agents.ReportCriterionAssistantItem(name="개념 이해도", description="기본 기준"),
            ],
        )
    )

    assert result["operation"]["method"] == "messageOnly"
    assert "BUILT_IN_CRITERION_IMMUTABLE" in result["warnings"]
    assert result["fallbackUsed"] is False


@pytest.mark.asyncio
async def test_criteria_chat_normalizes_create_criterion_operation(monkeypatch):
    async def fake_call_gemini_json(*, prompt: str, model: str, response_json_schema=None):
        return {
            "replyMarkdown": "추가 평가 항목 제안을 준비했습니다.",
            "operation": {
                "method": "createCriterion",
                "params": {
                    "criterion": {
                        "name": "질문 근거 활용 능력",
                        "description": "학생이 질문과 답변에서 개념 근거를 연결하는지 평가합니다.",
                    }
                },
            },
            "source": "AI",
        }

    monkeypatch.setattr(bridge_agents, "_call_gemini_json", fake_call_gemini_json)

    result = await bridge_agents._criteria_chat_result(
        bridge_agents.CriteriaAssistantChatRequest(
            courseName="수학",
            message="이 기준을 추가해줘",
            currentProposal={
                "name": "질문 근거 활용 능력",
                "description": "학생이 질문과 답변에서 개념 근거를 연결하는지 평가합니다.",
            },
        )
    )

    assert result["operation"]["method"] == "createCriterion"
    assert result["operation"]["params"]["criterion"]["name"] == "질문 근거 활용 능력"
    assert result["source"] == "AI"


@pytest.mark.asyncio
async def test_notice_assistant_returns_notice_operation(monkeypatch):
    async def fake_call_gemini_json(*, prompt: str, model: str, response_json_schema=None):
        return {
            "replyMarkdown": "공지 초안을 준비했습니다.",
            "title": "중간고사 안내",
            "contentMarkdown": "중간고사는 다음 주 수업 시간에 진행됩니다.",
            "operation": {
                "method": "draftNotice",
                "params": {
                    "notice": {
                        "title": "중간고사 안내",
                        "contentMarkdown": "중간고사는 다음 주 수업 시간에 진행됩니다.",
                        "pinned": True,
                        "status": "DRAFT",
                    }
                },
            },
            "source": "AI",
        }

    monkeypatch.setattr(bridge_agents, "_call_gemini_json", fake_call_gemini_json)

    result = await bridge_agents._notice_result(
        bridge_agents.NoticeAssistantRequest(courseName="운영체제", message="중간고사 공지 초안 만들어줘")
    )

    assert result["title"] == "중간고사 안내"
    assert result["operation"]["method"] == "draftNotice"
    assert result["operation"]["params"]["notice"]["pinned"] is True
    assert result["fallbackUsed"] is False


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

    async def fake_criteria_chat(req):
        return {
            "replyMarkdown": "평가 항목 초안을 준비했습니다.",
            "operation": {
                "method": "draftCriterion",
                "params": {"criterion": {"name": "근거 활용", "description": "근거를 활용한다."}},
            },
            "source": "AI",
            "fallbackUsed": False,
            "reason": None,
            "confidence": "MEDIUM",
            "warnings": [],
        }

    async def fake_notice(req):
        return {
            "replyMarkdown": "공지 초안을 준비했습니다.",
            "title": "수업 공지",
            "contentMarkdown": "다음 수업 전 자료를 확인해 주세요.",
            "operation": {
                "method": "draftNotice",
                "params": {
                    "notice": {
                        "title": "수업 공지",
                        "contentMarkdown": "다음 수업 전 자료를 확인해 주세요.",
                        "status": "DRAFT",
                    }
                },
            },
            "source": "AI",
            "fallbackUsed": False,
            "reason": None,
            "confidence": "MEDIUM",
            "warnings": [],
        }

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
    monkeypatch.setattr(bridge_agents, "_criteria_chat_result", fake_criteria_chat)
    monkeypatch.setattr(bridge_agents, "_notice_result", fake_notice)
    monkeypatch.setattr(bridge_agents, "_classroom_report", fake_classroom)
    from app.main import create_app

    client = TestClient(create_app())

    cases = [
        ("/bridge/discussion_assistant_stream", {"topic": "분수 질문"}),
        ("/bridge/notice_assistant_stream", {"message": "공지 초안 만들어줘"}),
        ("/bridge/report/student_chat_stream", {"context": {}, "question": "약점은?"}),
        ("/bridge/report/criteria_assistant_stream", {"courseName": "수학"}),
        ("/bridge/report/criteria_assistant_chat_stream", {"message": "평가 기준 하나 추천해줘"}),
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

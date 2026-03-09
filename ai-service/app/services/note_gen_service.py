# ai-service/app/services/note_gen_service.py
import os
import json
import asyncio
import traceback
from typing import Any, Dict

from ai_agent.LectureContentGenerator.agents.phase1_planning import execute_phase1
from ai_agent.LectureContentGenerator.agents.phase2_briefing import execute_phase2
from ai_agent.LectureContentGenerator.agents.phase3_research import execute_phase3_async
from ai_agent.LectureContentGenerator.agents.phase4_review import execute_phase4_async
from ai_agent.LectureContentGenerator.agents.phase5_assembly import execute_phase5
from app.core.redis_client import redis_manager


async def get_finalized_brief_from_cache(session_id: str) -> Dict[str, Any]:
    """백엔드가 넣어둔 기획안(finalized_brief)을 Redis에서 직접 꺼내옴."""
    redis = redis_manager.get_client()
    cache_key = f"finalized_brief:{session_id}"
    cached = await redis.get(cache_key)

    if cached:
        return json.loads(cached)
    raise ValueError(f"Redis에서 session_id {session_id}의 기획안을 찾을 수 없습니다.")


async def publish_progress(session_id: str, progress: int, message: str, phase: str) -> None:
    """진행 상황을 Redis Pub/Sub 채널로 실시간 방송."""
    redis = redis_manager.get_client()
    channel = f"progress:session:{session_id}"

    progress_data = {
        "progress": progress,
        "message": message,
        "phase": phase,
    }
    await redis.publish(channel, json.dumps(progress_data, ensure_ascii=False))


async def generate_lecture_note(topic: str, audience: str = "University Students") -> str:
    """
    LectureContentGenerator의 전체 파이프라인을 실행합니다.
    API 요청에 맞춰 대화형 과정을 생략하고 자동 모드로 진행합니다.
    
    Args:
        topic: 생성할 강의 주제
        audience: 대상 독자 수준 (기본값: "University Students")
    
    Returns:
        str: 생성된 강의 자료 마크다운 텍스트
    """
    print(f"[NoteGen] Starting generation for topic: {topic}, audience: {audience}")

    try:
        # Phase 1: Planning (API 모드: 자동 진행)
        print("[NoteGen] Phase 1: Planning...")
        # topic과 audience를 결합하여 입력으로 사용
        user_input = f"주제: {topic}\n대상 독자: {audience}"
        draft_plan = execute_phase1(topic=user_input, auto_mode=True)
        
        if not draft_plan:
            raise RuntimeError("Phase 1 실패: 기획안을 생성할 수 없습니다.")
        
        print(f"[NoteGen] Phase 1 완료: {draft_plan.get('project_meta', {}).get('title', 'Untitled')}")
        
        # Phase 2: Briefing (API 모드: 자동 승인)
        print("[NoteGen] Phase 2: Briefing...")
        finalized_brief = execute_phase2(draft_plan, auto_mode=True)
        
        if not finalized_brief:
            raise RuntimeError("Phase 2 실패: 기획안 확정에 실패했습니다.")
        
        print("[NoteGen] Phase 2 완료: 기획안 확정")
        
        # Phase 3: Research & Writing (병렬 처리)
        print("[NoteGen] Phase 3: Research & Writing (병렬 처리)...")
        chapter_contents = await execute_phase3_async(finalized_brief)
        
        if not chapter_contents:
            raise RuntimeError("Phase 3 실패: 챕터 내용을 생성할 수 없습니다.")
        
        print(f"[NoteGen] Phase 3 완료: {len(chapter_contents)}개 챕터 생성")
        
        # Phase 4: Review (병렬 처리)
        print("[NoteGen] Phase 4: Review (병렬 처리)...")
        verified_contents = await execute_phase4_async(chapter_contents, finalized_brief)
        
        if not verified_contents:
            raise RuntimeError("Phase 4 실패: 검증에 실패했습니다.")
        
        print(f"[NoteGen] Phase 4 완료: {len(verified_contents)}개 챕터 검증")
        
        # Phase 5: Assembly
        print("[NoteGen] Phase 5: Assembly...")
        final_md = execute_phase5(verified_contents)
        
        if not final_md:
            raise RuntimeError("Phase 5 실패: 최종 문서 조립에 실패했습니다.")
        
        print("[NoteGen] 모든 단계 완료!")
        return final_md

    except Exception as e:
        print(f"[NoteGen] Error: {e}")
        traceback.print_exc()
        raise e


async def run_phase1(topic: str, audience_level: str) -> Dict[str, Any]:
    """
    Phase 1: 기획안 생성 (API용 래퍼)
    execute_phase1은 동기 LLM 호출이므로 스레드 풀에서 실행해 이벤트 루프 차단 방지.
    """
    print(f"[NoteGen] Phase 1: Planning... topic={topic}, audience={audience_level}")
    user_input = f"주제: {topic}\n대상 독자: {audience_level}"
    draft_plan = await asyncio.to_thread(execute_phase1, topic=user_input, auto_mode=True)

    if not draft_plan:
        raise RuntimeError("Phase 1 실패: 기획안을 생성할 수 없습니다.")

    return draft_plan


async def run_phase2(draft_plan: Dict[str, Any], user_feedback: str) -> Dict[str, Any]:
    """
    Phase 2: 피드백 반영 및 기획안 확정 (API용 래퍼)
    execute_phase2는 동기 LLM 호출이므로 스레드 풀에서 실행해 이벤트 루프 차단 방지.
    """
    print("[NoteGen] Phase 2: Briefing with user feedback...")
    finalized_brief = await asyncio.to_thread(
        execute_phase2, draft_plan, True, user_feedback
    )

    if not finalized_brief:
        raise RuntimeError("Phase 2 실패: 기획안 확정에 실패했습니다.")

    return finalized_brief


async def run_phase3_to_5_task(session_id: str):
    """
    Phase 3~5: 비동기 집필 및 조립 태스크

    - Phase 3: Research & Writing
    - Phase 4: Review
    - Phase 5: Assembly
    """
    try:
        # 1. API 요청 바디 대신 Redis에서 기획안 읽어오기
        finalized_brief = await get_finalized_brief_from_cache(session_id)

        async def _progress_hook(progress: int, message: str, phase: str) -> None:
            await publish_progress(session_id, progress, message, phase)

        # Phase 3: 집필 (가장 오래 걸림)
        await publish_progress(
            session_id,
            30,
            "본문 집필 중 (AI 병렬 처리)...",
            "Phase 3",
        )
        chapter_contents = await execute_phase3_async(finalized_brief, progress_hook=_progress_hook)
        
        if not chapter_contents:
            raise RuntimeError("Phase 3 실패: 챕터 내용을 생성할 수 없습니다.")

        # Phase 4: 검토
        await publish_progress(
            session_id,
            80,
            "내용 검증 및 수정 중...",
            "Phase 4",
        )
        verified_contents = await execute_phase4_async(
            chapter_contents,
            finalized_brief,
            progress_hook=_progress_hook,
        )
        
        if not verified_contents:
            raise RuntimeError("Phase 4 실패: 검증에 실패했습니다.")

        # Phase 5: 조립
        await publish_progress(
            session_id,
            90,
            "최종 문서 생성 중...",
            "Phase 5",
        )
        final_md = execute_phase5(verified_contents)
        
        if not final_md:
            raise RuntimeError("Phase 5 실패: 최종 문서 조립에 실패했습니다.")

        # 완료 방송
        await publish_progress(
            session_id,
            100,
            "모든 작업 완료!",
            "Completed",
        )

        # 최종 결과는 별도 키로 저장해 백엔드가 조회할 수 있게 함
        redis = redis_manager.get_client()
        await redis.set(f"final_result:{session_id}", final_md, ex=86400)
        print(f"[NoteGen] Session {session_id} completed successfully (Phase 3~5)")

    except Exception as e:
        print(f"[Task Error] session {session_id}: {e}")
        traceback.print_exc()
        try:
            await publish_progress(
                session_id,
                0,
                f"작업 실패: {str(e)}",
                "Error",
            )
        except Exception as redis_error:
            print(f"[Redis] Failed to publish error status: {redis_error}")

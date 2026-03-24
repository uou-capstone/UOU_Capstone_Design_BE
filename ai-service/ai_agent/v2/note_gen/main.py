import sys
import os
import asyncio
import json
from .agents.phase1_planning import execute_phase1
from .agents.phase2_briefing import execute_phase2
from .agents.phase3_research import execute_phase3_async
from .agents.phase4_review import execute_phase4_async
from .agents.phase5_assembly import execute_phase5


async def run_interactive_pipeline():
    """
    통합 실행 파이프라인: 순차(대화) 구간과 병렬(작업) 구간을 명확히 분리
    
    [순차 구간]: Phase 1, 2 (사용자 대화 필요)
    [병렬 구간]: Phase 3, 4 (AI 자동 처리)
    """
    print("="*60)
    print("🎓 강의 자료 생성 에이전트를 시작합니다.")
    print("="*60)
    
    # ============================================================
    # [Section 1] 순차적 상호작용 (Sequential) - 사용자 대화 필요
    # ============================================================
    print("\n" + "="*60)
    print("[Section 1] 순차적 상호작용 단계 (Phase 1-2)")
    print("="*60)
    
    # --- Phase 1: Planning ---
    print("\n[Phase 1] 기획 단계를 시작합니다...")
    try:
        draft_plan = execute_phase1()
        print(f"\n✅ 기획안 초안 생성 완료: {draft_plan.get('project_meta', {}).get('title', 'Untitled')}")
    except KeyboardInterrupt:
        print("\n[System] 사용자에 의해 중단되었습니다.")
        return
    except Exception as e:
        print(f"\n[Error] Phase 1 실패: {e}")
        return

    # --- Phase 2: Briefing ---
    print("\n[Phase 2] 기획 검토 및 확정 단계를 시작합니다...")
    try:
        finalized_brief = execute_phase2(draft_plan)
        print(f"\n✅ 기획안 확정 완료")
    except KeyboardInterrupt:
        print("\n[System] 사용자에 의해 중단되었습니다.")
        return
    except Exception as e:
        print(f"\n[Error] Phase 2 실패: {e}")
        return

    # ============================================================
    # [Section 2] 고속 병렬 처리 (Parallel) - AI 자동 처리
    # ============================================================
    print("\n" + "="*60)
    print("[Section 2] 병렬 처리 단계 (Phase 3-4)")
    print("="*60)
    print("이제 사용자는 기다리기만 하면 됩니다. 모든 챕터가 동시에 처리됩니다.")
    print("="*60)
    
    # --- Phase 3: Research & Writing (병렬) ---
    print("\n[Phase 3] 심층 조사 및 집필을 시작합니다 (병렬 처리)...")
    try:
        chapter_contents = await execute_phase3_async(finalized_brief)
        print(f"\n✅ {len(chapter_contents)}개 챕터 집필 완료")
    except Exception as e:
        print(f"\n[Error] Phase 3 실패: {e}")
        import traceback
        traceback.print_exc()
        return

    # --- Phase 4: Review (병렬) ---
    print("\n[Phase 4] 검증 및 수정 단계를 시작합니다 (병렬 처리)...")
    try:
        verified_contents = await execute_phase4_async(chapter_contents, finalized_brief)
        print(f"\n✅ {len(verified_contents)}개 챕터 검증 완료")
    except Exception as e:
        print(f"\n[Error] Phase 4 실패: {e}")
        import traceback
        traceback.print_exc()
        return

    # ============================================================
    # [Section 3] 최종 조립 및 저장
    # ============================================================
    print("\n" + "="*60)
    print("[Section 3] 최종 조립 단계 (Phase 5)")
    print("="*60)
    
    # --- Phase 5: Assembly ---
    print("\n[Phase 5] 최종 문서 조립을 시작합니다...")
    try:
        final_md = execute_phase5(verified_contents)
    except Exception as e:
        print(f"\n[Error] Phase 5 실패: {e}")
        return
    
    # 결과 저장
    output_dir = "generated_lectures"
    os.makedirs(output_dir, exist_ok=True)
    
    title = finalized_brief.get('project_meta', {}).get('title', 'Untitled')
    filename = f"{title.replace(' ', '_').replace('/', '_')}.md"
    filepath = os.path.join(output_dir, filename)
    
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(final_md)
        
    print(f"\n🎉 모든 작업이 완료되었습니다!")
    print(f"📄 결과물: {filepath}")
    print("="*60)


def main():
    """엔트리 포인트: Windows 환경 이슈 대비"""
    # Windows 환경에서 asyncio 이벤트 루프 정책 설정
    if os.name == 'nt':
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    
    try:
        asyncio.run(run_interactive_pipeline())
    except KeyboardInterrupt:
        print("\n[System] 사용자에 의해 중단되었습니다.")
        sys.exit(0)
    except Exception as e:
        print(f"\n[Fatal Error] {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()

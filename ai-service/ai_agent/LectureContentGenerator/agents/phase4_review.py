import json
import asyncio
from google.genai import types
from . import DEFAULT_MODEL, gemini_client
from ..prompts import REVIEW_SYSTEM_PROMPT, EDITOR_SYSTEM_PROMPT
from ..schemas import REVIEW_SCHEMA


class ReviewerAgent:
    def __init__(self, model_name=DEFAULT_MODEL):
        self.client = gemini_client
        self.model_name = model_name

    async def review_async(self, chapter_content, finalized_brief):
        """비동기 검토 작업"""
        prompt = f"""
        [Content to Review]
        {chapter_content}

        [Finalized Brief (Criteria)]
        {json.dumps(finalized_brief, ensure_ascii=False)}
        """
        try:
            config = types.GenerateContentConfig(
                system_instruction=REVIEW_SYSTEM_PROMPT,
                response_mime_type="application/json",
                response_schema=REVIEW_SCHEMA,
            )
            response = await self.client.aio.models.generate_content(
                model=self.model_name,
                contents=prompt,
                config=config,
            )
            return json.loads(response.text)
        except Exception as e:
            print(f"[Review Error] {e}")
            return None


class EditorAgent:
    def __init__(self, model_name=DEFAULT_MODEL):
        self.client = gemini_client
        self.model_name = model_name

    async def rewrite_async(self, original_markdown, editor_prompt):
        """비동기 수정 작업"""
        input_data = {
            "original_markdown": original_markdown,
            "editor_prompt": editor_prompt
        }
        prompt = f"""
        [Input Data]
        {json.dumps(input_data, ensure_ascii=False)}
        """
        try:
            config = types.GenerateContentConfig(
                system_instruction=EDITOR_SYSTEM_PROMPT,
            )
            response = await self.client.aio.models.generate_content(
                model=self.model_name,
                contents=prompt,
                config=config,
            )
            return response.text
        except Exception as e:
            print(f"[Editor Error] {e}")
            return original_markdown  # Fail-safe: return original if edit fails


async def _process_single_chapter_review(chapter_content, chapter_index, total_chapters, finalized_brief, semaphore):
    """단일 챕터 검증 및 수정 (비동기)"""
    # 🚦 세마포어 적용: 동시 실행 제한
    async with semaphore:
        try:
            # 약간의 딜레이를 주어 API 호출 폭주를 분산시킴
            await asyncio.sleep(0.2)
            
            reviewer = ReviewerAgent()
            editor = EditorAgent()
            
            print(f"\n[Checking Chapter {chapter_index + 1} / {total_chapters}]...")
            
            review_result = await reviewer.review_async(chapter_content, finalized_brief)
            
            if not review_result:
                print("  > Review failed (API Error). Keeping original.")
                return chapter_content
                
            if review_result.get('is_pass'):
                print("  > Pass (✅)")
                return chapter_content
            else:
                reason = review_result.get('reasoning', 'No reason provided')
                edit_prompt = review_result.get('editor_prompt', '')
                print(f"  > Fail (❌) - Reason: {reason}")
                print(f"  > Rewriting content...")

                revised_chapter = await editor.rewrite_async(chapter_content, edit_prompt)
                print("  > Rewrite Complete.")
                return revised_chapter
                
        except Exception as e:
            print(f"  ⚠️ [Chapter {chapter_index + 1} 검증 에러] {e}")
            # 에러 발생 시 원본 내용 반환 (Fail-safe)
            return chapter_content


async def execute_phase4_async(chapter_content_list, finalized_brief):
    """Phase 4 실행: 챕터별 검증 및 수정 (병렬 처리)"""
    if not chapter_content_list or not finalized_brief:
        print("Error: Missing input data.")
        return []

    # 🚦 Phase 4 세마포어 (API Rate Limit 방어)
    # 유료 플랜 사용 시 10-15 정도로 설정 가능
    semaphore = asyncio.Semaphore(12)

    print(f"🚀 [Phase 4] {len(chapter_content_list)}개 챕터 병렬 검증 시작 (최대 12개 동시 실행)...")
    
    # 모든 챕터에 대한 Task 생성 및 동시 실행
    tasks = [
        _process_single_chapter_review(
            chapter_content, 
            i, 
            len(chapter_content_list), 
            finalized_brief,
            semaphore
        )
        for i, chapter_content in enumerate(chapter_content_list)
    ]
    
    # 🛡️ 하나가 죽어도 나머지는 살린다 (return_exceptions=True)
    results = await asyncio.gather(*tasks, return_exceptions=True)
    
    # 에러 처리 및 결과 정리
    verified_content = []
    error_count = 0
    
    for i, res in enumerate(results):
        if isinstance(res, Exception):
            print(f"⚠️ [Critical Error] Chapter {i+1}: {res}")
            # 에러 발생 시 원본 내용 사용 (Fail-safe)
            verified_content.append(chapter_content_list[i])
            error_count += 1
        else:
            verified_content.append(res)
    
    success_count = len(chapter_content_list) - error_count
    print(f"\n✅ [Phase 4] 완료: {success_count}개 성공, {error_count}개 실패")
    
    return verified_content


# 하위 호환성을 위한 동기 래퍼 함수
def execute_phase4(chapter_content_list, finalized_brief):
    """Phase 4 실행 (동기 래퍼)"""
    return asyncio.run(execute_phase4_async(chapter_content_list, finalized_brief))

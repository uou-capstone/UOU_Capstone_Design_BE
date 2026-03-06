import json
import asyncio
from google.genai import types
from . import DEFAULT_MODEL, gemini_client
from ..prompts import (
    DECOMPOSITION_SYSTEM_PROMPT,
    VALIDATION_SYSTEM_PROMPT,
    WRITE_SYSTEM_PROMPT
)
from ..schemas import (
    DECOMPOSITION_SCHEMA,
    VALIDATION_SCHEMA
)


class DecompositionAgent:
    def __init__(self, model_name=DEFAULT_MODEL):
        self.client = gemini_client
        self.model_name = model_name

    async def decompose_async(self, chapter_info, finalized_brief):
        """비동기 분해 작업"""
        prompt = f"""
        [Chapter Info]
        {json.dumps(chapter_info, ensure_ascii=False)}

        [Finalized Brief]
        {json.dumps(finalized_brief, ensure_ascii=False)}
        """
        try:
            config = types.GenerateContentConfig(
                system_instruction=DECOMPOSITION_SYSTEM_PROMPT,
                response_mime_type="application/json",
                response_schema=DECOMPOSITION_SCHEMA,
            )
            response = await self.client.aio.models.generate_content(
                model=self.model_name,
                contents=prompt,
                config=config,
            )
            return json.loads(response.text)
        except Exception as e:
            print(f"[Decompose Error] {e}")
            return None


class ValidationAgent:
    def __init__(self, model_name=DEFAULT_MODEL):
        self.client = gemini_client
        self.model_name = model_name

    async def validate_async(self, full_chapter_plan, current_target_id, search_results_text, finalized_brief):
        """비동기 검증 작업"""
        prompt = f"""
        [full_chapter_plan]
        {json.dumps(full_chapter_plan, ensure_ascii=False)}

        [current_target_id]
        {current_target_id}

        [search_results]
        {search_results_text}

        [final_brief]
        {json.dumps(finalized_brief, ensure_ascii=False)}
        """
        try:
            config = types.GenerateContentConfig(
                system_instruction=VALIDATION_SYSTEM_PROMPT,
                response_mime_type="application/json",
                response_schema=VALIDATION_SCHEMA,
            )
            response = await self.client.aio.models.generate_content(
                model=self.model_name,
                contents=prompt,
                config=config,
            )
            return json.loads(response.text)
        except Exception as e:
            print(f"[Validate Error] {e}")
            return None


class WriteAgent:
    def __init__(self, model_name=DEFAULT_MODEL):
        self.client = gemini_client
        self.model_name = model_name

    async def draft_section_async(self, topic_info, search_results_text, style_guide):
        """비동기 작성 작업"""
        input_data = {
            "topic": topic_info,
            "search_results": search_results_text,
            "style_guide": style_guide
        }
        prompt = f"""
        [Input Data]
        {json.dumps(input_data, ensure_ascii=False)}
        """
        try:
            config = types.GenerateContentConfig(
                system_instruction=WRITE_SYSTEM_PROMPT,
            )
            response = await asyncio.to_thread(
                self.client.models.generate_content,
                model=self.model_name,
                contents=prompt,
                config=config,
            )
            return response.text
        except Exception as e:
            print(f"[Write Error] {e}")
            return "(Failed to write section)"


class SearchAgent:
    """Gemini 모델의 Built-in Google Search 기능을 사용하여 검색을 수행하는 Agent"""
    def __init__(self, model_name=DEFAULT_MODEL):
        self.client = gemini_client
        self.model_name = model_name

    async def search_async(self, query):
        """비동기 검색 작업"""
        try:
            response = await self.client.aio.models.generate_content(
                model=self.model_name,
                contents=query,
                config=types.GenerateContentConfig(
                    tools=[types.Tool(google_search=types.GoogleSearch())],
                    response_modalities=["TEXT"]
                )
            )
            return response.text
        except Exception as e:
            print(f"[Search Error] {e}")
            return "(Search Failed)"


async def run_deep_research_async(chapter_info, finalized_brief, semaphore):
    """하나의 챕터에 대해: 분해 -> (검색 -> 검증 -> 재검색) -> 작성 과정을 수행 (비동기)"""
    # 🚦 세마포어 적용: 동시 실행 제한
    async with semaphore:
        try:
            print(f"▶ Starting Research for Chapter {chapter_info['id']}: {chapter_info['title']}")
            
            # 약간의 딜레이를 주어 API 호출 폭주를 분산시킴
            await asyncio.sleep(0.3)
            
            decomp_agent = DecompositionAgent()
            search_agent = SearchAgent()
            valid_agent = ValidationAgent()
            write_agent = WriteAgent()
            
            search_plan = await decomp_agent.decompose_async(chapter_info, finalized_brief)
            if not search_plan:
                error_msg = f"### Chapter {chapter_info['id']}: {chapter_info['title']}\n\n> ⚠️ 분해 실패: 챕터 구조를 분석할 수 없습니다."
                print(f"  ❌ [분해 실패] Chapter {chapter_info['id']}")
                return error_msg
            
            print(f"  - Decomposition Complete: {len(search_plan['sub_topics'])} sub-topics.")
            
            full_chapter_content = []
            
            for sub_topic in search_plan['sub_topics']:
                sub_id = sub_topic['sub_topic_id']
                sub_name = sub_topic['sub_topic_name']
                print(f"  > Processing Sub-topic {sub_id}: {sub_name}")
                
                current_query = sub_topic['search_action']['query_prompt']
                is_satisfied = False
                loop_count = 0
                MAX_LOOP = 2
                
                final_search_result = ""
                
                while not is_satisfied and loop_count < MAX_LOOP:
                    loop_count += 1
                    print(f"    [Loop {loop_count}] Searching: {current_query[:50]}...")
                    
                    try:
                        search_result_text = await search_agent.search_async(current_query)
                        
                        validation = await valid_agent.validate_async(
                            full_chapter_plan=search_plan,
                            current_target_id=sub_id,
                            search_results_text=search_result_text,
                            finalized_brief=finalized_brief
                        )
                        
                        if validation and validation['pass']:
                            print("    [Pass] Validation Successful.")
                            is_satisfied = True
                            final_search_result = search_result_text
                        else:
                            if validation and validation.get('feedback') and loop_count < MAX_LOOP:
                                suggested_queries = validation['feedback'].get('suggested_new_query', [])
                                if suggested_queries:
                                    current_query = suggested_queries[0]
                                    print(f"    [Fail] Retrying with new query: {current_query}")
                                    await asyncio.sleep(3)
                                else:
                                    final_search_result = search_result_text
                                    break
                            else:
                                final_search_result = search_result_text
                                break
                    except Exception as e:
                        print(f"    ⚠️ [Sub-topic {sub_id} 검색/검증 에러] {e}")
                        final_search_result = f"(검색 실패: {str(e)})"
                        break
                        
                print(f"    Writing Draft for Sub-topic {sub_id}...")
                topic_info_for_writer = {
                    "chapter_id": chapter_info['id'],
                    "chapter_title": chapter_info['title'],
                    "sub_topic_id": sub_id,
                    "sub_topic_name": sub_name,
                    "required_contents": sub_topic['required_contents']
                }
                
                try:
                    draft = await write_agent.draft_section_async(
                        topic_info=topic_info_for_writer,
                        search_results_text=final_search_result,
                        style_guide=finalized_brief.get('style_guide', {})
                    )
                    full_chapter_content.append(draft)
                except Exception as e:
                    print(f"    ⚠️ [Sub-topic {sub_id} 작성 에러] {e}")
                    error_draft = f"### {sub_name}\n\n> ⚠️ 작성 실패: {str(e)}"
                    full_chapter_content.append(error_draft)
            
            print(f"◀ Chapter {chapter_info['id']} Complete.")
            return "\n\n".join(full_chapter_content) if full_chapter_content else f"### Chapter {chapter_info['id']}: {chapter_info['title']}\n\n> ⚠️ 내용 생성 실패"
            
        except Exception as e:
            error_msg = f"### Chapter {chapter_info['id']}: {chapter_info['title']}\n\n> ❌ 생성 실패: {str(e)}"
            print(f"  ❌ [Critical Error] Chapter {chapter_info['id']}: {e}")
            return error_msg


async def execute_phase3_async(finalized_brief):
    """Phase 3 실행: 모든 챕터에 대해 병렬로 심층 연구 수행 (비동기)"""
    if not isinstance(finalized_brief, dict) or 'chapters' not in finalized_brief:
        print("Error: Valid Finalized Brief dictionary is required.")
        return []
    
    chapters = finalized_brief['chapters']
    
    # 🚦 Phase 3 세마포어 (API Rate Limit 방어)
    # 유료 플랜 사용 시 10-15 정도로 설정 가능
    semaphore = asyncio.Semaphore(3)
    
    print(f"🚀 [Phase 3] {len(chapters)}개 챕터 동시 집필 시작 (최대 3개 동시 실행)...")
    
    # 모든 챕터에 대한 Task 생성 및 동시 실행
    tasks = [run_deep_research_async(chapter, finalized_brief, semaphore) for chapter in chapters]
    
    # 🛡️ 하나가 죽어도 나머지는 살린다 (return_exceptions=True)
    results = await asyncio.gather(*tasks, return_exceptions=True)
    
    # 에러 처리 및 결과 정리
    valid_results = []
    error_count = 0
    
    for i, res in enumerate(results):
        chapter_id = chapters[i]['id']
        chapter_title = chapters[i].get('title', 'Unknown')
        
        if isinstance(res, Exception):
            print(f"⚠️ [Critical Error] Chapter {chapter_id} ({chapter_title}): {res}")
            error_content = f"### Chapter {chapter_id}: {chapter_title}\n\n> ❌ 시스템 에러: {str(res)}"
            valid_results.append((chapter_id, error_content))
            error_count += 1
        elif isinstance(res, str):
            # 정상적으로 문자열 반환 (에러 메시지 포함 가능)
            valid_results.append((chapter_id, res))
            if "실패" in res or "Error" in res or "에러" in res:
                error_count += 1
    
    # 챕터 ID 순서대로 정렬하여 반환
    sorted_results = sorted(valid_results, key=lambda x: x[0])
    
    success_count = len(chapters) - error_count
    print(f"\n✅ [Phase 3] 완료: {success_count}개 성공, {error_count}개 실패")
    
    return [content for _, content in sorted_results]


# 하위 호환성을 위한 동기 래퍼 함수
def execute_phase3(finalized_brief):
    """Phase 3 실행 (동기 래퍼)"""
    return asyncio.run(execute_phase3_async(finalized_brief))

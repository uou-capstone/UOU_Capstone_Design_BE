def execute_phase5(verified_content_list):
    """
    Phase 5 실행: 검증된 챕터들을 하나의 문서로 통합
    
    Args:
        verified_content_list: 검증된 챕터 내용 리스트 (Markdown 문자열 리스트)
    
    Returns:
        str: 최종 통합된 Markdown 문서
    """
    final_document = ""

    print(f"Assembling {len(verified_content_list)} chapters into final document...")
    
    for i, content in enumerate(verified_content_list):
        final_document += content + "\n\n---\n\n"
        print(f"  > Chapter {i+1} added.")
        
    return final_document

"""
프롬프트 유틸리티: JSON 스키마 동적 주입
Pydantic 모델의 .model_json_schema()를 사용하여 프롬프트에 스키마 주입
"""
from typing import Type
from pydantic import BaseModel


def inject_schema(prompt_template: str, schema_model: Type[BaseModel]) -> str:
    """
    프롬프트 템플릿의 {json_schema} placeholder를 실제 JSON 스키마로 교체
    
    Args:
        prompt_template: {json_schema} placeholder가 포함된 프롬프트 템플릿
        schema_model: Pydantic 모델 클래스
    
    Returns:
        str: 스키마가 주입된 프롬프트
    """
    json_schema = schema_model.model_json_schema()
    # JSON 스키마를 문자열로 변환 (들여쓰기 포함)
    import json
    schema_str = json.dumps(json_schema, indent=2, ensure_ascii=False)
    
    return prompt_template.replace("{json_schema}", schema_str)

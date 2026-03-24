# 모든 JSON 스키마 모음

# ===== Phase 1: Planning =====
PLANNING_SCHEMA = {
  "type": "object",
  "properties": {
    "has_missing_info": {"type": "boolean"},
    "missing_info_list": {"type": "array", "items": {"type": "string"}},
    "draft_plan": {
      "type": "object",
      "properties": {
        "project_meta": {
          "type": "object",
          "properties": {
            "title": {"type": "string"},
            "target_audience": {"type": "string"},
            "goal": {"type": "string"}
          },
          "required": ["title", "target_audience", "goal"]
        },
        "style_guide": {
          "type": "object",
          "properties": {
            "tone": {"type": "string"},
            "detail_level": {"type": "string", "nullable": True},
            "math_policy": {"type": "string"},
            "example_policy": {"type": "string"},
            "formatting": {"type": "string"}
          },
          "required": ["tone", "detail_level", "math_policy", "example_policy", "formatting"]
        },
        "chapters": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "id": {"type": "integer"},
              "title": {"type": "string"},
              "objective": {"type": "string"},
              "key_topics": {"type": "array", "items": {"type": "string"}},
              "must_include": {"type": "array", "items": {"type": "string"}}
            },
            "required": ["id", "title", "objective", "key_topics", "must_include"]
          }
        }
      },
      "required": ["project_meta", "style_guide", "chapters"]
    }
  },
  "required": ["has_missing_info", "missing_info_list", "draft_plan"]
}

# ===== Phase 2: Briefing =====
CONFIRM_SCHEMA = {
  "type": "object",
  "properties": {
    "is_approval": {"type": "boolean"},
    "reasoning": {"type": "string"},
    "feedback_analysis": {
      "type": "object",
      "properties": {
        "summary": {"type": "string"},
        "action_items": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "target_category": {"type": "string", "enum": ["project_meta", "style_guide", "chapters", "general"]},
              "target_id": {"type": "integer", "nullable": True},
              "target_field": {"type": "string", "nullable": True},
              "intent": {"type": "string", "enum": ["modify", "create_new", "add_content", "delete", "reorder"]},
              "instruction": {"type": "string"}
            },
            "required": ["target_category", "intent", "instruction"]
          }
        }
      },
      "required": ["summary", "action_items"]
    }
  },
  "required": ["is_approval", "feedback_analysis"]
}

UPDATE_SCHEMA = {
  "type": "object",
  "properties": {
    "draft_plan": {
      "type": "object",
      "properties": {
        "project_meta": {
          "type": "object",
          "properties": {
            "title": {"type": "string"},
            "target_audience": {"type": "string"},
            "goal": {"type": "string"}
          },
          "required": ["title", "target_audience", "goal"]
        },
        "style_guide": {
          "type": "object",
          "properties": {
            "tone": {"type": "string"},
            "detail_level": {"type": "string"},
            "math_policy": {"type": "string"},
            "example_policy": {"type": "string"},
            "formatting": {"type": "string"}
          },
          "required": ["tone", "detail_level", "math_policy", "example_policy", "formatting"]
        },
        "chapters": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "id": {"type": "integer"},
              "title": {"type": "string"},
              "objective": {"type": "string"},
              "key_topics": {"type": "array", "items": {"type": "string"}},
              "must_include": {"type": "array", "items": {"type": "string"}}
            },
            "required": ["id", "title", "objective", "key_topics", "must_include"]
          }
        }
      },
      "required": ["project_meta", "style_guide", "chapters"]
    }
  },
  "required": ["draft_plan"]
}

# ===== Phase 3: Research =====
DECOMPOSITION_SCHEMA = {
  "type": "object",
  "properties": {
    "chapter_id": {"type": "integer"},
    "decomposition_reasoning": {"type": "string"},
    "sub_topics": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "sub_topic_id": {"type": "integer"},
          "sub_topic_name": {"type": "string"},
          "required_contents": {"type": "array", "items": {"type": "string"}},
          "search_action": {
            "type": "object",
            "properties": {
              "tool_use_instruction": {"type": "string", "enum": ["MUST_USE_SEARCH_TOOL"]},
              "query_prompt": {"type": "string"}
            },
            "required": ["tool_use_instruction", "query_prompt"]
          }
        },
        "required": ["sub_topic_id", "sub_topic_name", "required_contents", "search_action"]
      }
    }
  },
  "required": ["chapter_id", "decomposition_reasoning", "sub_topics"]
}

VALIDATION_SCHEMA = {
  "type": "object",
  "properties": {
    "pass": {"type": "boolean"},
    "reasoning": {"type": "string"},
    "feedback": {
      "type": "object",
      "nullable": True,
      "properties": {
        "missing_points": {"type": "array", "items": {"type": "string"}},
        "suggested_new_query": {"type": "array", "items": {"type": "string"}}
      },
      "required": ["missing_points", "suggested_new_query"]
    }
  },
  "required": ["pass", "reasoning"]
}

# ===== Phase 4: Review =====
REVIEW_SCHEMA = {
  "type": "object",
  "properties": {
    "is_pass": {"type": "boolean"},
    "reasoning": {"type": "string", "nullable": True},
    "editor_prompt": {"type": "string", "nullable": True}
  },
  "required": ["is_pass"]
}

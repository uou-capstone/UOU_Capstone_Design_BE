import os
from dotenv import load_dotenv
from typing import TypedDict, Dict, Any
from google import genai
from google.genai import types
import pathlib
from langgraph.graph import StateGraph, START, END
import ast

# API Key 설정 (.env에서 로드)
load_dotenv()
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")


class QAState(TypedDict):
    original_question: str
    user_answer: str
    pdf_path: str
    state: str
    model_answer: str
    validation_result: str
    supplementary_explanation: str
    concept_queue: list
    current_concept_index: int
    bad_mode_history: list


# 모범 답안 생성 시스템 프롬프트
MODEL_ANSWER_PROMPT = """### 롤 (Role)

당신은 고도로 전문화된 **학술 모범 답안 생성기(Academic Model Answer Generator)** 입니다.

### 임무 (Mission)

제공된 **[PDF]**(강의 자료 전체)만을 신뢰 근거로 하여 **[Original Question]**에 대한 **완결된 모범 답안**을 작성합니다. 답변은 채점 기준으로 바로 사용 가능한 정확·정밀 수준이어야 합니다.

### 입력 (Inputs)

1. **[PDF]**: 강의 자료 전체(가능하면 현재 개념의 페이지/섹션 힌트 포함).
2. **[Original Question]**: 실제로 묻는 질문(단일·다중 문항 가능).

### 출력 규격 (Output Constraints — 반드시 준수)

* **오직 정답 본문만 출력**합니다.
* **금지**: 제목, 섹션, 레이블("Answer:"), 마크다운 문법(`#`, `*`, `-`, `>`, 코드펜스 ```), 표, 링크, 인용, 페이지 표기, 각주, 출처 표기.
* 다중 문항은 **(a), (b), (c)** 처럼 괄호 번호로 구분(하이픈·번호목록 금지).
* 수식은 평문으로 표기(e.g., `y = mx + b`, `O(n log n)`), LaTeX 금지.
* 질문이 코드 산출물을 요구하면 **코드만** 평문으로 출력(설명 금지, 코드펜스 금지).
* **질문과 동일한 언어**로 답변합니다.

### 핵심 규칙 (Core Directives)

1. **PDF-우선**: 정의·정리·조건·알고리즘·수치·관계는 **반드시 PDF와 합치**해야 합니다. 외부 지식은 보충 설명이 꼭 필요할 때만 최소한으로 사용하며, PDF와 **모순 금지**.
2. **질문-직결**: 질문이 요구하는 산출물 형태(정의/비교/증명/계산/알고리즘/사례/설계/코드)를 정확히 충족합니다. 서론·배경·잡설 금지.
3. **충분·간결**: 핵심 개념과 필수 조건은 빠짐없이 포함하되 군더더기 없이 작성합니다. 용어·기호·단위는 **PDF와 동일**하게 사용합니다.
4. **증명·유도·계산**: 질문이 요구할 때에만 간결한 핵심 단계로 제시하며, 불필요한 사고 과정 공개 금지. 수치엔 단위와 유효숫자 준수.
5. **다중 문항**: 모든 하위 문항을 **각각** 답합니다. 일부만 답하는 행위 금지.
6. **정보 부족 시**: PDF에 핵심 근거가 없으면, PDF에서 보장되는 범위 내에서 최선의 답을 제시하고, 필요한 최소 가정을 평문으로 짧게 명시합니다(불필요한 변명 금지).
7. **일관성 검사**: 자기모순·범위 일탈·조건 누락·표기 불일치가 없도록 최종 점검합니다.

### 작성 절차 (Workflow)

1. **요구 파악**: [Original Question]을 해체하여 필요한 산출물 요소를 목록화(내부적으로).
2. **근거 수집**: [PDF]에서 해당 정의·정리·조건·공식·예외·알고리즘을 찾고 용어·기호를 확정(내부적으로).
3. **정답 구성**: 질문이 요구하는 형식으로 **바로 정답 본문**을 작성.
4. **품질 점검**: 모든 하위 요구 충족, PDF 합치, 용어·단위·기호 일치, 과잉 설명·마크다운·레이블 **부재** 확인.

### 금지사항 (Prohibitions)

* 마크다운·레이블·표·링크·페이지/섹션 인용 표기 출력.
* PDF와 상충하는 주장·정의·수식·알고리즘.
* 불필요한 서론, 농담, 메타 코멘트, 내부 추론 노출.
* 일부 문항만 답하거나, 질문 형식을 임의 변경.

**최종 규칙**: 지금부터 당신의 모든 출력은 **정답 본문 그 자체**여야 합니다. 어떤 경우에도 제목·레이블·마크다운·설명 블록을 붙이지 마십시오.
"""


# 상태 결정 시스템 프롬프트
VALIDATION_PROMPT = """### 롤 (Role)

당신은 고도로 전문화된 **학습자 답변 검증기(Learner Answer Validator)** 입니다.

### 임무 (Mission)

주어진 **[모범답안]**, **[PDF 전체 또는 스니펫]**, **[사용자 답변]**, **[원래 질문]**을 바탕으로, 사용자의 답변이 질문의 취지와 PDF 근거, 모범답안의 핵심 주장과 **실질적으로 합치**하는지 판정하여 **오직 `GOOD` 또는 `BAD`**를 출력합니다.

### 입력 (Inputs)

1. **[Model Answer]**: 기준이 되는 모범 답안.
2. **[PDF]**: 강의 자료(전체 파일 또는 관련 스니펫). 가능하면 현재 개념의 페이지/구간 정보가 포함됨.
3. **[User Answer]**: 학습자가 작성한 답변.
4. **[Original Question]**: 실제로 묻는 질문.

### 핵심 규칙 (Core Directives)

1. **PDF·질문·모범답안 합치 여부가 전부 기준**
   * 1차 기준: **[Original Question]**의 요구(핵심 쟁점/하위 문항).
   * 2차 기준: **[PDF]**의 내용과 **모순 없음**(핵심 명제·정의·관계·조건).
   * 3차 기준: **[Model Answer]**의 **핵심 포인트**(동의어/재서술 허용).

2. **의미 중심 매칭**
   * 표현, 순서, 예시는 달라도 **핵심 주장·조건·결론**이 동일하면 합격.
   * 사소한 누락·사실상 동일한 요약은 허용. 단, **핵심 단계/조건/정의**의 누락·오해는 불합격.

3. **논리 일관성**
   * 자기모순, 순환논증, PDF와 충돌, 질문의 범위를 벗어난 단정이 있으면 **BAD**.

4. **다중 문항 처리**
   * 하위 문항이 있는 경우 **모두 충족**해야 **GOOD**. 핵심 하위 문항 중 하나라도 틀리면 **BAD**.

5. **정량/조건식 판단**
   * 수치·조건은 PDF/모범답안의 허용 범위 내 근사치·등가식이면 인정. 임의 추정/근거 불명 수치는 **BAD**.

6. **불가분명성 처리**
   * 입력이 불완전해 핵심 판정 근거를 찾을 수 없거나, 사용자 답변이 질문에 **직접 답하지 않으면** **BAD**.

7. **출력 포맷(엄수)**
   * 결과는 **대문자 영문 한 단어**로만 출력: `GOOD` 또는 `BAD`.
   * **추가 설명, 마크업, 공백, 기호 금지.**

### 판정 절차 (Decision Steps)

1. **질문 해체**: [Original Question]에서 **핵심 요구사항 목록**을 뽑는다.
2. **근거 추출**: [PDF]에서 관련 문장/정의/조건을 찾아 **핵심 근거 목록**을 만든다.
3. **기준 요점화**: [Model Answer]에서 **필수 핵심 포인트**를 3~7개로 요약한다.
4. **응답 매핑**: [User Answer]의 주장/단계/결론을 위 세 목록과 **의미 단위로 일치 여부**를 매핑한다.
5. **스코프 체크**: 질문 요구 충족(전부/부분), PDF와의 비모순, 모범답안 핵심 포인트 적중률을 평가한다.
6. **최종 판정**:
   * 아래 **GOOD 조건**을 모두 만족하면 `GOOD`, 아니면 `BAD`.

### GOOD 조건 (모두 충족)

* **관련성**: 질문의 핵심 요구사항을 직접적으로 다룸.
* **정합성**: PDF의 명시 내용/정의/관계와 **모순 없음**.
* **핵심 적중**: 모범답안의 **필수 핵심 포인트의 대부분(≈80% 이상)**을 의미상 충족.
* **논리성**: 주요 비약/자기모순 없고 결론이 전개로부터 타당하게 따라옴.

### 금지사항 (Prohibitions)

* 설명, 점수, 이유, 예시, 부가 텍스트 출력 금지.
* 외부 지식으로 PDF·모범답안과 **충돌**하는 보정 금지.
* 질문과 무관한 사실로 **GOOD** 판정 보정 금지.

### 출력 (Output)

* 딱 한 줄, 딱 한 단어: `GOOD` **또는** `BAD`
"""


# 보충 설명 시스템 프롬프트 (GOOD 모드)
SUPPLEMENTARY_GOOD_PROMPT = """### 롤 (Role)

당신은 고도로 전문화된 **학습 강화 설명 생성기(Learning Reinforcement Explainer)** 입니다.

### 임무 (Mission)

제공된 **[PDF]**, **[Original Question]**, **[Model Answer]**를 바탕으로, 이미 정답에 도달한 학습자가 **핵심 개념을 확실히 자기 것으로 만들도록** 돕는 **보충 설명문**을 작성합니다. 목표는 재답변이 아니라 **재설명**입니다.

### 입력 (Inputs)

1. **[PDF]**: 강의 자료 전체 또는 관련 스니펫.
2. **[Original Question]**: 실제로 묻는 질문.
3. **[Model Answer]**: 확정된 모범 답안(정답 본문).

### 출력 (Output)

* **보충 설명문 1개**만 출력합니다.
* **마크다운 사용 허용**(제목, 소제목, 굵게, 목록). 다만 **인용 표기(페이지·섹션 각주 등) 출력 금지**.
* **길이 가이드**: 한국어 기준 7–12문장 또는 150–300자 단락 2–4개(너무 요약 금지).
* **언어**: 질문과 동일한 언어로 작성.

### 핵심 규칙 (Core Directives)

1. **PDF·모범답안 정합성**
   * 정의, 조건, 수식, 관계는 **PDF와 일치**해야 하며 **[Model Answer]의 결론을 변경하거나 약화/확장하지 않습니다**.

2. **재설명 중심**
   * 정답을 그대로 반복하지 말고, **왜 맞는지(핵심 메커니즘)**, **언제 성립하는지(조건/가정)**, **어떻게 쓰는지(적용 절차·간단 예)**를 분명히 제시합니다.

3. **명료·직설**
   * 군더더기, 장황한 증명, 장식적 비유, 농담, 외부 출처 인용을 배제합니다. 필요한 최소한의 직관과 연결만 제공합니다.

4. **용어 일치**
   * 용어·기호·단위는 가능하면 **PDF 표기**를 그대로 따릅니다.

5. **오개념 예방**
   * 학습자가 혼동하기 쉬운 **경계 조건·전제·부호·단위**를 짧게 짚어 줍니다.

### 작성 절차 (Workflow)

1. **핵심 포인트 파악**: [Model Answer]의 핵심 주장·조건·결과를 2–3개 내부적으로 정리.
2. **근거 확인**: 대응되는 PDF 정의·정리·조건을 내부적으로 확인(출력에 인용 표기 없음).
3. **재설명 구성**: "핵심 개념 → 성립 조건 → 적용 방법/간단 예 → 흔한 오류와 주의점" 순으로 간결하게 작성.
4. **QC**: 모범답안과 모순 없음, PDF 용어 일치, 과도한 요약/장황함/인용 표기/농담 **없음** 확인.

### 출력 템플릿 (이 형식을 권장)

# 핵심 개념 재설명

[핵심 개념을 한두 단락으로 명확히 재설명]

## 왜 맞는가

[핵심 논리 또는 메커니즘을 요점형 문장 2–4개로]

## 적용 방법

[간단한 절차 또는 짧은 예시 1개]

## 주의해야 할 점

[경계 조건·전제·단위·부호 등 자주 틀리는 포인트 2–3개]
"""


# BAD 모드 - 하위 개념 분할 시스템 프롬프트
CONCEPT_DECOMPOSITION_PROMPT = """### 롤 (Role)

당신은 **하위 개념 분할기(Sub-Concept Decomposer)** 입니다. BAD 모드에서 원본 질문을 풀기 위한 개념을 **선수→후속** 구조로 최대 3단계로 나눕니다.

### 임무 (Mission)

**[PDF]**와 **[Original Question]**을 근거로, 문제 해결에 필수적인 **핵심 하위 개념 1–3개**를 **의존 순서**로 산출합니다.

(*주의: [User Answer]는 참고용으로만 제공되며 **분할 개수·내용 결정에 사용하지 않습니다***)

### 입력 (Inputs)

1. **[PDF]**
2. **[Original Question]**
3. **[User Answer]** *(무시해도 됨)*

### 출력 (Output)

* **형식**: `[(개념1 title), (개념2 title), (개념3 title)]`
  * 1개면 `[(개념1 title)]`, 2개면 `[(개념1 title), (개념2 title)]`.
* **반드시 한 줄 리스트만 출력**(설명·접두사·번호·추가 문장 금지).

### 핵심 규칙 (Core Directives)

1. **PDF 우선**: 정의·관계·기호는 PDF와 일치. 모호하면 더 상위·보편 개념으로 안전 축약.
2. **의존 순서**: 보편 기본값은 `정의/용어 → 조건/관계 → 절차/적용`.
3. **개수 결정(사용자와 무관)**
   * **단순 문제**: 1개.
   * **보통 복합**: 2개.
   * **개념 사슬 길거나 핵심 다단**: 3개(최대).
4. **타이틀 규격**: 질문 언어 유지, **명사/명사구**(4–20자 권장), 중복·겹침 금지. 필요 시 핵심 기호 포함(예: "연속성 정의(ε-δ)").
5. **금지사항**: 예시, 근거, 페이지 표기, 불릿/문단, 여는·닫는 문구 **모두 금지**. **괄호 리스트만 허용**.

### 체크리스트

* [ ] 세 항목(또는 두/한 항목)을 **차례로 통과하면** 원문 질문을 풀 수 있는가?
* [ ] 항목 간 **의존**이 성립하고 **중복이 없는가?**
* [ ] 출력이 **정확히 한 줄 리스트**인가?

**지금부터 오직 리스트 한 줄만 출력하십시오.**
"""


# BAD 모드 - 질문 생성 시스템 프롬프트
QUESTION_GENERATION_PROMPT = """### 롤 (Role)

당신은 BAD 모드에서 동작하는 **질문 생성기**입니다.
매 턴 **이전 개념을 설명**하고 **현재 개념을 묻는 질문**을 **정확한 서식**으로 출력합니다.
단, **첫 턴**에는 평가결과가 없으므로 **질문만** 출력합니다.

### 입력 (Inputs)

* **prev_concept** *(선택)*: 직전 턴에 다뤘던 개념의 제목. 첫 턴에는 제공되지 않음.
* **prev_eval** *(선택)*: 직전 개념의 이해도 평가. 값은 `상 | 중 | 하`. 첫 턴에는 제공되지 않음.
* **current_concept** *(필수)*: 이번 턴에 진단할 새로운 개념의 제목.
  * **마지막 턴 신호**: `current_concept`가 비어있거나 `"END"`이면 **설명만 출력**하고 종료 신호(`NEXT_STATE: GOOD`)를 함께 표기합니다.

### 출력 (Output)

* **첫 턴**(prev_concept/prev_eval 없음): **[질문] 블록만** 출력.
* **일반 턴**(둘 다 있음): **[설명] + [질문]** 두 블록 **순서대로** 출력.
* **마지막 턴**(current_concept 없음 또는 `"END"`): **[설명] 블록만** 출력하고 마지막 줄에 `NEXT_STATE: GOOD`.

#### 출력 서식(엄수)

```
[설명]
[이전 개념 제목]
[평가결과에 맞춘 설명문]

[질문]
[현재 개념 제목]
[사용자에게 제시할 질문 1개]
```

* 첫 턴: `[설명]` 블록 **전부 생략**.
* 마지막 턴: `[질문]` 블록 **전부 생략** + 마지막 줄에 `NEXT_STATE: GOOD`.
* **두 블록 외 텍스트 금지**(인사, 추가 문장, 메타 설명, 목록, 코드블록, 표 금지).

### 규칙 (Directives)

1. **설명 길이/깊이 = prev_eval 기반**
   * **상**: 2–4문장. 핵심 요점, 적용 조건 한 줄, 흔한 함정 1개.
   * **중**: 4–6문장. 정의/관계 재정리 + 간단 절차나 미니 예시 1개.
   * **하**: 6–10문장. **정의 → 조건/관계 → 단계별 적용** 순서로 친절히, 오개념 교정 포함.

2. **질문 설계**
   * **단일 질문 1개**. 예/아니오형 금지.
   * **진단형**으로 설계: 정의 회상 / 조건 판별 / 간단 적용(계산·식·절차) 중 **하나만** 선택.
   * 정답을 누설하지 말 것. 필요하면 **응답 형식**만 힌트(예: "한 문장으로", "식으로 표현").
   * 기대 답변 분량: **1–3문장 또는 짧은 식**.

3. **용어 일치**
   * `prev_concept`, `current_concept`의 용어를 그대로 사용. 불필요한 외부 확장 금지.

4. **언어/형식**
   * 입력 언어를 따르고, 문장은 **명료·직설**. 비유·농담·수식어 과다 금지.
   * 블록 라벨([설명]/[질문])·제목 줄·본문 외 **그 어떤 추가 형식도 금지**.

5. **마지막 턴 처리**
   * `current_concept`가 없거나 `"END"`이면 **설명만** 쓰고, 맨 마지막 줄에 정확히 `NEXT_STATE: GOOD`.

### 작성 절차 (Workflow)

1. **턴 판정**:
   * `prev_concept`와 `prev_eval`이 없으면 → **첫 턴**.
   * `current_concept` 없음/`"END"`이면 → **마지막 턴**.
   * 그 외 → **일반 턴**.

2. **설명 생성**(일반/마지막 턴): 규칙 1에 맞춰 `prev_concept`를 설명.

3. **질문 생성**(첫/일반 턴): 규칙 2에 맞춰 `current_concept`에 대한 진단 질문 1개 작성.

4. **서식 검수**: 블록 개수·순서·라벨·제목 줄·금지요소 여부 확인.

**지금부터 위 서식을 정확히 따르는 출력만 생성하십시오.**
"""


# BAD 모드 - 답변 평가 시스템 프롬프트
EVALUATION_PROMPT = """## 역할 (Role)

하위 개념에 대한 사용자 답변의 **이해도**를 판정하는 평가기.

## 목표 (Objective)

**[Question]**과 **[User Answer]**만을 근거로 사용자의 이해 수준을 **단일 등급**으로 결정하여 반환한다.

## 입력 (Inputs)

* **Question**: 단일 하위 개념 질문 텍스트
* **User Answer**: 사용자 답변 텍스트(서술/식/절차 등).

## 출력 (Output)

* **정확히 한 단어**만 출력: `상` | `중` | `하`
* **형식 제약**: 앞뒤 공백, 개행, 구두점, 기타 텍스트 **모두 금지**. 예: `상`

## 평가 루브릭 (Rubric)

* **상**: 질문 의도 정확히 반영, **핵심 요소 모두 충족**, 정의·조건·관계·기호/단위 정확, 모순 없음.
* **중**: 일부 누락/사소한 오류/모호함 존재. 방향성은 올바르나 조건·단계·표현이 불완전.
* **하**: 핵심 오해 또는 동문서답. 주요 전제·정의·관계의 오류, 모순 큼, 무응답/잡담/복붙 등.

## 유형별 체크포인트

* **정의형**: 필수 구성요소(필·충 조건, 기호 의미) 포함 여부.
* **관계/정리형**: 전제–결론의 방향과 적용 범위의 정확성.
* **절차/적용형**: 단계 타당성, 기준·임계치, 단위·부호 일치.
* **식/계산형**: 공식 선택, 대입, 변형의 정확성(허용 오차 범위 내).

## 판정 규칙 (Edge Cases)

* 내용이 정확하지만 장황함 → **상**(장황함만으로 감점하지 않음).
* 정답 요소와 **치명적 오류** 혼재 → **하향 판정**.
* 조건 부족을 정확히 지적만 한 경우 → **중**(정확 보완까지 제시 시 **상**).
* 근거 없는 예/아니오 단답 → **중 이하**.
* 모호한 일반론/부적절 표현 → **하**.

## 절차 (Workflow)

1. 질문 의도 식별(정의/관계/절차/적용/계산).
2. 핵심 요소 충족도 점검.
3. 조건·기호·단위·논리의 오류/모순 검사.
4. 루브릭에 가장 부합하는 **단 하나의 등급** 선택.
5. **형식 검증**: 결과가 정규식 `^(상|중|하)$`을 만족하는지 확인 후 그대로 출력.

## 제약사항 (Constraints)

* 외부 지식 확장 또는 질문 의도 변경 금지.
* 판정 사유·예시·메타 텍스트·마크다운·코드블록 출력 금지.

## 최종 지시 (Final Directive)

**응답은 반드시 `상`, `중`, `하` 중 정확히 하나의 단어만이어야 한다.**
다른 문자, 공백, 기호, 개행을 **절대 포함하지 않는다**.
"""


# 모범 답안 생성 노드
def generate_model_answer(state: QAState) -> QAState:
    """PDF 파일을 읽어 모범 답안을 생성하는 함수"""
    client = genai.Client(api_key=GEMINI_API_KEY)
    filepath = pathlib.Path(state["pdf_path"])
    
    user_prompt = f"""[Original Question]: {state["original_question"]}

위의 질문에 대한 모범 답안을 생성해주세요."""
    
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            MODEL_ANSWER_PROMPT,
            types.Part.from_bytes(
                data=filepath.read_bytes(),
                mime_type='application/pdf',
            ),
            user_prompt
        ]
    )
    
    return {**state, "model_answer": response.text}


# 상태 결정 노드
def validate_understanding(state: QAState) -> QAState:
    """사용자 답변을 검증하여 GOOD/BAD를 판정하는 함수"""
    client = genai.Client(api_key=GEMINI_API_KEY)
    filepath = pathlib.Path(state["pdf_path"])
    
    user_prompt = f"""[Original Question]: {state["original_question"]}

[Model Answer]: {state["model_answer"]}

[User Answer]: {state["user_answer"]}

위의 정보를 바탕으로 사용자의 이해도를 판정해주세요."""
    
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            VALIDATION_PROMPT,
            types.Part.from_bytes(
                data=filepath.read_bytes(),
                mime_type='application/pdf',
            ),
            user_prompt
        ]
    )
    
    validation_result = response.text.strip()
    return {**state, "validation_result": validation_result}


# GOOD 모드 보충 설명 노드
def generate_supplementary_good(state: QAState) -> QAState:
    """GOOD 모드에서 보충 설명을 생성하는 함수"""
    client = genai.Client(api_key=GEMINI_API_KEY)
    filepath = pathlib.Path(state["pdf_path"])
    
    user_prompt = f"""[Original Question]: {state["original_question"]}

[Model Answer]: {state["model_answer"]}

위의 정보를 바탕으로 학습자의 이해를 강화하는 보충 설명문을 작성해주세요."""
    
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            SUPPLEMENTARY_GOOD_PROMPT,
            types.Part.from_bytes(
                data=filepath.read_bytes(),
                mime_type='application/pdf',
            ),
            user_prompt
        ]
    )
    
    return {**state, "supplementary_explanation": response.text}


# BAD 모드 처리 노드
def handle_bad_mode(state: QAState) -> QAState:
    """BAD 모드에서 하위 개념 학습을 진행하는 함수"""
    client = genai.Client(api_key=GEMINI_API_KEY)
    filepath = pathlib.Path(state["pdf_path"])
    pdf_bytes = filepath.read_bytes()
    
    # 1. 하위 개념 분할
    decompose_prompt = f"""[Original Question]: {state["original_question"]}

[User Answer]: {state["user_answer"]}

위의 정보를 바탕으로 하위 개념을 분할해주세요."""
    
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            CONCEPT_DECOMPOSITION_PROMPT,
            types.Part.from_bytes(data=pdf_bytes, mime_type='application/pdf'),
            decompose_prompt
        ]
    )
    
    # 개념 리스트 파싱
    concepts_text = response.text.strip()
    try:
        concepts = ast.literal_eval(concepts_text)
    except:
        concepts = [c.strip() for c in concepts_text.strip('[]').split(',')]
        concepts = [c.strip('()"\'') for c in concepts]
    
    print(f"\n=== 하위 개념 분할 완료 ===")
    print(f"학습할 개념: {concepts}\n")
    
    # 2. 각 개념에 대해 질문-답변-평가 반복
    prev_concept = None
    prev_eval = None
    history = []
    
    for i, current_concept in enumerate(concepts):
        is_first = (i == 0)
        
        # 질문 생성
        if is_first:
            question_prompt = f"""current_concept: {current_concept}

첫 턴입니다. 질문만 생성해주세요."""
        else:
            question_prompt = f"""prev_concept: {prev_concept}
prev_eval: {prev_eval}
current_concept: {current_concept}

설명과 질문을 생성해주세요."""
        
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=[QUESTION_GENERATION_PROMPT, question_prompt]
        )
        
        output = response.text
        
        # 질문 출력
        print(output)
        print("\n" + "="*50)
        
        # 서버 환경 비대화형 동작: 입력 대기 대신 초기 state의 user_answer 사용
        user_answer = state["user_answer"]
        # print("="*50 + "\n")
        
        # 답변 평가
        eval_prompt = f"""[Question]: {output}

[User Answer]: {user_answer}

위의 답변을 평가해주세요."""
        
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=[EVALUATION_PROMPT, eval_prompt]
        )
        
        evaluation = response.text.strip()
        
        # 다음 턴 준비
        history.append({
            "concept": current_concept,
            "question": output,
            "user_answer": user_answer,
            "evaluation": evaluation
        })
        
        prev_concept = current_concept
        prev_eval = evaluation
    
    # 3. 마지막 개념 설명 생성
    final_prompt = f"""prev_concept: {prev_concept}
prev_eval: {prev_eval}
current_concept: END

마지막 턴입니다. 설명만 생성해주세요."""
    
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[QUESTION_GENERATION_PROMPT, final_prompt]
    )
    
    final_explanation = response.text
    print(final_explanation)
    history.append({"concept": prev_concept, "output": final_explanation})
    
    print("\n=== BAD 모드 완료, GOOD 모드로 전환 ===\n")
    
    return {
        **state,
        "concept_queue": concepts,
        "bad_mode_history": history,
        "validation_result": "GOOD"
    }


# 조건부 분기 함수
def route_after_validation(state: QAState) -> str:
    """검증 결과에 따라 GOOD/BAD 모드로 분기"""
    if state["validation_result"] == "GOOD":
        return "generate_supplementary_good"
    else:
        return "handle_bad_mode"


def format_final_response(final_state: QAState) -> Dict[str, Any]:
    """
    LangGraph 실행 결과를 API 응답으로 사용 가능한 딕셔너리로 변환
    """
    return {
        "supplementary_explanation": final_state.get("supplementary_explanation", ""),
        "validation_result": final_state.get("validation_result", ""),
        "concept_queue": final_state.get("concept_queue", []),
        "current_concept_index": final_state.get("current_concept_index", 0),
        "bad_mode_history": final_state.get("bad_mode_history", []),
        "state": final_state.get("state", "GOOD"),
        "needs_follow_up": final_state.get("validation_result", "").strip().upper() == "BAD"
    }


def main(qa_input: list) -> Dict[str, Any]:
    """
    Main 함수: 질문, 사용자 답변, PDF 경로를 받아 보충 설명을 생성
    
    Args:
        qa_input (list): [(원래 질문, 사용자 답변), pdf경로] 형태
                        예: [("질문내용", "사용자답변"), "/path/to/pdf"]
    
    Returns:
        str: 보충 설명문
    """
    # 입력 파싱
    (original_question, user_answer), pdf_path = qa_input
    
    # 워크플로우 구성
    workflow = StateGraph(QAState)
    workflow.add_node("generate_model_answer", generate_model_answer)
    workflow.add_node("validate_understanding", validate_understanding)
    workflow.add_node("handle_bad_mode", handle_bad_mode)
    workflow.add_node("generate_supplementary_good", generate_supplementary_good)
    
    workflow.add_edge(START, "generate_model_answer")
    workflow.add_edge("generate_model_answer", "validate_understanding")
    workflow.add_conditional_edges(
        "validate_understanding",
        route_after_validation,
        {
            "generate_supplementary_good": "generate_supplementary_good",
            "handle_bad_mode": "handle_bad_mode"
        }
    )
    workflow.add_edge("handle_bad_mode", "generate_supplementary_good")
    workflow.add_edge("generate_supplementary_good", END)
    
    qa_agent = workflow.compile()
    
    # 초기 상태 설정
    initial_state = {
        "original_question": original_question,
        "user_answer": user_answer,
        "pdf_path": pdf_path,
        "state": "GOOD",
        "model_answer": "",
        "validation_result": "",
        "supplementary_explanation": "",
        "concept_queue": [],
        "current_concept_index": 0,
        "bad_mode_history": []
    }
    
    # 워크플로우 실행
    final_state = qa_agent.invoke(initial_state)
    
    # 최종 결과를 구조화하여 반환
    return format_final_response(final_state)


# 테스트용 코드 (직접 실행하지 않는 이상 수행 X)
if __name__ == "__main__":
    # 테스트 예시 - GOOD 모드
    print("=== GOOD 모드 테스트 ===")
    result = main([
        ("소프트웨어 프로세스란 무엇인가?", "소프트웨어 시스템을 개발하기 위한 구조화된 활동들의 집합입니다."),
        "/Users/jhkim/Desktop/Edu_Agent/component/02-SW-Process/소프트웨어 프로세스.pdf"
    ])
    print(result)
    
    # 테스트 예시 - BAD 모드 (대화형)
    # print("\n\n=== BAD 모드 테스트 ===")
    # result = main([
    #     ("소프트웨어 프로세스란 무엇인가?", "잘 모르겠습니다."),
    #     "/Users/jhkim/Desktop/Edu_Agent/component/02-SW-Process/소프트웨어 프로세스.pdf"
    # ])
    # print("\n=== 최종 보충 설명 ===")
    # print(result)


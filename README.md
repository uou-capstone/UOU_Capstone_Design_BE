# [UOU_Capstone_Design_BE]

[LMS서비스]

---

## ## 1. 프로젝트 아키텍처

이 프로젝트는 MSA(마이크로서비스 아키텍처) 구조를 따릅니다.

- **`main-service` (Spring Boot)**: 메인 API 서버로, 사용자 인증 및 핵심 비즈니스 로직을 담당합니다.
- **`ai-service` (FastAPI)**: AI 모델을 서빙하는 전문 API 서버입니다.



두 서비스는 독립적으로 실행되며, `main-service`가 `ai-service`를 HTTP로 호출하여 통신합니다.

---

## ## 2. 기술 스택

**`main-service`**
- Java 17
- Spring Boot
- Gradle
- [기타 라이브러리...]

**`ai-service`**
- Python 3.9+
- FastAPI
- Uvicorn
- [AI 모델 관련 라이브러리...]

---

## ## 3. 로컬 환경 설정 및 실행 방법

### ### `ai-service` (FastAPI) 실행

1.  **가상환경 생성 및 활성화**
    ```bash
    python -m venv venv
    source venv/bin/activate
    ```

2.  **의존성 설치**
    ```bash
    cd ai-service
    pip install -r requirements.txt
    ```

3.  **서버 실행**
    ```bash
    uvicorn main:app --reload
    ```
    - 서버는 `http://localhost:8000` 에서 실행됩니다.

### ### `main-service` (Spring Boot) 실행

1.  **프로젝트 빌드**
    ```bash
    cd main-service
    ./gradlew build
    ```

2.  **서버 실행**
    ```bash
    java -jar build/libs/[생성된 jar 파일 이름].jar
    ```
    - 서버는 `http://localhost:8080` 에서 실행됩니다.

---

## ## 4. API 명세 (API Specification)

서비스 간 통신에 사용되는 API 명세는 아래 링크에서 확인할 수 있습니다.

- **[Notion, Swagger, Postman 등 API 문서 링크]**

---

## ## 5. Git 서브모듈 (Submodule)

이 프로젝트는 `ai-service`의 AI 모델 코드를 Git 서브모듈로 관리합니다.

- **서브모듈 초기화 및 업데이트**
  ```bash
  git submodule init
  git submodule update
  ```
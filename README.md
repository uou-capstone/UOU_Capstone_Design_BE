# 스테이지 반드시 추가해야함!!

# Capstone_BE

학습/강의 보조용 AI 에이전트 백엔드 모노레포입니다.

- `main-service` (Spring Boot): 메인 API 서버(추후 연동)
- `ai-service` (FastAPI): AI 에이전트 API 서버

---

## 1) 아키텍처 개요

- Coordinator: `ai-service/app/main.py` (FastAPI 엔트리포인트, 라우터 포함)
- Delegator: `ai-service/ai_agent/Lecture_Agent/integration.py` (단계별 호출/상태 관리, `main(pdf_path)`) 
- PDF Analysis Agent: `ai-service/ai_agent/Lecture_Agent/component/PdfAnalyisis.py`
- Main Lecture Agent: `ai-service/ai_agent/Lecture_Agent/component/MainLectureAgent.py`
- Main Q&A Agent: `ai-service/ai_agent/Lecture_Agent/component/MainQandAAgent.py`

---

## 2) 기술 스택

- Python 3.13
- FastAPI, Uvicorn, Pydantic
- google-genai, langgraph
- PyPDF2 / pypdf

---

## 3) 로컬 실행 가이드 (ai-service)

1. 의존성 설치
   ```powershell
   cd Capstone_BE/ai-service
   python -m pip install -r requirements.txt
   ```

2. 환경 변수(.env)
   - 위치: `Capstone_BE/ai-service/.env`
   - 내용:
     ```
     GEMINI_API_KEY=YOUR_API_KEY
     ```

3. 업로드 디렉토리 준비(테스트용 PDF 위치)
   ```powershell
   mkdir .\uploads
   # 예시 파일: .\uploads\sample.pdf
   ```

4. 서버 실행
   ```powershell
   python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
   ```
   - 헬스 체크: `http://127.0.0.1:8000/health`
   - Swagger: `http://127.0.0.1:8000/docs`

---

## 4) 제공 API

- POST `/api/pdf/analyze` — PDF 챕터 구조 분석 및 분할
  - Body(JSON):
    ```json
    { "pdf_path": "C:\\Users\\<user>\\...\\ai-service\\uploads\\sample.pdf" }
    ```
  - Response(JSON): `{ "items": [{ "chapter_title": string, "pdf_path": string }, ...] }`

- POST `/api/lecture/generate` — 챕터 강의 설명 생성
  - Body(JSON):
    ```json
    {
      "chapter_title": "DQN 핵심 개념",
      "pdf_path": "C:\\Users\\<user>\\...\\ai-service\\uploads\\sample.pdf"
    }
    ```
  - Response(JSON): `{ "chapter_title": string, "content": string }`

- POST `/api/qa/evaluate` — 질문/사용자 답변 평가 후 보충설명 생성
  - Body(JSON):
    ```json
    {
      "original_q": "DQN의 핵심 아이디어는?",
      "user_answer": "경험 재플레이와 타깃 네트워크를 사용해 안정화합니다.",
      "pdf_path": "C:\\Users\\<user>\\...\\ai-service\\uploads\\sample.pdf"
    }
    ```
  - Response(JSON): `{ "supplementary_explanation": string }`
  - 주의: BAD 경로 분기 시 콘솔 입력을 요구하는 로직이 있어 서버 환경에서는 블로킹될 수 있습니다.

- POST `/api/files/upload` — PDF 업로드 (멀티파트)
  - Body(form-data): `file` 필드에 PDF 첨부
  - Response(JSON): `{ "filename": string, "path": string }` (절대 경로 반환)

- POST `/api/delegator/dispatch` — 통합 파이프라인 실행 (백그라운드 작업 + 웹훅)
  - **동작 방식**: 즉시 응답 반환 후 백그라운드에서 작업 실행, 완료 시 웹훅 호출
  - Body(JSON):
    ```json
    {
      "stage": "run_all",
      "payload": {
        "lecture_id": "123",
        "pdf_path": "C:\\Users\\<user>\\...\\ai-service\\uploads\\ch6_DQN.pdf",
        "webhook_url": "https://michal-unvulnerable-benita.ngrok-free.dev/api/ai/callback/123"
      }
    }
    ```
  - **즉시 Response(JSON)**: `{ "status": "accepted", "message": "작업이 시작되었습니다", "lecture_id": "123" }`
  - **웹훅 호출 (완료 시)**:
    - URL: `webhook_url` (Spring Boot에서 제공)
    - Method: `POST`
    - Body (성공): 
      ```json
      {
        "lecture_id": "123",
        "status": "completed",
        "result": {...},
        "pdf_path": "..."
      }
      ```
    - Body (실패):
      ```json
      {
        "lecture_id": "123",
        "status": "failed",
        "error": "에러 메시지",
        "pdf_path": "..."
      }
      ```

---

## 5) Spring Boot 연동 가이드

### 5.1) 기본 정보
- **FastAPI 서버 URL**: `http://127.0.0.1:8000` (기본값, 환경에 따라 변경)
- **API 문서**: `http://127.0.0.1:8000/docs` (Swagger UI)
- **CORS**: 모든 origin 허용 (프로덕션에서는 제한 권장)

### 5.2) 주요 연동 API

#### 1) 파일 업로드
- **엔드포인트**: `POST /api/files/upload`
- **Content-Type**: `multipart/form-data`
- **요청 형식**:
  ```
  file: [PDF 파일]
  ```
- **응답 형식**:
  ```json
  {
    "filename": "ch6_DQN.pdf",
    "path": "C:\\Users\\<user>\\...\\ai-service\\uploads\\ch6_DQN.pdf"
  }
  ```
- **Spring Boot 예시** (RestTemplate):
  ```java
  MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
  body.add("file", new ByteArrayResource(file.getBytes()) {
      @Override
      public String getFilename() {
          return file.getOriginalFilename();
      }
  });
  
  HttpHeaders headers = new HttpHeaders();
  headers.setContentType(MediaType.MULTIPART_FORM_DATA);
  
  HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
  ResponseEntity<Map> response = restTemplate.postForEntity(
      "http://127.0.0.1:8000/api/files/upload", 
      request, 
      Map.class
  );
  ```

#### 2) 파이프라인 실행
- **엔드포인트**: `POST /api/delegator/dispatch`
- **Content-Type**: `application/json`
- **요청 형식**:
  ```json
  {
    "stage": "run_all",
    "payload": {
      "pdf_path": "C:\\Users\\<user>\\...\\ai-service\\uploads\\ch6_DQN.pdf"
    }
  }
  ```
- **응답 형식**:
  ```json
  {
    "status": "ok",
    "result": null
  }
  ```
- **Spring Boot 예시** (RestTemplate):
  ```java
  Map<String, Object> requestBody = Map.of(
      "stage", "run_all",
      "payload", Map.of("pdf_path", pdfPath)
  );
  
  HttpHeaders headers = new HttpHeaders();
  headers.setContentType(MediaType.APPLICATION_JSON);
  
  HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
  ResponseEntity<Map> response = restTemplate.postForEntity(
      "http://127.0.0.1:8000/api/delegator/dispatch", 
      request, 
      Map.class
  );
  ```

### 5.3) 연동 플로우 (백그라운드 작업 + 웹훅) ⚠️ 권장

**⚠️ 중요: Spring Boot 서버의 경로를 직접 전달하면 안 됩니다!**
- ❌ `C:\dev\ai-platform-uploads\...` 같은 Spring Boot 서버 경로는 FastAPI에서 접근 불가
- ✅ 반드시 `/api/files/upload`를 먼저 호출해서 FastAPI 서버에 파일을 업로드해야 합니다

**Spring Boot → FastAPI 파일 업로드 → 백그라운드 작업 시작 → 웹훅으로 결과 수신**

1. **Spring Boot에서 클라이언트로부터 파일 수신** (MultipartFile)
2. **⚠️ 필수: FastAPI `/api/files/upload` 호출** → 파일을 FastAPI 서버의 `uploads` 디렉토리로 복사
   - 응답: `{ "filename": "...", "path": "C:\\...\\ai-service\\uploads\\파일명.pdf" }`
   - ⚠️ 이 `path`를 반드시 사용해야 합니다!
3. **FastAPI `/api/delegator/dispatch` 호출** → 즉시 응답 반환 (0.1초)
   - Body: 
     ```json
     {
       "stage": "run_all",
       "payload": {
         "lecture_id": "123",
         "pdf_path": "업로드된_경로",
         "webhook_url": "https://michal-unvulnerable-benita.ngrok-free.dev/api/ai/callback/123"
       }
     }
     ```
   - 즉시 Response: `{ "status": "accepted", "message": "작업이 시작되었습니다", "lecture_id": "123" }`
4. **Spring Boot는 사용자에게 즉시 응답** → "작업이 시작되었습니다"
5. **FastAPI가 백그라운드에서 작업 실행** (1분~수분 소요)
6. **작업 완료 시 FastAPI가 Spring Boot 웹훅 호출**
   - URL: `webhook_url` (예: `POST /api/ai/callback/{lectureId}`)
   - Body: `{ "lecture_id": "123", "status": "completed", "result": {...} }`
7. **Spring Boot가 웹훅에서 결과 수신** → DB 저장 및 상태 업데이트

**Spring Boot 예시 코드:**

```java
@Service
@RequiredArgsConstructor
public class LectureService {
    
    @Value("${ai-service.base-url:http://127.0.0.1:8000}")
    private String aiServiceBaseUrl;
    
    @Value("${spring.boot.base-url:https://michal-unvulnerable-benita.ngrok-free.dev}")
    private String springBootBaseUrl;
    
    private final RestTemplate restTemplate;
    
    public Map<String, Object> processLecture(String lectureId, MultipartFile file) {
        // 1. FastAPI로 파일 업로드
        String uploadUrl = aiServiceBaseUrl + "/api/files/upload";
        
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };
        body.add("file", resource);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> uploadRequest = 
            new HttpEntity<>(body, headers);
        
        ResponseEntity<Map> uploadResponse = restTemplate.postForEntity(
            uploadUrl, uploadRequest, Map.class
        );
        
        // 2. 업로드된 파일 경로 획득
        String pdfPath = (String) uploadResponse.getBody().get("path");
        
        // 3. 웹훅 URL 생성
        String webhookUrl = springBootBaseUrl + "/api/ai/callback/" + lectureId;
        
        // 4. 파이프라인 실행 (백그라운드 작업 시작)
        String dispatchUrl = aiServiceBaseUrl + "/api/delegator/dispatch";
        Map<String, Object> payload = Map.of(
            "lecture_id", lectureId,
            "pdf_path", pdfPath,
            "webhook_url", webhookUrl
        );
        Map<String, Object> dispatchBody = Map.of(
            "stage", "run_all",
            "payload", payload
        );
        
        HttpHeaders dispatchHeaders = new HttpHeaders();
        dispatchHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> dispatchRequest = 
            new HttpEntity<>(dispatchBody, dispatchHeaders);
        
        ResponseEntity<Map> dispatchResponse = restTemplate.postForEntity(
            dispatchUrl, dispatchRequest, Map.class
        );
        
        // 5. 즉시 응답 반환 (작업은 백그라운드에서 진행)
        return dispatchResponse.getBody(); // { "status": "accepted", "lecture_id": "123" }
    }
    
    // 웹훅 엔드포인트 (FastAPI가 호출)
    @PostMapping("/api/ai/callback/{lectureId}")
    public ResponseEntity<?> handleWebhook(
        @PathVariable String lectureId,
        @RequestBody Map<String, Object> webhookPayload
    ) {
        String status = (String) webhookPayload.get("status");
        
        if ("completed".equals(status)) {
            // 성공: 결과를 DB에 저장
            Object result = webhookPayload.get("result");
            // DB 저장 로직...
            return ResponseEntity.ok().build();
        } else if ("failed".equals(status)) {
            // 실패: 에러 처리
            String error = (String) webhookPayload.get("error");
            // 에러 처리 로직...
            return ResponseEntity.ok().build();
        }
        
        return ResponseEntity.badRequest().build();
    }
}
```

**⚠️ 체크리스트 (Spring Boot 개발자용):**
- [ ] 클라이언트로부터 파일을 받았나요? (MultipartFile)
- [ ] `/api/files/upload`를 호출했나요? (파일을 FastAPI 서버로 업로드)
- [ ] 업로드 응답의 `path`를 받았나요?
- [ ] `/api/delegator/dispatch`에 `lecture_id`, `pdf_path`, `webhook_url`을 전달했나요?
- [ ] 웹훅 엔드포인트(`POST /api/ai/callback/{lectureId}`)를 구현했나요?
- [ ] 웹훅에서 결과를 받아서 DB에 저장하는 로직이 있나요?
- [ ] ❌ Spring Boot 서버의 경로(`C:\dev\...`)를 직접 전달하지 않았나요?

**주의:** 이 방법을 사용하면 파일이 FastAPI 서버의 `uploads` 디렉토리에 저장되므로, FastAPI 서버가 해당 경로에 접근할 수 있습니다.

### 5.4) 주의사항
- **백그라운드 작업**: `/api/delegator/dispatch`는 즉시 응답을 반환하므로 `RestTemplate`의 `readTimeout`은 짧게 설정해도 됩니다 (5초 정도)
- **웹훅 타임아웃**: FastAPI가 웹훅을 호출할 때 30초 타임아웃이 설정되어 있습니다
- **에러 처리**: 
  - FastAPI 서버가 응답하지 않을 경우를 대비한 예외 처리 필요
  - 웹훅 호출 실패 시에도 로그에 기록되므로 모니터링 필요
- **파일 경로**: Windows 경로는 `\\` 이스케이프 필요
- **서버 실행**: Spring Boot 서버 실행 전에 FastAPI 서버가 실행 중이어야 함
- **웹훅 엔드포인트**: Spring Boot에서 웹훅 엔드포인트(`/api/ai/callback/{lectureId}`)를 반드시 구현해야 합니다

### 5.5) 설정 예시 (application.yml)
```yaml
ai-service:
  base-url: http://127.0.0.1:8000  # FastAPI 서버 URL

spring:
  boot:
    base-url: https://michal-unvulnerable-benita.ngrok-free.dev  # Spring Boot 서버 URL (웹훅용)
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB
```

---

## 6) 의존성(요약)

`ai-service/requirements.txt` 참고:

```
fastapi==0.121.0
uvicorn[standard]==0.38.0
pydantic==2.11.9
python-dotenv==1.0.1
google-genai==1.37.0
google-api-core==2.25.1
google-auth==2.40.3
langgraph==1.0.2
langgraph-checkpoint==3.0.0
langgraph-prebuilt==1.0.2
pypdf==5.9.0
PyPDF2==3.0.1
```

설치:
```powershell
cd Capstone_BE/ai-service
python -m pip install -r requirements.txt
```

---

## 7) 트러블슈팅

- `ModuleNotFoundError: No module named 'google'` → `python -m pip install -r requirements.txt` 재실행
- Windows Swagger 입력 시 경로는 `\\` 로 이스케이프 필요(예: `C:\\path\\to\\file.pdf`)
- PowerShell 한글 깨짐 → 출력 인코딩 설정
  ```powershell
  [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
  ```

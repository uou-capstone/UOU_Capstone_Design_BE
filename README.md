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

## ## 3. 로컬 실행 (각 서비스 개별 빌드)

### ### `ai-service` (FastAPI)
```bash
cd ai-service
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

### ### `main-service` (Spring Boot)
```bash
cd main-service/uou-capstone
./gradlew bootRun   # 또는 ./gradlew bootJar 후 java -jar
```

---

## ## 4. Docker 기반 배포

### ### 4-1. main-service (Spring Boot + MySQL)
1. 의존성: Docker 24+, Docker Compose v2.
2. 루트에서 `.env` 생성
   ```bash
   cp env.example .env
   # MYSQL_ROOT_PASSWORD, SPRING_DATASOURCE_PASSWORD, JWT_SECRET, AI_SERVICE_BASE_URL 등 실제 값으로 수정
   ```
3. 컨테이너 기동
   ```bash
   docker compose pull        # 선택
   docker compose up -d
   docker compose logs -f main-service
   ```
   - MySQL은 `ports: 3306`으로 공개됩니다. 이미 로컬 MySQL이 있다면 `SPRING_DATASOURCE_URL`과 `ports`를 조정하세요.
   - `AI_SERVICE_BASE_URL`에는 FastAPI 배포 URL(동일 EC2라면 `http://localhost:8000/`)을 넣습니다.
4. 중지 및 정리
   ```bash
   docker compose down        # 컨테이너만 정지
   docker compose down -v     # + 볼륨 삭제
   ```

### ### 4-2. ai-service (FastAPI 단독)
FastAPI 팀은 `docker-compose.ai.yml`을 사용해 같은 EC2 혹은 별도 서버에서 독립적으로 배포할 수 있습니다.
```bash
cp ai-service/env.example ai-service/.env
docker compose -f docker-compose.ai.yml up -d
docker compose -f docker-compose.ai.yml logs -f ai-service
```
- 업로드 폴더는 기본적으로 `./ai-service/uploads` → 컨테이너 `/data/uploads`에 마운트됩니다.
- 콜백 주소(`SPRING_BOOT_BASE_URL` 등)는 ai-service 전용 `.env`에서 관리하세요.

---

## ## 5. API 명세 (API Specification)

서비스 간 계약은 Swagger/Notion 등 별도 문서에서 관리합니다. (링크 삽입 예정)

---

## ## 6. 기타 운영 가이드
- `.env` 파일은 Git에 올리지 말고, `env.example` 템플릿을 참고해 각 환경에서 직접 작성합니다.
- main-service와 ai-service는 동일 EC2에 있어도 포트만 다르면 독립적으로 업그레이드할 수 있습니다.
- 배포 자동화를 원하면 서비스별 GitHub Actions/CodeDeploy 파이프라인을 분리해 구성할 수 있습니다.
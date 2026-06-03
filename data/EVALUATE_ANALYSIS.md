# evaluate.py 분석 문서

## 1. 역할 요약

`evaluate.py`는 학습자가 구현한 Spring Boot 챗봇 서버의 **답변 품질을 자동으로 측정**하는 평가 스크립트입니다.

- 사람이 150개 질문을 수동으로 검토하는 대신, `gpt-4o-mini`를 **판정 모델(Judge)** 로 활용해 자동 채점합니다.
- 전체 정확도뿐 아니라 **난이도(easy / medium / hard)별 정확도**를 분리해서 보여줍니다.
- 결과를 `eval_result.json`에 저장하여 개선 전후를 비교할 수 있게 합니다.

---

## 2. 전체 흐름

```
test_questions.json
        │
        ▼
[질문을 하나씩 순회]
        │
        ├─ ask_server(question_ko)
        │       POST /api/chat → 학습자 서버
        │       ← { "answer": "..." }
        │
        ├─ judge_answer(question, expected, actual)
        │       gpt-4o-mini에게 "맞는지 틀리는지" 채점 요청
        │       ← { "score": 1 or 0, "reason": "..." }
        │
        └─ 결과 집계
                │
                ├─ 콘솔 출력 (전체 / 난이도별 정확도)
                └─ eval_result.json 저장
```

---

## 3. 주요 구성 요소

### 3.1 설정 (모듈 상단)

| 변수 | 값 | 역할 |
|------|-----|------|
| `SERVER_URL` | `http://localhost:8080/api/chat` | 평가 대상 서버 |
| `JUDGE_MODEL` | `gpt-4o-mini` | 채점에 사용할 LLM 모델 |
| `OPENAI_API_KEY` | `.env` 파일에서 로드 | OpenAI 인증 키 |

`.env` 로드 방식:
```python
env_path = ROOT_DIR / ".env"          # 프로젝트 루트의 .env
env_vars = dotenv_values(env_path)
OPENAI_API_KEY = env_vars.get("OPENAI_API_KEY") or os.environ.get("OPENAI_API_KEY")
```
`.env`가 없으면 환경변수에서 fallback합니다.

---

### 3.2 `ask_server(question)` — 서버 호출

```python
def ask_server(question: str) -> dict | None
```

- 학습자의 챗봇 서버(`POST /api/chat`)에 질문을 보냅니다.
- 요청 payload: `{"question": question}`
- 타임아웃: **60초** (LLM 처리 시간을 고려한 값)
- 실패 처리:
  - HTTP 200 외: `None` 반환 + 에러 로그
  - `ConnectionError`: 서버 미실행 감지
  - `Timeout`: 60초 초과 감지

반환값은 서버가 내려주는 JSON 그대로입니다. 이후 `response.get("answer", "")`로 답변 텍스트만 추출합니다.

---

### 3.3 `judge_answer(question, expected, actual)` — LLM 판정

```python
def judge_answer(question: str, expected: str, actual: str) -> dict
```

**판정 기준 (프롬프트에 명시된 내용):**
- 표현이 달라도 **핵심 사실이 같으면 정답(score: 1)**
- 핵심 사실이 빠지거나 틀리면 오답(score: 0)
- **부분적으로만 맞아도 오답**으로 처리

**모델 호출 설정:**
- `temperature=0` → 판정 결과의 재현성 확보
- `response_format={"type": "json_object"}` → JSON만 반환하도록 강제

**반환 형식:**
```json
{"score": 1, "reason": "핵심 사실 일치"}
{"score": 0, "reason": "마켓플레이스 예외 정책 누락"}
```

파싱 실패 시 `{"score": 0, "reason": "판정 파싱 실패"}`를 반환합니다.

---

### 3.4 `main()` — 메인 루프

**CLI 인수:**

| 인수 | 기본값 | 역할 |
|------|--------|------|
| `--verbose` | `False` | 질문마다 판정 결과 상세 출력 |
| `--limit N` | `0` (전체) | 처음 N개만 평가 (빠른 테스트용) |

**서버 연결 사전 확인:**
```python
test_resp = ask_server("test")
if test_resp is None:
    # 안내 메시지 출력 후 종료
```
실제 평가 전에 연결을 먼저 확인해 불필요한 OpenAI API 비용을 방지합니다.

**집계 구조:**
```python
results = {"correct": 0, "incorrect": 0, "error": 0}   # 전체
tier_results = {tier: {"correct": 0, "total": 0}}       # 난이도별
```

**진행 출력 (non-verbose 모드):**
```
  진행: 10/150
  진행: 20/150
  ...
```
10개마다 한 번 출력합니다.

---

## 4. 입력 데이터 구조 (`test_questions.json`)

```json
{
  "id": "Q001",
  "question_ko": "주문 취소는 어떻게 하나요?",
  "question_en": "How do I cancel my order?",
  "expected_answer": "Go to My Orders and tap Cancel Order...",
  "tier": "easy",
  "source_layers": ["faq"],
  "primary_intent": "cancel_order",
  "wall_type": null
}
```

| 필드 | 설명 | evaluate.py 사용 여부 |
|------|------|----------------------|
| `id` | 질문 식별자 (Q001~Q150) | 로그 출력에 사용 |
| `question_ko` | 서버에 전달되는 한국어 질문 | 서버 호출 + 판정 프롬프트 |
| `expected_answer` | 정답 기준 (영어) | 판정 프롬프트 |
| `tier` | easy / medium / hard | 난이도별 집계 |
| `source_layers` | 어느 데이터 레이어 기반인지 | **사용 안 함** |
| `primary_intent` | 질문 의도 분류 | **사용 안 함** |
| `wall_type` | 어떤 "벽"에 해당하는지 | **사용 안 함** |

---

## 5. 출력 형식

### 콘솔 출력 (verbose 모드)
```
[Q001] ✓ (easy) 주문 취소는 어떻게 하나요?...
[Q021] ✗ (medium) 마켓플레이스 상품 반품 가능한가요?...
        이유: 마켓플레이스 셀러별 정책 예외를 언급하지 않음
```

### 콘솔 출력 (요약)
```
=== 평가 결과 ===
전체: 62/150 (41.3%)

난이도별:
  easy    : 22/30 (73%)
  hard    :  2/26  (8%)
  medium  : 38/94 (40%)

소요 시간: 312.4초
평균 응답: 2.1초/질문
```

### `eval_result.json`
```json
{
  "total": 150,
  "correct": 62,
  "incorrect": 85,
  "error": 3,
  "accuracy": 0.413,
  "tier_results": {
    "easy":   {"correct": 22, "total": 30},
    "medium": {"correct": 38, "total": 94},
    "hard":   {"correct":  2, "total": 26}
  },
  "elapsed_seconds": 312.4
}
```

---

## 6. 비용 구조

평가 한 번 실행 시 API 호출이 두 군데 발생합니다:

| 호출 대상 | 주체 | 모델 | 비용 주체 |
|----------|------|------|-----------|
| 학습자 챗봇 서버 | `ask_server()` | 학습자가 선택한 모델 (기본 gpt-4.1-nano) | 학습자 키 |
| 판정 모델 | `judge_answer()` | `gpt-4o-mini` | 학습자 키 |

- 150문항 전체 실행: **추가 $0.3~0.5** (judge 비용만)
- `--limit 10`으로 먼저 테스트하면 비용을 크게 줄일 수 있습니다.

---

## 7. 설계상 고려된 한계 (의도된 벽)

`GUIDE.md`에 다음과 같이 명시되어 있습니다:

> "이 평가 도구는 가장 기본적인 형태입니다. LLM 판정 방식, 채점 기준, 평가 범위 모두 손볼 곳이 있습니다. 평가 도구 자체도 벽의 일부입니다."

| 한계 | 내용 |
|------|------|
| **부분 정답 = 오답** | 핵심 사실이 70% 맞아도 0점 처리 → 점수가 실제보다 낮게 나올 수 있음 |
| **expected_answer가 영어** | 서버는 한국어로 답하는데, 판정 기준은 영어 → LLM 번역 능력에 의존 |
| **판정 모델 편향** | `gpt-4o-mini`의 판단이 항상 공정하다는 보장 없음 |
| **source_layers 미활용** | 어느 데이터 레이어에서 틀렸는지 분석 불가 |
| **wall_type 미활용** | 어떤 유형의 "벽"에서 실패했는지 집계 불가 |
| **재현성 이슈** | 챗봇 서버의 temperature > 0이면 같은 질문도 매번 다른 결과 |

---

## 8. 실행 흐름 요약 (시퀀스)

```
학습자          evaluate.py         챗봇 서버           OpenAI (gpt-4o-mini)
   │                 │                   │                       │
   │  python evaluate.py                 │                       │
   │────────────────>│                   │                       │
   │                 │ POST /api/chat    │                       │
   │                 │──────────────────>│                       │
   │                 │   {"answer": ...} │                       │
   │                 │<──────────────────│                       │
   │                 │                   │  judge prompt          │
   │                 │────────────────────────────────────────── >│
   │                 │                   │  {"score":1,"reason":..}│
   │                 │<─────────────────────────────────────────── │
   │                 │  [150회 반복]      │                       │
   │                 │                   │                       │
   │  결과 출력 + eval_result.json        │                       │
   │<────────────────│                   │                       │
```

---

## 9. 개선 포인트 (학습자가 직접 탐구할 수 있는 영역)

1. **부분 점수 도입**: `score: 0~1` 범위로 변경해 더 세밀한 측정
2. **source_layers / wall_type 집계**: 어느 데이터 레이어에서 실패하는지 파악
3. **실패 케이스 저장**: 틀린 질문과 이유를 파일로 덤프해 분석 용이화
4. **concurrent 실행**: `asyncio` 또는 `ThreadPoolExecutor`로 병렬화해 소요 시간 단축
5. **배치 API 활용**: OpenAI Batch API로 judge 비용 절감 (~50%)
6. **반복 실행 안정화**: 동일 질문을 N회 평가해 평균 점수로 변동성 측정

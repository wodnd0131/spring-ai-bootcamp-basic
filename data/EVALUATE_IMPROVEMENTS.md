# evaluate.py 한계 분석 및 개선안

## 먼저: 가장 흥미로운 사실 하나

> 이 평가 도구는 평가하려는 챗봇과 **같은 종류의 문제**를 가지고 있습니다.

- 챗봇이 구버전 데이터를 쓰면 틀리듯 → judge가 잘못된 기준으로 채점하면 틀린 점수가 나옵니다
- 챗봇이 노이즈를 걸러내야 하듯 → judge가 한국어 답변에서 핵심 사실을 제대로 추출해야 합니다
- 챗봇이 조건을 조합해 판단해야 하듯 → judge도 wall_type마다 다른 채점 기준이 필요합니다

"평가 점수가 정말 정확한가?"가 학습 과제 중 하나인 이유가 여기 있습니다.
이 문서는 그 질문에 답합니다.

---

## 한계 1: 이진 채점 — 부분 정답이 없다

### 현재 동작

```
실제 답변에서 핵심 사실이 3개 중 2개 맞음 → score: 0
```

judge 프롬프트에 명시되어 있습니다:
```
- 부분적으로만 맞으면 오답으로 처리하세요
```

### 진짜 문제

이게 단순히 "점수가 낮게 나온다"는 문제가 아닙니다.
**wall_type마다 "부분 정답"의 의미가 완전히 다릅니다.**

| wall_type | 부분 정답 사례 | 현재 처리 | 실제로는? |
|-----------|-------------|---------|----------|
| versioning | "10일"이라 답함 (구버전, 정답은 14일) | 오답(0) | 완전 오답에 가까움 — 올바름 |
| conditional | VIP+냉장 질문에서 "VIP는 무료" 만 언급 | 오답(0) | 치명적 오류 — 올바름 |
| precedent | "보상 사례 있음, 쿠폰 제공" 언급하되 금액 미언급 | 오답(0) | 의미 있는 부분 정답 — **과도하게 엄격** |
| memory | "모르지만 주문번호 알려주시면 확인 가능" | 오답(0) 가능 | 좋은 답변 — **오채점 가능성** |

**특히 문제가 되는 케이스:**

Q037 (`wall_type: memory`):
```
expected: "I don't have access to your previous conversation history. 
           Please provide your order number..."
```
챗봇이 "이전 대화 내역이 없습니다"만 말하고 주문번호 안내를 생략하면 → 0점
하지만 이건 핵심을 맞힌 것입니다. 현재 judge는 이 차이를 구분 못합니다.

### 개선 방향

**옵션 A: 3단계 점수 (가장 현실적)**
```python
# judge 프롬프트 변경
"""
{"score": 1,   "reason": "..."}  # 핵심 사실 모두 일치
{"score": 0.5, "reason": "..."}  # 핵심 사실 일부 일치 또는 방향은 맞으나 누락 있음
{"score": 0,   "reason": "..."}  # 핵심 사실 틀렸거나 없음
"""
```

**옵션 B: wall_type별 채점 기준 분리 (더 정확하지만 복잡)**
```python
JUDGE_PROMPTS = {
    "memory": "챗봇이 '모른다'고 적절히 거절하면서 대안을 제시했는가?",
    "precedent": "챗봇이 사례 기반으로 유사한 결론을 도출했는가? 정확한 수치가 없어도 방향이 맞으면 부분 점수.",
    "noise": "챗봇이 잘못된 정보(chatlog의 오답)를 반복하지 않고 공식 정책을 따랐는가?",
    "default": "기존 프롬프트"
}
```

---

## 한계 2: expected_answer가 영어, 실제 답변은 한국어

### 현재 동작

judge가 받는 입력:
```
질문:    "VIP인데 냉장 상품 배송비도 무료인가요?" (한국어)
기대 답변: "No. VIP members receive free Priority shipping... cold-chain carries a flat 4,000 won fee..." (영어)
실제 답변: "VIP 회원이시더라도 냉장 배송은 4,000원 요금이 별도로 부과됩니다." (한국어)
```

### 진짜 문제

단순히 "번역에 의존한다"는 게 아닙니다.
두 가지 구체적인 오채점 시나리오가 있습니다:

**시나리오 1: 챗봇이 더 많이 말했을 때**

expected_answer가 3가지 사실을 담고 있는데, 챗봇이 4가지를 말했다면?
현재 judge는 "핵심 사실이 같으면 정답"이라는 기준만 있어서 이건 맞을 수 있습니다.
하지만 챗봇이 추가로 말한 내용이 틀렸다면? 현재 기준상으론 그냥 통과됩니다.

**시나리오 2: 한국 특화 용어의 불일치**

```
expected: "Kakao Pay refunds are typically instant"
actual:   "카카오페이는 거의 즉시 환불됩니다" (맞음)
actual2:  "카카오페이 환불은 보통 당일 처리됩니다" (맞음)
actual3:  "카카오톡 결제 환불은 빠릅니다" (용어 혼용, 맞는데 어색함)
```

gpt-4o-mini가 "카카오톡 결제" vs "카카오페이"를 같은 것으로 인식하는지는 실행마다 다를 수 있습니다.

### 개선 방향

`test_questions.json`에 `expected_answer_ko` 필드를 추가하는 것이 근본 해결책입니다:

```json
{
  "expected_answer": "No. VIP members receive free Priority shipping...",
  "expected_answer_ko": "아닙니다. VIP 회원도 냉장 배송 시 4,000원 요금이 부과됩니다..."
}
```

judge 프롬프트를 수정해서 한국어 기준으로 먼저 비교하도록:

```python
if expected_ko:
    # 한국어 expected 사용
else:
    # 영어 expected로 fallback
```

---

## 한계 3: judge 프롬프트가 wall_type을 모른다

### 현재 동작

모든 150개 질문에 동일한 judge 프롬프트가 사용됩니다:
```
실제 답변이 기대 답변과 사실적으로 일치하는지 평가하세요.
```

### 진짜 문제

**`noise` wall_type의 경우:**

Q085 (`wall_type: noise`):
```
expected: "Yes. Canceling while Preparing is processed instantly with no fee.
           Some agents have mistakenly advised 1-2 business days — this is incorrect."
```

챗봇이 "준비중 취소는 즉시 처리됩니다"라고만 답하면?
- 핵심 사실(즉시, 무료)은 맞음 → 정답으로 채점될 가능성 높음

하지만 실제로 이 챗봇이 잘 동작하는 건지 알 수 없습니다.
왜냐하면 chatlog에는 "1-2 영업일 걸린다"는 잘못된 상담 로그가 있고,
그걸 그대로 가져온 챗봇도 우연히 같은 점수를 받을 수 있기 때문입니다.

**`memory` wall_type의 경우:**

Q037:
```
expected: "I don't have access to your previous conversation history..."
```

챗봇이 아무 말이나 만들어서 답하면?
- judge는 기대 답변과 "다르다"고 판단해 0점 → 올바름

챗봇이 "죄송합니다, 이전 대화를 알 수 없습니다"라고만 하면?
- expected_answer의 두 번째 문장("주문번호 제공하면 확인 가능")이 없음
- judge가 엄격하면 0점, 관대하면 1점 → 판정 불일치 가능성

### 개선 방향

```python
# judge_answer 함수에 wall_type 파라미터 추가
def judge_answer(question, expected, actual, wall_type=None):
    
    wall_instructions = {
        "noise": """
            추가 기준: 챗봇이 공식 정책을 따랐는지 확인하세요.
            만약 챗봇이 잘못된 정보(예: 없는 수수료, 잘못된 기간)를 말했다면 오답입니다.
            기대 답변의 "some agents mistakenly advised" 이후 내용이 오답 기준입니다.
        """,
        "memory": """
            추가 기준: 챗봇이 '이전 대화를 알 수 없다'고 적절히 인정하면 핵심 요건을 충족합니다.
            추가 안내가 있으면 가점 요소이지만 없어도 정답입니다.
        """,
        "precedent": """
            추가 기준: 정확한 수치가 없어도 방향과 패턴이 일치하면 부분 정답으로 처리하세요.
            'Based on records, typically...' 류의 hedging은 좋은 신호입니다.
        """,
    }
    
    extra = wall_instructions.get(wall_type, "")
    prompt = BASE_PROMPT.format(question=question, expected=expected, actual=actual) + extra
```

---

## 한계 4: source_layers / wall_type 미활용 — 가장 중요한 한계

### 현재 출력

```
난이도별:
  easy    : 22/30 (73%)
  medium  : 38/94 (40%)
  hard    :  2/26  (8%)
```

이 숫자만 보면 **어디를 고쳐야 할지 모릅니다.**

### 진짜 문제

medium에서 40%가 나왔을 때 실패 원인이 다음 세 가지 중 무엇인지 알 수 없습니다:

1. **구버전 문서를 검색함** → `deprecated/` 폴더 제거 또는 메타데이터 필터링이 해결책
2. **chatlog 노이즈에 오염됨** → chatlog 처리 방식 변경이 해결책
3. **여러 조건 중 하나만 검색됨** → 쿼리 분해 또는 멀티-홉 검색이 해결책

이 세 가지는 **완전히 다른 개선 방향**을 가리킵니다.
wall_type 없이 개선하면 운이 좋아야 올바른 방향을 찾을 뿐입니다.

### 개선 방향

집계 구조 확장:

```python
# 현재
tier_results = {"easy": {"correct": 0, "total": 0}, ...}

# 개선 후
wall_results   = {}  # {"versioning": {"correct": 0, "total": 0}, ...}
layer_results  = {}  # {"faq": ..., "policy": ..., "chatlog": ..., "faq+policy": ...}
intent_results = {}  # {"check_refund_policy": ..., "delivery_options": ..., ...}
```

추가 출력:

```
벽 유형별:
  versioning   :  4/15 (27%)  ← 구버전 문서 처리 문제
  noise        :  2/12 (17%)  ← chatlog 오염 문제
  conditional  : 18/40 (45%)  ← 조건 조합 문제
  cross_language: 20/29 (69%) ← 언어 처리 양호
  contradiction:  2/8  (25%)  ← 출처 우선순위 문제
  precedent    :  1/6  (17%)  ← chatlog 활용 문제
  memory       :  3/4  (75%)  ← 거절 처리 양호

출처 레이어별:
  faq only          : 20/30 (67%)
  policy only       :  8/18 (44%)
  faq + policy      : 22/55 (40%)
  chatlog + policy  :  4/18 (22%)  ← chatlog 관련 질문 취약
```

이 정보가 있어야 "챗봇에서 무엇을 고쳐야 하는지"가 명확해집니다.

---

## 한계 5: 재현성 — 점수가 실행마다 다를 수 있다

### 현재 상황

judge는 `temperature=0`으로 설정되어 일관됩니다.
하지만 **챗봇 서버의 temperature는 학습자가 설정합니다.**

만약 챗봇이 `temperature=0.7`이라면:
- 1회 실행: 62/150 (41.3%)
- 2회 실행: 58/150 (38.7%)
- 3회 실행: 65/150 (43.3%)

"코드를 바꿨더니 점수가 올랐다" vs "그냥 운이 좋았다"를 구분할 방법이 없습니다.

### 진짜 문제

개선 → 측정의 사이클에서 **측정 자체가 노이즈**입니다.
2~3% 차이가 유의미한 변화인지 통계적 변동인지 알 수 없습니다.

### 개선 방향

**옵션 A: 신뢰 구간 측정 (가장 정직한 방법)**

```python
parser.add_argument("--runs", type=int, default=1, help="평가 반복 횟수")

# runs > 1이면 동일 질문셋을 N회 실행해 평균 ± 표준편차 출력
# 예: 41.3% ± 2.1% (3회 평균)
```

**옵션 B: 결정론적 평가 모드**

```python
# evaluate.py 실행 시 챗봇 서버에 temperature=0 강제 요청
# (챗봇 API가 파라미터를 받도록 학습자가 구현해야 함)
json={"question": question, "temperature": 0}
```

이 자체가 학습 과제입니다: "평가용 모드를 챗봇에 추가하려면 어떻게 해야 하는가?"

---

## 한계 6: 실패 원인이 저장되지 않는다

### 현재 동작

틀린 질문에서 저장되는 것:
- verbose 모드라면 콘솔에 출력 (휘발됨)
- `eval_result.json`에는 집계 숫자만 있음

### 진짜 문제

개선하려면 "왜 틀렸는지"를 알아야 합니다.
지금은 다시 verbose 모드로 전체를 돌려야만 알 수 있습니다.

또한 judge의 reason을 모으면 **실패 패턴**이 보입니다:
- "마켓플레이스 예외를 언급하지 않음" → 5번 반복
- "구버전 기간(7일)을 언급함" → 3번 반복
- "냉장 배송 예외를 누락" → 4번 반복

이 패턴이 벽 리포트의 핵심 근거가 됩니다.

### 개선 방향

`failed_cases.json` 저장:

```python
failed_cases = []

# 오답 시
if score == 0:
    failed_cases.append({
        "id": qid,
        "tier": tier,
        "wall_type": wall_type,
        "source_layers": source_layers,
        "primary_intent": primary_intent,
        "question": question_ko,
        "expected": expected,
        "actual": actual_answer,
        "judge_reason": judgment.get("reason", "")
    })

# 저장
with open(DATA_DIR / "failed_cases.json", "w") as f:
    json.dump(failed_cases, f, indent=2, ensure_ascii=False)
```

이 파일 하나가 벽 리포트의 절반을 채워줄 수 있습니다.

---

## 한계 7: 순차 실행 — 5분이 너무 길다

### 현재 상황

150개 질문 × (챗봇 응답 1-3초 + judge 응답 1-2초) ≒ **5-8분**

개선 사이클이 "수정 → 5분 대기 → 결과 확인"이 됩니다.

### 개선 방향

**병렬화 (ThreadPoolExecutor)**:

```python
from concurrent.futures import ThreadPoolExecutor, as_completed

def evaluate_single(q):
    response = ask_server(q["question_ko"])
    if response is None:
        return q["id"], None, q
    actual = response.get("answer", "")
    judgment = judge_answer(q["question_ko"], q["expected_answer"], actual)
    return q["id"], judgment, q

with ThreadPoolExecutor(max_workers=5) as executor:
    futures = {executor.submit(evaluate_single, q): q for q in questions}
    for future in as_completed(futures):
        qid, judgment, q = future.result()
        # 집계 처리
```

주의: `max_workers`를 너무 높이면 챗봇 서버 또는 OpenAI rate limit에 걸립니다.
5 정도가 현실적입니다. **예상 소요 시간: 1-2분으로 단축.**

---

## 개선 우선순위

개선 효과 대비 구현 난이도로 정렬했습니다.

| 순위 | 개선 항목 | 효과 | 구현 난이도 | 이유 |
|------|---------|------|-----------|------|
| ★★★ | **wall_type / source_layers 집계 추가** | 분석 방향이 명확해짐 | 낮음 | test_questions.json에 이미 데이터 있음. 집계 코드만 추가하면 됨 |
| ★★★ | **failed_cases.json 저장** | 벽 리포트 작성이 쉬워짐 | 낮음 | 오답 시 dict append 하나면 됨 |
| ★★  | **wall_type별 judge 프롬프트 분기** | 채점 정확도 향상 | 중간 | memory, noise, precedent용 추가 지침만 작성하면 됨 |
| ★★  | **병렬화** | 실행 시간 80% 단축 | 중간 | ThreadPoolExecutor 도입 |
| ★   | **3단계 점수 (0/0.5/1)** | 미세 개선 측정 가능 | 중간 | judge 프롬프트 + 집계 로직 변경 |
| ★   | **expected_answer_ko 추가** | 판정 언어 불일치 해소 | 높음 | test_questions.json 전체 수정 필요 |
| ★   | **반복 실행 + 신뢰 구간** | 통계적 안정성 | 중간 | --runs 파라미터 추가 |

---

## 메타 교훈: 평가 도구도 "벽"을 가진다

이 개선안들을 보고 나면 한 가지 패턴이 보입니다.

**챗봇을 개선하는 방법과 평가 도구를 개선하는 방법이 구조적으로 같습니다:**

| 챗봇의 문제 | 평가 도구의 같은 문제 |
|-----------|-------------------|
| 구버전 문서를 구분 못함 | expected_answer가 단 하나의 버전만 반영 |
| 노이즈 데이터를 필터링 못함 | judge가 noise wall_type을 모르고 채점 |
| 조건 조합을 처리 못함 | 모든 질문에 동일한 judge 기준 적용 |
| "모른다"고 말해야 할 때를 모름 | memory 질문의 거절 답변을 오답 처리 |
| 결과의 근거를 남기지 않음 | 실패 이유가 휘발되어 분석 불가 |

좋은 평가 도구를 만드는 것과 좋은 챗봇을 만드는 것은 다른 문제처럼 보이지만,
실제로는 같은 엔지니어링 감각이 필요합니다.

그래서 GUIDE.md가 "평가 도구 자체도 벽의 일부입니다"라고 말하는 것입니다.

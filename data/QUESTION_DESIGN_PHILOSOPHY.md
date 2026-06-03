# 질문 설계 철학 분석

## 이 문서의 목적

이 문서는 `test_questions.json`의 150개 질문이 **왜 이렇게 구성되었는지**를 분석합니다.
다른 도메인에서 동일한 학습 환경을 재현하려는 사람을 위한 설계 가이드이기도 합니다.

---

## 1. 데이터 계층 구조가 이렇게 만들어진 이유

현실의 고객지원 데이터는 언제나 이 세 종류가 섞여 있습니다.

```
layer1_faq/               ← 공식 문서 (정제됨, 공개됨)
layer2_policies/
  ├── current/            ← 현행 정책 (권위 있음, 비공개)
  ├── deprecated/         ← 구버전 정책 (오래됨, 위험함)
  └── internal/           ← 내부 지침 (비공개, 예외 규정 포함)
layer3_chatlogs/          ← 실제 상담 로그 (노이즈 있음, 선례 있음)
```

각 계층은 단순히 "데이터 출처"가 아닙니다.
**신뢰도와 접근 권한의 계층**입니다.

| 계층 | 신뢰도 | 공개 여부 | 함정 요소 |
|------|--------|----------|----------|
| layer1_faq | 높음 | 공개 | 최신성 (FAQ가 정책 개정을 못 따라갈 수 있음) |
| layer2/current | 최고 | 비공개 | 복잡한 조건부 규칙 |
| layer2/deprecated | 낮음 (구버전) | 비공개 | **존재 자체가 함정** |
| layer2/internal | 높음 (내부) | 내부 전용 | 공개하면 안 되는 예외 규정 포함 |
| layer3_chatlogs | 혼합 | 비공개 | `agent_accuracy: "incorrect"` 항목 존재 |

**핵심 설계 의도**: 세 계층을 단순히 합치면 틀린 챗봇이 만들어지도록 설계되어 있습니다.
어떤 계층을 어떻게 다룰지를 스스로 결정하는 것이 학습 과제의 본질입니다.

---

## 2. 7가지 Wall Type — 실제 RAG 시스템의 실패 모드

각 질문에 붙어있는 `wall_type`은 순전히 레이블이 아닙니다.
**특정 구현 방식이 왜 실패하는지를 유형화한 것**입니다.

### 2.1 `null` — 순수 사실 질문 (함정 없음)

```json
"question_ko": "포인트 유효기간이 어떻게 되나요?",
"wall_type": null,
"source_layers": ["faq"]
```

FAQ 한 곳에만 있는 단순 사실. 어떤 RAG 구현이든 맞혀야 합니다.
**목적**: baseline 성능을 측정하기 위한 앵커 질문들.

---

### 2.2 `versioning` — 구버전 정책과의 충돌

```json
"question_ko": "환불 기간이 7일이라고 들었는데 맞나요?",
"expected_answer": "No. The current return window is 14 calendar days (as of April 2024, policy v3)...",
"wall_type": "versioning"
```

**함정 구조**:
```
layer2/deprecated/return-policy-v1.md  →  "7일"  (deprecated)
layer2/deprecated/return-policy-v2.md  →  "10일" (deprecated)
layer2/current/return-policy-v3.md     →  "14일" ← 정답
```

단순히 모든 문서를 벡터DB에 넣으면 오래된 숫자(7일, 10일, 3만원, 7% 포인트 등)가 검색 결과에 섞입니다.
**배우는 것**: 문서의 버전/상태를 메타데이터로 관리해야 한다. 검색 시 `status: current`만 사용해야 한다.

현재 프로젝트에서 versioning 함정이 설정된 수치들:

| 항목 | 구버전 (함정) | 현행 |
|------|------------|------|
| 반품 기간 | 7일(v1), 10일(v2) | **14일** |
| 무료배송 기준 | 3만원 | **2만원** |
| Standard 포인트 적립률 | 3% | **1%** |
| Plus 포인트 적립률 | 5% | **3%** |
| VIP 포인트 적립률 | 7% | **5%** |
| Plus 등급 기준 | 15만원 | **20만원** |
| VIP 등급 기준 | 60만원 | **80만원** |

---

### 2.3 `contradiction` — 두 공식 출처의 충돌

```json
"question_ko": "마켓플레이스 상품도 14일 안에 반품할 수 있나요?",
"wall_type": "contradiction"
```

**함정 구조**:
```
FAQ: "모든 상품 14일 반품 가능"
Policy: "마켓플레이스 상품은 판매자별 정책 적용 (3~30일)"
```

두 공식 출처가 모순되는 것처럼 보입니다.
FAQ는 "일반 원칙"을, 정책 문서는 "예외"를 다루기 때문입니다.

**배우는 것**: 출처 간 우선순위가 있다. 더 구체적인 정책이 일반 원칙을 오버라이드한다.
내부 지침(`cs-team-return-exceptions.md`)에는 "VIP에게 30일 허용" 같은 비공개 예외도 있어서,
이를 그대로 노출하면 안 된다는 문제도 함께 있음.

---

### 2.4 `conditional` — 복수 조건의 교차

```json
"question_ko": "VIP인데 냉장 상품 배송비도 무료인가요?",
"expected_answer": "No. VIP members receive free Priority shipping on standard orders, but cold-chain delivery carries a flat 4,000 won fee for all members...",
"wall_type": "conditional"
```

**함정 구조**:
```
규칙 A: VIP → 모든 주문 우선배송 무료
규칙 B: 냉장배송 → 전 등급 4,000원 별도 과금

→ VIP + 냉장배송 = 4,000원 과금 (규칙 B가 규칙 A를 override)
```

단일 규칙만 검색하면 "VIP는 무료"라는 틀린 답이 나옵니다.
여러 조건을 동시에 만족하는 경우에만 정답을 낼 수 있습니다.

**배우는 것**: 질문이 여러 도메인에 걸쳐 있을 때 단일 검색 결과만으론 부족하다.

conditional 질문들이 다루는 조건 조합:
- 등급(Standard/Plus/VIP) × 배송 유형(일반/냉장/당일)
- 구독 여부 × 무료배송 기준
- 반품 시기(준비중/발송후) × 사유(변심/결함)
- 등급 × 마켓플레이스 상품
- 구독 기간(6개월/12개월) × 혜택 누적

---

### 2.5 `noise` — 채팅 로그의 오답

```json
"question_ko": "준비중 상태에서 취소하면 취소 수수료 2,000원 나오나요?",
"wall_type": "noise"
```

채팅 로그에는 실수한 상담사의 대화가 포함되어 있습니다:

```json
// 2024-01.jsonl — 실제 오답 로그 예시
{
  "agent_accuracy": "incorrect",
  "accuracy_note": "현행 반품 기간은 14일(v3 정책). agent_jung이 구버전(v1, 7일) 기준으로 잘못 안내함."
}
```

**함정 구조**: 채팅 로그를 그대로 RAG에 넣으면, 상담사의 오답이 "실제 상담에서 그렇게 말했다"는 근거로 검색됩니다.

noise 함정이 포함된 잘못된 정보들:
- 준비중 취소 수수료 2,000원 (실제: 없음)
- 고객센터 24시간 운영 (실제: 평일 9-18시)
- 페이코 결제 지원 (실제: 미지원)
- 가상계좌 결제 지원 (실제: 미지원)
- 회원 탈퇴 후 즉시 재가입 가능 (실제: 30일 대기)

**배우는 것**: 채팅 로그는 선례 파악에는 유용하지만, 사실 정보를 직접 추출할 때는 위험하다.
`agent_accuracy` 메타데이터를 활용하거나, FAQ/Policy를 우선시해야 한다.

---

### 2.6 `precedent` — 선례 기반 질문

```json
"question_ko": "배송 지연됐는데 보상받은 사례가 있나요?",
"expected_answer": "Based on customer support records, delivery delays exceeding 3 business days typically result in a 2,000 won coupon compensation...",
"wall_type": "precedent",
"source_layers": ["chatlog", "policy"]
```

이 질문은 공식 FAQ나 정책 문서에는 없습니다.
실제 상담 로그에서 패턴을 읽어야만 답할 수 있습니다.

**함정 구조**: 공식 문서에만 의존하면 "모르겠습니다"가 되거나 엉뚱한 답이 나옵니다.
채팅 로그가 유용하게 쓰이는 유일한 방식 = 선례 패턴 합성.

**배우는 것**: layer3_chatlogs는 사실 검증에는 위험하지만, 패턴 합성(어떤 보상이 통상 이루어지는가)에는 적합하다.

---

### 2.7 `cross_language` — 구어체/비규범적 한국어

```json
"question_ko": "걍 환불해주세요 ㅠㅠ",
"tier": "hard",
"wall_type": "cross_language"
```

```json
"question_ko": "취소했는데 돈언제들어옴ㅠㅠ",
"tier": "medium",
"wall_type": "cross_language"
```

**설계 의도**: 실제 고객은 맞춤법이나 띄어쓰기를 지키지 않습니다.
"어케함", "ㅠㅠ", "걍", "좀", 뒤에 "ㅋ" 붙이기 등이 모두 포함됩니다.

cross_language 질문들의 언어 스타일:
- 어휘 단축: "어케함", "얼마됨", "됨?"
- 구어체: "걍", "좀", "ㅠㅠ", "ㄱㄱ"
- 문법 생략: "환불어케함? 반품하고싶은데"
- 음슬어: "비번까먹었는데ㅠ"

**배우는 것**: LLM이 의미를 이해하는 부분보다, 검색(embedding) 단계에서 비정형 입력을 어떻게 처리하는지가 관건이다.

---

### 2.8 `memory` — 대화 기억 필요

```json
"question_ko": "저번에 물어본 배송 건 어떻게 됐어요?",
"expected_answer": "I don't have access to your previous conversation history...",
"wall_type": "memory"
```

**의도적으로 답할 수 없는 질문**입니다.
정답은 "모른다"고 적절히 응답하는 것입니다.

**배우는 것**: 챗봇이 모든 질문에 답할 수 있는 척하면 안 된다. 맥락이 없을 때 graceful하게 거절하는 것도 품질이다.

---

## 3. 티어 설계 원리

```
easy   30개 = 단일 출처 × null wall × 정형 언어
medium 94개 = 복수 출처 또는 오류 가정 × 다양한 wall
hard   26개 = 비정형 언어 + 복합 조건 + 선례/기억
```

### easy → medium 전환 메커니즘

같은 주제도 "가정이 잘못된 질문"이면 medium이 됩니다:

| easy | medium |
|------|--------|
| "반품 기간이 얼마인가요?" | "반품 기간이 7일이라고 들었는데 맞나요?" |
| "무료배송 기준이 얼마인가요?" | "무료배송 기준이 3만원 아닌가요?" |
| "포인트 적립률이 얼마인가요?" | "Standard가 3%라고 들었는데 맞나요?" |

잘못된 전제를 가진 질문 = versioning/noise 함정 = medium으로 분류.

### medium → hard 전환 메커니즘

같은 내용도 비정형 언어로 쓰면 hard가 됩니다:

| medium | hard |
|--------|------|
| "반품을 신청하려면 어떻게 하나요?" | "걍 환불해주세요 ㅠㅠ" |
| "배송은 얼마나 걸리나요?" | "배송 언제 와요?" |
| "포인트를 현금으로 바꿀 수 있나요?" | "포인트 환금되나요?" |

---

## 4. `primary_intent` 분류가 존재하는 이유

```json
"primary_intent": "check_refund_policy"
```

evaluate.py는 현재 이 필드를 사용하지 않습니다.
그러나 이 필드는 다음 분석을 위한 준비입니다:

- **어떤 의도 유형에서 가장 많이 실패하는가?**
- **delivery_options에서의 실패 원인이 check_refund_policy와 다른가?**

이것이 벽 리포트의 핵심 분석 도구가 됩니다.
evaluate.py를 개선하는 방향 중 하나가 intent별 정확도를 추가하는 것입니다.

---

## 5. 다른 도메인에서 동일한 환경을 만들기 위한 설계 공식

### 5.1 도메인 선택 기준

이 학습 환경이 잘 작동하는 도메인의 공통점:

1. **규칙이 시간이 지나면서 바뀐다** → versioning 함정 가능
2. **공식 채널과 실제 운영 사이에 갭이 있다** → noise/precedent 함정 가능
3. **여러 조건이 교차하는 예외 규정이 있다** → conditional 함정 가능
4. **사용자가 비전문가여서 비정형 언어를 쓴다** → cross_language 함정 가능

적합한 도메인 예: 은행 상품/대출 정책, 병원 수납/보험 안내, 인사/노무 규정, 커머스 플랫폼, 공공 행정 안내

---

### 5.2 데이터 계층 설계 레시피

```
layer1_<공개문서>/
  각 주제별 .md 파일 (FAQ, 안내문, 제품 설명 등)
  → 명확하고 정제된 내용
  → 단일 질문에 단일 답변이 나오도록 설계

layer2_<내부정책>/
  current/   → 현행 정책 (버전 메타데이터 포함: version, status, effective_date)
  deprecated/ → 구버전 정책 (deprecated_date, superseded_by 포함)
               ← 이 디렉토리가 "versioning" 함정의 핵심
  internal/  → 예외 규정 (공개하면 안 되는 내용 포함)
               ← 이 디렉토리가 "contradiction" 함정의 일부

layer3_<상호작용로그>/
  JSONL 파일 (월별 또는 주제별)
  → agent_accuracy: "correct" | "incorrect"
  → accuracy_note: 오답인 경우 이유 설명
  ← "noise" 함정과 "precedent" 데이터의 원천
```

**Policy 파일 메타데이터 필수 항목**:
```yaml
---
version: v3
status: current  # current | deprecated | internal
effective_date: 2024-04-01
supersedes: return-policy-v2.md  # deprecated일 경우 superseded_by
---
```

---

### 5.3 질문 설계 레시피 (150개 기준)

**비율 권장**: easy 20% / medium 63% / hard 17%
(현재 프로젝트: 30/94/26 = 20%/63%/17%)

**easy 질문 설계 (30개)**:
- 단일 출처(layer1)에서 직접 읽히는 질문
- 정형 한국어
- wall_type = null
- expected_answer은 해당 문서의 사실을 그대로 표현
- source_layers = ["faq"]

**medium 질문 설계 (94개)**:
크게 세 유형으로 나눕니다:

① versioning/noise 가정 질문 (약 30개):
   "X라고 들었는데 맞나요?" 형식
   구버전 수치나 오답을 전제에 심어둠

② conditional 복합 조건 질문 (약 35개):
   "A이면서 B인 경우 C는 어떻게 되나요?" 형식
   적어도 두 개 이상의 규칙을 조합해야 정답 가능

③ cross_language 구어체 질문 (약 29개):
   easy 질문의 내용을 비정형 한국어로 재작성
   "어케함", "ㅠㅠ", 문법 생략 등

**hard 질문 설계 (26개)**:
- precedent: "다른 고객들은 어떻게 해결했나요?" (layer3 필요)
- memory: "지난번에 물어본 거 어떻게 됐나요?" (답 불가)
- cross_language + 복합 조건 조합
- 여러 wall_type이 중첩

---

### 5.4 wall_type 태깅 기준

| wall_type | 태깅 조건 |
|-----------|---------|
| null | 단일 출처, 정형 언어, 조건 없음 |
| versioning | 구버전 문서와 현행 문서의 수치 차이가 있을 때 |
| contradiction | 두 공식 출처가 같은 주제에 다른 답을 줄 때 |
| conditional | 답이 2개 이상의 조건 조합에 따라 달라질 때 |
| noise | chatlog에 오답이 있을 때 |
| precedent | 공식 문서에 없고 chatlog 패턴에서만 답 가능할 때 |
| cross_language | 비정형/구어체 한국어 사용 시 |
| memory | 개인 맥락/대화 기록 없이는 답 불가할 때 |

---

### 5.5 expected_answer 작성 원칙

현재 프로젝트의 expected_answer는 영어로 작성되어 있습니다.
이유: judge 모델(gpt-4o-mini)이 영어로 비교할 때 더 안정적이기 때문입니다.

1. **사실 중심**: 문장이 달라도 핵심 수치/사실이 같으면 정답이 되도록
2. **조건 명시**: "단, X인 경우 예외" 형식으로 conditional 상황을 포함
3. **출처 힌트 포함**: "as of April 2024, policy v3" 같이 버전 정보 포함
4. **부정 정정 포함**: versioning 질문은 "No. The current X is Y (not Z)"로 시작

---

## 6. 설계의 핵심 의도 요약

이 데이터셋은 학습자에게 다음 사실을 직접 경험하게 하기 위해 만들어졌습니다:

**①** 같은 내용이라도 어디서 가져오느냐에 따라 답이 달라진다 (versioning)

**②** 공식 문서가 여러 개일 때 무엇이 "더 맞는지"를 판단해야 한다 (contradiction/conditional)

**③** 현실 데이터에는 노이즈가 있고, 그 노이즈를 그대로 믿으면 틀린 챗봇이 된다 (noise)

**④** FAQ/정책에 없는 질문이 존재하며, 그것은 선례(chatlog)로 답해야 한다 (precedent)

**⑤** 고객은 맞춤법 따위를 지키지 않는다 (cross_language)

**⑥** 챗봇이 답할 수 없는 질문에는 솔직하게 "모른다"고 해야 한다 (memory)

이 여섯 가지가 **"벽"**입니다.
wall-report.md에 써야 하는 것들이 바로 이것들입니다.

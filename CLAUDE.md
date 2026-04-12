# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

트랜잭션 ACID 속성을 학습 테스트로 검증하는 Spring Boot 프로젝트. PostgreSQL과 MySQL 두 DB를 사용하여 DB별 동작 차이를 비교 실험한다.

## Tech Stack

- Java 21, Spring Boot 4.0.5, Spring Data JPA
- PostgreSQL 17 / MySQL 8.4 (docker-compose)
- JUnit 5, AssertJ, Lombok, p6spy

## Build & Test Commands

```bash
./gradlew build              # 빌드
./gradlew test               # 전체 테스트
./gradlew test --tests "eastmeet.voyage.transaction.learning_test.DurabilityTest"  # 단일 테스트 클래스
./gradlew test --tests "*.DurabilityTest.커밋된*"  # 단일 테스트 메서드
docker compose up -d         # DB 컨테이너 실행
```

## DB Profile

- 기본 프로필: `postgresql` (`SPRING_PROFILE` 환경변수로 전환)
- `.env` 파일에 DB 접속정보 설정 (git 추적 제외)
- 일부 학습 테스트는 MySQL 전용 (DirtyReadTest, NonRepeatableReadTest, LostUpdateMySQLTest)

## Architecture

```
src/main/java/eastmeet/voyage/transaction/
├── account/
│   ├── domain/        # Account 엔티티, AccountStatus enum
│   ├── repository/    # AccountRepository (JPA + 낙관적/비관적 락 쿼리)
│   └── service/       # AccountService (이체, 조회)
└── global/entity/     # BaseTimeEntity (Auditing)

src/test/java/eastmeet/voyage/transaction/
├── learning_test/     # ACID 학습 테스트 (Atomicity, Consistency, Isolation, Durability)
├── basic/             # 트랜잭션 AOP 기본 동작 테스트
└── account/service/   # AccountService 단위 테스트
```

## Code Conventions

- 검증/유틸리티: Apache Commons Lang3 (`Validate.notNull`, `StringUtils` 등)
- deprecated API 사용 금지 — 대체 API 사용 (예: `jakarta.persistence.CheckConstraint`)
- 커밋 전 불필요한 import 제거 필수

## Git & PR Rules

- PR merge는 사용자가 직접 수행. Claude는 PR 생성까지만.
- 브랜치 병합: merge 대신 rebase 사용
- `.gitignore` 규칙 최우선 — `git add -f` 금지
- PR body에 `Generated with Claude Code` 문구 넣지 않기

### PR Message Convention

```
Title: [Type]: <간결한 제목>
Type: [Feature], [Fix], [Refactor], [Chore], [Docs], [Test]

Body 섹션: Summary → Changes (도메인별 그룹핑) → Technical Notes → Checklist
Checklist: 다음 PR 항목은 (다음 PR)로 표기
```

## Learning Test Pattern

- 학습 테스트는 `learning_test/` 패키지에 작성
- `TransactionTemplate`으로 프로그래밍 방식 트랜잭션 제어
- `@SpringBootTest`로 실제 DB 연동 테스트 (mock 미사용)
- 테스트 메서드명은 한글로 시나리오 설명 (예: `커밋된_데이터는_새로운_트랜잭션에서도_조회되는가`)

## Teaching Method — 파인만 교수법 (Feynman Technique)

> "What I cannot create, I do not understand." — Richard Feynman

이 프로젝트에서 Claude는 **선생이 아니라 조력자**다. 코드를 대신 작성해주는 것이 아니라, 사용자가 스스로 이해하고 작성할 수 있도록 이끈다.

### 원칙

1. **코드를 먼저 주지 않는다** — 개념 설명과 힌트를 제공하고, 사용자가 직접 코드를 작성한다
2. **질문으로 사고를 유도한다** — "이 경우 어떤 결과가 나올까요?", "왜 이렇게 동작할까요?" 같은 질문으로 스스로 답을 찾게 한다
3. **틀려도 답을 바로 알려주지 않는다** — 어디가 틀렸는지 방향만 짚어주고, 수정도 사용자가 한다
4. **단순한 언어로 설명한다** — 파인만이 강조한 것처럼, 복잡한 개념도 쉬운 말로 핵심만 전달한다

### 학습 흐름

```
1. 개념 소개 — 핵심을 1~2문장으로 요약
2. 학습 포인트 제시 — 이번에 검증할 것이 무엇인지 명확히
3. 힌트 제공 — 테스트 시나리오, 사용할 API, 메서드명 추천
4. 사용자가 코드 작성 — Claude는 기다린다
5. 리뷰 & 피드백 — 작성한 코드를 검토하고 개선점 제시
6. 다음 단계로 — 통과하면 다음 테스트로 진행
```

### 역사적 맥락 제공

새로운 개념을 학습할 때 반드시 **왜 이 기술이 등장했는지** 역사적 배경을 함께 전달한다.

- **어떤 문제가 있었는가** — 이 기술이 없던 시절에 어떤 고통이 있었는지
- **누가, 언제 해결했는가** — 핵심 논문, 인물, 시대적 배경 (예: Jim Gray의 트랜잭션 이론, Kung & Robinson의 낙관적 동시성 제어)
- **어떤 트레이드오프를 선택했는가** — 모든 기술은 무언가를 포기하고 얻은 것이다. 그 선택지를 사용자가 직접 판단해보게 한다
- **지금은 어떻게 발전했는가** — 초기 설계와 현재 구현의 차이, DB마다 다른 선택을 한 이유

```
예시 흐름:
"REPEATABLE_READ를 학습하기 전에 — 1976년 System R에서 격리 수준을 처음 정의했을 때,
성능과 안전성 사이에서 어떤 타협을 했을까요? 4단계로 나눈 이유가 뭘까요?"
→ 사용자가 생각해본 뒤 → 당시의 제약과 선택을 설명
→ "그렇다면 MySQL과 PostgreSQL이 같은 REPEATABLE_READ인데 왜 동작이 다를까요?"
→ 사용자가 실험으로 직접 확인
```

### 인지 편향 주의

- **능력 착각 (Illusion of Competence)** — 코드를 읽고 "이해했다"고 느끼는 것과 직접 작성할 수 있는 것은 다르다. 그래서 코드를 대신 주지 않는다.
- **확증 편향 (Confirmation Bias)** — 테스트가 통과하면 거기서 멈추기 쉽다. "반대 경우는?" "이 조건이 바뀌면?" 같은 반례를 던져서 검증 습관을 기른다.
- **던닝-크루거 효과** — "다 알겠다" 느낌이 올 때가 가장 위험하다. 그때 더 깊은 질문을 던진다.

### 금지 사항

- 사용자가 명시적으로 "작성해줘"라고 하기 전에 완성된 테스트 코드를 제공하지 않는다
- 답을 모를 때 "이렇게 하면 됩니다"로 끝내지 않는다 — 왜 그런지까지 이해시킨다
- 한 번에 너무 많은 개념을 던지지 않는다 — 한 테스트, 한 개념씩 진행한다

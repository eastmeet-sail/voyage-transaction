# voyage-transaction

트랜잭션의 본질을 이해하기 위한 학습 프로젝트.

"트랜잭션은 왜 필요한가?"라는 질문에서 출발하여, ACID 각 속성을 **직접 깨뜨려보고 복구하는 실험**을 통해 데이터베이스가 신뢰성을 보장하는 원리를 체득한다.

## 왜 이 프로젝트를 만들었는가

`@Transactional`을 붙이면 트랜잭션이 된다는 건 누구나 안다. 하지만 이런 질문에 답할 수 있는가?

- 트랜잭션 없이 송금하면 **정확히 어떻게** 깨지는가?
- Checked Exception이 발생하면 왜 롤백이 안 되는가?
- MySQL과 PostgreSQL의 REPEATABLE_READ가 왜 다르게 동작하는가?
- `flush()`를 했는데 왜 데이터가 사라지는가?
- 낙관적 락과 비관적 락 중 무엇을 선택해야 하는가?

이 프로젝트는 이런 질문들에 대해 **코드로 실험하고, 결과로 증명하고, 역사적 맥락으로 이해하는** 학습 과정을 기록한다.

## 학습 로드맵

### ACID

| 순서 | 속성 | 핵심 질문 | 문서 |
|------|------|-----------|------|
| 1 | **Atomicity** (원자성) | 실패 시 전부 취소되는가? | [docs/atomicity.md](docs/atomicity.md) |
| 2 | **Consistency** (일관성) | 트랜잭션 전후 데이터 무결성이 유지되는가? | [docs/consistency.md](docs/consistency.md) |
| 3 | **Isolation** (격리성) | 동시 트랜잭션 간 간섭이 방지되는가? | [docs/isolation.md](docs/isolation.md) |
| 4 | **Durability** (지속성) | 커밋된 데이터는 영구 보존되는가? | [docs/durability.md](docs/durability.md) |

### 학습 테스트 목록

**Atomicity**
- 트랜잭션 미사용 시 원자성 깨짐 확인
- 트랜잭션 사용 시 원자성 보장 확인
- Checked / Unchecked Exception 롤백 동작 차이
- `rollbackFor` 설정에 따른 롤백 제어

**Consistency**
- DB CHECK 제약조건으로 음수 잔액 방지
- 송금 전후 총 잔액 보존 검증

**Isolation**
- Dirty Read 발생 / 방지 (MySQL READ_UNCOMMITTED vs READ_COMMITTED)
- Non-Repeatable Read 발생 / 방지 (MySQL READ_COMMITTED vs REPEATABLE_READ)
- Phantom Read 방지 (MySQL Gap Lock, PostgreSQL MVCC)
- Lost Update — MySQL vs PostgreSQL 동작 차이
- 낙관적 락 (`@Version`) / 비관적 락 (`SELECT FOR UPDATE`)

**Durability**
- 커밋된 데이터 영구 조회 검증
- 롤백된 데이터 미존재 검증
- flush != commit 검증

**Transaction AOP**
- `@Transactional` AOP 프록시 생성 확인
- 메서드 레벨 vs 클래스 레벨 우선순위

## 기술 스택

- Java 21 / Spring Boot 4.0.5 / Spring Data JPA
- PostgreSQL 17 / MySQL 8.4
- JUnit 5 / AssertJ

## 실행 방법

```bash
# DB 실행
docker compose up -d

# 테스트 실행
./gradlew test

# 특정 테스트만 실행
./gradlew test --tests "eastmeet.voyage.transaction.learning_test.DurabilityTest"
```

## 학습 방법론

이 프로젝트는 **파인만 교수법 (Feynman Technique)** 을 기반으로 한다.

> "What I cannot create, I do not understand." — Richard Feynman

1. **개념 선택** — 학습할 개념을 하나 정한다 (예: Dirty Read)
2. **단순하게 설명** — 어린아이에게 가르치듯, 쉬운 말로 설명을 시도한다. 설명이 막히는 지점이 곧 이해의 빈틈이다
3. **빈틈 채우기** — 설명이 막힌 부분을 코드로 실험하고, 역사적 맥락(논문, 인물, 트레이드오프)을 통해 **왜** 이렇게 설계되었는지 이해한다
4. **단순화와 비유** — 실험 결과를 문서로 기록하여, 자신의 언어로 설명할 수 있을 때까지 다듬는다

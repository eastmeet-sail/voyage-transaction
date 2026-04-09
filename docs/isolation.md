# Isolation (격리성)

> 동시에 실행되는 트랜잭션이 서로 간섭하지 않아야 한다.

## 핵심 개념

- 여러 트랜잭션이 동시에 실행될 때, 각 트랜잭션은 **다른 트랜잭션의 중간 상태를 볼 수 없어야** 한다
- 격리 수준이 높을수록 안전하지만, 성능(동시성)은 떨어진다
- DB마다 기본 격리 수준이 다르다

## 동시성 문제 3가지

| 문제 | 설명 | 위험도 |
|------|------|--------|
| **Dirty Read** | 커밋되지 않은 데이터를 읽음 | 높음 |
| **Non-Repeatable Read** | 같은 조회인데 결과가 달라짐 | 중간 |
| **Phantom Read** | 없던 행이 갑자기 나타남 | 낮음 |

## 격리 수준 4단계

| 레벨 | Dirty Read | Non-Repeatable Read | Phantom Read |
|------|-----------|-------------------|-------------|
| READ_UNCOMMITTED | 허용 | 허용 | 허용 |
| READ_COMMITTED | **방지** | 허용 | 허용 |
| REPEATABLE_READ | **방지** | **방지** | 허용 |
| SERIALIZABLE | **방지** | **방지** | **방지** |

### DB별 기본 격리 수준

| DB | 기본 격리 수준 | 특이사항 |
|----|--------------|---------|
| PostgreSQL | READ_COMMITTED | READ_UNCOMMITTED 설정해도 READ_COMMITTED로 동작 |
| MySQL (InnoDB) | REPEATABLE_READ | READ_UNCOMMITTED 실제 지원 |

---

## 1. Dirty Read

> 커밋되지 않은 데이터를 다른 트랜잭션이 읽는 문제.

### 시나리오

```
트랜잭션1 (출금)                    트랜잭션2 (조회)
─────────────                      ─────────────
트랜잭션 시작
출금 (10,000 → 3,000)
flush (DB에 쓰기, 커밋은 아님)
                                   잔액 조회 → 3,000 읽힘 (Dirty Read!)
롤백 → DB 원복 (10,000)
                                   → 3,000원은 존재하지 않는 데이터였음
```

### 실험 결과

```java
// reader1 (READ_UNCOMMITTED): 커밋되지 않은 데이터가 읽힘 → Dirty Read 발생
assertThat(reader1FromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(3_000));

// reader2 (READ_COMMITTED): 커밋된 데이터만 읽힘 → Dirty Read 방지
assertThat(reader2FromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
```

**같은 시점, 같은 데이터를 조회**해도 격리 수준에 따라 결과가 다르다.

### MySQL 내부 동작 — MVCC (Multi-Version Concurrency Control)

```
트랜잭션1: UPDATE balance = 3,000
    │
    ▼
┌─────────────────────────────┐
│  Buffer Pool (메모리)         │
│  balance = 3,000 (최신 값)    │ ← READ_UNCOMMITTED는 여기를 읽음
└─────────────────────────────┘
    │
    ▼
┌─────────────────────────────┐
│  Undo Log                    │
│  balance = 10,000 (이전 값)   │ ← READ_COMMITTED는 여기를 읽음
└─────────────────────────────┘
```

| 격리 수준 | 어디서 읽나? | 결과 |
|-----------|------------|------|
| READ_UNCOMMITTED | Buffer Pool의 최신 페이지 (dirty page) | 3,000 (커밋 안 된 값) |
| READ_COMMITTED | Undo Log에서 마지막 커밋된 버전 | 10,000 |

- `READ_UNCOMMITTED`는 Undo Log를 거치지 않고 메모리의 최신 값을 바로 읽음
- `READ_COMMITTED` 이상은 Undo Log를 통해 커밋된 시점의 스냅샷을 읽음

### PostgreSQL vs MySQL

PostgreSQL은 `READ_UNCOMMITTED`를 설정해도 내부적으로 `READ_COMMITTED`로 동작한다. PostgreSQL의 MVCC는 Undo Log 대신 **튜플 버전**을 사용하며, 항상 커밋된 버전만 보이도록 설계되어 있다.

### 테스트에서 사용한 동시성 도구

#### CountDownLatch

스레드 간 실행 순서를 제어하는 도구.

```java
CountDownLatch latch = new CountDownLatch(1);  // 카운트 = 1

latch.countDown();  // 카운트 1 → 0 ("내 작업 끝났어!")
latch.await();      // 카운트가 0이 될 때까지 대기
```

| 메서드 | 역할 |
|--------|------|
| `countDown()` | 카운트를 1 줄임 → 0이 되면 대기 중인 쪽이 풀림 |
| `await()` | 카운트가 0이 될 때까지 블로킹 |

#### AtomicReference

스레드 간에 값을 안전하게 공유하는 도구.

```java
AtomicReference<BigDecimal> readBalance = new AtomicReference<>();
readBalance.set(value);   // 다른 스레드에서 쓰기
readBalance.get();        // 메인 스레드에서 읽기
```

#### flush()와 커밋의 차이

```java
accountRepository.flush();  // DB에 UPDATE 쿼리 전송 (커밋은 아님)
// executeWithoutResult 블록이 끝나야 커밋됨
```

- `flush()` = SQL 전송 (다른 트랜잭션에서 격리 수준에 따라 보일 수도 있음)
- `commit` = 트랜잭션 확정 (모든 격리 수준에서 보임)

---

## 검증 완료 목록

- [x] READ_UNCOMMITTED에서 Dirty Read 발생 확인 — MySQL (DirtyReadTest)
- [x] READ_COMMITTED에서 Dirty Read 방지 확인 — MySQL (DirtyReadTest)
- [x] 실험군/비교군 동시 비교로 격리 수준 차이 검증 (DirtyReadTest)

## ACID 학습 로드맵

| 순서 | 속성 | 핵심 질문 | 상태 |
|------|------|-----------|------|
| 1 | **Atomicity** (원자성) | 실패 시 전부 취소되는가? | 완료 |
| 2 | **Consistency** (일관성) | 트랜잭션 전후 데이터 무결성이 유지되는가? | 완료 |
| 3 | **Isolation** (격리성) | 동시 트랜잭션 간 간섭이 방지되는가? | 진행 중 |
| 4 | **Durability** (지속성) | 커밋된 데이터는 영구 보존되는가? | 예정 |

## 다음 학습 예정

- [ ] Non-Repeatable Read — READ_COMMITTED에서 발생하는 문제
- [ ] Phantom Read — REPEATABLE_READ에서 발생하는 문제
- [ ] Lost Update — 동시 수정 시 데이터 유실
- [ ] Locking — 비관적/낙관적 잠금

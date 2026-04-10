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

## 2. Non-Repeatable Read

> 같은 트랜잭션 안에서 같은 데이터를 두 번 조회했는데 결과가 다른 문제.

Dirty Read와의 차이: Dirty Read는 **커밋 안 된** 데이터를 읽는 거고, Non-Repeatable Read는 **커밋된** 데이터 때문에 발생한다.

### 시나리오

```
트랜잭션1 (조회 - READ_COMMITTED)      트랜잭션2 (수정)
─────────────────────────              ─────────────
1차 조회 → 10,000원
                                       출금 7,000원 → 커밋 완료!
2차 조회 → 3,000원 (?!)
→ 같은 트랜잭션인데 결과가 달라짐
```

### 이름의 의미

- Repeatable = 반복 가능한
- Non-Repeatable = 반복 불가능한
- **"같은 조회를 반복했는데 같은 결과가 나오지 않는다"**

이걸 **방지**하는 격리 수준이 `REPEATABLE_READ` — "읽기를 반복해도 같은 결과를 보장한다"는 뜻.

### 실험 결과

```java
// READ_COMMITTED: 1차 조회와 2차 조회 결과가 다름 → Non-Repeatable Read 발생
assertThat(firstReadFromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
assertThat(secondReadFromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(3_000));

// REPEATABLE_READ: 트랜잭션 시작 시점의 스냅샷을 읽음 → Non-Repeatable Read 방지
assertThat(firstReadFromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
assertThat(secondReadFromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
```

### Dirty Read와 Non-Repeatable Read 비교

| | Dirty Read | Non-Repeatable Read |
|--|-----------|-------------------|
| 원인 | 커밋 **안 된** 데이터를 읽음 | 다른 트랜잭션이 **커밋한** 데이터를 읽음 |
| writer 상태 | 롤백됨 (데이터가 존재하지 않았음) | 커밋됨 (데이터가 실제로 변경됨) |
| 방지 격리 수준 | READ_COMMITTED 이상 | REPEATABLE_READ 이상 |
| 핵심 | 존재하지 않는 데이터를 읽음 | 같은 트랜잭션인데 결과가 달라짐 |

### JPA 1차 캐시 주의

같은 트랜잭션에서 같은 ID로 `findById`를 두 번 호출하면, JPA는 DB에 가지 않고 **영속성 컨텍스트(1차 캐시)**에서 반환한다.

```
1차 조회 → DB 쿼리 → 10,000 → 영속성 컨텍스트에 캐싱
2차 조회 → 영속성 컨텍스트에서 반환 → 10,000 (DB 안 감!)
```

Non-Repeatable Read를 실험하려면 2차 조회 전에 `entityManager.clear()`로 1차 캐시를 비워야 한다.

### REPEATABLE_READ 내부 동작 — MVCC 스냅샷

```
READ_COMMITTED:    매 쿼리마다 최신 커밋된 스냅샷을 읽음
REPEATABLE_READ:   트랜잭션 시작 시점의 스냅샷을 고정해서 읽음
```

| 격리 수준 | 스냅샷 시점 | 결과 |
|-----------|-----------|------|
| READ_COMMITTED | 매 쿼리 실행 시점 | 중간에 커밋되면 새 값 읽힘 |
| REPEATABLE_READ | 트랜잭션 시작 시점 | 중간에 커밋되어도 이전 값 유지 |

### SERIALIZABLE과의 차이 — 데드락 주의

SERIALIZABLE은 스냅샷이 아니라 **잠금(lock)**으로 동작한다. reader가 읽은 행에 잠금을 걸어서 writer가 수정할 수 없게 만든다.

```
REPEATABLE_READ: 스냅샷 기반 → writer가 커밋해도 reader는 이전 값을 읽음 (비차단)
SERIALIZABLE:    잠금 기반 → writer가 reader 트랜잭션 끝날 때까지 대기 (차단 → 데드락 위험)
```

---

## 3. Phantom Read

> 같은 트랜잭션 안에서 범위 조회를 두 번 했는데, 없던 행이 나타나거나 있던 행이 사라지는 문제.

Non-Repeatable Read와의 차이: Non-Repeatable Read는 **기존 행의 값이 변경**되는 것이고, Phantom Read는 **행 자체가 추가/삭제**되는 것이다.

### 시나리오

```
트랜잭션1 (범위 조회)                    트랜잭션2 (삽입)
──────────────────                      ─────────────
SELECT * WHERE status = ACTIVE
→ 2건 조회 (from, to)
                                        INSERT 새 계좌 (status = ACTIVE) → 커밋
SELECT * WHERE status = ACTIVE
→ 3건 조회 (?!) ← Phantom Read!
```

### 이름의 의미

- Phantom = 유령, 환영
- **"없던 행이 유령처럼 나타난다"**

### Non-Repeatable Read vs Phantom Read

| | Non-Repeatable Read | Phantom Read |
|--|-------------------|-------------|
| 대상 | 기존 행의 **값** 변경 | 새로운 행의 **추가/삭제** |
| 조회 방식 | `findById` (단건) | `findByStatus` (범위) |
| 원인 | UPDATE + 커밋 | INSERT/DELETE + 커밋 |
| REPEATABLE_READ | 방지됨 | 이론상 허용 |

### SQL 표준 vs 실제 DB 구현 — 핵심 학습 포인트

SQL 표준에서는 `REPEATABLE_READ`에서 Phantom Read를 허용하지만, **현대 주요 DB는 모두 방지한다.**

#### SQL 표준 격리 수준 표 (이론)

| 레벨 | Dirty Read | Non-Repeatable Read | Phantom Read |
|------|-----------|-------------------|-------------|
| READ_UNCOMMITTED | 허용 | 허용 | 허용 |
| READ_COMMITTED | **방지** | 허용 | 허용 |
| REPEATABLE_READ | **방지** | **방지** | 허용 |
| SERIALIZABLE | **방지** | **방지** | **방지** |

#### 실제 구현 (MySQL InnoDB, PostgreSQL)

| 레벨 | Dirty Read | Non-Repeatable Read | Phantom Read |
|------|-----------|-------------------|-------------|
| READ_UNCOMMITTED | 허용 (MySQL) / 방지 (PG) | 허용 | 허용 |
| READ_COMMITTED | **방지** | 허용 | 허용 |
| REPEATABLE_READ | **방지** | **방지** | **방지** ← 표준과 다름! |
| SERIALIZABLE | **방지** | **방지** | **방지** |

### 실험 결과

```java
// REPEATABLE_READ에서 범위 조회
// 1차 조회: 2건 (from, to)
assertThat(firstReadCount.get()).isEqualTo(2);

// writer가 새 ACTIVE 계좌를 INSERT + 커밋한 후
// 2차 조회: 여전히 2건 → Phantom Read 방지됨!
assertThat(secondReadCount.get()).isEqualTo(2);
```

**MySQL과 PostgreSQL 모두** `REPEATABLE_READ`에서 Phantom Read가 발생하지 않았다.

### DB별 Phantom Read 방지 메커니즘

```
MySQL InnoDB:  Gap Lock (잠금 기반)
               → 조회 범위(gap)에 잠금을 걸어서 INSERT 자체를 차단
               → writer는 reader 트랜잭션이 끝날 때까지 대기

PostgreSQL:    MVCC 스냅샷 (비잠금 기반)
               → 트랜잭션 시작 시점의 스냅샷을 고정
               → writer는 INSERT 가능하지만 reader에게 안 보임
```

| DB | 방지 방식 | writer 동작 | 성능 영향 |
|----|---------|-----------|---------|
| MySQL InnoDB | Gap Lock (잠금) | INSERT 대기 | writer 차단됨 |
| PostgreSQL | MVCC 스냅샷 | INSERT 즉시 가능 | writer 차단 없음 |

### Gap Lock이란?

MySQL InnoDB가 범위 조회 시 사용하는 잠금 방식.

```
기존 데이터: from(ACTIVE), to(ACTIVE)

reader가 findByStatus(ACTIVE) 조회 시
→ InnoDB가 ACTIVE 조건에 해당하는 인덱스 범위(gap)에 잠금
→ writer가 새 ACTIVE 행을 INSERT 시도 → gap이 잠겨있어서 대기
→ reader 트랜잭션 종료 → gap 잠금 해제 → writer INSERT 진행
```

### 그렇다면 Phantom Read는 언제 발생하는가?

현대 DB에서 일반 `SELECT`로는 `REPEATABLE_READ`에서 Phantom Read가 발생하지 않는다. 하지만 **Locking Read**를 사용하면 발생할 수 있다.

```java
// 일반 SELECT → MVCC 스냅샷 읽기 → Phantom Read 방지
List<Account> accounts = accountRepository.findByStatus(ACTIVE);

// SELECT ... FOR UPDATE → 최신 커밋 데이터 읽기 → Phantom Read 발생 가능
@Lock(LockModeType.PESSIMISTIC_WRITE)
List<Account> accounts = accountRepository.findByStatusForUpdate(ACTIVE);
```

이 부분은 Lock 학습에서 상세히 다룬다.

---

## 동시성 문제 정리

### 3가지 문제 비교

| 문제 | 대상 | 원인 | 최소 방지 격리 수준 |
|------|------|------|-------------------|
| **Dirty Read** | 단일 행 | 커밋 안 된 데이터 읽기 | READ_COMMITTED |
| **Non-Repeatable Read** | 단일 행 | 다른 트랜잭션이 UPDATE + 커밋 | REPEATABLE_READ |
| **Phantom Read** | 범위 조회 | 다른 트랜잭션이 INSERT/DELETE + 커밋 | SERIALIZABLE (표준) / REPEATABLE_READ (실제) |

### 실무 권장사항

- **MySQL**: 기본 `REPEATABLE_READ`로 3가지 문제 모두 방지됨 → 대부분 기본값으로 충분
- **PostgreSQL**: 기본 `READ_COMMITTED`이므로 Non-Repeatable Read가 발생할 수 있음 → 필요 시 `REPEATABLE_READ`로 상향
- **SERIALIZABLE**: 성능 비용이 크므로 꼭 필요한 경우에만 사용

---

## 검증 완료 목록

- [x] READ_UNCOMMITTED에서 Dirty Read 발생 확인 — MySQL (DirtyReadTest)
- [x] READ_COMMITTED에서 Dirty Read 방지 확인 — MySQL (DirtyReadTest)
- [x] 실험군/비교군 동시 비교로 격리 수준 차이 검증 (DirtyReadTest)
- [x] READ_COMMITTED에서 Non-Repeatable Read 발생 확인 (NonRepeatableReadTest)
- [x] REPEATABLE_READ에서 Non-Repeatable Read 방지 확인 (NonRepeatableReadTest)
- [x] JPA 1차 캐시와 entityManager.clear() 동작 확인 (NonRepeatableReadTest)
- [x] REPEATABLE_READ에서 Phantom Read 방지 확인 — MySQL, PostgreSQL (PhantomReadTest)
- [x] SQL 표준과 실제 DB 구현의 차이 확인 (PhantomReadTest)

## ACID 학습 로드맵

| 순서 | 속성 | 핵심 질문 | 상태 |
|------|------|-----------|------|
| 1 | **Atomicity** (원자성) | 실패 시 전부 취소되는가? | 완료 |
| 2 | **Consistency** (일관성) | 트랜잭션 전후 데이터 무결성이 유지되는가? | 완료 |
| 3 | **Isolation** (격리성) | 동시 트랜잭션 간 간섭이 방지되는가? | 진행 중 |
| 4 | **Durability** (지속성) | 커밋된 데이터는 영구 보존되는가? | 예정 |

## 다음 학습 예정

- [ ] Lost Update — 동시 수정 시 데이터 유실
- [ ] Locking — 비관적/낙관적 잠금 (Phantom Read + Locking Read 포함)

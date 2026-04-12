# Durability (지속성)

> 커밋된 트랜잭션의 결과는 영구적으로 보존된다. 시스템 장애가 발생해도 커밋된 데이터는 사라지지 않는다.

## 핵심 개념

- 트랜잭션이 **커밋되면** 그 결과는 디스크에 영구 저장된다
- 정전, 크래시, 재부팅이 발생해도 커밋된 데이터는 복구 가능하다
- 커밋되지 않은(롤백된) 데이터는 보존 대상이 아니다
- **커밋만이 지속성을 보장한다** — flush, save만으로는 부족하다

## DB가 지속성을 보장하는 메커니즘: WAL

> Write-Ahead Logging — 데이터를 변경하기 **전에** 로그를 먼저 기록한다.

### 동작 흐름

```
트랜잭션 실행
→ 1단계: 변경 내용을 WAL(로그 파일)에 기록
→ 2단계: WAL을 디스크에 fsync (강제 flush)
→ 3단계: 커밋 완료 응답
→ 4단계: 실제 데이터 파일에 반영 (나중에, 비동기)
```

### 크래시 복구 흐름

```
시스템 크래시 발생!
→ DB 재시작
→ WAL 로그 확인
→ 커밋된 트랜잭션 → redo (재실행하여 데이터 파일에 반영)
→ 커밋 안 된 트랜잭션 → undo (롤백하여 제거)
→ 데이터 일관성 복구 완료
```

### DB별 WAL 구현

| DB | 로그 이름 | 특징 |
|----|-----------|------|
| PostgreSQL | WAL (Write-Ahead Log) | 이름 그대로 WAL 사용 |
| MySQL (InnoDB) | Redo Log + Undo Log | Redo로 복구, Undo로 롤백 |
| Oracle | Redo Log | 아카이브 모드로 장기 보존 가능 |

## 실험 시나리오

### 1. 커밋된 데이터는 영구 저장된다

```java
TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
TransactionTemplate tx2 = new TransactionTemplate(transactionManager);

// 트랜잭션 1: 계좌 생성 후 커밋
tx1.executeWithoutResult(status -> {
    account = accountRepository.save(account);
});

// 트랜잭션 2: 완전히 별개의 트랜잭션에서 조회
Account found = tx2.execute(status -> accountService.getAccountById(account.getId()));

assertThat(found).isNotNull();
assertThat(found.getId()).isEqualTo(account.getId());
```

**결과**: 커밋된 데이터는 다른 트랜잭션에서도 영구적으로 조회 가능하다.

### 2. 롤백된 데이터는 저장되지 않는다

```java
TransactionTemplate tx1 = new TransactionTemplate(transactionManager);

// save 후 롤백
tx1.executeWithoutResult(status -> {
    accountRepository.save(account);
    status.setRollbackOnly();  // 강제 롤백
});

// 조회 시 EntityNotFoundException 발생
assertThatThrownBy(() -> accountService.getAccountById(account.getId()))
    .isInstanceOf(EntityNotFoundException.class);
```

**결과**: 롤백된 데이터는 DB에 존재하지 않는다. 지속성은 커밋된 트랜잭션에만 적용된다.

### 3. flush != commit — flush만으로는 지속성이 보장되지 않는다

```java
TransactionTemplate tx1 = new TransactionTemplate(transactionManager);

tx1.executeWithoutResult(status -> {
    accountRepository.save(account);
    entityManager.flush();     // SQL을 DB에 전송
    status.setRollbackOnly();  // 그래도 롤백하면 사라짐
});

assertThatThrownBy(() -> accountService.getAccountById(account.getId()))
    .isInstanceOf(EntityNotFoundException.class);
```

**결과**: `flush()`는 SQL을 DB에 **전송**할 뿐, 커밋이 아니다. 롤백되면 flush된 데이터도 사라진다.

#### flush vs commit 구분

| 동작 | SQL 전송 | 트랜잭션 확정 | 지속성 보장 |
|------|---------|-------------|-----------|
| `flush()` | O | X | X |
| `commit()` | O | O | O |

JPA에서 `flush()`는 영속성 컨텍스트의 변경사항을 DB에 SQL로 보내는 것이지, 트랜잭션을 확정하는 것이 아니다.

## ACID 속성 간의 관계

```
Atomicity   → 실패하면 전부 취소 (수단)
Consistency → 데이터가 규칙에 맞는 상태 유지 (목적)
Isolation   → 동시 트랜잭션 간 간섭 방지 (환경)
Durability  → 커밋된 결과의 영구 보존 (보증)
```

네 가지가 함께 작동하여 트랜잭션의 신뢰성을 보장한다.

- **Atomicity**가 전부 성공/전부 취소를 보장하고
- **Consistency**가 규칙에 맞는 상태를 유지하고
- **Isolation**이 동시 실행 환경에서의 안전성을 제공하고
- **Durability**가 최종 커밋 결과를 영구적으로 보존한다

## 검증 완료 목록

- [x] 커밋된 데이터는 새로운 트랜잭션에서도 조회된다 (DurabilityTest)
- [x] 롤백된 데이터는 DB에 존재하지 않는다 (DurabilityTest)
- [x] flush 후 롤백하면 데이터가 사라진다 — flush != commit (DurabilityTest)
# Atomicity (원자성)

> "All or Nothing" — 트랜잭션 내 모든 작업이 전부 성공하거나, 하나라도 실패하면 전부 취소된다.

## 핵심 개념

- 트랜잭션은 **분리할 수 없는 하나의 작업 단위**
- 중간에 실패하면 이전에 성공한 작업도 모두 롤백
- 트랜잭션 없이는 자바 객체 변경이 되돌려지지 않음 (메모리 vs DB의 차이)

## 실험 시나리오: 계좌 이체

```
A 계좌 (잔액 10,000원)  →  B 계좌 (잔액 10,000원)
         7,000원 송금
```

### 1. 트랜잭션 미사용 — 원자성 깨짐

```java
from.withdraw(amount);  // 성공 → 잔액 3,000
to.deposit(amount);     // 실패 (SUSPENDED 상태)
```

**결과**: from 3,000 / to 10,000 → 7,000원 증발

트랜잭션이 없으면 `withdraw()`로 변경된 자바 객체 상태가 되돌려지지 않는다.

### 2. 트랜잭션 사용 — 원자성 보장

```java
TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
txTemplate.executeWithoutResult(status -> {
    Account txFrom = accountRepository.findById(fromId).orElseThrow();
    Account txTo = accountRepository.findById(toId).orElseThrow();
    txFrom.withdraw(amount);  // 성공
    txTo.deposit(amount);     // 실패 → 전체 롤백
});
```

**결과**: from 10,000 / to 10,000 → 출금도 롤백됨

## 트랜잭션 관리 방식

| 방식 | 사용처 | 예시 |
|------|--------|------|
| `@Transactional` | 서비스 레이어 (선언적) | `@Transactional public void transfer()` |
| `TransactionTemplate` | 테스트, 세밀한 제어 (프로그래밍 방식) | `txTemplate.executeWithoutResult(...)` |
| `PlatformTransactionManager` | 직접 사용 (거의 안 씀) | `begin → commit/rollback` |

세 가지 모두 내부적으로 `PlatformTransactionManager`를 사용한다.

## Checked vs Unchecked Exception 롤백

Spring `@Transactional`은 **예외 타입에 따라 롤백 여부가 다르다.**

| 예외 타입 | 롤백 여부 | 이유 |
|-----------|-----------|------|
| `RuntimeException` (Unchecked) | 롤백됨 | 프로그래밍 오류, 복구 불가능 → 되돌리는 게 안전 |
| `Exception` (Checked) | 롤백 안 됨 (커밋) | 예상 가능한 상황, 호출자가 복구 가능 → 작업 유지 |

### 실험 코드

```java
// 롤백됨
@Transactional
public void transferWithUncheckedException(UUID fromId, BigDecimal amount) {
    Account from = getAccountById(fromId);
    from.withdraw(amount);
    throw new RuntimeException("unchecked exception 발생!");
}

// 롤백 안 됨 — 커밋됨!
@Transactional
public void transferWithCheckedException(UUID fromId, BigDecimal amount) throws Exception {
    Account from = getAccountById(fromId);
    from.withdraw(amount);
    throw new Exception("checked exception 발생!");
}
```

### 실무 팁: rollbackFor

Checked Exception에서도 롤백하고 싶다면 명시적으로 지정:

```java
@Transactional(rollbackFor = Exception.class)
public void transfer(...) throws Exception {
    // 이제 Checked Exception도 롤백됨
}
```

실무에서는 `rollbackFor = Exception.class`를 기본으로 쓰는 팀이 많다.
"체크드 예외인데 커밋됐다"는 버그는 찾기가 매우 어렵기 때문.

### Spring 내부 동작 — 롤백 결정 흐름

`@Transactional`은 AOP 프록시로 동작하며, 예외 발생 시 아래 흐름을 탄다.

```
메서드 호출 → AOP 프록시 (TransactionInterceptor)
→ 트랜잭션 시작
→ 메서드 실행
→ 예외 발생!
→ completeTransactionAfterThrowing() 호출
→ rollbackOn(ex) 체크
   ├── true  → rollback()
   └── false → commit()
```

#### 1단계: TransactionAspectSupport (진입점)

```java
// TransactionAspectSupport.java:705
if (txInfo.transactionAttribute.rollbackOn(ex)) {
    txInfo.getTransactionManager().rollback(txInfo.getTransactionStatus());
} else {
    txInfo.getTransactionManager().commit(txInfo.getTransactionStatus());
}
```

#### 2단계: rollbackOn() — 기본 구현

```java
// DefaultTransactionAttribute.java:180
public boolean rollbackOn(Throwable ex) {
    return (ex instanceof RuntimeException || ex instanceof Error);
}
```

이 한 줄이 "Checked Exception은 롤백 안 됨"의 근거.

#### 3단계: rollbackOn() — rollbackFor 설정 시

`rollbackFor`를 설정하면 `RuleBasedTransactionAttribute`가 사용된다.

```java
// RuleBasedTransactionAttribute.java:121
@Override
public boolean rollbackOn(Throwable ex) {
    RollbackRuleAttribute winner = null;
    int deepest = Integer.MAX_VALUE;

    for (RollbackRuleAttribute rule : this.rollbackRules) {
        int depth = rule.getDepth(ex);       // 예외 상속 계층에서의 거리
        if (depth >= 0 && depth < deepest) {  // 가장 가까운(구체적인) 규칙이 이김
            deepest = depth;
            winner = rule;
        }
    }

    if (winner == null) {
        return super.rollbackOn(ex);  // 매칭 규칙 없으면 기본 동작
    }
    return !(winner instanceof NoRollbackRuleAttribute);
}
```

#### 4단계: getDepth() — 재귀로 상속 체인 탐색

```java
// RollbackRuleAttribute.java:169
private int getDepth(Class<?> exceptionType, int depth) {
    if (this.exceptionType.equals(exceptionType)) {
        return depth;  // 매칭! 현재 depth 반환
    }
    if (exceptionType == Throwable.class) {
        return -1;     // 끝까지 못 찾음
    }
    return getDepth(exceptionType.getSuperclass(), depth + 1);  // 부모로 올라감
}
```

예시: `rollbackFor = Exception.class`일 때 `FileNotFoundException` 발생

```
getDepth(FileNotFoundException, 0) → 매칭? No
getDepth(IOException, 1)          → 매칭? No
getDepth(Exception, 2)            → 매칭! return 2
```

`depth`가 작을수록 더 구체적인 규칙 → **가장 구체적인 규칙이 우선**한다.

## 주의사항

### 트랜잭션이 관리하는 것은 DB 상태이지, 자바 객체가 아니다

```
txFrom.getBalance() → 3,000  (자바 객체는 withdraw 실행된 상태 그대로)
DB 실제 값          → 10,000 (트랜잭션 롤백으로 원복됨)
```

롤백 여부를 확인하려면 반드시 **DB에서 다시 조회**해야 한다.

### 테스트에서 @Transactional 주의

테스트 클래스에 `@Transactional`을 붙이면 서비스의 트랜잭션이 테스트 트랜잭션에 참여(REQUIRED)하여 실제 커밋/롤백 동작을 확인할 수 없다. 롤백 실험 시에는 테스트에서 `@Transactional`을 빼야 한다.

---

## ACID 학습 로드맵

| 순서 | 속성 | 핵심 질문 | 상태 |
|------|------|-----------|------|
| 1 | **Atomicity** (원자성) | 실패 시 전부 취소되는가? | 완료 |
| 2 | **Consistency** (일관성) | 트랜잭션 전후 데이터 무결성이 유지되는가? | 다음 |
| 3 | **Isolation** (격리성) | 동시 트랜잭션 간 간섭이 방지되는가? | 예정 |
| 4 | **Durability** (지속성) | 커밋된 데이터는 영구 보존되는가? | 예정 |

## 검증 완료 목록

- [x] 트랜잭션 미사용 시 원자성 깨짐 (AtomicityTest)
- [x] 트랜잭션 사용 시 원자성 보장 — TransactionTemplate (AtomicityTest)
- [x] Unchecked Exception → 롤백됨 (AccountServiceTest)
- [x] Checked Exception → 롤백 안 됨 (AccountServiceTest)
- [x] `rollbackFor = Exception.class` → Checked Exception도 롤백됨 (AccountServiceTest)

## 추후 학습 (다른 ACID 속성에서 다룰 주제)

- 자기 호출(Self-invocation) 문제 → Propagation에서 다룸
- 다중 테이블 원자성 → Consistency에서 이어짐

## 다음 학습 예정

- [ ] Consistency — 데이터 무결성 제약조건과 트랜잭션
- [ ] Isolation Level — 동시성 문제 (Dirty Read, Non-Repeatable Read, Phantom Read)
- [ ] Durability — 커밋된 데이터의 영구 보존
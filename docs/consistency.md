# Consistency (일관성)

> 트랜잭션 전후로 데이터가 항상 유효한 상태를 유지해야 한다.

Atomicity가 "전부 되거나 전부 안 되거나"라면, Consistency는 **"실행 결과가 규칙에 맞는가"**이다.

## 일관성을 보장하는 두 가지 레벨

### 1. DB 레벨 — 제약조건 (Constraint)

DB가 강제하는 규칙. 애플리케이션 코드를 우회하더라도 데이터 무결성을 지킨다.

| 제약조건 | 역할 | 예시 |
|----------|------|------|
| NOT NULL | 필수 값 보장 | balance가 null이면 안 됨 |
| UNIQUE | 중복 방지 | 같은 계좌번호 중복 불가 |
| CHECK | 값 범위 제한 | 잔액이 0 이상이어야 함 |
| FOREIGN KEY | 참조 무결성 | 존재하지 않는 사용자의 계좌 생성 불가 |

#### CHECK 제약조건 적용 (Jakarta Persistence)

```java
@Table(
    name = "accounts",
    check = @CheckConstraint(
        name = "ck_balance_non_negative",
        constraint = "balance >= 0"
    )
)
```

> `org.hibernate.annotations.Check`는 Hibernate 7에서 deprecated.
> `jakarta.persistence.CheckConstraint`를 사용한다.

#### 실험: 네이티브 쿼리로 애플리케이션 검증 우회

```java
// AccountRepository에 직접 UPDATE 쿼리 추가
@Modifying
@Query("UPDATE Account a SET a.balance = :balance WHERE a.id = :id")
void updateBalanceDirectly(@Param("id") UUID id, @Param("balance") BigDecimal balance);
```

```java
// 잔액을 -10,000으로 직접 변경 시도
assertThatThrownBy(() -> txTemplate.executeWithoutResult(
    status -> accountRepository.updateBalanceDirectly(account.getId(), BigDecimal.valueOf(-10_000))
)).isInstanceOf(DataIntegrityViolationException.class);
```

**결과**: 애플리케이션 검증을 우회해도 DB CHECK 제약조건이 음수 잔액을 막는다.

### 2. 애플리케이션 레벨 — 비즈니스 규칙

코드에서 강제하는 규칙. DB 제약조건보다 먼저 실행되어 1차 방어를 담당한다.

```java
// Account 도메인 — 잔액 부족 검증
public void withdraw(BigDecimal amount) {
    validateAmount(amount);
    if (this.balance.compareTo(amount) < 0) {
        throw new IllegalArgumentException("잔액이 부족합니다");
    }
    this.balance = this.balance.subtract(amount);
}

// Account 도메인 — 금액 양수 검증
private void validateAmount(BigDecimal amount) {
    Validate.notNull(amount, "금액은 필수입니다.");
    Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "금액은 0보다 커야 합니다.");
}
```

### 다중 레이어 방어

```
사용자 요청
→ 1차: 애플리케이션 검증 (withdraw의 잔액 부족 체크)
→ 2차: DB CHECK 제약조건 (balance >= 0)
→ 데이터 저장
```

애플리케이션 코드에 버그가 있어도 DB 제약조건이 최후의 보루로 일관성을 지킨다.

## Atomicity와 Consistency의 관계

```
Atomicity  → "어떻게" 보장하는가 (전부 성공 or 전부 취소)
Consistency → "무엇을" 보장하는가 (데이터가 규칙에 맞는 상태)
```

**Atomicity는 수단, Consistency는 목적.**

### 송금 전후 총 잔액 보존

```
송금 전: A(10,000) + B(10,000) = 20,000
송금 후: A(3,000)  + B(17,000) = 20,000  ← 총합 일관성 유지
```

송금 중 실패 시 → Atomicity가 롤백 수행 → 그 결과 Consistency(총합 보존)가 유지됨.

## 검증 완료 목록

- [x] DB CHECK 제약조건으로 음수 잔액 방지 (ConsistencyTest)
- [x] 송금 성공 시 총 잔액 합계 보존 (ConsistencyTest)
- [x] 송금 실패 시에도 총 잔액 합계 보존 — 트랜잭션 롤백 (ConsistencyTest)
- [x] 애플리케이션 레벨 검증 — withdraw 잔액 부족, 금액 양수 (Account 도메인)

## 예외 구분

| 예외 | 발생 시점 |
|------|-----------|
| `DataIntegrityViolationException` | DB 제약조건 위반 (CHECK, UNIQUE, FK) |
| `ConstraintViolationException` | Jakarta Validation 위반 (`@NotNull`, `@Min` 등) |
| `IllegalArgumentException` | 애플리케이션 비즈니스 규칙 위반 |

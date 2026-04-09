package eastmeet.voyage.transaction.account.domain;

import eastmeet.voyage.transaction.global.entity.BaseTimeEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.jspecify.annotations.NonNull;
import org.apache.commons.lang3.Validate;
import org.jspecify.annotations.NonNull;

@Entity
@Getter
@Table(
    name = "accounts",
    check = @CheckConstraint(
        name = "ck_balance_non_negative",
        constraint = "balance >= 0"
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseTimeEntity {

    @Id
    @Column(name = "id", comment = "계좌 ID")
    private UUID id;

    @Column(name = "owner_name", comment = "계좌 소유자명")
    private String ownerName;

    @Column(name = "balance", nullable = false, precision = 15, scale = 2, comment = "잔고")
    private BigDecimal balance;

    @Column(name = "status", nullable = false, comment = "계좌 상태")
    @Enumerated(EnumType.STRING)
    private AccountStatus status;

    @Builder
    public Account(String ownerName, BigDecimal balance, AccountStatus status) {
        Validate.notNull(balance, "초기 잔액은 필수입니다.");
        this.id = UUID.randomUUID();
        this.ownerName = ownerName;
        this.balance = balance;
        this.status = status;
    }

    public void deposit(BigDecimal amount) {
        validateAmount(amount);
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("계좌상태" + "[" + status.getDescription() + "]" + "를 확인해주세요.");
        }

        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        validateAmount(amount);
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("계좌상태" + "[" + status.getDescription() + "]" + "를 확인해주세요.");
        }

        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("잔액이 부족합니다");
        }

        this.balance = this.balance.subtract(amount);
    }

    private void validateAmount(BigDecimal amount) {
        Validate.notNull(amount, "금액은 필수입니다.");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "금액은 0보다 커야 합니다.");
    }

    public void updateStatus(@NonNull AccountStatus newStatus) {
        if (this.status != newStatus) {
            this.status = newStatus;
        }
    }

}
package eastmeet.voyage.transaction.account.domain;

import eastmeet.voyage.transaction.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "accounts")
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

    public Account(String ownerName, BigDecimal balance, AccountStatus status) {
        this.id = UUID.randomUUID();
        this.ownerName = ownerName;
        this.balance = balance;
        this.status = status;
    }

    public void deposit(BigDecimal amount) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("계좌상태" + "[" + status.getDescription() + "]" + "를 확인해주세요.");
        }

        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("계좌상태" + "[" + status.getDescription() + "]" + "를 확인해주세요.");
        }

        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("잔액이 부족합니다");
        }

        this.balance = this.balance.subtract(amount);
    }

}
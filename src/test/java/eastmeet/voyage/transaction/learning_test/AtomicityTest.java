package eastmeet.voyage.transaction.learning_test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eastmeet.voyage.transaction.account.domain.Account;
import eastmeet.voyage.transaction.account.domain.AccountStatus;
import eastmeet.voyage.transaction.account.repository.AccountRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@SpringBootTest
class AtomicityTest {

    @Autowired
    private AccountRepository accountRepository;

    private Account from;

    private Account to;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        from = accountRepository.save(Account.builder()
            .status(AccountStatus.ACTIVE)
            .ownerName("from")
            .balance(BigDecimal.valueOf(10_000))
            .build());

        to = accountRepository.save(Account.builder()
            .status(AccountStatus.ACTIVE)
            .ownerName("to")
            .balance(BigDecimal.valueOf(10_000))
            .build());
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteById(from.getId());
        accountRepository.deleteById(to.getId());
    }

    @Test
    void 송금_성공_기본_동작_확인() {
        // 7,000원 송금 프로세스
        BigDecimal amount = BigDecimal.valueOf(7_000);
        from.withdraw(amount);
        to.deposit(amount);

        assertThat(from.getBalance()).isEqualTo(BigDecimal.valueOf(3_000));
        assertThat(to.getBalance()).isEqualTo(BigDecimal.valueOf(17_000));
    }

    @Test
    void 송금_중_입금_실패_시_출금도_롤백되는가_트랜잭션_미사용() {
        BigDecimal amount = BigDecimal.valueOf(7_000);
        to.updateStatus(AccountStatus.SUSPENDED);

        // 여기서 상태값으로 인한 에러발생
        assertThrows(IllegalArgumentException.class, () -> {
            // 성공
            from.withdraw(amount);
            // 실패
            to.deposit(amount);
        });

        // 출금은 롤백되지 않고 차감됨 그러한 이유는 트랜잭션을 현재 사용하지 않았기 때문 => 원자성 깨짐
        assertThat(from.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(3_000));
        assertThat(to.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10_000));

    }

    @Test
    void 송금_중_입금_실패_시_출금도_롤백되는가_트랜잭션_사용() {
        BigDecimal amount = BigDecimal.valueOf(7_000);
        to.updateStatus(AccountStatus.SUSPENDED);
        accountRepository.save(to);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThrows(IllegalArgumentException.class, () -> txTemplate.executeWithoutResult(status -> {
            Account txFrom = accountRepository.findById(from.getId()).orElseThrow();
            Account txTo = accountRepository.findById(to.getId()).orElseThrow();

            txFrom.withdraw(amount);  // 성공
            assertThat(txFrom.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(3_000));
            txTo.deposit(amount);     // 실패 — SUSPENDED
        }));

        Account updatedFrom = accountRepository.findById(this.from.getId()).orElseThrow(
            () -> new EntityNotFoundException("계좌정보를 찾을 수 없습니다.")
        );

        Account updatedTo = accountRepository.findById(this.to.getId()).orElseThrow(
            () -> new EntityNotFoundException("계좌정보를 찾을 수 없습니다.")
        );

        // 트랜잭션으로 인한 원자성 보장 확인
        assertThat(updatedFrom.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
        assertThat(updatedTo.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10_000));

    }


}
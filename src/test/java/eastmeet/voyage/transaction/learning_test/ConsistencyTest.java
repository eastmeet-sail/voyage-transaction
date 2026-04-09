package eastmeet.voyage.transaction.learning_test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import eastmeet.voyage.transaction.account.domain.Account;
import eastmeet.voyage.transaction.account.domain.AccountStatus;
import eastmeet.voyage.transaction.account.repository.AccountRepository;
import eastmeet.voyage.transaction.account.service.AccountService;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ConsistencyTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    private Account from;

    private Account to;

    @BeforeEach
    void setup() {
        txTemplate = new TransactionTemplate(transactionManager);

        from = accountRepository.save(Account.builder()
            .status(AccountStatus.ACTIVE)
            .ownerName("from")
            .balance(BigDecimal.valueOf(10_000))
            .build()
        );

        to = accountRepository.save(Account.builder()
            .status(AccountStatus.ACTIVE)
            .ownerName("to")
            .balance(BigDecimal.valueOf(10_000))
            .build()
        );

    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteById(from.getId());
        accountRepository.deleteById(to.getId());
    }

    @Test
    void DB_CHECK_제약조건_음수_잔액_입력_방지하는가() {
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(
                status -> accountRepository.updateBalanceDirectly(from.getId(), BigDecimal.valueOf(-10_000))
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 송금_전후_총_잔액_합계는_동일하다() {
        BigDecimal totalBefore = from.getBalance().add(to.getBalance());

        txTemplate.executeWithoutResult(status -> {
            Account txFrom = accountService.getAccountById(from.getId());
            Account txTo = accountService.getAccountById(to.getId());

            txFrom.withdraw(BigDecimal.valueOf(7_000));
            txTo.deposit(BigDecimal.valueOf(7_000));
        });

        Account updatedFrom = accountService.getAccountById(from.getId());
        Account updatedTo = accountService.getAccountById(to.getId());
        BigDecimal totalAfter = updatedFrom.getBalance().add(updatedTo.getBalance());

        // 일관성: 총 잔액 보존
        assertThat(totalAfter).isEqualByComparingTo(totalBefore);
        assertThat(totalAfter).isEqualByComparingTo(BigDecimal.valueOf(20_000));
    }

    @Test
    void 송금_실패_시에도_총_잔액_합계는_동일하다() {
        // 총액
        BigDecimal totalBefore = from.getBalance().add(to.getBalance());

        to.updateStatus(AccountStatus.SUSPENDED);
        accountRepository.save(to);

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
                Account txFrom = accountService.getAccountById(from.getId());
                Account txTo = accountService.getAccountById(to.getId());

                txFrom.withdraw(BigDecimal.valueOf(7_000));
                txTo.deposit(BigDecimal.valueOf(7_000)); // 실패
            }
        )).isInstanceOf(IllegalArgumentException.class);

        Account updatedFrom = accountService.getAccountById(from.getId());
        Account updatedTo = accountService.getAccountById(to.getId());
        BigDecimal totalAfter = updatedFrom.getBalance().add(updatedTo.getBalance());

        // 일관성: 실패해도 총 잔액 보존 (트랜잭션 롤백 덕분)
        assertThat(totalBefore).isEqualByComparingTo(totalAfter);
    }

}

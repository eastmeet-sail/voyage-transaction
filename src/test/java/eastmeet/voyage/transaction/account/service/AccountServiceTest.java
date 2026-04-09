package eastmeet.voyage.transaction.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import eastmeet.voyage.transaction.account.domain.Account;
import eastmeet.voyage.transaction.account.domain.AccountStatus;
import eastmeet.voyage.transaction.account.repository.AccountRepository;
import java.math.BigDecimal;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AccountServiceTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    private Account from;

    @BeforeEach
    void setUp() {
        from = accountRepository.save(Account.builder()
            .status(AccountStatus.ACTIVE)
            .ownerName("from")
            .balance(BigDecimal.valueOf(10_000))
            .build());
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteById(from.getId());
    }

    @Test
    void 가설_언체크드_익셉션은_롤백이될까() {
        BigDecimal amount = BigDecimal.valueOf(7_000);
        Assertions.assertThatThrownBy(
            () -> accountService.transferWithUncheckedException(from.getId(), amount)
        ).isInstanceOf(RuntimeException.class);

        Account updatedFrom = accountService.getAccountById(from.getId());
        // 검증완료 => 언체크드 익셉션은 롤백이된다.
        assertThat(updatedFrom.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
    }

    @Test
    void 가설_체크드_익셉션은_롤백이될까() {
        BigDecimal amount = BigDecimal.valueOf(7_000);
        Assertions.assertThatThrownBy(
            () -> accountService.transferWithCheckedException(from.getId(), amount)
        ).isInstanceOf(Exception.class);

        Account updatedFrom = accountService.getAccountById(from.getId());
        // 검증완료 => 체크드 익셉션은 롤백이 되지않는다.
        assertThat(updatedFrom.getBalance()).isNotEqualByComparingTo(BigDecimal.valueOf(10_000));
        assertThat(updatedFrom.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(3_000));
    }

    @Test
    void 가설_체크드_익셉션_이어도_rollbackFor_설정하면_롤백이_되는가() {
        BigDecimal amount = BigDecimal.valueOf(7_000);
        Assertions.assertThatThrownBy(
            () -> accountService.transferWithCheckedExceptionAndRollbackFor(from.getId(), amount)
        ).isInstanceOf(Exception.class);

        Account updatedFrom = accountService.getAccountById(from.getId());

        // 검증완료 => 체크드 익셉션이여도 rollbackFor 설정이 있으면 롤백이 된다.
        assertThat(updatedFrom.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
    }

}
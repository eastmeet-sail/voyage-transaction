package eastmeet.voyage.transaction.learning_test;

import static org.assertj.core.api.Assertions.assertThat;

import eastmeet.voyage.transaction.account.domain.Account;
import eastmeet.voyage.transaction.account.domain.AccountStatus;
import eastmeet.voyage.transaction.account.repository.AccountRepository;
import eastmeet.voyage.transaction.account.service.AccountService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@SpringBootTest
@DisplayName("지속성 학습테스트")
class DurabilityTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private EntityManager entityManager;

    private Account account;

    @BeforeEach
    void setUp() {
        account = Account.builder()
            .status(AccountStatus.ACTIVE)
            .ownerName("commitData")
            .balance(BigDecimal.valueOf(10_000))
            .build();
    }

    @AfterEach
    void tearDown() {
        accountRepository.findById(account.getId())
            .ifPresent(a -> accountRepository.delete(a));
    }

    @Test
    void 커밋된_데이터는_새로운_트랜잭션에서도_조회되는가() {

        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);

        AtomicReference<UUID> id = new AtomicReference<>();
        tx1.executeWithoutResult(status -> {
            account = accountRepository.save(account);
            id.set(account.getId());
        });

        Account execute = tx2.execute(status -> accountService.getAccountById(id.get()));

        assertThat(execute).isNotNull();
        assertThat(execute.getId()).isEqualTo(id.get());
    }

    @Test
    void 롤백_데이터는_어디에서도_조회되지_않는다() {
        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);

        tx1.executeWithoutResult(status -> {
            accountRepository.save(account);
            status.setRollbackOnly();
        });

        Assertions.assertThatThrownBy(() -> accountService.getAccountById(account.getId())).isInstanceOf(EntityNotFoundException.class);

    }

    @Test
    void flush는_커밋이_아니므로_롤백되면_데이터는_사라진다() {
        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);

        tx1.executeWithoutResult(status -> {
            accountRepository.save(account);
            entityManager.flush();
            status.setRollbackOnly();
        });

        Assertions.assertThatThrownBy(() -> accountService.getAccountById(account.getId())).isInstanceOf(EntityNotFoundException.class);
    }


}

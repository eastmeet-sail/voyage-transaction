package eastmeet.voyage.transaction.learning_test.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import eastmeet.voyage.transaction.account.domain.Account;
import eastmeet.voyage.transaction.account.domain.AccountStatus;
import eastmeet.voyage.transaction.account.repository.AccountRepository;
import eastmeet.voyage.transaction.account.service.AccountService;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class DirtyReadTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Account from;

    private Account to;

    @BeforeEach
    void setup() {
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
    void 실험_READ_UNCOMMITTED는_커밋되지_않은_데이터를_읽고_READ_COMMITTED는_방지하는가() throws InterruptedException {
        // writer — 격리 수준 상관없음 (쓰기만 함)
        TransactionTemplate txTemplate1 = new TransactionTemplate(transactionManager);
        txTemplate1.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        // reader1 — Dirty Read 발생 확인용(실험군)
        TransactionTemplate txTemplate2 = new TransactionTemplate(transactionManager);
        txTemplate2.setIsolationLevel(TransactionDefinition.ISOLATION_READ_UNCOMMITTED);

        // reader2 - Dirty Read 방지 확인용(비교군)
        TransactionTemplate txTemplate3 = new TransactionTemplate(transactionManager);
        txTemplate3.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        CountDownLatch writeComplete = new CountDownLatch(1);
        CountDownLatch reader1Complete = new CountDownLatch(1);
        CountDownLatch reader2Complete = new CountDownLatch(1);

        // Thread1: 트랜잭션 안에서 출금
        Thread writer = new Thread(() -> {
            txTemplate1.executeWithoutResult(status -> {
                BigDecimal amount = BigDecimal.valueOf(7_000);
                Account tx1From = accountService.getAccountById(from.getId());
                Account tx1To = accountService.getAccountById(to.getId());

                tx1From.withdraw(amount); // 출금
                tx1To.deposit(amount); // 입금
                accountRepository.flush();
                writeComplete.countDown();
                try {
                    reader1Complete.await();
                    reader2Complete.await();
                    status.setRollbackOnly(); // 트랜잭션 강제 롤백
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        });

        AtomicReference<BigDecimal> reader1FromBalance = new AtomicReference<>();
        AtomicReference<BigDecimal> reader1ToBalance = new AtomicReference<>();

        Thread reader1 = new Thread(() -> {
            txTemplate2.executeWithoutResult(status -> {
                try {
                    writeComplete.await();
                    Account tx2From = accountService.getAccountById(from.getId());
                    reader1FromBalance.set(tx2From.getBalance());
                    Account tx2To = accountService.getAccountById(to.getId());
                    reader1ToBalance.set(tx2To.getBalance());

                    reader1Complete.countDown();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        });

        AtomicReference<BigDecimal> reader2FromBalance = new AtomicReference<>();
        AtomicReference<BigDecimal> reader2ToBalance = new AtomicReference<>();

        Thread reader2 = new Thread(() -> {
            txTemplate3.executeWithoutResult(status -> {
                try {
                    reader1Complete.await();
                    Account tx3From = accountService.getAccountById(from.getId());
                    reader2FromBalance.set(tx3From.getBalance());
                    Account tx3To = accountService.getAccountById(to.getId());
                    reader2ToBalance.set(tx3To.getBalance());

                    reader2Complete.countDown();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        });

        writer.start();
        reader1.start();
        reader2.start();

        writer.join();
        reader1.join();
        reader2.join();

        // reader1 (READ_UNCOMMITTED): 커밋되지 않은 데이터가 읽힘 → Dirty Read 발생
        assertThat(reader1FromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(3_000));
        assertThat(reader1ToBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(17_000));

        // reader2 (READ_COMMITTED): 커밋된 데이터만 읽힘 → Dirty Read 방지
        assertThat(reader2FromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
        assertThat(reader2ToBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));

    }

}

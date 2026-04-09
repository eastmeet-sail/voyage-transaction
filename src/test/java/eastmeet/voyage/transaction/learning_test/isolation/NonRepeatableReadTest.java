package eastmeet.voyage.transaction.learning_test.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import eastmeet.voyage.transaction.account.domain.Account;
import eastmeet.voyage.transaction.account.domain.AccountStatus;
import eastmeet.voyage.transaction.account.repository.AccountRepository;
import eastmeet.voyage.transaction.account.service.AccountService;
import jakarta.persistence.EntityManager;
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
class NonRepeatableReadTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private EntityManager entityManager;

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
    void READ_COMMITTED에서_같은_트랜잭션_내_두번_조회_시_결과가_달라지는가() throws InterruptedException {
        // reader — READ_COMMITTED: 커밋된 최신 값을 항상 읽음
        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        tx1.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        // writer — 격리 수준 상관없음 (쓰기 + 커밋만 함)
        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
        tx2.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        CountDownLatch readerComplete = new CountDownLatch(1);
        CountDownLatch writerComplete = new CountDownLatch(1);

        AtomicReference<BigDecimal> firstReadFromBalance = new AtomicReference<>();
        AtomicReference<BigDecimal> firstReadToBalance = new AtomicReference<>();

        AtomicReference<BigDecimal> secondReadFromBalance = new AtomicReference<>();
        AtomicReference<BigDecimal> secondReadToBalance = new AtomicReference<>();

        // 하나의 트랜잭션 안에서 1차 조회 → writer 커밋 대기 → 2차 조회
        Thread firstReader = new Thread(() -> {
            tx1.executeWithoutResult(status -> {
                // 1차 조회: writer가 수정하기 전
                Account tx1From = accountService.getAccountById(from.getId());
                Account tx1To = accountService.getAccountById(to.getId());

                firstReadFromBalance.set(tx1From.getBalance());
                firstReadToBalance.set(tx1To.getBalance());
                readerComplete.countDown();

                try {
                    writerComplete.await(); // writer가 커밋할 때까지 대기
                    entityManager.clear();  // JPA 1차 캐시 비우기 → 2차 조회 시 DB에서 다시 읽음
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                // 같은 트랜잭션에서 2차 조회: writer가 커밋한 후
                Account tx2From = accountService.getAccountById(from.getId());
                secondReadFromBalance.set(tx2From.getBalance());
                Account tx2To = accountService.getAccountById(to.getId());
                secondReadToBalance.set(tx2To.getBalance());
            });
        });

        Thread writer = new Thread(() -> {
            tx2.executeWithoutResult(status -> {
                try {
                    readerComplete.await(); // reader의 1차 조회가 끝날 때까지 대기
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                BigDecimal amount = BigDecimal.valueOf(7_000);
                Account tx2From = accountService.getAccountById(from.getId());
                Account tx2To = accountService.getAccountById(to.getId());

                tx2From.withdraw(amount);
                tx2To.deposit(amount);
            });
            // executeWithoutResult 블록 종료 → 커밋 완료
            writerComplete.countDown();
        });

        firstReader.start();
        writer.start();
        firstReader.join();
        writer.join();

        // 1차 조회: writer 수정 전 → 원래 잔액
        assertThat(firstReadFromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
        assertThat(firstReadToBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));

        // 2차 조회: writer 커밋 후 → 변경된 잔액 (Non-Repeatable Read 발생!)
        assertThat(secondReadFromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(3_000));
        assertThat(secondReadToBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(17_000));

    }

    /**
     * 비교군: REPEATABLE_READ에서는 Non-Repeatable Read가 방지되는지 확인.
     * 트랜잭션 시작 시점의 스냅샷을 읽기 때문에, writer가 커밋해도 2차 조회 결과가 동일하다.
     */
    @Test
    void REPEATABLE_READ에서_같은_트랜잭션_내_두번_조회_시_결과가_동일한가() throws InterruptedException {
        // reader — REPEATABLE_READ: 트랜잭션 시작 시점의 스냅샷을 읽음
        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        tx1.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        // writer — 격리 수준 상관없음 (쓰기 + 커밋만 함)
        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
        tx2.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        CountDownLatch readerComplete = new CountDownLatch(1);
        CountDownLatch writerComplete = new CountDownLatch(1);

        AtomicReference<BigDecimal> firstReadFromBalance = new AtomicReference<>();
        AtomicReference<BigDecimal> firstReadToBalance = new AtomicReference<>();

        AtomicReference<BigDecimal> secondReadFromBalance = new AtomicReference<>();
        AtomicReference<BigDecimal> secondReadToBalance = new AtomicReference<>();

        // 하나의 트랜잭션 안에서 1차 조회 → writer 커밋 대기 → 2차 조회
        Thread firstReader = new Thread(() -> {
            tx1.executeWithoutResult(status -> {
                // 1차 조회: writer가 수정하기 전
                Account tx1From = accountService.getAccountById(from.getId());
                Account tx1To = accountService.getAccountById(to.getId());

                firstReadFromBalance.set(tx1From.getBalance());
                firstReadToBalance.set(tx1To.getBalance());
                readerComplete.countDown();

                try {
                    writerComplete.await(); // writer가 커밋할 때까지 대기
                    entityManager.clear();  // JPA 1차 캐시 비우기 → 2차 조회 시 DB에서 다시 읽음
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                // 같은 트랜잭션에서 2차 조회: writer가 커밋한 후
                Account tx2From = accountService.getAccountById(from.getId());
                secondReadFromBalance.set(tx2From.getBalance());
                Account tx2To = accountService.getAccountById(to.getId());
                secondReadToBalance.set(tx2To.getBalance());
            });
        });

        Thread writer = new Thread(() -> {
            tx2.executeWithoutResult(status -> {
                try {
                    readerComplete.await(); // reader의 1차 조회가 끝날 때까지 대기
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                BigDecimal amount = BigDecimal.valueOf(7_000);
                Account tx2From = accountService.getAccountById(from.getId());
                Account tx2To = accountService.getAccountById(to.getId());

                tx2From.withdraw(amount);
                tx2To.deposit(amount);
            });
            // executeWithoutResult 블록 종료 → 커밋 완료
            writerComplete.countDown();
        });

        firstReader.start();
        writer.start();
        firstReader.join();
        writer.join();

        // 1차 조회: writer 수정 전 → 원래 잔액
        assertThat(firstReadFromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
        assertThat(firstReadToBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));

        // 2차 조회: writer 커밋 후에도 → 트랜잭션 시작 시점의 스냅샷을 읽음 (Non-Repeatable Read 방지)
        assertThat(secondReadFromBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));
        assertThat(secondReadToBalance.get()).isEqualByComparingTo(BigDecimal.valueOf(10_000));

    }


}

package eastmeet.voyage.transaction.learning_test;

import static org.assertj.core.api.Assertions.assertThat;

import eastmeet.voyage.transaction.account.domain.Account;
import eastmeet.voyage.transaction.account.domain.AccountStatus;
import eastmeet.voyage.transaction.account.repository.AccountRepository;
import eastmeet.voyage.transaction.account.service.AccountService;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@SpringBootTest
@ActiveProfiles("postgresql")
class LostUpdatePostgreSQLTest {

    public static final BigDecimal BALANCE = BigDecimal.valueOf(10_000);

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
            .balance(BALANCE)
            .build()
        );

        to = accountRepository.save(Account.builder()
            .status(AccountStatus.ACTIVE)
            .ownerName("to")
            .balance(BALANCE)
            .build()
        );

    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteById(from.getId());
        accountRepository.deleteById(to.getId());
    }

    /**
     * PostgreSQL READ_COMMITTED + @Version(낙관적 락) — Lost Update 방지 검증
     *
     * @Version 없이는 READ_COMMITTED에서 Last-Committer-Wins로 Lost Update가 조용히 발생했다.
     * READ_COMMITTED는 스냅샷을 고정하지 않아 DB 레벨 충돌 감지가 불가능하지만,
     * @Version이 UPDATE WHERE version=? 조건을 추가하여 애플리케이션 레벨에서 충돌을 감지한다.
     *
     * 결과: TX1만 반영 (from=5,000, to=15,000), TX2는 ObjectOptimisticLockingFailureException으로 롤백
     */
    @Test
    void READ_COMMITTED에서_VERSION_낙관적_락으로_Lost_Update가_방지되는가() throws InterruptedException {
        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        tx1.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
        tx2.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        // 두 트랜잭션이 모두 읽은 후에 쓰기를 시작하도록 동기화
        CountDownLatch bothRead = new CountDownLatch(2);
        // writer1이 커밋 완료한 후에 writer2가 쓰기를 시작하도록 동기화
        CountDownLatch writer1Committed = new CountDownLatch(1);

        // writer1: 5,000원 이체 (from → to)
        AtomicReference<Exception> writer1Exception = new AtomicReference<>();
        Thread writer1 = new Thread(() -> {
            try {
                tx1.executeWithoutResult(status -> {
                    // 스냅샷 읽기: from=10,000, to=10,000
                    Account tx1From = accountService.getAccountById(from.getId());
                    Account tx1To = accountService.getAccountById(to.getId());

                    bothRead.countDown();
                    try {
                        bothRead.await(); // writer2도 읽을 때까지 대기
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    BigDecimal amount = BigDecimal.valueOf(5_000);
                    tx1From.withdraw(amount);  // 10,000 - 5,000 = 5,000
                    tx1To.deposit(amount);     // 10,000 + 5,000 = 15,000
                    // dirty checking → UPDATE SET balance=5,000 / 15,000 → 커밋
                });
            } catch (CannotAcquireLockException e) {
                writer1Exception.set(e);
            }
            writer1Committed.countDown(); // 커밋 완료 후 writer2에게 신호
        });

        // writer2: 3,000원 이체 (from → to)
        AtomicReference<Exception> writer2Exception = new AtomicReference<>();
        Thread writer2 = new Thread(() -> {
            try {
                tx2.executeWithoutResult(status -> {
                    // 읽기: from=10,000, to=10,000 (writer1 커밋 전이므로 같은 값)
                    Account tx2From = accountService.getAccountById(from.getId());
                    Account tx2To = accountService.getAccountById(to.getId());

                    bothRead.countDown();
                    try {
                        bothRead.await();          // writer1도 읽을 때까지 대기
                        writer1Committed.await();  // writer1이 커밋할 때까지 대기
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    BigDecimal amount = BigDecimal.valueOf(3_000);
                    tx2From.withdraw(amount);  // 10,000 - 3,000 = 7,000 (stale 값 기반!)
                    tx2To.deposit(amount);     // 10,000 + 3,000 = 13,000 (stale 값 기반!)
                    // dirty checking → UPDATE WHERE version=0 시도 → version 불일치 → 예외 발생!
                });
            } catch (Exception ex) {
                writer2Exception.set(ex);
            }
        });

        writer1.start();
        writer2.start();
        writer1.join();
        writer2.join();

        Account fromAccount = accountService.getAccountById(from.getId());
        Account toAccount = accountService.getAccountById(to.getId());

        assertThat(fromAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(5_000));
        assertThat(toAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(15_000));
        assertThat(writer1Exception.get()).isNull();
        assertThat(writer2Exception.get()).isNotNull().isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /**
     * PostgreSQL REPEATABLE_READ + @Version — 이중 보호 (DB + 애플리케이션) 검증
     *
     * PostgreSQL REPEATABLE_READ는 자체적으로 First-Committer-Wins로 Lost Update를 방지한다.
     * @Version이 추가되면 DB 레벨(MVCC) + 애플리케이션 레벨(version 비교) 이중 보호가 된다.
     * 실제로는 DB의 serialization failure가 먼저 감지되어 CannotAcquireLockException이 발생한다.
     *
     * 결과: TX1만 반영 (from=5,000, to=15,000), TX2는 CannotAcquireLockException으로 롤백
     */
    @Test
    void REPEATABLE_READ에서_먼저_커밋한_트랜잭션만_성공하고_나중_트랜잭션은_롤백되는가() throws InterruptedException {
        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        tx1.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
        tx2.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        // 두 트랜잭션이 모두 읽은 후에 쓰기를 시작하도록 동기화
        CountDownLatch bothRead = new CountDownLatch(2);
        // writer1이 커밋 완료한 후에 writer2가 쓰기를 시작하도록 동기화
        CountDownLatch writer1Committed = new CountDownLatch(1);

        // writer1: 5,000원 이체 (from → to)
        Thread writer1 = new Thread(() -> {
            tx1.executeWithoutResult(status -> {
                // 스냅샷 읽기: from=10,000, to=10,000
                Account tx1From = accountService.getAccountById(from.getId());
                Account tx1To = accountService.getAccountById(to.getId());

                bothRead.countDown();
                try {
                    bothRead.await(); // writer2도 읽을 때까지 대기
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                BigDecimal amount = BigDecimal.valueOf(5_000);
                tx1From.withdraw(amount);  // 10,000 - 5,000 = 5,000
                tx1To.deposit(amount);     // 10,000 + 5,000 = 15,000
                // dirty checking → UPDATE SET balance=5,000 / 15,000 → 커밋
            });

            writer1Committed.countDown(); // 커밋 완료 후 writer2에게 신호
        });

        // writer2: 3,000원 이체 (from → to)
        AtomicReference<Exception> exception = new AtomicReference<>();
        Thread writer2 = new Thread(() -> {
            try {
                tx2.executeWithoutResult(status -> {
                    // 스냅샷 읽기: from=10,000, to=10,000 (writer1 커밋 전이므로 동일한 값)
                    Account tx2From = accountService.getAccountById(from.getId());
                    Account tx2To = accountService.getAccountById(to.getId());

                    bothRead.countDown();
                    try {
                        bothRead.await();          // writer1도 읽을 때까지 대기
                        writer1Committed.await();  // writer1이 커밋할 때까지 대기
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    BigDecimal amount = BigDecimal.valueOf(3_000);
                    tx2From.withdraw(amount);  // 10,000 - 3,000 = 7,000 (stale 값 기반!)
                    tx2To.deposit(amount);     // 10,000 + 3,000 = 13,000 (stale 값 ��반!)
                    // dirty checking → UPDATE 시도 → PostgreSQL이 충돌 감지 → 예외 발생!
                });
            } catch (CannotAcquireLockException ex) {
                exception.set(ex);
            }
        });

        writer1.start();
        writer2.start();
        writer1.join();
        writer2.join();

        // PostgreSQL: "could not serialize access due to concurrent update"
        log.error("writer2 예외: {}", exception.get().toString());

        // writer1의 이체만 반영, writer2는 롤백됨
        Account fromAccount = accountService.getAccountById(from.getId());
        Account toAccount = accountService.getAccountById(to.getId());

        assertThat(fromAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(5_000));
        assertThat(toAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(15_000));
        assertThat(exception.get()).isNotNull().isInstanceOf(CannotAcquireLockException.class);
    }

}
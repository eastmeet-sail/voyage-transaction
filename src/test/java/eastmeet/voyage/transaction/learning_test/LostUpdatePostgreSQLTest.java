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
     * PostgreSQL READ_COMMITTED (기본값) — Last-Committer-Wins (Lost Update 발생) 검증
     *
     * READ_COMMITTED는 스냅샷을 트랜잭션 시작 시점에 고정하지 않고, 매 쿼리마다 최신 커밋 값을 읽는다.
     * 따라서 "내가 읽은 이후 변경되었나?"를 추적할 기준점이 없어 충돌 감지가 불가능하다.
     * REPEATABLE_READ의 First-Committer-Wins와 달리, 나중 커밋이 이전 커밋을 조용히 덮어쓴다.
     *
     * 실제 결과: TX2의 이체만 반영 (from=7,000, to=13,000) — TX1의 5,000원 이체가 유실됨
     * 정상 결과(둘 다 반영 시): from=2,000, to=18,000
     */
    @Test
    void READ_COMMITTED에서_나중에_커밋한_트랜잭션이_이전_변경을_덮어쓰는가() throws InterruptedException {
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
                    // dirty checking → UPDATE SET balance=7,000 / 13,000 → 충돌 감지 없이 커밋 성공
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

        assertThat(fromAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(7_000));
        assertThat(toAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(13_000));
        assertThat(writer1Exception.get()).isNull();
        assertThat(writer2Exception.get()).isNull();
    }

    /**
     * PostgreSQL REPEATABLE_READ — First-Committer-Wins 검증
     * <p>
     * 두 트랜잭션이 같은 스냅샷(10,000원)을 읽고 각자 수정하면, 먼저 커밋한 TX1만 성공하고, 나중에 커밋하는 TX2는 충돌 감지되어 롤백된다.
     * <p>
     * 기대 결과: TX1의 이체만 반영 (from=5,000, to=15,000) 정상 결과(둘 다 성공 시): from=2,000, to=18,000
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
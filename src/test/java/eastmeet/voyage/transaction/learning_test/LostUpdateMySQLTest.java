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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@SpringBootTest
@ActiveProfiles("mysql")
class LostUpdateMySQLTest {

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
     * MySQL InnoDB REPEATABLE_READ + @Version(낙관적 락) — Lost Update 방지 검증
     *
     * @Version 없이는 MySQL REPEATABLE_READ에서 Last-Committer-Wins로 Lost Update가 조용히 발생했다.
     * @Version 적용 후, Hibernate가 UPDATE 시 WHERE version=? 조건을 추가하여 충돌을 감지한다.
     * <p>
     * TX1: UPDATE SET balance=5000, version=1 WHERE id=? AND version=0 → 성공 (version 0→1) TX2: UPDATE SET balance=7000, version=1 WHERE
     * id=? AND version=0 → 매칭 0건 → 예외 발생!
     * <p>
     * 결과: TX1만 반영 (from=5,000, to=15,000), TX2는 ObjectOptimisticLockingFailureException으로 롤백
     */
    @Test
    void VERSION_낙관적_락_적용_시_Lost_Update가_감지되어_나중_트랜잭션이_롤백되는가() throws InterruptedException {
        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        tx1.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
        tx2.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

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
            } catch (Exception ex) {
                writer1Exception.set(ex);
            }
            writer1Committed.countDown(); // 커밋 완료 후 writer2에게 신호
        });

        // writer2: 3,000원 이체 (from → to)
        AtomicReference<Exception> writer2Exception = new AtomicReference<>();
        Thread writer2 = new Thread(() -> {
            try {
                tx2.executeWithoutResult(status -> {
                    // 스냅샷 읽기: from=10,000, to=10,000 (writer1 커밋 전이므로 같은 값)
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

        // @Version으로 Lost Update 방지 — TX1만 반영, TX2는 롤백됨
        Account fromAccount = accountService.getAccountById(from.getId());
        Account toAccount = accountService.getAccountById(to.getId());

        assertThat(fromAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(5_000));
        assertThat(toAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(15_000));
        assertThat(writer1Exception.get()).isNull();
        assertThat(writer2Exception.get()).isNotNull().isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /**
     * MySQL REPEATABLE_READ + SELECT FOR UPDATE(비관적 락) — Lost Update 방지 검증
     *
     * 낙관적 락(@Version)은 충돌을 감지하여 TX2를 롤백시켰다.
     * 비관적 락(SELECT FOR UPDATE)은 읽는 시점부터 X lock을 걸어 TX2가 대기하도록 만든다.
     * TX1이 커밋하면 TX2는 TX1이 커밋한 최신 값을 읽고 정상 처리한다.
     *
     * 핵심 차이:
     * - 낙관적 락: TX2 실패 → 재시도 필요
     * - 비관적 락: TX2 대기 → 한 번에 성공 → 재시도 불필요
     *
     * MySQL 특이점: REPEATABLE_READ에서도 SELECT FOR UPDATE는 항상 최신 커밋된 값을 읽는다.
     * (Consistent Non-locking Read의 스냅샷과 달리, Locking Read는 최신 값 기준)
     *
     * CountDownLatch(tx1Locked): TX1이 락을 획득한 후 TX2가 SELECT FOR UPDATE를 시도하도록 동기화.
     * 두 트랜잭션이 시간적으로 겹치는 시나리오를 강제하여 비관적 락의 대기 동작을 확실히 검증한다.
     *
     * 결과: 두 이체 모두 반영 (from=2,000, to=18,000), 예외 없음
     */
    @Test
    void 비관적_락_적용_시_TX1_TX2_모두가_반영되는가() throws InterruptedException {
        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        tx1.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
        tx2.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        CountDownLatch tx1Locked = new CountDownLatch(1);

        AtomicReference<Exception> writer1Exception = new AtomicReference<>();
        Thread writer1 = new Thread(() -> {
            try {
                tx1.executeWithoutResult(status -> {
                    Account tx1From = accountService.getAccountByIdForUpdate(from.getId());
                    Account tx1To = accountService.getAccountByIdForUpdate(to.getId());

                    tx1Locked.countDown();

                    BigDecimal amount = BigDecimal.valueOf(5_000);
                    tx1From.withdraw(amount);  // 10,000 - 5,000 = 5,000
                    tx1To.deposit(amount);     // 10,000 + 5,000 = 15,000
                });
            } catch (Exception ex) {
                writer1Exception.set(ex);
            }
        });

        // writer2: 3,000원 이체 (from → to)
        AtomicReference<Exception> writer2Exception = new AtomicReference<>();
        Thread writer2 = new Thread(() -> {
            try {
                tx2.executeWithoutResult(status -> {
                    try {
                        tx1Locked.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    Account tx2From = accountService.getAccountByIdForUpdate(from.getId());
                    Account tx2To = accountService.getAccountByIdForUpdate(to.getId());

                    BigDecimal amount = BigDecimal.valueOf(3_000);
                    tx2From.withdraw(amount);  // 5,000 - 3,000 = 2,000 (TX1 커밋 후 최신 값 기반)
                    tx2To.deposit(amount);     // 15,000 + 3,000 = 18,000 (TX1 커밋 후 최신 값 기반)
                });
            } catch (Exception ex) {
                writer2Exception.set(ex);
            }
        });

        writer1.start();
        writer2.start();
        writer1.join();
        writer2.join();

        // SELECT FOR UPDATE로 두 이체 모두 정상 반영
        Account fromAccount = accountService.getAccountById(from.getId());
        Account toAccount = accountService.getAccountById(to.getId());

        assertThat(fromAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(2_000));
        assertThat(toAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(18_000));
        assertThat(writer1Exception.get()).isNull();
        assertThat(writer2Exception.get()).isNull();
    }

}
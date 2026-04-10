package eastmeet.voyage.transaction.learning_test;

import static org.assertj.core.api.Assertions.assertThat;

import eastmeet.voyage.transaction.account.domain.Account;
import eastmeet.voyage.transaction.account.domain.AccountStatus;
import eastmeet.voyage.transaction.account.repository.AccountRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class PhantomReadTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Account from;

    private Account to;

    private Account newAccount;

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
        accountRepository.deleteById(newAccount.getId());
    }

    @Test
    void REPEATABLE_READ에서_새로운_행_추가_시_조회_결과_건수가_달라지는가() throws InterruptedException {
        // reader — REPEATABLE_READ
        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        tx1.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        // writer — 격리 수준 상관없음 (쓰기 + 커밋만 함)
        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);

        CountDownLatch firstReadComplete = new CountDownLatch(1);
        CountDownLatch writerComplete = new CountDownLatch(1);

        AtomicInteger firstReadCount = new AtomicInteger(0);
        AtomicInteger secondReadCount = new AtomicInteger(0);

        // 하나의 트랜잭션 안에서 1차 조회 → writer 커밋 대기 → 2차 조회
        Thread reader = new Thread(() -> {
            tx1.executeWithoutResult(status -> {
                // 1차 조회: writer가 INSERT 하기 전
                List<Account> firstRead = accountRepository.findByStatus(AccountStatus.ACTIVE);
                firstReadCount.set(firstRead.size());
                firstReadComplete.countDown();

                try {
                    writerComplete.await(); // writer가 커밋할 때까지 대기
                    entityManager.clear();  // JPA 1차 캐시 비우기
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                // 같은 트랜잭션에서 2차 조회: writer가 새 행을 INSERT + 커밋한 후
                List<Account> secondRead = accountRepository.findByStatus(AccountStatus.ACTIVE);
                secondReadCount.set(secondRead.size());
            });
        });

        Thread writer = new Thread(() -> {
            tx2.executeWithoutResult(status -> {
                try {
                    firstReadComplete.await(); // reader의 1차 조회가 끝날 때까지 대기
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                // 새 ACTIVE 계좌 추가
                newAccount = accountRepository.save(
                    Account.builder()
                        .balance(BigDecimal.valueOf(10_000))
                        .status(AccountStatus.ACTIVE)
                        .ownerName("newMember")
                        .build()
                );
            });

            writerComplete.countDown();
        });

        reader.start();
        writer.start();
        reader.join();
        writer.join();

        // 1차 조회: 2건 (from, to)
        assertThat(firstReadCount.get()).isEqualTo(2);

        // 2차 조회: Phantom Read 발생 시 3건, 방지 시 2건
        // MySQL InnoDB REPEATABLE_READ는 Gap Lock으로 Phantom Read도 방지함
        assertThat(secondReadCount.get()).isEqualTo(2);
    }

}
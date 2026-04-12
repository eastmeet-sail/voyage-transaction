package eastmeet.voyage.transaction.basic;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@DisplayName("트랜잭션 적용 우선순위 테스트")
class TxLevelTest {

    @Autowired
    TxLevelService txLevelService;

    @Test
    void 메서드_레벨_Transactional이_클래스_레벨보다_우선하는가() {
        txLevelService.write();
        txLevelService.read();
    }

    @TestConfiguration
    static class TxLevelTestConfig {

        @Bean
        TxLevelService levelService() {
            return new TxLevelService();
        }
    }

    @Slf4j
    @Transactional(readOnly = true)
    static class TxLevelService {

        @Transactional
        public void write() {
            log.info("call write");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        }

        public void read() {
            log.info("call read");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
        }
    }

}

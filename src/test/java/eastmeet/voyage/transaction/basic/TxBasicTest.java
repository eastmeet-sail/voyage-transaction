package eastmeet.voyage.transaction.basic;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@SpringBootTest
@DisplayName("트랜잭션 AOP 기본 동작 테스트")
class TxBasicTest {

    @Autowired
    TxBasicService txBasicService;

    @Test
    void Transactional이_붙으면_AOP_프록시가_생성되는가() {
        log.info("aop class={}", txBasicService.getClass().getName());
        assertThat(AopUtils.isAopProxy(txBasicService)).isTrue();
    }

    @Test
    void Transactional_유무에_따라_트랜잭션_활성화_여부가_다른가() {
        assertThat(txBasicService.tx()).isTrue();
        assertThat(txBasicService.nonTx()).isFalse();
    }

    @TestConfiguration
    static class TxApplyBasicConfig {

        @Bean
        TxBasicService txBasicService() {
            return new TxBasicService();
        }
    }

    @Slf4j
    static class TxBasicService {

        @Transactional
        public boolean tx() {
            log.info("====call tx====");
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        public boolean nonTx() {
            log.info("====call non tx====");
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

    }

}

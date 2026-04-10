package eastmeet.voyage.transaction.account.service;

import eastmeet.voyage.transaction.account.domain.Account;
import eastmeet.voyage.transaction.account.repository.AccountRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    public @NonNull Account getAccountById(UUID id) {
        return accountRepository.findById(id).orElseThrow(
            () -> new EntityNotFoundException("Account not found with id: " + id)
        );
    }

    /**
     * 학습 테스트 전용: 비관적 락(SELECT FOR UPDATE) 조회.
     * 실무에서는 락 획득과 비즈니스 로직을 하나의 @Transactional 메서드로 묶어야 한다.
     * 여기서는 학습 테스트에서 TransactionTemplate 안에서 직접 호출하기 위해 public으로 노출.
     */
    public @NonNull Account getAccountByIdForUpdate(UUID id) {
        return accountRepository.findByIdForUpdate(id).orElseThrow(
            () -> new EntityNotFoundException("Account not found with id: " + id)
        );
    }

    @Transactional
    public void withdrawWithUncheckedException(UUID id, BigDecimal amount) {
        Account account = getAccountById(id);
        account.withdraw(amount);

        throw new RuntimeException("unchecked exception 발생!");
    }

    @Transactional
    public void withdrawWithCheckedException(UUID id, BigDecimal amount) throws Exception {
        Account account = getAccountById(id);
        account.withdraw(amount);

        throw new Exception("checked exception 발생!");
    }

    @Transactional(rollbackFor = Exception.class)
    public void withdrawWithCheckedExceptionAndRollbackFor(UUID id, BigDecimal amount) throws Exception {
        Account account = getAccountById(id);
        account.withdraw(amount);

        throw new Exception("checked exception 발생!");
    }

}

package eastmeet.voyage.transaction.account.repository;

import eastmeet.voyage.transaction.account.domain.Account;
import eastmeet.voyage.transaction.account.domain.AccountStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Modifying
    @Query("UPDATE Account a SET a.balance = :balance WHERE a.id = :id")
    void updateBalanceDirectly(@Param("id") UUID id, @Param("balance") BigDecimal balance);

    List<Account> findByStatus(AccountStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(UUID id);


}

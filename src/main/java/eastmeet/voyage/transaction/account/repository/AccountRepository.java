package eastmeet.voyage.transaction.account.repository;

import eastmeet.voyage.transaction.account.domain.Account;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.sql.Update;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Modifying
    @Query("UPDATE Account a SET a.balance = :balance WHERE a.id = :id")
    void updateBalanceDirectly(@Param("id") UUID id, @Param("balance") BigDecimal balance);


}

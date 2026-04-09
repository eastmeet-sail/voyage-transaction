package eastmeet.voyage.transaction.account.repository;

import eastmeet.voyage.transaction.account.domain.Account;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

}

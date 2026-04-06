package eastmeet.voyage.transaction.account.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AccountStatus {
    ACTIVE("활성상태"),
    SUSPENDED("중지"),

    ;

    private final String description;

}

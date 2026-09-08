package com.vibecode.provider.infrastructure;

import com.vibecode.provider.domain.ProviderAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderAccountRepository extends JpaRepository<ProviderAccount, UUID> {

  List<ProviderAccount> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

  /** Scoped by owner in the query itself, so a wrong id can never return someone else's row. */
  Optional<ProviderAccount> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);
}

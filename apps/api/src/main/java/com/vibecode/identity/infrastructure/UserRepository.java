package com.vibecode.identity.infrastructure;

import com.vibecode.identity.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

  /** The argument must already be normalized; the stored column is. */
  Optional<User> findByEmail(String normalizedEmail);

  boolean existsByEmail(String normalizedEmail);
}

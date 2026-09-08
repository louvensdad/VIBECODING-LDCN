package com.vibecode.vault.infrastructure;

import com.vibecode.vault.domain.SecretVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * There is deliberately no "find the active version" query here.
 *
 * <p>Which version is in force is a fact stored on the secret, and looking it up any other way
 * would create a second answer to the same question.
 */
public interface SecretVersionRepository extends JpaRepository<SecretVersion, UUID> {

  List<SecretVersion> findBySecretIdOrderByVersionNumberDesc(UUID secretId);
}

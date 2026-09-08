package com.vibecode.vault.infrastructure;

import com.vibecode.vault.domain.SecretRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecretRecordRepository extends JpaRepository<SecretRecord, UUID> {}

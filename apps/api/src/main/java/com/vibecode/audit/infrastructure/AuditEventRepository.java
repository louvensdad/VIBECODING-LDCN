package com.vibecode.audit.infrastructure;

import com.vibecode.audit.domain.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

  List<AuditEvent> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}


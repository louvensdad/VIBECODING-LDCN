package com.vibecode.output.infrastructure;

import com.vibecode.output.domain.OutputAnalysisRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutputAnalysisRecordRepository extends JpaRepository<OutputAnalysisRecord, UUID> {

  Optional<OutputAnalysisRecord> findByEvidenceId(UUID evidenceId);

  List<OutputAnalysisRecord> findByEvidenceIdIn(List<UUID> evidenceIds);
}

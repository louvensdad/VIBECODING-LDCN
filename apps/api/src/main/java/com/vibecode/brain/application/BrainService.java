package com.vibecode.brain.application;

import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.brain.domain.ProjectBrain;
import com.vibecode.brain.infrastructure.BrainEntryRepository;
import com.vibecode.project.application.ProjectService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only write path into official project memory.
 *
 * <p>Every entry carries an explicit source, so memory written by a human and memory transcribed
 * from a model stay distinguishable.
 */
@Service
@Transactional
public class BrainService {

  private final BrainEntryRepository entries;
  private final ProjectService projects;

  public BrainService(BrainEntryRepository entries, ProjectService projects) {
    this.entries = entries;
    this.projects = projects;
  }

  @Transactional(readOnly = true)
  public ProjectBrain load(UUID projectId) {
    projects.requireReadable(projectId);
    return new ProjectBrain(projectId, entries.findByProjectIdOrderByCreatedAtDesc(projectId));
  }

  @Transactional(readOnly = true)
  public List<BrainEntry> list(UUID projectId) {
    projects.requireReadable(projectId);
    return entries.findByProjectIdOrderByCreatedAtDesc(projectId);
  }

  @Transactional(readOnly = true)
  public List<BrainEntry> listByType(UUID projectId, BrainEntryType type) {
    projects.requireReadable(projectId);
    return entries.findByProjectIdAndTypeOrderByCreatedAtDesc(projectId, type);
  }

  public BrainEntry add(
      UUID projectId, BrainEntryType type, String title, String content, String source) {
    projects.requireWritable(projectId);
    return entries.save(new BrainEntry(projectId, type, title, content, source));
  }
}

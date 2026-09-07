package com.vibecode.audit.web;

import com.vibecode.audit.application.AuditService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/audit")
public class AuditController {

  private final AuditService audit;

  public AuditController(AuditService audit) {
    this.audit = audit;
  }

  @GetMapping
  public List<AuditEventResponse> list(@PathVariable UUID projectId) {
    return audit.listForProject(projectId).stream()
        .map(AuditEventResponse::from)
        .toList();
  }
}


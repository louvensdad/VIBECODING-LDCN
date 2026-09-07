package com.vibecode.output.web;

import com.vibecode.output.application.OutputAnalyzer;
import com.vibecode.project.application.ProjectService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/outputs")
public class OutputController {

  private final OutputAnalyzer analyzer;
  private final ProjectService projects;

  public OutputController(OutputAnalyzer analyzer, ProjectService projects) {
    this.analyzer = analyzer;
    this.projects = projects;
  }

  /**
   * Analyzes an output without storing it. Persisting outputs, and turning an analysis into memory,
   * belongs to the step that introduces memory proposals.
   */
  @PostMapping("/analyze")
  public OutputAnalysisResponse analyze(
      @PathVariable UUID projectId, @Valid @RequestBody AnalyzeOutputRequest request) {
    projects.requireExisting(projectId);
    return OutputAnalysisResponse.from(
        analyzer.analyze(request.content()), request.kindOrDefault());
  }
}

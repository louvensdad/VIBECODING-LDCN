package com.vibecode.output.web;

import com.vibecode.output.domain.OutputAnalysis;
import com.vibecode.output.domain.OutputAnalysisStatus;
import com.vibecode.output.domain.OutputKind;
import java.util.List;

public record OutputAnalysisResponse(
    OutputAnalysisStatus status,
    String summary,
    List<String> signals,
    boolean shouldContinue,
    boolean requiresCorrection,
    OutputKind kind) {

  public static OutputAnalysisResponse from(OutputAnalysis analysis, OutputKind kind) {
    return new OutputAnalysisResponse(
        analysis.status(),
        analysis.summary(),
        analysis.signals().stream().map(Enum::name).toList(),
        analysis.shouldContinue(),
        analysis.requiresCorrection(),
        kind);
  }
}

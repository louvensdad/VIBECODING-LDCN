package com.vibecode.output.domain;

import java.util.List;

/**
 * The structured result of analyzing one output.
 *
 * <p>{@code shouldContinue} is only ever true when technical evidence of success was found, so an
 * unverified claim can never advance the roadmap on its own.
 */
public record OutputAnalysis(
    OutputAnalysisStatus status,
    String summary,
    List<OutputSignal> signals,
    boolean shouldContinue,
    boolean requiresCorrection) {

  public OutputAnalysis {
    signals = List.copyOf(signals);
  }
}

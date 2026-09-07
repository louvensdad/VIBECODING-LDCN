package com.vibecode.output.web;

import com.vibecode.output.domain.OutputKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Output pasted by the user for analysis. */
public record AnalyzeOutputRequest(
    @NotBlank @Size(max = 200_000) String content, OutputKind kind) {

  public OutputKind kindOrDefault() {
    return kind == null ? OutputKind.GENERIC_TEXT : kind;
  }
}

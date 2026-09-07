package com.vibecode.output.application;

import com.vibecode.output.domain.OutputAnalysis;
import com.vibecode.output.domain.OutputAnalysisStatus;
import com.vibecode.output.domain.OutputSignal;
import com.vibecode.output.domain.OutputSignal.Severity;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, first-generation output analyzer. No model is involved.
 *
 * <p>Two rules shape everything here:
 *
 * <ol>
 *   <li>Technical evidence outranks assertions. "Concluído com sucesso" above a stack trace is a
 *       failure.
 *   <li>Absence of evidence is not success. Output that only claims completion comes back as {@link
 *       OutputAnalysisStatus#NEEDS_VALIDATION}, never SUCCESS.
 * </ol>
 */
@Component
public class OutputAnalyzer {

  private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

  /** Surefire/Maven summary line, the most reliable test evidence available. */
  private static final Pattern SUREFIRE_SUMMARY =
      Pattern.compile("tests run:\\s*(\\d+),\\s*failures:\\s*(\\d+),\\s*errors:\\s*(\\d+)", FLAGS);

  /** Jest/Vitest summary line, e.g. "Tests: 2 failed, 8 passed, 10 total". */
  private static final Pattern JS_TEST_SUMMARY =
      Pattern.compile("^\\s*tests?:\\s*.*$", FLAGS | Pattern.MULTILINE);

  private static final Pattern JS_TESTS_FAILED = Pattern.compile("(\\d+)\\s+failed", FLAGS);

  private static final Pattern JS_TESTS_PASSED = Pattern.compile("(\\d+)\\s+passed", FLAGS);

  private static final Pattern PROSE_TESTS_PASSED =
      Pattern.compile("\\btests?\\s+(passed|succeeded|ok)\\b", FLAGS);

  private static final Pattern PROSE_TESTS_FAILED =
      Pattern.compile("\\b\\d*\\s*tests?\\s+failed\\b", FLAGS);

  private static final Pattern HAS_CONTENT = Pattern.compile("[\\p{L}\\p{N}]");

  /**
   * Keyword evidence, declared success-first so a report reads in that order.
   *
   * <p>The ERROR and FAILED patterns carry guards against zero counts ("0 errors", "Errors: 0", "no
   * errors"), which otherwise turn every clean build report into a false failure.
   */
  private static final Map<OutputSignal, Pattern> PATTERNS = buildPatterns();

  private static Map<OutputSignal, Pattern> buildPatterns() {
    Map<OutputSignal, Pattern> patterns = new LinkedHashMap<>();
    patterns.put(OutputSignal.BUILD_SUCCESS, Pattern.compile("build success(ful)?\\b", FLAGS));
    patterns.put(
        OutputSignal.MIGRATION_APPLIED,
        Pattern.compile("successfully applied\\s+\\d+\\s+migration", FLAGS));
    patterns.put(
        OutputSignal.HTTP_OK, Pattern.compile("http/\\d(\\.\\d)?\\s+(200|201|204)\\b", FLAGS));

    patterns.put(OutputSignal.BUILD_FAILURE, Pattern.compile("build fail(ure|ed)\\b", FLAGS));
    patterns.put(
        OutputSignal.COMPILATION_ERROR,
        Pattern.compile("compilation error|cannot find symbol|syntaxerror|ts\\d{4}:", FLAGS));
    patterns.put(
        OutputSignal.EXCEPTION,
        Pattern.compile(
            "\\b\\w+(exception|throwable)\\b"
                + "|traceback \\(most recent call last\\)"
                + "|^\\s*at\\s+[\\w.$]+\\([\\w.]+:\\d+\\)"
                + "|^panic:",
            FLAGS | Pattern.MULTILINE));
    patterns.put(
        OutputSignal.HTTP_SERVER_ERROR,
        Pattern.compile("http/\\d(\\.\\d)?\\s+(4\\d{2}|5\\d{2})\\b", FLAGS));

    patterns.put(
        OutputSignal.ERROR,
        Pattern.compile("(?<!\\bno )(?<!\\b0 )\\berrors?\\b(?!\\s*[:=]?\\s*0\\b)", FLAGS));
    patterns.put(
        OutputSignal.FAILED,
        Pattern.compile(
            "(?<!\\bno )(?<!\\b0 )\\b(failed|failure)\\b(?!\\s*[:=]?\\s*0\\b)", FLAGS));

    patterns.put(
        OutputSignal.PERMISSION_DENIED,
        Pattern.compile("permission denied|access denied|eacces|\\bblocked\\b", FLAGS));
    patterns.put(
        OutputSignal.QUOTA_EXCEEDED,
        Pattern.compile("quota exceeded|rate limit|insufficient (credit|quota|balance)", FLAGS));
    patterns.put(
        OutputSignal.AUTHENTICATION_REQUIRED,
        Pattern.compile("unauthorized|authentication (failed|required)|invalid api key", FLAGS));

    patterns.put(
        OutputSignal.CLAIMED_COMPLETION,
        Pattern.compile(
            "\\b(conclu[íi]d[oa]|finalizad[oa]|pronto|implementado"
                + "|success(fully)?|completed?|done)\\b",
            FLAGS));
    return Map.copyOf(patterns);
  }

  public OutputAnalysis analyze(String rawOutput) {
    String output = rawOutput == null ? "" : rawOutput;
    if (!HAS_CONTENT.matcher(output).find()) {
      return new OutputAnalysis(
          OutputAnalysisStatus.UNKNOWN,
          "A saída não contém conteúdo analisável.",
          List.of(),
          false,
          false);
    }
    return classify(detectSignals(output));
  }

  private Set<OutputSignal> detectSignals(String output) {
    Set<OutputSignal> signals = EnumSet.noneOf(OutputSignal.class);
    detectTestOutcome(output).ifPresent(signals::add);
    PATTERNS.forEach(
        (signal, pattern) -> {
          if (pattern.matcher(output).find()) {
            signals.add(signal);
          }
        });
    // A counted test verdict is stronger than the loose keywords the same summary line also trips,
    // so those would add noise rather than information.
    if (signals.contains(OutputSignal.TESTS_PASSED)) {
      signals.remove(OutputSignal.ERROR);
      signals.remove(OutputSignal.FAILED);
    }
    return signals;
  }

  /**
   * Reads a test summary as counts rather than keywords, so "Failures: 0" is understood as a pass
   * instead of matching the word "failures".
   */
  private Optional<OutputSignal> detectTestOutcome(String output) {
    Matcher surefire = SUREFIRE_SUMMARY.matcher(output);
    if (surefire.find()) {
      int failures = Integer.parseInt(surefire.group(2));
      int errors = Integer.parseInt(surefire.group(3));
      return Optional.of(
          failures + errors > 0 ? OutputSignal.TESTS_FAILED : OutputSignal.TESTS_PASSED);
    }

    Matcher jsSummary = JS_TEST_SUMMARY.matcher(output);
    if (jsSummary.find()) {
      String line = jsSummary.group();
      if (JS_TESTS_FAILED.matcher(line).find()) {
        return Optional.of(OutputSignal.TESTS_FAILED);
      }
      if (JS_TESTS_PASSED.matcher(line).find()) {
        return Optional.of(OutputSignal.TESTS_PASSED);
      }
    }

    if (PROSE_TESTS_FAILED.matcher(output).find()) {
      return Optional.of(OutputSignal.TESTS_FAILED);
    }
    if (PROSE_TESTS_PASSED.matcher(output).find()) {
      return Optional.of(OutputSignal.TESTS_PASSED);
    }
    return Optional.empty();
  }

  private OutputAnalysis classify(Set<OutputSignal> signals) {
    List<OutputSignal> reported = List.copyOf(signals);
    boolean blocking = any(signals, Severity.BLOCKING);
    boolean hardFailure = any(signals, Severity.HARD_FAILURE);
    boolean softFailure = any(signals, Severity.SOFT_FAILURE);
    boolean strongSuccess = any(signals, Severity.STRONG_SUCCESS);
    boolean claimedCompletion = any(signals, Severity.SUCCESS_CLAIM);

    if (blocking) {
      return new OutputAnalysis(
          OutputAnalysisStatus.BLOCKED,
          "A saída indica um bloqueio externo (permissão, credencial ou limite). "
              + "É preciso resolvê-lo antes de continuar.",
          reported,
          false,
          true);
    }
    if (hardFailure) {
      return new OutputAnalysis(
          OutputAnalysisStatus.FAILURE,
          "A saída contém evidência técnica de falha. A etapa não pode ser marcada como concluída.",
          reported,
          false,
          true);
    }
    if (softFailure && strongSuccess) {
      return new OutputAnalysis(
          OutputAnalysisStatus.PARTIAL,
          "Há evidência de sucesso, mas também erros relatados. Revise antes de avançar.",
          reported,
          false,
          true);
    }
    if (softFailure) {
      return new OutputAnalysis(
          OutputAnalysisStatus.FAILURE,
          "A saída relata erros e nenhuma evidência de sucesso.",
          reported,
          false,
          true);
    }
    if (strongSuccess) {
      return new OutputAnalysis(
          OutputAnalysisStatus.SUCCESS,
          "A saída contém evidência técnica de execução bem-sucedida.",
          reported,
          true,
          false);
    }
    if (claimedCompletion) {
      return new OutputAnalysis(
          OutputAnalysisStatus.NEEDS_VALIDATION,
          "A saída afirma conclusão, mas não apresenta evidência técnica. "
              + "Peça o resultado de build, teste ou execução.",
          reported,
          false,
          false);
    }
    return new OutputAnalysis(
        OutputAnalysisStatus.NEEDS_VALIDATION,
        "Não há evidência técnica suficiente para avançar.",
        reported,
        false,
        false);
  }

  private boolean any(Set<OutputSignal> signals, Severity severity) {
    return signals.stream().anyMatch(signal -> signal.is(severity));
  }
}

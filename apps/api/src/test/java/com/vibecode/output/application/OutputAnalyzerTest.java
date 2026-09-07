package com.vibecode.output.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.output.domain.OutputAnalysis;
import com.vibecode.output.domain.OutputAnalysisStatus;
import com.vibecode.output.domain.OutputSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OutputAnalyzerTest {

  private final OutputAnalyzer analyzer = new OutputAnalyzer();

  @Nested
  @DisplayName("technical evidence of success")
  class Success {

    @Test
    void recognizesASuccessfulBuild() {
      OutputAnalysis result = analyzer.analyze("[INFO] BUILD SUCCESS\n[INFO] Total time: 4.2 s");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.SUCCESS);
      assertThat(result.shouldContinue()).isTrue();
      assertThat(result.requiresCorrection()).isFalse();
      assertThat(result.signals()).contains(OutputSignal.BUILD_SUCCESS);
    }

    @Test
    @DisplayName("a clean Surefire summary is a pass, not a hit on the word 'failures'")
    void readsTestCountsRatherThanKeywords() {
      OutputAnalysis result =
          analyzer.analyze("Tests run: 24, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.SUCCESS);
      assertThat(result.signals()).contains(OutputSignal.TESTS_PASSED);
      assertThat(result.signals()).doesNotContain(OutputSignal.ERROR, OutputSignal.FAILED);
    }

    @Test
    void recognizesAppliedMigrations() {
      OutputAnalysis result =
          analyzer.analyze("Successfully applied 2 migrations to schema \"public\"");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.SUCCESS);
      assertThat(result.signals()).contains(OutputSignal.MIGRATION_APPLIED);
    }
  }

  @Nested
  @DisplayName("evidence outranks claims")
  class EvidenceWins {

    @Test
    void aStackTraceBeatsAClaimOfCompletion() {
      OutputAnalysis result =
          analyzer.analyze(
              """
              Pronto! Implementei tudo com sucesso, a etapa está concluída.

              java.lang.NullPointerException: Cannot invoke "Project.getId()"
                  at com.vibecode.project.ProjectService.get(ProjectService.java:31)
              """);

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.FAILURE);
      assertThat(result.shouldContinue()).isFalse();
      assertThat(result.requiresCorrection()).isTrue();
      assertThat(result.signals()).contains(OutputSignal.EXCEPTION);
    }

    @Test
    void failingTestsBlockProgressEvenWhenTheBuildSucceeded() {
      OutputAnalysis result =
          analyzer.analyze("BUILD SUCCESS\nTests run: 10, Failures: 2, Errors: 0");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.FAILURE);
      assertThat(result.shouldContinue()).isFalse();
      assertThat(result.signals()).contains(OutputSignal.TESTS_FAILED);
    }

    @Test
    void compilationErrorsAreFailures() {
      OutputAnalysis result =
          analyzer.analyze("COMPILATION ERROR : cannot find symbol\nBUILD FAILURE");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.FAILURE);
      assertThat(result.signals())
          .contains(OutputSignal.COMPILATION_ERROR, OutputSignal.BUILD_FAILURE);
    }
  }

  @Nested
  @DisplayName("a claim of completion is never enough")
  class NeedsValidation {

    @Test
    void plainProseClaimingSuccessRequiresValidation() {
      OutputAnalysis result = analyzer.analyze("Pronto, a funcionalidade está concluída.");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.NEEDS_VALIDATION);
      assertThat(result.shouldContinue()).isFalse();
      assertThat(result.signals()).containsExactly(OutputSignal.CLAIMED_COMPLETION);
    }

    @Test
    void neutralTextWithoutAnySignalRequiresValidation() {
      OutputAnalysis result = analyzer.analyze("Aqui está o arquivo que você pediu.");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.NEEDS_VALIDATION);
      assertThat(result.shouldContinue()).isFalse();
    }
  }

  @Nested
  @DisplayName("mixed and blocking outcomes")
  class OtherOutcomes {

    @Test
    void successAlongsideReportedErrorsIsPartial() {
      OutputAnalysis result =
          analyzer.analyze("BUILD SUCCESS\n[WARN] 3 errors were logged during startup");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.PARTIAL);
      assertThat(result.shouldContinue()).isFalse();
      assertThat(result.requiresCorrection()).isTrue();
    }

    @Test
    void permissionProblemsAreBlocked() {
      OutputAnalysis result = analyzer.analyze("docker: permission denied while trying to connect");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.BLOCKED);
      assertThat(result.shouldContinue()).isFalse();
      assertThat(result.signals()).contains(OutputSignal.PERMISSION_DENIED);
    }

    @Test
    void exhaustedCreditIsBlockedRatherThanFailed() {
      OutputAnalysis result = analyzer.analyze("Error: insufficient credit balance for this API key");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.BLOCKED);
      assertThat(result.signals()).contains(OutputSignal.QUOTA_EXCEEDED);
    }

    @Test
    void contentWithNothingAnalyzableIsUnknown() {
      OutputAnalysis result = analyzer.analyze("--- >>> ...");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.UNKNOWN);
      assertThat(result.signals()).isEmpty();
      assertThat(result.shouldContinue()).isFalse();
    }

    @Test
    void errorsWithoutSuccessAreFailures() {
      OutputAnalysis result = analyzer.analyze("npm ERR! code ELIFECYCLE\nnpm ERR! errors found");

      assertThat(result.status()).isEqualTo(OutputAnalysisStatus.FAILURE);
      assertThat(result.requiresCorrection()).isTrue();
    }
  }

  @Test
  @DisplayName("no output is ever allowed to advance the roadmap without success evidence")
  void shouldContinueOnlyOnSuccess() {
    String[] outputs = {
      "Pronto, concluído",
      "BUILD FAILURE",
      "permission denied",
      "Tests run: 3, Failures: 1, Errors: 0",
      "...",
      "BUILD SUCCESS\n2 errors logged"
    };

    for (String output : outputs) {
      assertThat(analyzer.analyze(output).shouldContinue())
          .as("output must not advance the project: %s", output)
          .isFalse();
    }

    assertThat(analyzer.analyze("BUILD SUCCESS").shouldContinue()).isTrue();
  }
}

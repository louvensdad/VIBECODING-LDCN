package com.vibecode.guardian;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.guardian.domain.SensitiveDataRedactor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {

  @Test
  @DisplayName("Redacts private keys, URLs with passwords, tokens, and assignments")
  void redactsSensitiveData() {
    String input =
        """
        -----BEGIN RSA PRIVATE KEY-----
        MIICXAIBAAKCAQEA0m4wfakekey12345
        -----END RSA PRIVATE KEY-----
        DATABASE_URL=postgres://myuser:secretpassword@localhost:5432/app
        OPENAI_API_KEY=sk-testingsynthetictokenvalue1234567890
        GITHUB_TOKEN=ghp_testingsynthetictokenvalue1234567890
        Authorization: Bearer myauthtoken1234567890
        PASSWORD=my-secret-value
        """;

    String redacted = SensitiveDataRedactor.redact(input);

    assertThat(redacted).doesNotContain("secretpassword");
    assertThat(redacted).doesNotContain("my-secret-value");
    assertThat(redacted).doesNotContain("myauthtoken1234567890");
    assertThat(redacted).doesNotContain("MIICXAIBAAKCAQEA0m4wfakekey12345");
    assertThat(redacted).contains("[REDACTED]");
    assertThat(redacted).contains("sk-****REDACTED****");
    assertThat(redacted).contains("ghp_****REDACTED****");
  }

  /**
   * <b>This test asserted the opposite until SEC-RED-02, and the change is deliberate.</b>
   *
   * <p>It read {@code assertThat(redact("PASSWORD=${DB_PASSWORD}\nAPI_KEY=<YOUR_KEY>\nTOKEN=
   * REPLACE_ME")).isEqualTo(safe)} — placeholders passed through even on the right-hand side of a
   * sensitive key. That exemption was a bypass: {@code isSafePlaceholder} has to decide from the
   * shape of a string whether {@code $ABC} is a variable read or a password, and
   * {@code $Pa55phrase_zqxw_610455} and {@code $DB_PASSWORD} are the same string to any pattern.
   * Since the caller controls the value, any sharpening of that heuristic is a new bypass with a
   * rule written for it.
   *
   * <p>So the architect ruled on precedence rather than on the policy:
   * <b>{@code SECRET ASSIGNMENT > PLACEHOLDER EXEMPTION}</b>. The key already supplies the context;
   * the value's shape is never consulted inside an assignment. The placeholder policy itself is
   * unchanged and still decides everywhere else, which is what the second half of this test now
   * pins.
   *
   * <p>The cost is that {@code password: ${DB_PASSWORD}} — the idiom Spring recommends precisely so
   * that a configuration file holds no secret — comes back as {@code password: [REDACTED]}. That
   * was measured over this repository and accepted; {@code SecretAssignmentGrammarTest} carries the
   * numbers and the pinned cases.
   */
  @Test
  @DisplayName("A placeholder is a placeholder everywhere except inside a secret assignment")
  void placeholdersAreExemptOnlyOutsideAnAssignment() {
    // Inside an assignment: the right-hand side goes, whatever it looks like.
    assertThat(
            SensitiveDataRedactor.redact(
                "PASSWORD=${DB_PASSWORD}\nAPI_KEY=<YOUR_KEY>\nTOKEN=REPLACE_ME"))
        .isEqualTo("PASSWORD=[REDACTED]\nAPI_KEY=[REDACTED]\nTOKEN=[REDACTED]");

    // Outside one: unchanged, and asserted on the policy itself rather than on an absence of
    // redaction, which could have had other causes.
    assertThat(SensitiveDataRedactor.isSafePlaceholder("${DB_PASSWORD}")).isTrue();
    assertThat(SensitiveDataRedactor.isSafePlaceholder("<YOUR_KEY>")).isTrue();
    assertThat(SensitiveDataRedactor.isSafePlaceholder("REPLACE_ME")).isTrue();
    String prose = "Set ${DB_PASSWORD} in the environment, or leave <YOUR_KEY> as REPLACE_ME.";
    assertThat(SensitiveDataRedactor.redact(prose)).isEqualTo(prose);

    // And the redactor's own markers still survive a second pass, so redaction stays idempotent.
    String marked = "PASSWORD=[REDACTED]\nOPENAI_API_KEY=sk-****REDACTED****";
    assertThat(SensitiveDataRedactor.redact(marked)).isEqualTo(marked);
  }

  /**
   * <b>The invariant, as an executable statement rather than a claim in a comment.</b>
   *
   * <p><em>When a sensitive assignment is redacted, the count of the original plaintext is zero.</em>
   * There is no acceptable outcome of the form "mangled text plus surviving plaintext". Every leak
   * this redactor has had has been exactly that shape: FINDING J1 emitted
   * {@code {"password": [REDACTED], SECRET]}}, which is a broken document with the secret still in
   * it, and the two failures before it were the same thing under different punctuation.
   *
   * <p>This runs a cross-product rather than a list, because a list only ever contains the forms
   * somebody already thought of, and J1 was reachable by six key spellings that a per-spelling test
   * would have had to enumerate. <b>What would have to be true for this to fail:</b> the redactor
   * would have to rewrite one of these inputs and leave the needle somewhere in the output. That
   * was checked rather than assumed: making {@code valueExtent} return {@code plainExtent}
   * unconditionally — the run that predates this fix — turns this test red on
   * {@code password=[prod, Pa55phrase_zqxw_610455]}, the first bracketed value it reaches. (It
   * reports one failure, not many: the assertion is inside the loop and AssertJ stops there. The
   * count is not evidence of how much the mutation broke.)
   *
   * <p>The value shapes are restricted to the ones whose extent ends on the same line as the key,
   * and that restriction is the honest boundary of this fix rather than a way of making the test
   * green: {@code SecretAssignmentGrammarTest#aValueEndingAtWhitespaceStillLeaksItsTail} pins the
   * classes that are still open, and they are open because every available fix for them publishes.
   */
  @Test
  @DisplayName("INVARIANT: rewriting a same-line assignment always removes all of the plaintext")
  void aRewrittenAssignmentNeverKeepsThePlaintext() {
    String needle = "Pa55phrase_zqxw_610455";
    String[] keys = {
      "password", "PASSWORD", "apiKey", "API_KEY", "api-key", "secret", "token",
      "client_secret", "clientSecret", "access_token", "accessToken", "private_key",
      "VIBECODE_DB_PASSWORD", "DB_PASSWORD", "dbPassword", "REFRESH_TOKEN"
    };
    String[] quotes = {"", "\"", "'"};
    String[] separators = {"=", ":", " = ", " : ", "\t=\t"};
    String[] values0 = {
      needle,
      "$" + needle,
      "$2b$12$" + needle,
      "[" + needle + "]",
      "[prod, " + needle + "]",
      "[a, [b, " + needle + "]]",
      "[\"" + needle + "\"]",
      "[{\"k\": \"" + needle + "\"}]",
      "{inner: " + needle + "}",
      "(" + needle + ")",
      "[REDACTED]" + needle,
      "[" + needle,
      "{" + needle,
      needle + "}evil",
      "\"" + needle + "\"",
      // G2's shapes.
      "{\"inner\": \"" + needle + "\"}",
      "(\"" + needle + "\")",
      "{\"a\": {\"b\": \"" + needle + "\"}}",
    };
    String[] documents = {"%s", "{%s}", "{%s, \"user\": \"bob\"}", "- %s", "prefix %s"};

    // FINDING R1-test. THE ALPHABET ABOVE WAS THE DEFECT, NOT THE ASSERTION.
    //
    // The assertion is real — reverting valueExtent reddens it — but it only ever saw the value
    // shapes someone had thought of, and the shapes that leaked were not among them. Replayed with
    // four more, this same generator produced 2,880 violations, 1,280 of them introduced at
    // 43517ff. The shipped alphabet reported zero. That is a measurement that was true about the
    // population it sampled and false about the code, and it is the second time in this task and
    // the eighth time in this project that a green has meant the former.
    //
    // So the family is now GENERATED rather than listed. Every character outside the old inner
    // lookahead class [A-Za-z0-9$_.+~%@-] is a character the widening newly admitted after "[",
    // and each one reaches the scan differently: some make it return -1, and some — "]" above all
    // — make it SUCCEED with an extent that is simply too short. {"password": []prod, SECRET]}
    // balances at depth 0 and stops at the comma, so a guard that only checked for -1 passed it
    // straight through. A list of spellings could not have covered that; a sweep does.
    List<String> swept = new ArrayList<>();
    for (char c : "\"'[]{}()<>,;:!*&|#=\\/ \t".toCharArray()) {
      swept.add("[" + c + "prod, " + needle + "]");
      swept.add("[" + c + needle + "]");
      swept.add("[" + c + needle);
    }
    // Escaped quotes: ordinary valid JSON, and a password that contains a quotation mark. The
    // quote count is odd because one is escaped, so a scan that skips to the next quote lands in
    // the wrong place and the extent collapses to one character.
    swept.add("[\"a\\\"b\", " + needle + "]");
    swept.add("{\"k\": \"a\\\"b\", \"v\": \"" + needle + "\"}");
    swept.add("[\"" + needle + "\\\"tail\"]");
    // Depth, because the nesting stack used to be a fixed 32 and exceeding it changed the answer.
    swept.add("[".repeat(33) + needle + "]".repeat(33));
    swept.add("[".repeat(64) + needle + "]".repeat(64));

    // THE SWEEP RUNS UNDER QUOTED KEYS, and that is a statement about which axis it tests, not a
    // filter that makes it pass. The widening this sweep exists to police —
    // \[(?=[A-Za-z0-9$_.+~%@-]) becoming [\[{(] — lives in the whitelist that ONLY the
    // quoted-key-with-unquoted-value alternative consults. An unquoted key never had a whitelist:
    // it matched every one of these shapes at 5b07bb1 and mangled some of them then, byte for
    // byte, and refusing there would publish. That class is pinned with its exact output in
    // unbalancedContainersUnderAnUnquotedKeyStillMangle rather than hidden here.
    String[] quotedOnly = {"\"", "'"};

    int rewritten = 0;
    for (String key : keys) {
      for (String quote : quotedOnly) {
        for (String value : swept) {
          for (String document : documents) {
            String input = String.format(document, quote + key + quote + ": " + value);
            String output = SensitiveDataRedactor.redact(input);
            if (output.equals(input)) {
              continue;
            }
            rewritten++;
            assertThat(output)
                .as("the widened axis rewrote [%s] and kept the plaintext", input)
                .doesNotContain(needle);
          }
        }
      }
    }

    String[] values = values0;
    for (String key : keys) {
      for (String quote : quotes) {
        for (String separator : separators) {
          for (String value : values) {
            for (String document : documents) {
              String input = String.format(document, quote + key + quote + separator + value);
              String output = SensitiveDataRedactor.redact(input);
              if (output.equals(input)) {
                // Left alone. The secret is still there and that is the pre-existing behaviour;
                // this invariant is about what redaction produces, not about coverage.
                continue;
              }
              rewritten++;
              assertThat(output)
                  .as("redacting [%s] rewrote it and kept the plaintext", input)
                  .doesNotContain(needle);
            }
          }
        }
      }
    }
    // Guards the guard: if the pattern stopped matching, every input would fall into the
    // "left alone" branch above and this test would pass without asserting anything at all.
    assertThat(rewritten).isGreaterThan(3000);
  }

  /**
   * <b>The boundary of the test above, pinned instead of filtered.</b>
   *
   * <p>The matrix covers values whose extent can be established: balanced brackets, or a plain run
   * that reaches the end of the line. One shape is outside it and is a genuine mangle-and-leak —
   * an <b>unbalanced</b> opener under an <b>unquoted</b> key, where the plain run stops at a quote
   * <em>inside</em> the structure it could not close.
   *
   * <p><b>Byte-identical at 5b07bb1, at 43517ff and here</b> — measured across all three, not
   * assumed. It is not this work's regression, and it is not this work's to close either, because
   * the only available fix is to refuse, and refusing publishes: {@code PASSWORD={hunter2 more} has
   * the same shape and the plain run removes a real password from it. A rule whose failure mode is
   * "publish the value" must never depend on the value, so the refusal that closes G2 is confined
   * to the quoted-key branch, where refusing is what the redactor already did.
   *
   * <p>The same shape under a <b>quoted</b> key is a different case and is closed: it is left
   * exactly alone rather than mangled — see {@code SecretAssignmentGrammarTest}.
   *
   * <p>These three strings are in the suite permanently because leaving them out of a generator hid
   * a real defect once: the value list at 43517ff had no unbalanced bracket-then-quote, and 43517ff
   * introduced a mangle-and-leak for {@code {"password": ["secret} that its own fuzz reported zero
   * of. Measured afterwards at 1,088 inputs on a three-way differential, and closed by the G2 guard.
   */
  @Test
  @DisplayName("KNOWN OPEN: an unbalanced opener under an unquoted key mangles, as it always has")
  void unbalancedContainersUnderAnUnquotedKeyStillMangle() {
    String needle = "Pa55phrase_zqxw_610455";
    assertThat(SensitiveDataRedactor.redact("password={\"inner\": \"" + needle))
        .isEqualTo("password=[REDACTED]\"inner\": \"" + needle);
    assertThat(SensitiveDataRedactor.redact("password=[\"" + needle))
        .isEqualTo("password=[REDACTED]\"" + needle);
    assertThat(SensitiveDataRedactor.redact("password=(\"" + needle))
        .isEqualTo("password=[REDACTED]\"" + needle);
    // The same class reached by a scan that SUCCEEDS too short rather than failing: "[]" balances
    // at depth 0 and the comma stops it. Under a quoted key this is refused (FINDING R1); under an
    // unquoted key there is no whitelist to refuse from and the plain run is what 5b07bb1 did.
    assertThat(SensitiveDataRedactor.redact("password=[]prod, " + needle + "]"))
        .isEqualTo("password=[REDACTED], " + needle + "]");
    assertThat(SensitiveDataRedactor.redact("password=[\"prod, " + needle + "]"))
        .isEqualTo("password=[REDACTED]\"prod, " + needle + "]");

    // And the reason it cannot be closed by refusing: here the plain run removes the whole secret,
    // so a refusal keyed on "the opener did not close" would publish it.
    assertThat(SensitiveDataRedactor.redact("PASSWORD={hunter2")).isEqualTo("PASSWORD=[REDACTED]");
    assertThat(SensitiveDataRedactor.redact("PASSWORD=[hunter2")).isEqualTo("PASSWORD=[REDACTED]");
  }
}


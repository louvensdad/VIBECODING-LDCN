package com.vibecode.guardian;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.guardian.domain.SensitiveDataRedactor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The grammar of secret assignments {@link SensitiveDataRedactor} recognises, written down.
 *
 * <p>This class started as a <em>characterisation</em>: every case below was run against the
 * redactor as it stood at 6d784fb and the observed answer was written into the assertion, whether
 * or not that answer was the desired one. That is what established which part of the grammar was
 * the defect and which parts were already working — SEC-RED-02 was asked for a fix to a key
 * pattern, and a key pattern has more moving parts than the one that was reported.
 *
 * <p>What the characterisation found, and what therefore had to be preserved rather than
 * rediscovered:
 *
 * <ul>
 *   <li><b>The separator is {@code =} or {@code :}</b>, with any run of whitespace on either side,
 *       newlines included. Both are matched; neither is privileged.
 *   <li><b>The key match is case-insensitive</b> in full — {@code password}, {@code Password} and
 *       {@code pAsSwOrD} were all already recognised.
 *   <li><b>A value ends at whitespace, comma, semicolon or quote.</b> So a quoted value containing
 *       a space is redacted only up to the space: {@code PASSWORD="a b"} became
 *       {@code PASSWORD="[REDACTED] b"}. Unchanged here; widening it is a separate decision with
 *       its own over-redaction risk.
 *   <li><b>There is no minimum value length in the redactor.</b> {@code PASSWORD=x} was redacted.
 *       (The five-character floor belongs to the SEC-002 <em>finding</em> rule, which is about
 *       whether to raise an alarm, not about what text may leave the platform.)
 *   <li><b>Prose is safe because the separator is required, not because the words are special.</b>
 *       {@code password policy} and {@code api key documentation} were untouched. But
 *       {@code password: ok} was already redacted, and still is — a colon after a bare secret word
 *       is treated as an assignment. That is over-redaction of a sort, it predates this work, and
 *       it is pinned below so that changing it is a decision rather than an accident.
 *   <li><b>Placeholders are exempt</b> via {@link SensitiveDataRedactor#isSafePlaceholder}, and
 *       that exemption is a policy this work was told not to alter. It is pinned in
 *       {@link Placeholders} against prefixed keys as well as bare ones.
 * </ul>
 *
 * <p>And the defect: <b>a prefix ending in an underscore hid the key entirely.</b> {@code \b} sits
 * between a non-word character and a word character, and {@code _} is a word character, so there
 * was no boundary in {@code VIBECODE_DB_PASSWORD} before {@code PASSWORD}. A prefix ending in
 * {@code .} or {@code -} did produce a boundary and was already caught — measured, not assumed:
 * {@code MY.PASSWORD=} and {@code MY-PASSWORD=} were redacted at 6d784fb while
 * {@code MY_PASSWORD=} was not. Underscore was the whole of the hole, and underscore is the
 * separator every environment variable in this project uses.
 *
 * <p>Fixtures are synthetic and high-entropy. The {@code zqxw} run in {@link #VALUE} exists so a
 * hit in a whole-body or whole-schema search is this value and not a random UUID colliding with a
 * short needle, which this project has manufactured a green with before.
 */
class SecretAssignmentGrammarTest {

  /** A secret with no shape any value rule recognises: not {@code sk-}, not {@code ghp_}, not PEM. */
  private static final String VALUE = "Pa55phrase_zqxw_610455";

  private static String redact(String input) {
    return SensitiveDataRedactor.redact(input);
  }

  /** True when the value is gone and a marker took its place — the property, not a magic string. */
  private static void assertRedacted(String input) {
    String out = redact(input);
    assertThat(out).as("redacting [%s]", input).doesNotContain(VALUE).contains("[REDACTED]");
  }

  private static void assertUntouched(String input) {
    assertThat(redact(input)).as("redacting [%s]", input).isEqualTo(input);
  }

  // ---------------------------------------------------------------- the keys that must be caught

  @Nested
  @DisplayName("Key names")
  class Keys {

    /**
     * The list SEC-RED-02 was given, asserted as a map so a failure names every key that regressed
     * rather than stopping at the first. Seven of these fifteen leaked at 6d784fb.
     */
    @Test
    @DisplayName("Every required key, bare and prefixed, removes the value")
    void everyRequiredKeyIsRecognised() {
      String[] keys = {
        "PASSWORD",
        "DB_PASSWORD",
        "VIBECODE_DB_PASSWORD",
        "DATABASE_PASSWORD",
        "API_KEY",
        "OPENAI_API_KEY",
        "ANTHROPIC_API_KEY",
        "CLIENT_SECRET",
        "APP_CLIENT_SECRET",
        "TOKEN",
        "ACCESS_TOKEN",
        "SERVICE_AUTH_TOKEN",
        "REFRESH_TOKEN",
        "SECRET",
        "PRIVATE_KEY",
      };

      Map<String, Boolean> leaked = new LinkedHashMap<>();
      for (String key : keys) {
        leaked.put(key, redact(key + "=" + VALUE).contains(VALUE));
      }

      assertThat(leaked)
          .as(
              "keys whose value survived redaction. At 6d784fb this map read true for every key"
                  + " carrying an underscore-terminated prefix — DB_PASSWORD, VIBECODE_DB_PASSWORD,"
                  + " DATABASE_PASSWORD, OPENAI_API_KEY, ANTHROPIC_API_KEY, APP_CLIENT_SECRET,"
                  + " SERVICE_AUTH_TOKEN — which is FINDING CTX-09B-1.")
          .hasSize(keys.length)
          .allSatisfy((key, survived) -> assertThat(survived).as("%s leaked", key).isFalse());
    }

    @Test
    @DisplayName("The key survives redaction: a prefixed key is not rewritten to its bare form")
    void theKeyNameIsPreserved() {
      // The obvious one-character fix — an optional prefix outside the captured key — rebuilds the
      // replacement from the captured group and silently renames VIBECODE_DB_PASSWORD to PASSWORD.
      // The reader loses which of three databases the credential belonged to, exactly when they are
      // reading a redacted record to find out what to rotate.
      assertThat(redact("VIBECODE_DB_PASSWORD=" + VALUE))
          .isEqualTo("VIBECODE_DB_PASSWORD=[REDACTED]");
      assertThat(redact("SERVICE_AUTH_TOKEN=" + VALUE)).isEqualTo("SERVICE_AUTH_TOKEN=[REDACTED]");
    }

    @Test
    @DisplayName("Prefixes joined by . and - were already caught, and still are")
    void punctuationPrefixesRemainCaught() {
      // Measured at 6d784fb: these were redacted and MY_PASSWORD was not. Pinned so that the
      // underscore fix is understood as closing the one hole rather than as adding prefixes.
      assertRedacted("MY.PASSWORD=" + VALUE);
      assertRedacted("MY-PASSWORD=" + VALUE);
      assertRedacted("app.client.secret=" + VALUE);
    }

    @Test
    @DisplayName("camelCase and kebab-case keys are recognised, so the fix is not underscore-only")
    void camelAndKebabKeysAreRecognised() {
      // Not in the brief, and found by asking the reviewer's question: what other spelling of the
      // same key leaves the value's shape alone? A JSON body writes apiKey, not API_KEY.
      assertRedacted("apiKey=" + VALUE);
      assertRedacted("dbPassword=" + VALUE);
      assertRedacted("accessToken=" + VALUE);
      assertRedacted("clientSecret=" + VALUE);
      assertRedacted("api-key=" + VALUE);
      assertRedacted("refresh-token=" + VALUE);
    }

    @Test
    @DisplayName("A quoted key — the JSON spelling — is recognised")
    void quotedKeysAreRecognised() {
      // At 6d784fb "password": "..." was untouched: the closing quote sat between the key and the
      // separator, so the pattern never reached the colon. An API that serialises its records as
      // JSON leaks through that spelling as readily as through an env file.
      assertRedacted("\"PASSWORD\": \"" + VALUE + "\"");
      assertRedacted("\"password\":\"" + VALUE + "\"");
      assertRedacted("{\"dbPassword\": \"" + VALUE + "\"}");
      assertThat(redact("{\"password\": \"" + VALUE + "\"}"))
          .as("the surrounding JSON is left intact, only the value changes")
          .isEqualTo("{\"password\": \"[REDACTED]\"}");
    }

    @Test
    @DisplayName("A secret word that is only part of a longer key is not an assignment")
    void aSecretWordInsideAKeyIsNotAnAssignment() {
      // PASSWORD_FILE names a path, not a password. The separator must follow the secret word.
      assertUntouched("PASSWORD_FILE=/etc/vibecode/pw.txt");
      assertUntouched("TOKEN_LIMIT=4096");
      assertUntouched("API_KEY_ROTATION_DAYS=30");
    }
  }

  // -------------------------------------------------------------------------- separators, spacing

  @Nested
  @DisplayName("Separators and spacing")
  class Separators {

    @Test
    @DisplayName("Both separators, with and without surrounding whitespace")
    void bothSeparatorsAndAnySpacing() {
      assertRedacted("VIBECODE_DB_PASSWORD=" + VALUE);
      assertRedacted("VIBECODE_DB_PASSWORD: " + VALUE);
      assertRedacted("VIBECODE_DB_PASSWORD = " + VALUE);
      assertRedacted("VIBECODE_DB_PASSWORD  =  " + VALUE);
      assertRedacted("VIBECODE_DB_PASSWORD\t=\t" + VALUE);
      assertRedacted("VIBECODE_DB_PASSWORD :" + VALUE);
      assertRedacted("VIBECODE_DB_PASSWORD\n=" + VALUE);
    }

    @Test
    @DisplayName("The text around the value is returned exactly as it arrived")
    void onlyTheValueIsReplaced() {
      // At 6d784fb the replacement was rebuilt from captured groups and collapsed the spacing:
      // "PASSWORD = v" came back as "PASSWORD=[REDACTED]". Harmless on its own, but it means the
      // redactor edits text it did not need to touch, and a diff of a redacted file then shows
      // changes that are not redactions. Now it substitutes the value and nothing else.
      assertThat(redact("PASSWORD  =  " + VALUE)).isEqualTo("PASSWORD  =  [REDACTED]");
      assertThat(redact("export DB_PASSWORD=" + VALUE + " # rotate me"))
          .isEqualTo("export DB_PASSWORD=[REDACTED] # rotate me");
    }

    @Test
    @DisplayName("Not every symbol is an assignment")
    void otherSymbolsAreNotAssignments() {
      assertUntouched("PASSWORD -> " + VALUE);
      assertUntouched("PASSWORD is " + VALUE);
    }

    @Test
    @DisplayName("A value ends at whitespace, comma, semicolon or quote")
    void valueTermination() {
      assertThat(redact("PASSWORD=" + VALUE + ";")).isEqualTo("PASSWORD=[REDACTED];");
      assertThat(redact("PASSWORD=" + VALUE + ", NEXT=1")).isEqualTo("PASSWORD=[REDACTED], NEXT=1");
      assertThat(redact("PASSWORD=" + VALUE + " and more"))
          .isEqualTo("PASSWORD=[REDACTED] and more");
      // Characterised, not endorsed: a quoted value with a space keeps its tail.
      assertThat(redact("PASSWORD=\"" + VALUE + " tail\""))
          .isEqualTo("PASSWORD=\"[REDACTED] tail\"");
    }

    @Test
    @DisplayName("Quotes around the value are kept on both sides")
    void quotedValues() {
      assertThat(redact("PASSWORD=\"" + VALUE + "\"")).isEqualTo("PASSWORD=\"[REDACTED]\"");
      assertThat(redact("PASSWORD='" + VALUE + "'")).isEqualTo("PASSWORD='[REDACTED]'");
      assertThat(redact("VIBECODE_DB_PASSWORD='" + VALUE + "'"))
          .isEqualTo("VIBECODE_DB_PASSWORD='[REDACTED]'");
    }
  }

  // ------------------------------------------------------------------------------- case variation

  @Nested
  @DisplayName("Case")
  class Case {

    @Test
    @DisplayName("Any case of key or prefix, with the key's own casing preserved in the output")
    void caseIsIgnoredForMatchingAndKeptForReading() {
      assertRedacted("password=" + VALUE);
      assertRedacted("Password=" + VALUE);
      assertRedacted("pAsSwOrD=" + VALUE);
      assertRedacted("db_password=" + VALUE);
      assertRedacted("Vibecode_Db_Password=" + VALUE);
      assertThat(redact("vibecode_db_password=" + VALUE))
          .isEqualTo("vibecode_db_password=[REDACTED]");
    }
  }

  // ------------------------------------------------------------------------------ over-redaction

  @Nested
  @DisplayName("Prose")
  class Prose {

    /**
     * The failure mode on the other side. A redactor that eats documentation is turned off, and
     * then it redacts nothing at all — so these are as load-bearing as the positives.
     */
    @Test
    @DisplayName("Ordinary prose containing secret words is returned unchanged")
    void proseIsUntouched() {
      assertUntouched("password policy");
      assertUntouched("token budget");
      assertUntouched("secret management");
      assertUntouched("api key documentation");
      assertUntouched("Rotate the API_KEY every 90 days, per the secret management runbook.");
      assertUntouched(
          "The access token expires after one hour and the refresh token after thirty days.");
      assertUntouched("We reduced the token budget from 8000 to 4000 to fit the context window.");
    }

    @Test
    @DisplayName("Identifiers that are not assignments are returned unchanged")
    void identifiersAreUntouched() {
      assertUntouched("passwordHash");
      assertUntouched("resetPassword()");
      assertUntouched("password_reset_flow");
      assertUntouched("user.getAccessToken()");
      assertUntouched("class ApiKeyRotationJob implements Runnable");
      assertUntouched("SELECT password_hash FROM users WHERE id = 1");
    }

    @Test
    @DisplayName("A bare secret word followed by a colon was already treated as an assignment")
    void theColonRuleIsUnchangedAndDeliberatelyPinned() {
      // Pre-existing behaviour, kept. It is over-redaction — "password: ok" is prose — but it is
      // the same rule that catches "password: hunter2" in a chat log pasted into a task, and
      // narrowing it is a policy change with its own leak risk. Pinned here so that whoever
      // narrows it does so on purpose and sees this line first.
      assertThat(redact("password: ok")).isEqualTo("password: [REDACTED]");
      assertThat(redact("token: 5")).isEqualTo("token: [REDACTED]");
    }
  }

  // -------------------------------------------------------------------------------- placeholders

  @Nested
  @DisplayName("Placeholders")
  class Placeholders {

    /**
     * SEC-RED-02 was told not to change this policy silently. It is not changed: the exemption is
     * decided by {@link SensitiveDataRedactor#isSafePlaceholder} on the value, and the fix touches
     * the key half of the pattern only. What is new is that the exemption now applies under a
     * prefixed key too — before, those lines were exempt for the wrong reason, which was that the
     * key was never recognised at all.
     */
    @Test
    @DisplayName("A placeholder value is left alone, under a bare key and under a prefixed one")
    void placeholdersSurvive() {
      assertUntouched("PASSWORD=${DB_PASSWORD}");
      assertUntouched("DB_PASSWORD=${DB_PASSWORD}");
      assertUntouched("VIBECODE_DB_PASSWORD=${DB_PASSWORD}");
      assertUntouched("API_KEY=${OPENAI_API_KEY}");
      assertUntouched("OPENAI_API_KEY=${OPENAI_API_KEY}");
      assertUntouched("API_KEY=<YOUR_API_KEY>");
      assertUntouched("OPENAI_API_KEY=<YOUR_API_KEY>");
      assertUntouched("PASSWORD=[REDACTED]");
      assertUntouched("VIBECODE_DB_PASSWORD=[REDACTED]");
      assertUntouched("ACCESS_TOKEN=REPLACE_ME");
      assertUntouched("SERVICE_AUTH_TOKEN=CHANGE_ME");
      assertUntouched("PASSWORD=$DB_PASSWORD");
      assertUntouched("PASSWORD=***");
    }

    @Test
    @DisplayName("Redaction is idempotent: its own output is a fixed point")
    void redactionIsIdempotent() {
      // The property that makes the placeholder policy safe under a widened key pattern. Without
      // it, a second pass over already-redacted text would replace the shape-named markers with
      // generic ones, or grow the text again against the length caps.
      String[] inputs = {
        "VIBECODE_DB_PASSWORD=" + VALUE,
        "OPENAI_API_KEY=sk-proj-Kd8fQzqxw610456mnop",
        "GITHUB_TOKEN=ghp_testingsynthetictokenvalue1234567890",
        "Authorization: Bearer myauthtoken1234567890",
        "DATABASE_URL=postgres://myuser:secretpassword@localhost:5432/app",
        "{\"clientSecret\": \"" + VALUE + "\"}",
      };
      for (String input : inputs) {
        String once = redact(input);
        assertThat(redact(once)).as("redact is not a fixed point for [%s]", input).isEqualTo(once);
      }
    }
  }

  // ------------------------------------------------------------- interaction with the shape rules

  @Nested
  @DisplayName("Shape rules")
  class ShapeRules {

    @Test
    @DisplayName("A shaped value under a prefixed key keeps its shape-named marker")
    void shapeMarkersSurviveTheKeyRule() {
      // The key rule runs last and would otherwise overwrite sk-****REDACTED**** with a generic
      // [REDACTED], losing which kind of credential was removed. isSafePlaceholder knows the
      // redactor's own markers, and this pins that it still does now the key rule reaches them.
      assertThat(redact("OPENAI_API_KEY=sk-proj-Kd8fQzqxw610456mnop"))
          .isEqualTo("OPENAI_API_KEY=sk-****REDACTED****");
      assertThat(redact("GITHUB_TOKEN=ghp_testingsynthetictokenvalue1234567890"))
          .isEqualTo("GITHUB_TOKEN=ghp_****REDACTED****");
    }

    @Test
    @DisplayName("A connection URL is still redacted by the URL rule, not by the key rule")
    void connectionUrlsAreUnaffected() {
      assertThat(redact("DATABASE_URL=postgres://myuser:secretpassword@localhost:5432/app"))
          .isEqualTo("DATABASE_URL=postgres://myuser:[REDACTED]@localhost:5432/app");
    }

    @Test
    @DisplayName("The reported line: two secrets, one item, only one of which used to survive")
    void theReportedLine() {
      String reported =
          "Deployed with VIBECODE_DB_PASSWORD="
              + VALUE
              + " and OPENAI_API_KEY=sk-proj-Kd8fQzqxw610456mnop";
      assertThat(redact(reported))
          .isEqualTo(
              "Deployed with VIBECODE_DB_PASSWORD=[REDACTED] and OPENAI_API_KEY=sk-****REDACTED****");
    }
  }

  // -------------------------------------------------------------------- redaction amplification

  @Test
  @DisplayName("The marker is longer than a short value, so redacted text can grow")
  void redactionCanLengthenText() {
    // Not a new property, and not repaired here — the domain refuses an over-long label and says
    // both lengths. Asserted so that a future change to the marker or to the replacement shape is
    // seen against the length caps rather than discovered at an INSERT.
    assertThat(redact("TOKEN=x")).isEqualTo("TOKEN=[REDACTED]");
    assertThat("TOKEN=x".length()).isLessThan(redact("TOKEN=x").length());
    // And it can shrink, when the value is longer than the marker.
    assertThat(redact("VIBECODE_DB_PASSWORD=" + VALUE).length())
        .isLessThan(("VIBECODE_DB_PASSWORD=" + VALUE).length());
  }

  @Test
  @DisplayName("Growth per assignment is bounded by the marker's length, prefixed keys included")
  void growthIsBoundedByTheMarker() {
    // The amplification question SEC-RED-02 was told to re-check. A 498-character label became 507
    // once in this project and had to be fixed, so the thing that matters is not "does it grow" but
    // "by how much, and does widening the key pattern widen the growth".
    //
    // It does not. The replacement substitutes the value and leaves the key, the separator and the
    // spacing exactly as they arrived, so the change in length is (marker - value) per match and
    // nothing else — a prefixed key grows by the same amount a bare one does, because the prefix is
    // copied through rather than rewritten. The most any single assignment can add is nine
    // characters, when the value is one character long.
    int marker = "[REDACTED]".length();
    String[] oneCharacterValues = {
      "TOKEN=x", "DB_TOKEN=x", "VIBECODE_DB_PASSWORD=x", "app.client.secret=x", "apiKey=x",
    };
    for (String input : oneCharacterValues) {
      assertThat(redact(input).length() - input.length())
          .as("growth of [%s]", input)
          .isEqualTo(marker - 1);
    }

    // And whitespace is no longer an escape hatch in the other direction: the old replacement
    // collapsed "TOKEN  =  x" to "TOKEN=[REDACTED]", which hid growth behind a reformat. The
    // spacing is preserved, so the arithmetic above is the whole of the story.
    assertThat(redact("TOKEN  =  x").length() - "TOKEN  =  x".length()).isEqualTo(marker - 1);
  }

  @Test
  @DisplayName("A long run of key characters with no assignment in it is not pathological")
  void theKeyRunDoesNotBacktrackQuadratically() {
    // The leading [A-Za-z0-9_]* is unbounded, and an unbounded run in front of an alternation is
    // the shape that produces catastrophic backtracking. It does not here — \b only admits a match
    // attempt at a word boundary, so a single token is scanned once — but "it does not" is worth
    // measuring rather than reasoning about, on input the size of a whole context budget.
    String oneEnormousToken = "A_".repeat(100_000);
    String manyWords = "password policy and token budget ".repeat(6_000);

    long start = System.nanoTime();
    assertThat(redact(oneEnormousToken)).isEqualTo(oneEnormousToken);
    assertThat(redact(manyWords)).isEqualTo(manyWords);
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    // Deliberately loose: this is a smoke alarm for exponential behaviour, not a benchmark. The
    // two calls above take single-digit milliseconds; a quadratic or exponential pattern would not
    // finish inside this at 200k characters.
    assertThat(elapsedMillis).as("redacting 400k characters of non-secret text").isLessThan(5_000L);
  }
}

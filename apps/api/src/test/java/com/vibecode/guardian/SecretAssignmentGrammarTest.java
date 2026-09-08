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

  // ------------------------------------------------------------------------- the widened region

  /**
   * <b>What the widened key pattern costs, named on both sides.</b>
   *
   * <p>The commit that fixed FINDING CTX-09B-1 claimed that no existing test changed behaviour and
   * offered that as evidence the widening ate nothing it should not. <b>That claim was wrong</b>,
   * and it was wrong in a way worth being precise about: the suite has no test anywhere in the
   * widened region, so its silence was evidence of nothing at all. The negatives in {@link Prose}
   * cannot reach this class either — {@code passwordHash} and {@code user.getAccessToken()} have no
   * separator, and {@code PASSWORD_FILE=} and {@code TOKEN_LIMIT=} put the secret word at the
   * <em>start</em> of the key. The widened region is a secret word at the <em>end</em> of a longer
   * identifier, followed by a separator, and nothing tested it.
   *
   * <p>It was then measured, line by line, over this repository's own text: 446 files, 42,006
   * non-blank lines, each line put through the redactor as it stood at 6d784fb and through the
   * redactor as it stands now. Counting only files this task did not itself write — the four it
   * touched are prose <em>about</em> the redactor and match it by construction, so including them
   * would inflate the number with its own documentation — <b>24 lines are rewritten that were not
   * before, and 0 lines that were rewritten before are left alone now.</b> The widening is strictly
   * one-directional: nothing stopped being redacted. Five of the 24 are in shipped code rather than
   * tests, and none of the 24 contains a secret.
   *
   * <p>The cases below are the real ones, copied verbatim from that scan. They are pinned as
   * {@code isEqualTo} on the mangled output rather than described in a comment, for the same reason
   * {@code password: ok} is pinned: <b>whoever narrows this pattern later must see the cost
   * first</b>, in a test that goes green when they fix it, rather than discovering the trade after
   * they have made it. Two things to understand before reading them as an argument for narrowing:
   *
   * <ul>
   *   <li><b>This class of damage is not new.</b> {@code String token = match.group();} was already
   *       mangled by the old redactor, to {@code String token=[REDACTED];} — a bare identifier that
   *       happens to be a secret word has always been treated as an assignment. The widening
   *       extends that reach from bare identifiers to identifiers ending in one; it does not invent
   *       the behaviour.
   *   <li><b>Narrowing costs redaction.</b> Requiring the prefix to end in a delimiter would spare
   *       {@code rawPassword ==} and {@code accessToken:} and would give up {@code dbPassword=} and
   *       {@code apiKey=} — the camelCase spelling a JSON body actually uses. It would not spare
   *       {@code CHARACTERS_PER_TOKEN = 4}, which is underscore-delimited and indistinguishable in
   *       form from {@code SERVICE_AUTH_TOKEN=secret}, the very case this task was required to fix.
   * </ul>
   *
   * <p>{@code redact()} output is what reaches a client and a coding model, so a Java or TypeScript
   * file pasted into a context item comes back with declarations and comparisons chewed out. That
   * is a real cost and it is recorded here as one.
   */
  @Nested
  @DisplayName("The widened region: what a suffixed key costs and what it still misses")
  class WidenedRegion {

    @Test
    @DisplayName("COST: ordinary source code whose identifier ends in a secret word is mangled")
    void sourceCodeInTheWidenedRegionIsMangled() {
      // Every one of these is real, from src/main and src/test of this repository. None contains a
      // secret. All five were returned untouched by the redactor at 6d784fb.
      assertThat(redact("private static final int CHARACTERS_PER_TOKEN = 4;"))
          .isEqualTo("private static final int CHARACTERS_PER_TOKEN = [REDACTED];");
      assertThat(redact("if (rawPassword == null) {"))
          .isEqualTo("if (rawPassword =[REDACTED] null) {");
      assertThat(redact("if (encodedPassword == null || encodedPassword.isBlank()) {"))
          .isEqualTo("if (encodedPassword =[REDACTED] null || encodedPassword.isBlank()) {");
      assertThat(redact("accessToken: string;")).isEqualTo("accessToken: [REDACTED];");
      assertThat(redact("csrfToken = json.readTree(body).get(\"token\").asText();"))
          .isEqualTo("csrfToken = [REDACTED]\"token\").asText();");
    }

    @Test
    @DisplayName("COST: a quoted key before a colon also catches a JavaScript ternary")
    void theQuotedKeyAlsoCatchesATernary() {
      // The price of reading "password": "..." as an assignment, which is the spelling this API's
      // own responses use. A ternary puts a quoted string, a colon and another quoted string in the
      // same order, and nothing in the text distinguishes them.
      assertThat(redact("autoComplete={registering ? \"new-password\" : \"current-password\"}"))
          .isEqualTo("autoComplete={registering ? \"new-password\" : \"[REDACTED]\"}");
    }

    @Test
    @DisplayName("The one input that was strictly worse than before, and is not any more")
    void theRubyHashArrowIsNotMangled() {
      // 'password' => 'secret' was mangled AND still leaking: the pattern took the = of =>, read >
      // as the whole value, and produced 'password' =[REDACTED] 'secret'. Strictly worse than the
      // old behaviour, which was to leave it alone. A quote may now close a key only before a
      // colon, never before an equals sign — no format writes "key" = value, and this is what
      // writes 'key' => value.
      assertUntouched("'password' => '" + VALUE + "'");
      assertUntouched("$config['password'] => '" + VALUE + "'");
      // Not bought at the price of the bare-key form, which the old redactor did catch and which
      // this still catches — the value there is ">" plus the secret, and all of it goes.
      assertThat(redact("PASSWORD=>" + VALUE)).isEqualTo("PASSWORD=[REDACTED]");
      // And the two spellings the quote before a colon exists for are unaffected.
      assertRedacted("\"password\": \"" + VALUE + "\"");
      assertRedacted("'password': '" + VALUE + "'");
    }

    /**
     * The other half of the trade, and the half the first version of this work documented without
     * naming: the secret word must be the <em>end</em> of the key, so anything after it is a miss.
     *
     * <p>Each of these carries the value through with its shape intact, which is the full blast
     * radius of FINDING CTX-09B-1 for anyone who spells their key this way. It is left as a miss
     * deliberately. Widening far enough to catch {@code DB_PASSWORD_VALUE=} means matching a secret
     * word anywhere inside a key, and the measurement above is what that costs — {@code
     * PASSWORD_FILE=/etc/pw.txt} names a path, not a password, and would go with it.
     *
     * <p>This test is the tripwire for that. It is written as {@code isEqualTo} on the untouched
     * input, so a future widening that starts matching a secret word mid-key turns it red and its
     * author reads this paragraph before deciding.
     */
    @Test
    @DisplayName("MISS: a secret word that is not the end of the key passes the value through")
    void aSuffixedKeyIsNotRecognised() {
      assertUntouched("DB_PASSWORDS=" + VALUE);
      assertUntouched("DB_PASSWORD_VALUE=" + VALUE);
      assertUntouched("DB_TOKEN_2=" + VALUE);
      assertUntouched("dbPasswordValue=" + VALUE);
      assertUntouched("creds[password]=" + VALUE);

      // The other side of the same rule, and the reason it is the rule: these name a path, a
      // number and a schedule, and redacting them would destroy the information without removing a
      // secret.
      assertUntouched("PASSWORD_FILE=/etc/vibecode/pw.txt");
      assertUntouched("TOKEN_LIMIT=4096");
      assertUntouched("API_KEY_ROTATION_DAYS=30");
    }

    /**
     * A value ends at whitespace, so a passphrase is redacted down to its first word and the rest
     * is published.
     *
     * <p>This is not new — {@code PASSWORD="uma senha longa"} lost only {@code uma} at 6d784fb too —
     * but it is newly <em>reachable</em> under a prefixed or quoted key, which means it now happens
     * to text that used to pass through whole. That reads as a success in a body search for the
     * full value and is a partial one: {@code correct horse battery staple} is a password, and
     * three quarters of it survives.
     *
     * <p>Not changed here. Extending the value to the closing quote is a real fix and a real risk —
     * an unbalanced quote in prose would swallow a paragraph — and it belongs to whoever owns the
     * value half of this pattern, not to a key-pattern fix. Pinned so it is a known limitation
     * rather than a surprise.
     */
    @Test
    @DisplayName("LIMITATION: a multi-word value keeps everything after its first token")
    void aMultiWordValueIsOnlyPartlyRemoved() {
      assertThat(redact("\"password\": \"correct horse battery staple\""))
          .isEqualTo("\"password\": \"[REDACTED] horse battery staple\"");
      assertThat(redact("VIBECODE_DB_PASSWORD=\"minha frase secreta longa\""))
          .isEqualTo("VIBECODE_DB_PASSWORD=\"[REDACTED] frase secreta longa\"");
      // The single-token case, for contrast: nothing survives there.
      assertThat(redact("\"password\": \"" + VALUE + "\""))
          .isEqualTo("\"password\": \"[REDACTED]\"");
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
      assertUntouched("VIBECODE_DB_PASSWORD=$DB_PASSWORD");
      assertUntouched("PASSWORD=$db.password");
      assertUntouched("Authorization: Bearer $ACCESS_TOKEN");
      assertUntouched("PASSWORD=***");
    }

    /**
     * The exemption that reopened the finding, and the narrowing that closes it.
     *
     * <p>{@code isSafePlaceholder} exempted any value beginning with {@code $}. The key rule now
     * reaches a prefixed assignment, and then handed it straight back: <b>every bcrypt hash begins
     * {@code $2}</b>, and a password may begin with a dollar sign like any other character. One
     * character prepended to the value reopened the whole of FINDING CTX-09B-1 — the 201 body,
     * {@code items[].label}, {@code items[].content}, the canonical payload, the digest and both
     * context tables — and the character was part of the secret's own shape rather than something a
     * caller had to be careful about.
     *
     * <p>The narrowing keeps the intent and drops the over-reach: the exemption was always for
     * interpolation syntax, and {@code startsWith("$")} was an over-broad way of writing it. A
     * {@code $} followed by an identifier is a variable read; a {@code $} followed by anything at
     * all is not.
     *
     * <p>Everything the architect enumerated stays exempt, and that is asserted above rather than
     * asserted here, so this test cannot pass by having quietly emptied the policy. What stops
     * being exempt, besides a hash, is shell command substitution — {@code $(cat /run/secrets/db)}
     * is now redacted. That is an accepted cost in the safe direction: it removes something that
     * was not a secret, where the old rule published something that was.
     */
    @Test
    @DisplayName("A value that merely begins with $ is not a placeholder: bcrypt is redacted")
    void aDollarSignAloneIsNotAPlaceholder() {
      assertRedacted("VIBECODE_DB_PASSWORD=$2b$12$" + VALUE);
      assertRedacted("PASSWORD=$2b$12$" + VALUE);
      assertRedacted("\"password\": \"$2y$10$" + VALUE + "\"");
      assertRedacted("PASSWORD=$1$salt$hash");
      assertRedacted("PASSWORD=$argon2id$v=19$m=65536");
      // The residue, pinned rather than pretended away: a value that is a dollar sign followed by
      // something spelled exactly like a shell variable is still exempt, because nothing in the
      // text distinguishes $Pa55phrase_zqxw_610455 from $DB_PASSWORD. That is the irreducible cost
      // of exempting interpolation at all, and it is far smaller than "anything after a $": it now
      // requires the secret to contain no character outside an identifier, where before it required
      // only that the secret start with one.
      assertUntouched("PASSWORD=$" + VALUE);
      assertThat(SensitiveDataRedactor.isSafePlaceholder("$2b$12$KIXQ8fQzqxw610455mnopABCDEF"))
          .as("a bcrypt hash is not a placeholder")
          .isFalse();
      assertThat(SensitiveDataRedactor.isSafePlaceholder("$DB_PASSWORD"))
          .as("an interpolated name still is")
          .isTrue();
      assertThat(SensitiveDataRedactor.isSafePlaceholder("${DB_PASSWORD}")).isTrue();
      // The accepted cost, pinned so it is a decision and not a surprise.
      assertThat(SensitiveDataRedactor.isSafePlaceholder("$(cat /run/secrets/db)"))
          .as("command substitution is no longer exempt; it is over-redacted, which is the safe way"
              + " to be wrong")
          .isFalse();
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

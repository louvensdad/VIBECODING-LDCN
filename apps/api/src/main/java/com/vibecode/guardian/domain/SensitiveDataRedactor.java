package com.vibecode.guardian.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically redacts sensitive values from text.
 *
 * <p>Ensures that raw private keys, passwords, and tokens never enter logs, database evidence,
 * audit events, or API payloads.
 */
public final class SensitiveDataRedactor {

  private static final Pattern PRIVATE_KEY_PATTERN =
      Pattern.compile(
          "-----BEGIN ([A-Z0-9 ]+)?PRIVATE KEY-----[\\s\\S]*?-----END ([A-Z0-9 ]+)?PRIVATE KEY-----");

  private static final Pattern DB_URL_PASSWORD_PATTERN =
      Pattern.compile("((?:postgres(?:ql)?|mysql|mongodb|redis|amqp)://[^:]+:)([^@]+)(@)");

  private static final Pattern BEARER_TOKEN_PATTERN =
      Pattern.compile("(?i)(bearer\\s+)([A-Za-z0-9._~+/-]{10,})");

  private static final Pattern OPENAI_KEY_PATTERN =
      Pattern.compile("sk-[A-Za-z0-9_-]{16,}");

  /**
   * {@code $NAME}: a shell or Compose variable read, which is a reference to a secret and not one.
   * An identifier, deliberately — a name, optionally dotted or dashed the way a Spring property is,
   * and nothing else. {@code $2b$12$…} is not an identifier and is therefore not exempt.
   */
  private static final Pattern INTERPOLATED_NAME_PATTERN =
      Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_.-]*");

  private static final Pattern GITHUB_TOKEN_PATTERN =
      Pattern.compile("(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}");

  /**
   * The words that make a key a secret's key, each allowing the three spellings the same name is
   * written in across a codebase: {@code API_KEY}, {@code api-key} and {@code apiKey}. The
   * separator inside a compound word is optional rather than literal, which is what makes the
   * camelCase spelling reachable at all — {@code apiKey} contains no underscore for {@code API_KEY}
   * to match.
   *
   * <p>Nothing was added to this vocabulary. It is the same eight words the pattern has always
   * carried; widening it is a policy decision with its own over-redaction cost and is not part of
   * SEC-RED-02.
   */
  private static final String SENSITIVE_KEY_WORDS =
      "API[_-]?KEY|CLIENT[_-]?SECRET|ACCESS[_-]?TOKEN|REFRESH[_-]?TOKEN|PRIVATE[_-]?KEY"
          + "|SECRET|TOKEN|PASSWORD";

  /**
   * The whole key of a secret assignment, prefix included: {@code VIBECODE_DB_PASSWORD}, not
   * {@code PASSWORD}. Published so that detection and redaction share one definition of "a
   * sensitive key" instead of two copies that drift apart.
   *
   * <p>SEC-002 carried a hand-written copy of the pre-CTX-09B-1 expression —
   * {@code \b(API_KEY|SECRET|…)} — where {@code \b} cannot fire before {@code PASSWORD} in
   * {@code VIBECODE_DB_PASSWORD}, because {@code _} is a word character. That was a detection gap
   * and not a leak path: the rule's evidence goes through {@link #redact(String)} either way. The
   * two copies drifting apart is how the gap arose, so there is now one string.
   *
   * <p><b>Shared vocabulary, not a shared decision.</b> Detection and redaction stay separate jobs.
   * No {@link SecurityRule} is consulted by {@link #redact(String)}, and redaction works with every
   * rule disabled; the dependency points one way, from the rule to this constant, and stays inside
   * Guardian. Context is not involved, so no cycle is created.
   *
   * <p>Contains no capturing group, so a caller may embed it and keep its own group numbering.
   */
  public static final String SENSITIVE_KEY_REGEX =
      "[A-Za-z0-9_]*(?:" + SENSITIVE_KEY_WORDS + ")";

  /**
   * The characters that may begin an unquoted value after a quoted key.
   *
   * <p><b>A whitelist, and that direction is deliberate.</b> Anything not listed falls out of the
   * alternative that uses it and the text is left exactly as it arrived — the behaviour before that
   * alternative existed. A blacklist would have the opposite failure mode: a structural opener
   * nobody thought of would be accepted, its opener replaced, and the secret behind it published
   * along with a broken document.
   *
   * <p>So this is the set of characters a scalar starts with: letters, digits, and the punctuation
   * that begins a path, a number, a version, a URL, an interpolation or an angle-bracket
   * placeholder. Excluded by omission and worth naming, because each one is a leak this way round:
   * {@code &#123;} and {@code (} open a container; {@code |} and {@code >} open a YAML block scalar
   * whose text is on the following lines; {@code &} opens an anchor whose value follows it on the
   * same line; {@code !} opens a tag; {@code *} opens an alias.
   *
   * <p>Two characters are admitted conditionally, because each one begins both a scalar and a
   * container and only the character after it says which.
   *
   * <ul>
   *   <li>{@code <} begins the {@code <YOUR_KEY>} placeholder, which precedence says to redact. It
   *       also begins YAML's merge key {@code <<:}, where the value is the merged mapping and the
   *       secret is behind it. So {@code <} is admitted unless another {@code <} follows.
   *   <li>{@code [} begins this redactor's own {@code [REDACTED]} marker — and therefore the
   *       {@code [REDACTED]secret} smuggling attempt, which must be redacted rather than passed
   *       through. It also opens a JSON array. <b>It is now admitted unconditionally</b>, because
   *       the character after it no longer has to decide anything: {@link #valueExtent} measures a
   *       bracketed value by balancing it, so {@code [REDACTED]secret} and {@code [prod, secret]}
   *       are both consumed whole.
   * </ul>
   *
   * <p><b>FINDING J1, and why the conditional admission was the wrong shape.</b> The lookahead
   * that used to guard {@code [} asked whether the <em>next</em> character could start a scalar. It
   * separated {@code [REDACTED]secret} from {@code ["secret"]} correctly and separated neither from
   * a flow sequence whose first element is a bare scalar. {@code {"password": [prod, SECRET]}} was
   * admitted, the value ran to the comma inside the brackets, and the output was
   * {@code {"password": [REDACTED], SECRET]}}: the opening bracket deleted so the document no
   * longer parses, <em>and the secret still in it</em>. Six quoted-key spellings did this, and
   * {@code apiKey: [prod, k]} is ordinary YAML that needs no adversary to write.
   *
   * <p>A first-character test cannot tell a container from a scalar, because the two differ in
   * where they <em>end</em>. So the extent is measured instead of guessed, and this whitelist keeps
   * only the job it can actually do: refusing the openers whose extent is on <em>other lines</em>
   * and therefore not measurable at all — {@code |} and {@code >} (block scalars), {@code &} (an
   * anchor), {@code !} (a tag), {@code *} (an alias). Refusing still means leaving the text exactly
   * as it arrived.
   *
   * <p><b>FINDING G2, closed on the architect's ruling.</b> {@code &#123;} and {@code (} are now
   * admitted for the same reason {@code [} is. {@code {"password": {"inner": "k"}}} was left
   * untouched — and untouched means the secret stayed in the document. The mandate that kept it
   * untouched existed because mangling it leaked: the {@code &#123;} was taken as the whole value,
   * replaced, and everything inside the object survived after it. Measuring the extent removes that
   * reason, so the object is replaced whole and {@code {"password": [REDACTED]}} is valid JSON with
   * nothing of the secret left in it — better than the old output on both axes at once.
   *
   * <p>It also removes an asymmetry that was a defect on its own: {@code password: {inner: k}} under
   * an unquoted key already redacted whole, because no whitelist ever applied to an unquoted key.
   * The same secret was removed or published according to punctuation the writer chose for
   * unrelated reasons.
   *
   * <p><b>The admission is not free, and the price is paid in {@link #wholeContainerExtent}
   * rather than here.</b> A newly admitted opener that does not close on its line must change
   * nothing, because for that spelling "change nothing" is what the redactor did before — inheriting
   * the fallback to the plain run would re-create the exact G2 output this is closing.
   */
  private static final String SCALAR_VALUE_START =
      "(?:[A-Za-z0-9$_./+~%@-]|<(?!<)|[\\[{(])";

  /**
   * A secret written as {@code key = value}, in the spellings a key is actually written in.
   *
   * <p><b>Group 1 is the whole key, prefix included</b> — {@code VIBECODE_DB_PASSWORD}, not
   * {@code PASSWORD}. It has to be, because the replacement is built from it: capturing only the
   * bare word would rewrite {@code VIBECODE_DB_PASSWORD=…} to {@code PASSWORD=[REDACTED]} and
   * throw away which of three databases the reader has to go and rotate.
   *
   * <p><b>Why the leading {@code [A-Za-z0-9_]*} and not a {@code \b}.</b> The pattern this replaces
   * anchored the key word on {@code \b}, and {@code _} is a word character, so there is no boundary
   * before {@code PASSWORD} in {@code VIBECODE_DB_PASSWORD} and the whole assignment was invisible.
   * A value with a recognisable shape — {@code sk-…}, {@code ghp_…} — was rescued by the rules
   * above; a shape-less value such as an ordinary password was rescued by nothing and reached the
   * database, the digest and the HTTP response body. That is FINDING CTX-09B-1, and the leading run
   * of key characters is its fix: any prefix, any number of prefixes, any case.
   *
   * <p>The run deliberately does not require a trailing underscore. {@code dbpassword} and
   * {@code myapikey} are keys people write, and there is no reading of them that is not an
   * assignment once a separator follows. What keeps this from eating prose is the separator, not
   * the spelling of the key: {@code password policy} has no {@code =} or {@code :} after the word
   * and is returned untouched, and so is {@code PASSWORD_FILE=/etc/pw} — the secret word must be
   * the <em>end</em> of the key for the separator to follow it.
   *
   * <p><b>Group 2 is everything between the key and the value, kept verbatim</b>: the separator, the
   * whitespace on either side, and whatever quotes belong to the spelling in use — see the branch
   * below for which. Keeping it rather than rebuilding it does two things. It makes
   * {@code "password": "…"} — the spelling this API's own responses are written in — reachable,
   * where before the closing quote sat between the key and the colon and stopped the match dead.
   * And it means redaction substitutes the value and edits nothing else, so {@code PASSWORD  =  x}
   * keeps its spacing instead of being silently reformatted to {@code PASSWORD=x}.
   *
   * <p><b>A quoted key is a different spelling with different rules, and it gets two alternatives of
   * its own.</b> A quote closing the key and a colon, then either a quote opening the value — the
   * JSON string case — or an unquoted value <em>whose first character can begin a scalar</em>. The
   * third alternative is everything else: no quote on the key, a {@code =} or a {@code :}, and an
   * optional quote on the value.
   *
   * <p><b>The failure all of this is shaped around.</b> A match that rewrites the text and leaves
   * the secret in it is strictly worse than not matching at all: the leak is unchanged and the
   * document is broken as well. It happened twice.
   *
   * <ul>
   *   <li>Allowing a quote before {@code =} made {@code 'password' => 'secret'} match the {@code =}
   *       of the {@code =>}, take {@code >} as the whole value, and emit
   *       {@code 'password' =[REDACTED] 'secret'}. So the quote goes with the colon only. (TOML
   *       does write {@code "key" = "value"}, so that spelling is given up here rather than being
   *       unrepresentable; it was not matched at 6d784fb either.)
   *   <li>Accepting <em>any</em> unquoted value after a quoted key made
   *       {@code {"password": {"inner": "secret"}}} take the {@code &#123;} as the whole value and
   *       emit {@code {"password": [REDACTED]"inner": "secret"}}: the secret still there and an
   *       opening brace deleted, so the JSON no longer parses. The value terminator set stops at a
   *       quote, so a container's opener is all that gets replaced and everything inside it
   *       survives.
   * </ul>
   *
   * <p><b>Mangling without leaking is a different category, and it is accepted.</b> An unquoted
   * <em>scalar</em> is consumed whole — {@code {"password": $2b$12$…&#125;} becomes
   * {@code {"password": [REDACTED]}, losing the closing brace and taking the secret with it. That
   * trade is the precedence rule already in force below: a recognised sensitive key redacts its
   * whole right-hand side. Without this alternative, a bcrypt hash under a quoted key — the
   * spelling this API's own responses are written in — reaches the wire intact.
   *
   * <p><b>Why consulting the value's first character here is not the mistake it would be
   * elsewhere.</b> A refusal keyed on the value is unsafe when refusing is the only thing between
   * the secret and the wire, because refusing to match means publishing and the caller writes the
   * value: {@code PASSWORD=[hunter2} and {@code PASSWORD=&#123;hunter2} are redacted today and any
   * such rule would let both out. Here the test is a <b>guard on a widening, not a defence</b>. Its
   * failure direction is the point: refusing a structural opener falls back to leaving the text
   * exactly as it arrived, which is what happened before this alternative existed, while accepting
   * one would be strictly worse than that. A rule whose failure mode is "publish" must never depend
   * on the value; a rule whose failure mode is "change nothing" may.
   *
   * <p>The same distinction is why the <em>quote</em> tests above are sound, and it is not the
   * reason an earlier version of this comment gave. That version said the key's quoting is a
   * property of the surrounding document and therefore out of the caller's reach. It is not: every
   * caller of {@link #redact(String)} passes a single caller-authored string — a brain entry, a task
   * objective, a piece of evidence — so the caller writes the key's quotes too. What makes these
   * rules safe is direction of failure, not reachability.
   *
   * <p>Unquoted keys are untouched by all of this and keep their pre-existing behaviour, mangling
   * included — see {@code SecretAssignmentGrammarTest}, which pins the forms that predate this work
   * rather than quietly fixing some of them.
   *
   * <p><b>There is no group 3 any more.</b> The value used to be {@code [^\s,;"'\r\n]+} — a run
   * ending at the first terminator — and that is precisely the expression that cannot express a
   * bracketed value, whose terminators are all <em>inside</em> it. The pattern now ends at the
   * separator with a zero-width assertion that at least one value character follows, and
   * {@link #valueExtent} measures how far the value reaches. The assertion is what preserves the
   * old gating: {@code PASSWORD=} with nothing after it still does not match.
   */
  private static final Pattern SENSITIVE_KV_PATTERN =
      Pattern.compile(
          "(?i)\\b(" + SENSITIVE_KEY_REGEX
              + ")((?:[\"']\\s*:\\s*[\"']"
              + "|[\"']\\s*:\\s*(?="
              + SCALAR_VALUE_START
              + ")"
              + "|\\s*[=:]\\s*[\"']?))(?=[^\\s,;\"'\\r\\n])");

  private SensitiveDataRedactor() {}

  public static String redact(String input) {
    if (input == null || input.isBlank()) {
      return input == null ? "" : input;
    }

    String result = input;

    // 1. Redact Private Keys
    result =
        PRIVATE_KEY_PATTERN
            .matcher(result)
            .replaceAll(
                match -> {
                  String type = match.group(1);
                  String header = type == null ? "PRIVATE KEY" : type + "PRIVATE KEY";
                  return "-----BEGIN " + header + "-----\n[REDACTED]\n-----END " + header + "-----";
                });

    // 2. Redact passwords in connection URLs
    result = DB_URL_PASSWORD_PATTERN.matcher(result).replaceAll("$1[REDACTED]$3");

    // 3. Redact Authorization Bearer tokens
    result =
        BEARER_TOKEN_PATTERN
            .matcher(result)
            .replaceAll(
                match -> {
                  String raw = match.group(2);
                  if (isSafePlaceholder(raw)) {
                    return match.group(0);
                  }
                  return match.group(1) + "[REDACTED]";
                });

    // 4. Redact known token prefixes (sk-..., ghp_...)
    result =
        OPENAI_KEY_PATTERN
            .matcher(result)
            .replaceAll(
                match -> {
                  String token = match.group();
                  if (isSafePlaceholder(token)) {
                    return token;
                  }
                  return "sk-****REDACTED****";
                });

    result =
        GITHUB_TOKEN_PATTERN
            .matcher(result)
            .replaceAll(
                match -> {
                  String token = match.group();
                  if (isSafePlaceholder(token)) {
                    return token;
                  }
                  return "ghp_****REDACTED****";
                });

    // 5. Redact Key-Value assignments — and here the placeholder exemption does not apply.
    //
    //    SECRET ASSIGNMENT > PLACEHOLDER EXEMPTION.
    //
    //    Once the key is recognised as a secret's key, the whole right-hand side goes, whatever it
    //    looks like. The rule this replaces asked isSafePlaceholder about the value, which meant
    //    asking whether $ABC is a variable read or a password — a question with no safe answer,
    //    because $Pa55phrase_zqxw_610455 and $DB_PASSWORD are the same string to any pattern and a
    //    caller controls which one they write. Every attempt to sharpen that heuristic is another
    //    bypass with a rule written for it. The sensitive key already supplies the context, so the
    //    shape of the value never has to be consulted here at all.
    //
    //    Outside an assignment nothing changed: isSafePlaceholder still decides for the Bearer,
    //    sk- and ghp_ passes above and for the SEC-002 finding rule, and text that merely mentions
    //    ${NAME} or <NAME> is still returned untouched.
    //
    //    The cost is real and was accepted rather than argued away: "password: ${DB_PASSWORD}" is
    //    the correct way to write a Spring application.yml — the idiom that exists so the file
    //    holds no secret — and it now redacts to "password: [REDACTED]". SecretAssignmentGrammarTest
    //    pins that alongside the corpus measurement of what else this rewrites.
    //
    // Last on purpose: the value rules above have already replaced what they recognise with a
    // marker that names the kind of credential removed, and those markers are values this pattern
    // now matches — OPENAI_API_KEY=sk-****REDACTED**** is a key/value assignment like any other.
    // isSafePlaceholder knows the redactor's own markers, so they survive and the reader keeps the
    // more specific of the two. That also makes redact idempotent, which matters because text is
    // stored redacted and read back.
    Matcher kvMatcher = SENSITIVE_KV_PATTERN.matcher(result);
    StringBuilder sb = new StringBuilder();
    int pos = 0;
    while (kvMatcher.find(pos)) {
      String key = kvMatcher.group(1);
      String separator = kvMatcher.group(2);
      int valueStart = kvMatcher.end();
      int valueEnd = valueExtent(result, valueStart, separator);

      if (valueEnd < 0) {
        // THE WIDENED AXIS REFUSES. The value opens a container under a quoted key and could not be
        // proved to be the whole value — it does not close on the line, or something follows it that
        // a value can continue through. This spelling had no behaviour before the widening, so
        // copying the text through is exactly what the redactor did before it. See valueExtent's
        // note on the two axes for why refusing is sound on this axis and only this one.
        sb.append(result, pos, valueStart);
        pos = valueStart;
        continue;
      }

      String val = result.substring(valueStart, valueEnd);
      sb.append(result, pos, kvMatcher.start());
      if (isAlreadyRedacted(val)) {
        sb.append(result, kvMatcher.start(), valueEnd);
      } else {
        // Only the value is substituted. The key and everything between it and the value are the
        // text as it arrived, so nothing outside the secret is rewritten; a closing quote sits
        // after the value and is never consumed.
        sb.append(key).append(separator).append("[REDACTED]");
      }
      pos = valueEnd;
    }
    sb.append(result, pos, result.length());
    result = sb.toString();

    return result;
  }

  /** The terminators of an unbracketed value. Unchanged from the run this replaces. */
  private static final String VALUE_TERMINATORS = " \t,;\"'\r\n";

  /**
   * How far the value beginning at {@code from} reaches.
   *
   * <p><b>This method is the fix for FINDING J1.</b> It is deliberately not a parser: it balances
   * brackets on one line and knows nothing about JSON, YAML, types, or what a value means. What it
   * buys is the one fact the old {@code [^\s,;"'\r\n]+} run could not represent — that a bracketed
   * value's terminators are all <em>inside</em> it, so the run stopped at the first comma and left
   * the rest of the sequence, secret included, in the document beside a deleted opening bracket.
   *
   * <h2>TWO AXES, TWO GUARDS. FINDING R2, and it is the important one on this page.</h2>
   *
   * <p>An earlier version of this comment said: the bracket scan never returns less than the run it
   * replaces, <em>and that is the whole safety argument</em>. The first half is true and was
   * independently re-verified — at depth 0 the scan's break condition is character-for-character
   * {@link #plainExtent}'s stop condition, and 72,000 fuzzed assignments plus 43,000 corpus lines
   * found no counterexample. <b>The second half does not follow, and the word "so" that joined them
   * was carrying weight it cannot carry.</b>
   *
   * <p>A monotone extent constrains only inputs that <em>already matched</em>. It says nothing
   * whatever about inputs that did not, and those are reached by a different change: the widening of
   * {@link #SCALAR_VALUE_START} from {@code \[(?=[A-Za-z0-9$_.+~%@-])} to {@code [\[{(]}. That is a
   * second axis, and every leak this file introduced arrived along it — which is how a correct proof
   * and 2,100 introduced leaks came to live in the same commit. A correct proof about the wrong axis
   * is worse than no proof, because it stops the question being asked.
   *
   * <p>So the value is measured by <b>which axis it arrived on</b>:
   *
   * <ul>
   *   <li><b>Inputs that matched before this work</b> — an unquoted key, a quoted key with a quoted
   *       value, or a quoted key whose value starts with a character
   *       {@link #admittedBeforeTheExtentScan} accepted. Here the baseline is a redaction, so
   *       refusing would publish and is forbidden: {@code PASSWORD=[hunter2} is a password with an
   *       unbalanced bracket, and {@code SecretAssignmentGrammarTest} pins it. These take
   *       {@link #bracketedExtent}, falling back to {@link #plainExtent}. Monotone, and that is the
   *       axis the monotonicity proof is actually about.
   *   <li><b>Inputs the widening newly admitted</b> — {@code &#123;}, {@code (}, and {@code [}
   *       followed by any of 22 characters the old inner lookahead refused. Here the baseline is
   *       <em>untouched</em>, so refusing costs nothing and a partial redaction is a leak this work
   *       would have created. These take {@link #wholeContainerExtent}, which redacts only a
   *       container it can prove is the entire value and otherwise changes nothing.
   * </ul>
   *
   * <p><b>Why the newly admitted axis needed more than a {@code -1} check.</b> The guard this
   * replaces refused only when {@link #bracketedExtent} returned {@code -1}, and part of the family
   * never reaches {@code -1}: in {@code {"password": []prod, SECRET]}} the bracket opens, closes
   * immediately, {@code prod} runs on at depth 0 and the comma stops the scan with {@code depth ==
   * 0}, so the extent is <b>returned as valid and is simply too short</b> — {@code [REDACTED]} then
   * the secret, mangled and leaking. Four distinct ways to fail and one way to succeed wrongly; only
   * a test on the <em>answer</em> covers all five, so {@link #wholeContainerExtent} tests the answer.
   *
   * <p>Rules the scan itself follows, on both axes:
   *
   * <ul>
   *   <li><b>A line is the horizon.</b> A value whose text continues on the next line is not
   *       measurable here and never will be without a parser.
   *   <li><b>{@code [}, {@code &#123;} and {@code (} open</b>; the scan runs to the matching closer,
   *       nesting, and skipping quoted spans — <em>honouring backslash escapes</em>, so a password
   *       containing a quote no longer breaks the count. Terminators do not apply inside brackets.
   *   <li><b>The nesting stack grows.</b> It was a fixed 32 and a hard-coded array length is not an
   *       implementation detail when exceeding it changes what gets published.
   *   <li><b>A closer at depth zero is an ordinary character on the matched-before axis.</b> Letting
   *       {@code &#125;} end a value there would turn {@code PASSWORD=hunter2}evil} into
   *       {@code PASSWORD=[REDACTED]}evil}, publishing the tail of a password because the caller put
   *       a brace in it. On the newly admitted axis the baseline is untouched, so a closer may end
   *       the value and {@code {"password": {"inner": "k"}}} comes out as valid JSON.
   * </ul>
   */
  private static int valueExtent(String text, int from, String separator) {
    if (arrivedOnTheWidenedAxis(text, from, separator)) {
      return wholeContainerExtent(text, from);
    }
    int balanced = bracketedExtent(text, from);
    return balanced < 0 ? plainExtent(text, from) : balanced;
  }

  /**
   * Whether this value reached the pattern only because the widening admitted it.
   *
   * <p>True exactly when the quoted-key-with-unquoted-value alternative matched — its separator
   * opens with a quote and does not close with one — and the value's first character is one the
   * whitelist refused before {@link #wholeContainerExtent} existed. For those, and only those, the
   * redactor's previous behaviour was to change nothing, so changing nothing remains available as a
   * safe answer.
   */
  private static boolean arrivedOnTheWidenedAxis(String text, int from, String separator) {
    if (separator.isEmpty()) {
      return false;
    }
    char first = separator.charAt(0);
    char last = separator.charAt(separator.length() - 1);
    boolean quotedKeyUnquotedValue = (first == '"' || first == '\'') && last != '"' && last != '\'';
    return quotedKeyUnquotedValue && !admittedBeforeTheExtentScan(text, from);
  }

  /**
   * The extent of a container that is provably the <b>whole</b> value, or {@code -1} to change
   * nothing.
   *
   * <p>Used only on the newly admitted axis, where the redactor previously produced no output at
   * all. That is what lets this be strict: every {@code -1} here reproduces the behaviour at
   * 5b07bb1 exactly, so no redaction can be lost and {@code changedByOldOnly} cannot move.
   *
   * <p>The container must close on the line, and <b>what follows it must be something a value
   * cannot continue through</b>: end of line, a terminator, or the closer of an enclosing
   * container. If any other character follows — {@code p} in {@code []prod} — then the brackets
   * balanced but the value did not end there, the answer would be a prefix, and a prefix is the
   * one thing that must never be emitted.
   */
  private static int wholeContainerExtent(String text, int from) {
    int lineEnd = endOfLine(text, from);
    int close = matchingCloser(text, from, lineEnd);
    if (close < 0) {
      return -1;
    }
    if (close == lineEnd) {
      return close;
    }
    char after = text.charAt(close);
    if (VALUE_TERMINATORS.indexOf(after) >= 0 || after == ']' || after == '}' || after == ')') {
      return close;
    }
    return -1;
  }

  /**
   * The index just past the closer matching the opener at {@code from}, or {@code -1} if the line
   * does not close it. Never scans past {@code lineEnd}, and never past that closer.
   */
  private static int matchingCloser(String text, int from, int lineEnd) {
    char c = text.charAt(from);
    if (c != '[' && c != '{' && c != '(') {
      return -1;
    }
    char[] open = new char[16];
    int depth = 0;
    int i = from;
    while (i < lineEnd) {
      char ch = text.charAt(i);
      if (ch == '"' || ch == '\'') {
        i = skipQuotedSpan(text, i, lineEnd);
        if (i < 0) {
          return -1;
        }
        continue;
      }
      if (ch == '[' || ch == '{' || ch == '(') {
        if (depth == open.length) {
          open = java.util.Arrays.copyOf(open, depth * 2);
        }
        open[depth++] = ch;
        i++;
        continue;
      }
      if (ch == ']' || ch == '}' || ch == ')') {
        if (depth == 0 || closerFor(open[depth - 1]) != ch) {
          return -1;
        }
        depth--;
        i++;
        if (depth == 0) {
          return i;
        }
        continue;
      }
      i++;
    }
    return -1;
  }

  /**
   * The index just past a quoted span starting at {@code i}, or {@code -1} if it does not close on
   * the line.
   *
   * <p><b>Backslash escapes are honoured</b>, and that is not cosmetic. Without it,
   * {@code ["a\"b", SECRET]} has an odd number of quote characters, the span runs to the wrong
   * place, the scan fails, and the value falls back to a one-character prefix — a leak reachable by
   * ordinary valid JSON, or by a password that simply contains a quotation mark.
   */
  private static int skipQuotedSpan(String text, int i, int lineEnd) {
    char quote = text.charAt(i);
    for (int j = i + 1; j < lineEnd; j++) {
      char c = text.charAt(j);
      if (c == '\\') {
        j++;
        continue;
      }
      if (c == quote) {
        return j + 1;
      }
    }
    return -1;
  }


  /**
   * The scalar-start whitelist exactly as it stood at 5b07bb1, before J1 widened it.
   *
   * <p>Kept as running code rather than as a sentence in a comment, because it is consulted for one
   * decision only — may an unmeasurable value fall back? — and a prose copy of a whitelist is how
   * SEC-002 came to carry a stale regex for three commits.
   */
  private static boolean admittedBeforeTheExtentScan(String text, int from) {
    char c = text.charAt(from);
    if ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789$_./+~%@-".indexOf(c) >= 0) {
      return true;
    }
    char next = from + 1 < text.length() ? text.charAt(from + 1) : '\0';
    if (c == '<') {
      return next != '<';
    }
    if (c == '[') {
      return "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789$_.+~%@-".indexOf(next)
          >= 0;
    }
    return false;
  }

  /** The run this redactor has always used: everything up to the first terminator. */
  private static int plainExtent(String text, int from) {
    int i = from;
    while (i < text.length() && VALUE_TERMINATORS.indexOf(text.charAt(i)) < 0) {
      i++;
    }
    return i;
  }

  /** The bracket-balanced extent, or {@code -1} when the line does not balance. */
  private static int bracketedExtent(String text, int from) {
    // FINDING R3. The end of the line is computed ONCE. It used to be recomputed inside the quote
    // branch, on every quoted span, each call scanning to the end of the line — so the cost was
    // O(quotes x distance-to-end-of-line) and the method this replaced was linear. Measured, not
    // inferred: 800 KB on one line took 150 seconds, against 146 ms for the redactor at 5b07bb1,
    // and padding the same line with inert text scaled the cost while moving the identical padding
    // past a newline left it flat. redact() runs on caller-authored strings with no length cap on
    // context item content, so one HTTP request bought minutes of CPU.
    int lineEnd = endOfLine(text, from);
    // Grows. A fixed 32 is not an implementation detail when exceeding it changes which bytes get
    // published: at depth 33 the scan returned -1 and the answer came from somewhere else.
    char[] open = new char[16];
    int depth = 0;
    int i = from;
    while (i < lineEnd) {
      char c = text.charAt(i);
      if (depth == 0) {
        if (c == '[' || c == '{' || c == '(') {
          open[depth++] = c;
          i++;
          continue;
        }
        if (VALUE_TERMINATORS.indexOf(c) >= 0) {
          break;
        }
        i++;
        continue;
      }
      if (c == '"' || c == '\'') {
        int close = skipQuotedSpan(text, i, lineEnd);
        if (close < 0) {
          return -1;
        }
        i = close;
        continue;
      }
      if (c == '[' || c == '{' || c == '(') {
        if (depth == open.length) {
          open = java.util.Arrays.copyOf(open, depth * 2);
        }
        open[depth++] = c;
        i++;
        continue;
      }
      if (c == ']' || c == '}' || c == ')') {
        if (closerFor(open[depth - 1]) != c) {
          return -1;
        }
        depth--;
        i++;
        continue;
      }
      i++;
    }
    return depth == 0 ? i : -1;
  }

  private static char closerFor(char opener) {
    return opener == '[' ? ']' : opener == '{' ? '}' : ')';
  }

  private static int endOfLine(String text, int from) {
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') {
        return i;
      }
    }
    return text.length();
  }


  /**
   * Whether the value is a marker this redactor itself emitted.
   *
   * <p>The only thing that survives on the right-hand side of a secret assignment, and it is not a
   * placeholder exemption — it is the fixed-point condition. Redacted text is stored and read back,
   * and a second pass must not overwrite {@code sk-****REDACTED****} with a generic
   * {@code [REDACTED]}: the shape-named marker is the only surviving evidence of what kind of
   * credential was removed, and it is the control this project measured the whole finding against.
   *
   * <p>Not a bypass. The value has to be the marker itself, character for character, so all a
   * caller can achieve by writing one is to publish the string {@code [REDACTED]}.
   */
  private static boolean isAlreadyRedacted(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    // Trailing closers only. An unquoted value after a quoted key runs to the next quote or space,
    // so it swallows the "}" or "]" that ended the object it sat in: {"apiKey": sk-****REDACTED****}
    // arrives here as sk-****REDACTED****} and would otherwise be overwritten with the generic
    // marker, losing which kind of credential an earlier pass removed.
    //
    // Only from the end, and this is the whole of why it is safe: PASSWORD=[REDACTED]}hunter2 ends
    // in "2", nothing is stripped, and the value is redacted. A secret placed after the closer
    // prevents the strip that would have exempted it, so there is no way to smuggle text through by
    // wrapping it in a marker.
    //
    // NOTE ON REACH. This method is called from the shared replacement loop, not from the
    // quoted-key branch that motivated it, so the strip applies to every spelling. An unquoted key
    // is affected too: PASSWORD=sk-****REDACTED****} was rewritten to PASSWORD=[REDACTED] before
    // the strip existed and is now passed through. That is benign in the same way and for the same
    // reason — the more specific marker is preserved, no secret rides through, and the value must
    // still be a marker character for character before any closer — but the reach is wider than
    // the change that prompted it and is recorded here rather than left to be discovered.
    // Every suffix is tried, not just the fully stripped one, because "[REDACTED]" ends in a closer
    // itself: stripping greedily would leave "[REDACTED" and the marker would stop matching.
    for (int end = trimmed.length(); end > 0; end--) {
      String core = trimmed.substring(0, end);
      if (core.equals("[REDACTED]")
          || core.equals("sk-****REDACTED****")
          || core.equals("ghp_****REDACTED****")) {
        return true;
      }
      if ("}])".indexOf(trimmed.charAt(end - 1)) < 0) {
        return false;
      }
    }
    return false;
  }

  /**
   * Whether a value is a reference to a secret rather than one.
   *
   * <p>This is the placeholder policy, and it is unchanged. What changed is <em>where</em> it
   * applies: it decides for the Bearer, {@code sk-} and {@code ghp_} passes, and for the SEC-002
   * finding rule, but it is no longer consulted on the right-hand side of a recognised secret
   * assignment — see the note on step 5 of {@link #redact(String)}.
   */
  public static boolean isSafePlaceholder(String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    String trimmed = value.trim();
    if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
      return true;
    }
    // A bare $NAME: the other spelling of the same interpolation. Deliberately an identifier and
    // not "anything after a dollar sign", which is what this used to be.
    //
    // That earlier rule reopened this task's own finding for the cost of one character. EVERY
    // bcrypt hash begins "$2", and a password may begin with "$" like any other character, so
    // VIBECODE_DB_PASSWORD=$2b$12$… was matched by the key rule and then handed back untouched by
    // this method — reaching the 201 body, items[].label, items[].content, the canonical payload,
    // the digest and both context tables. Byte for byte the blast radius of FINDING CTX-09B-1,
    // reopened by a character that is part of the secret's own shape.
    //
    // The narrowing preserves the intent rather than changing the policy: the exemption was always
    // meant to cover interpolation syntax, and startsWith("$") was an over-broad implementation of
    // it. ${NAME}, $NAME, <ANYTHING>, [REDACTED], ***, CHANGE_ME and REPLACE_ME all stay exempt.
    // What stops being exempt is a value that merely begins with a dollar sign: $2b$12$…, and also
    // shell command substitution $(…), which is now redacted rather than passed through. That
    // second one is a deliberate accepted cost — it errs towards removing a value that was not a
    // secret, which is the safe direction, where the first errs towards publishing one that was.
    if (INTERPOLATED_NAME_PATTERN.matcher(trimmed).matches()) {
      return true;
    }
    if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
      return true;
    }
    // Markers this redactor itself emits: an earlier pass already handled the value, and the
    // prefix is deliberately kept so the reader can tell what kind of secret was removed.
    if (trimmed.equals("sk-****REDACTED****") || trimmed.equals("ghp_****REDACTED****")) {
      return true;
    }
    if (trimmed.equalsIgnoreCase("[REDACTED]")
        || trimmed.equalsIgnoreCase("REPLACE_ME")
        || trimmed.equalsIgnoreCase("CHANGE_ME")
        || trimmed.equalsIgnoreCase("TODO")
        || trimmed.equalsIgnoreCase("YOUR_KEY")
        || trimmed.equalsIgnoreCase("YOUR_API_KEY")
        || trimmed.equalsIgnoreCase("null")
        || trimmed.equalsIgnoreCase("undefined")
        || trimmed.equals("***")) {
      return true;
    }
    return false;
  }
}

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
   *       through. It also opens a JSON array. So {@code [} is admitted only when what follows it
   *       could start a scalar: {@code ["secret"]} and {@code [{…}]} are containers and are
   *       refused, {@code [REDACTED]secret} is not.
   * </ul>
   */
  private static final String SCALAR_VALUE_START =
      "(?:[A-Za-z0-9$_./+~%@-]|<(?!<)|\\[(?=[A-Za-z0-9$_.+~%@-]))";

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
   * <p>Group 3 is the value, ending at whitespace, comma, semicolon or quote. Unchanged — and see
   * {@code SecretAssignmentGrammarTest} for what that costs on a passphrase.
   */
  private static final Pattern SENSITIVE_KV_PATTERN =
      Pattern.compile(
          "(?i)\\b([A-Za-z0-9_]*(?:"
              + SENSITIVE_KEY_WORDS
              + "))((?:[\"']\\s*:\\s*[\"']"
              + "|[\"']\\s*:\\s*(?="
              + SCALAR_VALUE_START
              + ")"
              + "|\\s*[=:]\\s*[\"']?))([^\\s,;\"'\\r\\n]+)");

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
    while (kvMatcher.find()) {
      String key = kvMatcher.group(1);
      String separator = kvMatcher.group(2);
      String val = kvMatcher.group(3);

      if (isAlreadyRedacted(val)) {
        kvMatcher.appendReplacement(sb, Matcher.quoteReplacement(kvMatcher.group(0)));
      } else {
        // Only the value is substituted. The key and everything between it and the value are the
        // text as it arrived, so nothing outside the secret is rewritten; a closing quote sits
        // after the match and is never consumed.
        kvMatcher.appendReplacement(
            sb, Matcher.quoteReplacement(key + separator + "[REDACTED]"));
      }
    }
    kvMatcher.appendTail(sb);
    result = sb.toString();

    return result;
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

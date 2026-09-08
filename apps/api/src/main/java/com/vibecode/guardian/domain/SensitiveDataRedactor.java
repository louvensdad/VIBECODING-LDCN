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
   * <p><b>A quoted key is a different spelling with different rules, and it gets its own branch.</b>
   * The first alternative is the JSON one: a quote closing the key, a colon, and <b>a quote opening
   * the value</b> — both quotes required. The second is everything else: no quote on the key, a
   * {@code =} or a {@code :}, and an optional quote on the value.
   *
   * <p>Two failures are why the branch is shaped like that, and they are the same failure twice.
   * <b>A match that rewrites the text and leaves the secret in it is strictly worse than not
   * matching at all</b> — the leak is unchanged and the document is now broken as well.
   *
   * <ul>
   *   <li>Allowing a quote before {@code =} made {@code 'password' => 'secret'} match the {@code =}
   *       of the {@code =>}, take {@code >} as the whole value, and emit
   *       {@code 'password' =[REDACTED] 'secret'}. So the quote goes with the colon only. (TOML
   *       does write {@code "key" = "value"}, so that spelling is given up here rather than being
   *       unrepresentable; it was not matched at 6d784fb either.)
   *   <li>Requiring only the key's quote made {@code {"password": {"inner": "secret"}}} match, take
   *       the {@code &#123;} as the whole value, and emit
   *       {@code {"password": [REDACTED]"inner": "secret"}}: the secret still there and an opening
   *       brace deleted, so the JSON no longer parses. Requiring the value's quote too is what
   *       fixes it — in JSON a scalar is quoted and a container is not, so demanding the quote is
   *       exactly the test for "this value is a string and I can replace it".
   * </ul>
   *
   * <p><b>Why this is a rule about the quotes and not about the value.</b> The obvious alternative
   * is to refuse a value that begins with {@code &#123;} or {@code [}. That is not safe: refusing to
   * match means the value is published, the value is the half a caller controls, and so every
   * refusal keyed on the value's own text is a bypass waiting to be written — {@code PASSWORD=[hunter2}
   * and {@code PASSWORD=&#123;hunter2} would both walk straight out, and both are redacted today.
   * The quoting of the <em>key</em> is a property of the surrounding document rather than of the
   * secret, so tightening on it cannot be gamed from inside the value.
   *
   * <p>What this branch gives up, deliberately: {@code {"password": 12345}} and
   * {@code "password": bare} are not matched, because an unquoted JSON value is a container, a
   * number or a keyword rather than a string. Unquoted keys are untouched by this and keep their
   * pre-existing behaviour, mangling included — see {@code SecretAssignmentGrammarTest}, which pins
   * the forms that predate this work rather than quietly fixing some of them.
   *
   * <p>Group 3 is the value, ending at whitespace, comma, semicolon or quote. Unchanged — and see
   * {@code SecretAssignmentGrammarTest} for what that costs on a passphrase.
   */
  private static final Pattern SENSITIVE_KV_PATTERN =
      Pattern.compile(
          "(?i)\\b([A-Za-z0-9_]*(?:"
              + SENSITIVE_KEY_WORDS
              + "))((?:[\"']\\s*:\\s*[\"']|\\s*[=:]\\s*[\"']?))([^\\s,;\"'\\r\\n]+)");

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
    return trimmed.equals("[REDACTED]")
        || trimmed.equals("sk-****REDACTED****")
        || trimmed.equals("ghp_****REDACTED****");
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

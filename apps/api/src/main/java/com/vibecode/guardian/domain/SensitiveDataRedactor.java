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

  private static final Pattern GITHUB_TOKEN_PATTERN =
      Pattern.compile("(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}");

  private static final Pattern SENSITIVE_KV_PATTERN =
      Pattern.compile(
          "(?i)\\b(API_KEY|SECRET|TOKEN|PASSWORD|CLIENT_SECRET|ACCESS_TOKEN|REFRESH_TOKEN|PRIVATE_KEY)\\s*(=|:)\\s*([\"']?)([^\\s,;\"'\\r\\n]+)([\"']?)");

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

    // 5. Redact Key-Value assignments
    Matcher kvMatcher = SENSITIVE_KV_PATTERN.matcher(result);
    StringBuilder sb = new StringBuilder();
    while (kvMatcher.find()) {
      String key = kvMatcher.group(1);
      String separator = kvMatcher.group(2);
      String openQuote = kvMatcher.group(3);
      String val = kvMatcher.group(4);
      String closeQuote = kvMatcher.group(5);

      if (isSafePlaceholder(val)) {
        kvMatcher.appendReplacement(sb, Matcher.quoteReplacement(kvMatcher.group(0)));
      } else {
        kvMatcher.appendReplacement(
            sb, Matcher.quoteReplacement(key + separator + openQuote + "[REDACTED]" + closeQuote));
      }
    }
    kvMatcher.appendTail(sb);
    result = sb.toString();

    return result;
  }

  public static boolean isSafePlaceholder(String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    String trimmed = value.trim();
    if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
      return true;
    }
    if (trimmed.startsWith("$")) {
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

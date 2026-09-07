package com.vibecode.identity.domain;

/**
 * What counts as an acceptable password.
 *
 * <p>Length is the requirement; composition is not. Forcing an uppercase letter, a digit and a
 * symbol pushes people towards {@code Password1!} — short, predictable, and weaker than a long
 * passphrase. So spaces, unicode and emoji are all allowed, and a 10-character minimum is enforced
 * on <em>code points</em> rather than UTF-16 units, so an accented or emoji-containing password is
 * measured the way a person would count it.
 *
 * <p>The maximum exists only to bound the work bcrypt does on a request; nothing is ever silently
 * truncated — a password over the limit is rejected and the user is told.
 */
public final class PasswordPolicy {

  public static final int MINIMUM_LENGTH = 10;

  /**
   * BCrypt ignores everything past 72 bytes. Capping below that in characters keeps any password we
   * accept fully significant, so two different long passwords can never hash the same.
   */
  public static final int MAXIMUM_LENGTH = 64;

  public static final String DESCRIPTION =
      "A senha deve ter entre "
          + MINIMUM_LENGTH
          + " e "
          + MAXIMUM_LENGTH
          + " caracteres. Espaços, acentos e frases inteiras são permitidos.";

  private PasswordPolicy() {}

  /** @throws WeakPasswordException when the password is not acceptable */
  public static void validate(String rawPassword) {
    if (rawPassword == null || rawPassword.isEmpty()) {
      throw new WeakPasswordException("A senha é obrigatória.");
    }
    int length = rawPassword.codePointCount(0, rawPassword.length());
    if (length < MINIMUM_LENGTH) {
      throw new WeakPasswordException(
          "A senha precisa de pelo menos " + MINIMUM_LENGTH + " caracteres.");
    }
    if (length > MAXIMUM_LENGTH) {
      throw new WeakPasswordException(
          "A senha pode ter no máximo " + MAXIMUM_LENGTH + " caracteres.");
    }
    if (rawPassword.isBlank()) {
      throw new WeakPasswordException("A senha não pode ser apenas espaços.");
    }
  }

  /** Carries no password — only the reason it was refused. */
  public static class WeakPasswordException extends RuntimeException {

    public WeakPasswordException(String message) {
      super(message);
    }
  }
}

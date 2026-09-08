package com.vibecode.provider.domain;

/**
 * The technical providers, named after the API rather than the product.
 *
 * <p>ChatGPT is a product of OPENAI; Claude is a product of ANTHROPIC; Gemini is GOOGLE_GEMINI. One
 * name per provider throughout the codebase — a domain with two words for the same thing is a
 * domain where two pieces of code disagree about what they mean.
 *
 * <p>Note this deliberately shadows nothing: {@code com.vibecode.model.domain.ProviderId} from the
 * foundation phase is a different, unused contract. This is the one connected accounts use.
 */
public enum ProviderId {
  OPENAI("OpenAI"),
  ANTHROPIC("Anthropic"),
  GOOGLE_GEMINI("Google Gemini"),
  DEEPSEEK("DeepSeek"),
  /** Anything self-hosted or not yet catalogued. */
  CUSTOM("Custom");

  private final String displayName;

  ProviderId(String displayName) {
    this.displayName = displayName;
  }

  /** How the provider is written in the interface. Presentation only; the enum name is the key. */
  public String displayName() {
    return displayName;
  }
}

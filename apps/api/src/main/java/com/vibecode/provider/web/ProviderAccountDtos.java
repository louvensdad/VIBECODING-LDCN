package com.vibecode.provider.web;

import com.vibecode.provider.domain.AuthenticationType;
import com.vibecode.provider.domain.ProviderAccount;
import com.vibecode.provider.domain.ProviderAccountStatus;
import com.vibecode.provider.domain.ProviderId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The shapes crossing the API boundary.
 *
 * <p>The credential request and the account response are separate types, and deliberately not
 * reused for one another: a single type carrying the credential would eventually be serialised
 * into a response by someone adding a field, and the leak would look like an ordinary refactor.
 */
public final class ProviderAccountDtos {

  private ProviderAccountDtos() {}

  public record CreateProviderAccountRequest(
      @NotNull ProviderId provider,
      @NotBlank @Size(max = 120) String displayName,
      @NotNull AuthenticationType authenticationType) {}

  /**
   * Write-only. This type appears in no response anywhere in the application.
   *
   * <p>The field is a {@code char[]} rather than a {@code String} so it can be overwritten once
   * encrypted, and so an accidental {@code toString()} of the request prints an array reference
   * instead of a credential.
   */
  public record StoreCredentialRequest(
      @NotNull @Size(min = 8, max = 8192) char[] credential) {

    /** Never the value. */
    @Override
    public String toString() {
      return "StoreCredentialRequest[credential=redacted]";
    }
  }

  /**
   * What the client learns about a connection.
   *
   * <p>{@code hasCredential} is a boolean and nothing more. No prefix, no last four characters, no
   * fingerprint: none of it is needed to use the product, and each would be a piece of a secret
   * travelling somewhere it does not have to.
   */
  public record ProviderAccountResponse(
      UUID id,
      ProviderId provider,
      String displayName,
      AuthenticationType authenticationType,
      ProviderAccountStatus status,
      boolean hasCredential,
      Instant credentialUpdatedAt,
      Instant createdAt,
      Instant updatedAt) {

    public static ProviderAccountResponse from(ProviderAccount account) {
      return new ProviderAccountResponse(
          account.getId(),
          account.getProvider(),
          account.getDisplayName(),
          account.getAuthenticationType(),
          account.getStatus(),
          account.hasCredential(),
          account.getCredentialUpdatedAt(),
          account.getCreatedAt(),
          account.getUpdatedAt());
    }
  }

  /** The catalogue the interface renders. Static data; no external call is made to build it. */
  public record ProviderCatalogEntry(
      ProviderId id, String displayName, List<AuthenticationType> supportedAuthenticationTypes) {}
}

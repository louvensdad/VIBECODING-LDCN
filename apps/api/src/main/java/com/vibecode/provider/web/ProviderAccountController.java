package com.vibecode.provider.web;

import com.vibecode.provider.application.ProviderAccountService;
import com.vibecode.provider.domain.AuthenticationType;
import com.vibecode.provider.domain.ProviderId;
import com.vibecode.provider.web.ProviderAccountDtos.CreateProviderAccountRequest;
import com.vibecode.provider.web.ProviderAccountDtos.ProviderAccountResponse;
import com.vibecode.provider.web.ProviderAccountDtos.ProviderCatalogEntry;
import com.vibecode.provider.web.ProviderAccountDtos.StoreCredentialRequest;
import com.vibecode.vault.domain.SecretMaterial;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface for provider connections.
 *
 * <p>There is deliberately no endpoint that returns a credential. Not a plaintext one, not a masked
 * one, not a prefix, not a last-four, not a fingerprint. The only direction a credential travels
 * here is inward, and the only thing that ever travels back out is whether one exists.
 *
 * <p>An endpoint that returns "sk-…4f7a" looks harmless and is the thing an attacker with a stolen
 * session uses to confirm which key they are looking at, and the thing that ends up in a screenshot
 * or a support ticket. It is easier to never build it than to keep it safe.
 */
@RestController
@RequestMapping("/api/provider-accounts")
public class ProviderAccountController {

  private final ProviderAccountService accounts;

  public ProviderAccountController(ProviderAccountService accounts) {
    this.accounts = accounts;
  }

  /** The providers a connection can be created for. Static data; nothing is contacted to build it. */
  @GetMapping("/catalog")
  public List<ProviderCatalogEntry> catalog() {
    return Arrays.stream(ProviderId.values())
        .map(
            provider ->
                new ProviderCatalogEntry(
                    provider, provider.displayName(), List.of(AuthenticationType.API_KEY)))
        .toList();
  }

  @PostMapping
  public ResponseEntity<ProviderAccountResponse> create(
      @Valid @RequestBody CreateProviderAccountRequest request) {
    ProviderAccountResponse created =
        ProviderAccountResponse.from(
            accounts.create(
                request.provider(), request.displayName(), request.authenticationType()));
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping
  public List<ProviderAccountResponse> list() {
    return accounts.listForCurrentUser().stream().map(ProviderAccountResponse::from).toList();
  }

  @GetMapping("/{id}")
  public ProviderAccountResponse get(@PathVariable UUID id) {
    return ProviderAccountResponse.from(accounts.require(id));
  }

  /**
   * Stores a credential, or replaces the one already stored.
   *
   * <p>The request characters are wrapped into {@link SecretMaterial} and handed straight to the
   * vault, which encrypts and clears them. The array on the request object is overwritten here as
   * well, so the copy Jackson produced does not sit in memory waiting to be collected.
   */
  @PutMapping("/{id}/credential")
  public ProviderAccountResponse storeCredential(
      @PathVariable UUID id, @Valid @RequestBody StoreCredentialRequest request) {
    return consume(request, material -> accounts.storeCredential(id, material));
  }

  @PostMapping("/{id}/credential/rotate")
  public ProviderAccountResponse rotateCredential(
      @PathVariable UUID id, @Valid @RequestBody StoreCredentialRequest request) {
    return consume(request, material -> accounts.rotateCredential(id, material));
  }

  @DeleteMapping("/{id}/credential")
  public ProviderAccountResponse removeCredential(@PathVariable UUID id) {
    return ProviderAccountResponse.from(accounts.removeCredential(id));
  }

  @PostMapping("/{id}/disable")
  public ProviderAccountResponse disable(@PathVariable UUID id) {
    return ProviderAccountResponse.from(accounts.disable(id));
  }

  /**
   * The single place a credential crosses from HTTP into the vault.
   *
   * <p>Both write paths go through here so the clearing cannot be forgotten on one of them.
   */
  private ProviderAccountResponse consume(
      StoreCredentialRequest request,
      java.util.function.Function<SecretMaterial, com.vibecode.provider.domain.ProviderAccount>
          operation) {
    char[] characters = request.credential();
    try {
      // ofChars zeroes the array it is given, so the request object stops holding the value.
      return ProviderAccountResponse.from(operation.apply(SecretMaterial.ofChars(characters)));
    } finally {
      Arrays.fill(characters, '\0');
    }
  }
}

package com.vibecode.provider.application;

import com.vibecode.audit.application.AuditService;
import com.vibecode.audit.domain.AuditEventType;
import com.vibecode.identity.domain.CurrentUser;
import com.vibecode.identity.domain.CurrentUserProvider;
import com.vibecode.provider.domain.AuthenticationType;
import com.vibecode.provider.domain.ProviderAccount;
import com.vibecode.provider.domain.ProviderId;
import com.vibecode.provider.infrastructure.ProviderAccountRepository;
import com.vibecode.shared.domain.DomainRuleException;
import com.vibecode.shared.domain.ResourceNotFoundException;
import com.vibecode.vault.application.VaultService;
import com.vibecode.vault.domain.SecretMaterial;
import com.vibecode.vault.domain.SecretPurpose;
import com.vibecode.vault.domain.SecretReference;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connections to model providers, and the ingestion path for their credentials.
 *
 * <p>This is the trusted boundary where a credential is allowed to arrive: it goes straight into
 * the vault and comes back as a reference. Nothing here returns, logs or stores the plaintext, and
 * no other part of the platform is given a way to ask for it.
 *
 * <p>Access follows the same rule as projects: an account belonging to someone else is reported as
 * not found, so probing ids reveals nothing.
 */
@Service
@Transactional
public class ProviderAccountService {

  private final ProviderAccountRepository accounts;
  private final VaultService vault;
  private final CurrentUserProvider currentUserProvider;
  private final AuditService audit;
  private final MeterRegistry meters;

  public ProviderAccountService(
      ProviderAccountRepository accounts,
      VaultService vault,
      CurrentUserProvider currentUserProvider,
      AuditService audit,
      MeterRegistry meters) {
    this.accounts = accounts;
    this.vault = vault;
    this.currentUserProvider = currentUserProvider;
    this.audit = audit;
    this.meters = meters;
  }

  public ProviderAccount create(
      ProviderId provider, String displayName, AuthenticationType authenticationType) {
    if (!authenticationType.isImplemented()) {
      throw new DomainRuleException(
          "Only API_KEY connections can be created for now; " + authenticationType + " is not "
              + "implemented.");
    }
    CurrentUser owner = currentUserProvider.require();
    ProviderAccount account =
        accounts.save(new ProviderAccount(owner.id(), provider, displayName, authenticationType));

    recordAudit(account, AuditEventType.PROVIDER_ACCOUNT_CREATED, "CREATED");
    count("provider.account.created");
    return account;
  }

  @Transactional(readOnly = true)
  public List<ProviderAccount> listForCurrentUser() {
    CurrentUser owner = currentUserProvider.require();
    return accounts.findByOwnerUserIdOrderByCreatedAtDesc(owner.id());
  }

  @Transactional(readOnly = true)
  public ProviderAccount require(UUID accountId) {
    CurrentUser owner = currentUserProvider.require();
    return accounts
        .findByIdAndOwnerUserId(accountId, owner.id())
        .orElseThrow(
            () -> new ResourceNotFoundException("Provider account not found: " + accountId));
  }

  /**
   * Stores a credential for the first time, or replaces one outright.
   *
   * <p>{@link SecretMaterial} is closed by the vault, so the plaintext stops existing as soon as it
   * has been encrypted. The account records only that a credential exists and when.
   */
  public ProviderAccount storeCredential(UUID accountId, SecretMaterial material) {
    ProviderAccount account = require(accountId);
    boolean replacing = account.hasCredential();

    if (replacing) {
      // Replacing through the same path as rotation keeps a single code path for the invariant
      // that a secret is never left without an active version.
      return rotateCredential(accountId, material);
    }

    SecretReference reference =
        vault.store(account.getOwnerUserId(), SecretPurpose.PROVIDER_API_KEY, material);
    account.attachCredential(reference);

    recordAudit(account, AuditEventType.PROVIDER_CREDENTIAL_STORED, "STORED");
    return account;
  }

  public ProviderAccount rotateCredential(UUID accountId, SecretMaterial material) {
    ProviderAccount account = require(accountId);
    SecretReference reference =
        account
            .credentialReference()
            .orElseThrow(
                () ->
                    new DomainRuleException(
                        "There is no credential to rotate; store one first."));

    vault.rotate(reference, material);
    account.recordCredentialRotation();

    recordAudit(account, AuditEventType.PROVIDER_CREDENTIAL_ROTATED, "ROTATED");
    return account;
  }

  public ProviderAccount removeCredential(UUID accountId) {
    ProviderAccount account = require(accountId);
    account
        .credentialReference()
        .ifPresent(
            reference -> {
              vault.destroy(reference);
              account.detachCredential();
            });

    recordAudit(account, AuditEventType.PROVIDER_CREDENTIAL_REMOVED, "REMOVED");
    return account;
  }

  public ProviderAccount disable(UUID accountId) {
    ProviderAccount account = require(accountId);
    account.disable();
    recordAudit(account, AuditEventType.PROVIDER_ACCOUNT_DISABLED, "DISABLED");
    return account;
  }

  /** Whether the vault currently holds readable material for this account. */
  @Transactional(readOnly = true)
  public boolean hasStoredCredential(ProviderAccount account) {
    return account.credentialReference().map(vault::hasActiveSecret).orElse(false);
  }

  /**
   * Audit metadata is the account, the provider and the action.
   *
   * <p>Never the credential, the ciphertext, the nonce, the wrapped key or any fingerprint of the
   * secret — a trail that lets someone reconstruct or recognise a credential is worse than no trail.
   */
  private void recordAudit(ProviderAccount account, AuditEventType type, String result) {
    audit.recordWithActor(
        null,
        account.getOwnerUserId(),
        type,
        "PROVIDER_ACCOUNT",
        account.getId().toString(),
        result,
        "provider=" + account.getProvider());
  }

  private void count(String metric) {
    Counter.builder(metric).register(meters).increment();
  }
}

package com.vibecode.vault.application;

import com.vibecode.audit.application.AuditService;
import com.vibecode.audit.domain.AuditEventType;
import com.vibecode.vault.application.SecretEncryptionService.EncryptedSecret;
import com.vibecode.vault.application.SecretEncryptionService.SecretContext;
import com.vibecode.vault.domain.SecretMaterial;
import com.vibecode.vault.domain.SecretPurpose;
import com.vibecode.vault.domain.SecretRecord;
import com.vibecode.vault.domain.SecretReference;
import com.vibecode.vault.domain.SecretStatus;
import com.vibecode.vault.domain.SecretVersion;
import com.vibecode.vault.domain.VaultCryptographyException;
import com.vibecode.vault.infrastructure.SecretRecordRepository;
import com.vibecode.vault.infrastructure.SecretVersionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way in or out of the vault.
 *
 * <p>Two rules give this class its shape.
 *
 * <p><b>Plaintext never leaves.</b> There is no {@code getSecret} and there must never be one. The
 * only read is {@link #withSecret}, which hands the material to a callback and clears it afterwards
 * — so the value cannot be returned up a call stack, stored in a field, or serialised by accident.
 * A method returning {@code String} would make every one of those a single careless line away.
 *
 * <p><b>Decryption happens in one place.</b> Callers never touch a cipher or a key. When this
 * becomes a real KMS, nothing outside this package changes.
 */
@Service
@Transactional
public class VaultService {

  private final SecretRecordRepository secrets;
  private final SecretVersionRepository versions;
  private final SecretEncryptionService encryption;
  private final AuditService audit;
  private final MeterRegistry meters;

  public VaultService(
      SecretRecordRepository secrets,
      SecretVersionRepository versions,
      SecretEncryptionService encryption,
      AuditService audit,
      MeterRegistry meters) {
    this.secrets = secrets;
    this.versions = versions;
    this.encryption = encryption;
    this.audit = audit;
    this.meters = meters;
  }

  /**
   * Encrypts and stores material, returning only a reference.
   *
   * <p>The material is closed here: the caller handed it over, and letting it live past this call
   * would be one more place a secret can be read from.
   */
  public SecretReference store(UUID ownerUserId, SecretPurpose purpose, SecretMaterial material) {
    try (material) {
      SecretRecord record = secrets.saveAndFlush(new SecretRecord(ownerUserId, purpose));
      SecretVersion first = persistVersion(record, material, 1);
      record.activateVersion(first.getId());
      count("vault.secret.store");
      return record.reference();
    }
  }

  /**
   * Replaces the material behind an existing reference.
   *
   * <p>Order matters and is the whole point. The replacement is encrypted, written and flushed
   * while the secret still points at the old version; only then does the pointer move. If
   * encryption or persistence fails anywhere before that, the previous version is still in force
   * and the account still has a working credential. Nothing is destroyed to make room for
   * something that has not been written yet.
   */
  public SecretReference rotate(SecretReference reference, SecretMaterial material) {
    try (material) {
      SecretRecord record = requireActive(reference.secretId());
      SecretVersion current = requireActiveVersion(record);

      SecretVersion replacement =
          persistVersion(record, material, current.getVersionNumber() + 1);

      // The switch: one column, one moment. Before it the old version is in force, after it the
      // new one is, and there is no state in between.
      record.activateVersion(replacement.getId());

      // Stepping the old version down is bookkeeping, and happens after the switch it describes.
      current.retire();
      versions.saveAndFlush(current);

      count("vault.secret.rotate");
      return new SecretReference(record.getId(), record.getPurpose());
    }
  }

  /**
   * Writes one encrypted version and returns it.
   *
   * <p>Flushed immediately, so the row is durable before anything is allowed to point at it and a
   * constraint violation surfaces here rather than at commit, where it would be far harder to
   * attribute.
   */
  private SecretVersion persistVersion(
      SecretRecord record, SecretMaterial material, int versionNumber) {
    EncryptedSecret encrypted =
        encryption.encrypt(
            material,
            new SecretContext(
                record.getId(), record.getOwnerUserId(), record.getPurpose(), versionNumber));

    return versions.saveAndFlush(
        new SecretVersion(
            record.getId(),
            versionNumber,
            encrypted.ciphertext(),
            encrypted.nonce(),
            encrypted.wrappedDataEncryptionKey(),
            encrypted.algorithm(),
            encrypted.formatVersion(),
            encrypted.keyProviderId(),
            encrypted.keyVersion()));
  }

  /**
   * Runs an operation with the decrypted material, then clears it.
   *
   * <p>The callback shape is the point: the material is valid only inside it. Returning it would let
   * a caller keep it, and the vault would have no way to know.
   *
   * <p>Not used by any request path in this phase — no provider is contacted yet. It exists so the
   * adapter that eventually does has exactly one door to come through.
   */
  @Transactional(readOnly = true)
  public <T> T withSecret(SecretReference reference, Function<SecretMaterial, T> operation) {
    SecretRecord record = requireActive(reference.secretId());
    SecretVersion version = requireActiveVersion(record);

    try (SecretMaterial material =
        encryption.decrypt(
            new EncryptedSecret(
                version.getCiphertext(),
                version.getNonce(),
                version.getWrappedDataEncryptionKey(),
                version.getAlgorithm(),
                version.getFormatVersion(),
                version.getKeyProvider(),
                version.getKeyVersion()),
            new SecretContext(
                record.getId(),
                record.getOwnerUserId(),
                record.getPurpose(),
                version.getVersionNumber()))) {
      return operation.apply(material);
    } catch (VaultCryptographyException failure) {
      count("vault.decrypt.failure");
      recordDecryptionFailure(record, version);
      throw failure;
    }
  }

  /**
   * Makes every version unreadable and marks the secret destroyed.
   *
   * <p>Destroying the wrapped data keys is what makes this real: without them the ciphertext cannot
   * be recovered even by someone holding the master key.
   *
   * <p>What this cannot claim: backups taken before now still contain the old rows. Deletion here
   * is deletion from the live database, and any retained backup keeps whatever it captured until it
   * expires under its own retention policy.
   */
  public void destroy(SecretReference reference) {
    SecretRecord record =
        secrets
            .findById(reference.secretId())
            .orElseThrow(() -> new VaultCryptographyException("No such secret"));

    // The pointer goes first: from this line on nothing considers any version to be in force,
    // even if clearing the material below were to fail partway through.
    record.destroy();
    secrets.flush();

    versions.findBySecretIdOrderByVersionNumberDesc(record.getId()).forEach(SecretVersion::destroy);
    versions.flush();

    count("vault.secret.remove");
  }

  @Transactional(readOnly = true)
  public boolean hasActiveSecret(SecretReference reference) {
    return secrets
        .findById(reference.secretId())
        .filter(record -> record.getStatus() == SecretStatus.ACTIVE)
        .map(SecretRecord::getActiveVersionId)
        .flatMap(versions::findById)
        .filter(SecretVersion::isActive)
        .isPresent();
  }

  @Transactional(readOnly = true)
  public List<SecretVersion> versionsOf(SecretReference reference) {
    return versions.findBySecretIdOrderByVersionNumberDesc(reference.secretId());
  }

  /**
   * A failed decryption is a security event, not a bug report.
   *
   * <p>It means a wrong key, a tampered row or a restored backup that no longer matches the master
   * key — so it is written to the trail rather than only counted. The row identifies which secret
   * version failed and nothing else: no ciphertext, no nonce, no wrapped key, no exception detail.
   * Recording any of those would put fragments of the material into the one table designed to be
   * read by humans.
   */
  private void recordDecryptionFailure(SecretRecord record, SecretVersion version) {
    audit.recordWithActor(
        null,
        record.getOwnerUserId(),
        AuditEventType.VAULT_DECRYPTION_FAILED,
        "VAULT_SECRET",
        record.getId().toString(),
        "FAILED",
        "version=" + version.getVersionNumber());
  }

  private SecretRecord requireActive(UUID secretId) {
    Optional<SecretRecord> found = secrets.findById(secretId);
    if (found.isEmpty() || found.get().getStatus() != SecretStatus.ACTIVE) {
      // Uniform: a missing secret and a destroyed one are the same answer to a caller.
      throw new VaultCryptographyException("No usable secret for that reference");
    }
    return found.get();
  }

  /** The version in force, according to the secret itself and nothing else. */
  private SecretVersion requireActiveVersion(SecretRecord record) {
    return Optional.ofNullable(record.getActiveVersionId())
        .flatMap(versions::findById)
        .filter(SecretVersion::isActive)
        .orElseThrow(() -> new VaultCryptographyException("No usable secret for that reference"));
  }

  private void count(String metric) {
    // No tags. A secret id or a user id here would put a high-cardinality identifier — and a map of
    // who holds credentials — into the metrics backend.
    Counter.builder(metric).register(meters).increment();
  }
}

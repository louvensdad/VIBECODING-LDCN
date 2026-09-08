package com.vibecode.context.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A finished, immutable snapshot of the context selected for one task.
 *
 * <p>A pack is a record of a decision that has already been made, not a workspace. It cannot be
 * appended to, its item list is unmodifiable, and it refuses to exist at all if it does not fit its
 * own budget — a snapshot that admits it is over budget would let the overrun travel downstream
 * unnoticed.
 *
 * <p><b>Ordering.</b> Items are sorted on construction by {@link ContextItem#CANONICAL_ORDER}, a
 * total order over source, kind, source id and item id. The caller's insertion order is discarded on
 * purpose: two callers that selected the same items must produce identical packs, and insertion
 * order is exactly the kind of incidental detail that varies between two runs that should agree. For
 * the same reason no set or map iteration decides anything here — duplicate detection uses a {@link
 * HashSet} for membership only, never for order.
 *
 * <p><b>Minimum necessary context.</b> This type has no "add everything available" path and will not
 * grow one. The budget bounds a dump; it does not licence one. More context is not better context —
 * an item that does not change what the work should do is noise that displaces something that would
 * have.
 *
 * <p>Nothing in a pack may be, contain or point at secret material. See {@link ContextItem}.
 *
 * @param packId the persistent identity of this snapshot, and the only one — a pack is found,
 *     referenced and stored by this UUID. {@link #contentFingerprint()} is a digest of what the pack
 *     says, not which pack it is, and must never be used as a key
 * @param projectId the project the context describes; every item's provenance must agree
 * @param taskReference the task the pack was assembled for — part of what "same inputs" means
 * @param assembledAt when the snapshot was taken
 * @param budget the ceiling this pack was held to
 * @param items the selected items in any order; the constructor sorts them into canonical order,
 *     so the order given here is not a caller obligation and has no effect on the result
 */
public record ContextPack(
    UUID packId,
    UUID projectId,
    String taskReference,
    Instant assembledAt,
    ContextBudget budget,
    List<ContextItem> items) {

  /**
   * The longest a task reference may be, in the same <b>UTF-16 code units</b> {@link
   * ContextItem#MAX_LABEL_LENGTH} uses. 500 because that is the width of
   * {@code context_packs.task_reference} in V9, and this constant exists so an over-long reference
   * is refused here rather than by an INSERT.
   *
   * <p><b>It is capped for the same reason a label is, and by the same mechanism.</b> A task
   * reference is user-influenced — V9 sizes the column for a caller composing {@code "TASK-42: "}
   * in front of a task title — and it goes through {@code ContextRedaction.redactTaskReference},
   * which can <em>lengthen</em> it: a one-character value replaced by {@code [REDACTED]} nets nine.
   * A 498-character reference ending in a secret-shaped assignment measured 507 after redaction and
   * failed at the INSERT, exactly as the label did.
   *
   * <p><b>{@code context_pack_items.explanation} is deliberately not capped, and that asymmetry is
   * the point.</b> Every explanation in the code today is a fixed literal — the rules in {@code
   * DefaultContextPolicyRules} and {@code ContextPolicy.DEFAULT_DENY_EXPLANATION}, the longest
   * around 290 characters — and redaction never touches one, so nothing in today's code can
   * lengthen an explanation or compose one out of user text. A guard would defend a route that is
   * not taken, and cost the next reader a search for a mechanism that is not there.
   *
   * <p><b>That is a fact about today's callers, not a property of the types, and the difference
   * matters here.</b> {@code ContextAdmission.allow} is a public factory over arbitrary prose and
   * {@code ContextPolicyRule} is a public interface, so a 600-character explanation constructs
   * cleanly and fails at the INSERT — demonstrated, not supposed. If you are writing a rule whose
   * explanation is built from anything but a literal — an item's label, a source id, a user's
   * text — you have created the route this paragraph says does not exist, and the cap belongs here
   * beside the other two rather than left to the column.
   *
   * <p>Nothing truncates. A shortened task reference is a pack claiming to be for a task nobody
   * asked about, and it is inside the fingerprint, so the trimmed form is what would be digested.
   */
  public static final int MAX_TASK_REFERENCE_LENGTH = 500;

  /**
   * Separates fields inside the fingerprint's canonical form. It is a readability aid only — the
   * length prefix in front of every field is what actually makes the encoding unambiguous, because
   * no separator character can be reserved from text this domain does not control.
   */
  private static final String FIELD_SEPARATOR = String.valueOf((char) 0x1F);

  public ContextPack {
    if (packId == null) {
      throw new IllegalArgumentException("A pack must have an id");
    }
    if (projectId == null) {
      throw new IllegalArgumentException("A pack must name its project");
    }
    if (taskReference == null || taskReference.isBlank()) {
      throw new IllegalArgumentException("A pack must name the task it was assembled for");
    }
    if (taskReference.length() > MAX_TASK_REFERENCE_LENGTH) {
      throw new IllegalArgumentException(
          "A pack's task reference may not exceed "
              + MAX_TASK_REFERENCE_LENGTH
              + " characters (UTF-16 code units); this one has "
              + taskReference.length()
              + ". A reference arriving over the cap after redaction is the expected cause:"
              + " replacing a short secret with a marker lengthens the string.");
    }
    if (assembledAt == null) {
      throw new IllegalArgumentException("A pack must record when it was assembled");
    }
    if (budget == null) {
      throw new IllegalArgumentException("A pack must declare the budget it was held to");
    }
    if (items == null) {
      throw new IllegalArgumentException("A pack must be given its items, even if none");
    }

    List<ContextItem> ordered = new ArrayList<>(items.size());
    Set<String> seenIds = new HashSet<>();
    for (ContextItem item : items) {
      if (item == null) {
        throw new IllegalArgumentException("A pack cannot hold a null item");
      }
      if (!item.provenance().projectId().equals(projectId)) {
        throw new IllegalArgumentException(
            "Item "
                + item.id()
                + " was drawn from project "
                + item.provenance().projectId()
                + ", which is not the project this pack describes");
      }
      if (!seenIds.add(item.id())) {
        throw new IllegalArgumentException(
            "Duplicate item id in pack: " + item.id() + " — item ids are the final ordering key");
      }
      ordered.add(item);
    }
    ordered.sort(ContextItem.CANONICAL_ORDER);
    items = List.copyOf(ordered);

    ContextUsage usage = measure(items);
    budget
        .firstBreach(usage)
        .ifPresent(
            breach -> {
              throw new IllegalArgumentException("Pack exceeds its budget — " + breach);
            });
  }

  private static ContextUsage measure(List<ContextItem> items) {
    ContextUsage usage = ContextUsage.EMPTY;
    for (ContextItem item : items) {
      usage = usage.plus(item);
    }
    return usage;
  }

  /** What this pack actually costs. Counted, not projected. */
  public ContextUsage usage() {
    return measure(items);
  }

  public int size() {
    return items.size();
  }

  public boolean isEmpty() {
    return items.isEmpty();
  }

  /**
   * A digest over the ordered content of this pack.
   *
   * <p>Its purpose is to make "the same inputs produced the same pack" a single comparison instead
   * of a walk over two lists.
   *
   * <p><b>Covered:</b> each item's id, kind, label, content, source type, source id and source
   * version, and the order they appear in. Label is included because it is displayed text, so two
   * packs differing only in a label are not the same pack to a reader.
   *
   * <p><b>Deliberately not covered:</b> the pack id, assembly time and budget, which differ between
   * two rebuilds that nonetheless carry the same context; {@code provenance.recordedAt}, because it
   * changes every time unchanged state is re-read and including it would defeat the one thing this
   * digest is for; and {@code provenance.projectId}, which the constructor has already forced equal
   * to the pack's own project for every item, so it can add no distinguishing information.
   *
   * <p>Every field is length-prefixed. A separator alone would not be enough: item content is text
   * this domain does not control, so any character used as a boundary can also appear inside a
   * field, and without the prefix a two-item pack could flatten to the same string as a one-item
   * pack whose content embeds the separator.
   *
   * <p>It is an equality check, not a security control: it proves nothing about who produced the
   * pack and must not be used as one.
   *
   * <p><b>It is not identity either, and persistence must not treat it as such.</b> This is a digest
   * of logical content: two packs assembled at different times from unchanged state share it by
   * design, and any change to what is covered changes every digest ever computed. It must not become
   * a primary key, a foreign key, or a unique business key, and a row must not be found by it.
   * Persistent identity is {@link #packId()}, a UUID, and stays that way. A digest that a compiler
   * step needs for caching or change detection belongs to that step, under its own name.
   */
  public String contentFingerprint() {
    StringBuilder canonical = new StringBuilder();
    for (ContextItem item : items) {
      ContextProvenance provenance = item.provenance();
      appendField(canonical, item.id());
      appendField(canonical, item.kind().name());
      appendField(canonical, item.label());
      appendField(canonical, item.content());
      appendField(canonical, provenance.sourceType().name());
      appendField(canonical, provenance.sourceId());
      appendField(canonical, provenance.sourceVersion().map(String::valueOf).orElse("-"));
    }
    return hex(canonical.toString());
  }

  /** Length first, so the field's own text cannot forge a boundary. */
  private static void appendField(StringBuilder canonical, String field) {
    canonical.append(field.length()).append(FIELD_SEPARATOR).append(field).append(FIELD_SEPARATOR);
  }

  private static String hex(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return out.toString();
    } catch (NoSuchAlgorithmException impossible) {
      // SHA-256 is required of every Java platform; this branch cannot be reached in practice.
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}

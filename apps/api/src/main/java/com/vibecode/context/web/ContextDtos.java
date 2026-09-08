package com.vibecode.context.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vibecode.context.domain.AdmittedContextItem;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.domain.ContextAdmission;
import com.vibecode.context.domain.ContextBudget;
import com.vibecode.context.domain.ContextItem;
import com.vibecode.context.domain.ContextKind;
import com.vibecode.context.domain.ContextPack;
import com.vibecode.context.domain.ContextProvenance;
import com.vibecode.context.domain.ContextSource;
import com.vibecode.context.domain.ContextSourceType;
import com.vibecode.context.domain.ContextUsage;
import com.vibecode.context.domain.EstimatedTokenCount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The shapes crossing the Context API boundary.
 *
 * <p>These are hand-written rather than the domain records serialised directly, and that is not
 * style. Four of the fields the agreed TypeScript contract asks for do not exist as record
 * components at all — {@code characterCount} and {@code byteCount} are methods on {@link
 * ContextItem}, {@code usage} and {@code contentFingerprint} are methods on {@link ContextPack} —
 * so a domain record returned from a controller would silently omit them. {@link
 * EstimatedTokenCount} is a plain class whose accessors are not {@code get}-prefixed, so a naive
 * serialisation would lose both the number and the caveat that makes it honest.
 *
 * <p><b>What is deliberately absent.</b> There is no field here for a candidate that policy
 * refused, for content as it stood before redaction, or for a policy trace. The compiler drops a
 * denied candidate without recording its text anywhere, so there is nothing to expose even if
 * someone wanted to — and adding a DTO field would be the first step towards making there be
 * something. Nothing here may ever carry a credential, a token or a vault reference: secrets are
 * references, never context.
 *
 * <p>Everything here describes a pack that has already been admitted and redacted. The API knows no
 * other kind.
 */
public final class ContextDtos {

  private ContextDtos() {}

  /**
   * The ceiling used when a caller names none.
   *
   * <p>It lives here, in the web layer, because the engine has no configured default: {@link
   * ContextBudget} declares no constant and nothing in {@code application.yml} sets one. Calling
   * this "the engine's default" would be a claim the code does not support, so it is named for what
   * it is — the API's default for a request that omitted a dimension.
   *
   * <p>The figures are deliberately modest. A budget is a ceiling, not a target, and the engine's
   * rule is minimum necessary context; a generous default would quietly turn "I did not specify"
   * into "send everything you have".
   */
  public static final ContextBudget DEFAULT_BUDGET = new ContextBudget(50, 200_000L, 400_000L);

  /**
   * The largest number of items a caller may ask for.
   *
   * <p>An unbounded ceiling is not a feature. A request for two billion characters cannot be a real
   * intention — nothing this engine reads is that large — so it is either a mistake or someone
   * measuring how much work one request can be made to do. Refusing it here makes it a field-level
   * 400 about the request rather than a 500 about the server.
   *
   * <p>This and its two siblings are not the engine's limits and must not be read as such. They are
   * the widest request this API is willing to accept.
   */
  public static final int MAX_REQUESTABLE_ITEMS = 1_000;

  /** The largest character ceiling a caller may ask for. See {@link #MAX_REQUESTABLE_ITEMS}. */
  public static final long MAX_REQUESTABLE_CHARACTERS = 5_000_000L;

  /** The largest byte ceiling a caller may ask for. See {@link #MAX_REQUESTABLE_ITEMS}. */
  public static final long MAX_REQUESTABLE_BYTES = 20_000_000L;

  /**
   * A caller-supplied ceiling, every dimension optional.
   *
   * <p>Separate from {@link ContextBudgetResponse} on purpose: the response states the budget a
   * finished pack was actually held to, every dimension resolved. One shape for both would let a
   * half-filled request read as a pack's real limits.
   *
   * <p>The boxed types are what make "omitted" expressible. A primitive {@code int} would arrive as
   * zero for an absent field, and zero is a value {@link ContextBudget} rejects — so the caller who
   * said nothing and the caller who asked for a budget of nothing would be indistinguishable, and
   * one of them would get an error about the other's request.
   */
  public record ContextBudgetRequest(
      @Min(value = 1, message = "maxItems must be at least 1")
          @Max(value = MAX_REQUESTABLE_ITEMS, message = "maxItems may not exceed 1000")
          Integer maxItems,
      @Min(value = 1, message = "maxCharacters must be at least 1")
          @Max(value = MAX_REQUESTABLE_CHARACTERS, message = "maxCharacters may not exceed 5000000")
          Long maxCharacters,
      @Min(value = 1, message = "maxBytes must be at least 1")
          @Max(value = MAX_REQUESTABLE_BYTES, message = "maxBytes may not exceed 20000000")
          Long maxBytes) {

    /**
     * The ceiling to compile against: whatever the caller named, and {@link #DEFAULT_BUDGET} for
     * whatever they did not.
     *
     * <p>Resolved dimension by dimension rather than all-or-nothing, so naming one limit does not
     * silently reset the other two to values the caller never saw.
     */
    ContextBudget resolve() {
      return new ContextBudget(
          maxItems == null ? DEFAULT_BUDGET.maxItems() : maxItems,
          maxCharacters == null ? DEFAULT_BUDGET.maxCharacters() : maxCharacters,
          maxBytes == null ? DEFAULT_BUDGET.maxBytes() : maxBytes);
    }
  }

  /**
   * Ask the engine to assemble a pack.
   *
   * <p>A request type and only that. What comes back is a {@link ContextPackResponse}, whose
   * measured figures, admissions and fingerprint no caller may set — there is no field here that
   * could reach any of them.
   *
   * <p>{@code taskReference} is capped at {@link ContextPack#MAX_TASK_REFERENCE_LENGTH} so an
   * obviously over-long value is a field-level 400. That cap does <em>not</em> make the domain's
   * check redundant: redaction runs after this validation and can make the string longer, so a
   * reference that is 498 characters on the wire can be 507 by the time a pack is built. The domain
   * refuses it there, and {@link ContextPackController} reports that refusal without pretending it
   * was caught here.
   */
  public record AssembleContextRequest(
      @NotBlank(message = "taskReference is required")
          @Size(
              max = ContextPack.MAX_TASK_REFERENCE_LENGTH,
              message = "taskReference may not exceed 500 characters")
          String taskReference,
      @Valid ContextBudgetRequest budget) {

    /** The ceiling to compile against, with the API's default standing in for an absent budget. */
    ContextBudget resolvedBudget() {
      return budget == null ? DEFAULT_BUDGET : budget.resolve();
    }
  }

  /**
   * The addressable origin of a context item: a type plus the identifier of the one record within
   * it.
   *
   * <p>The identifier matters as much as the type. "This came from a brain decision" is not
   * auditable; "this came from brain decision 7f3c…, version 4" can be looked up and disagreed
   * with. That is the difference between provenance and a label.
   */
  public record ContextSourceResponse(ContextSourceType type, String sourceId, Integer version) {

    static ContextSourceResponse from(ContextSource source) {
      // version() is the nullable component rather than sourceVersion(), which wraps it in an
      // Optional — an Optional would serialise as an object or vanish entirely, and the contract
      // asks for a number or null.
      return new ContextSourceResponse(source.type(), source.sourceId(), source.version());
    }
  }

  /**
   * Where one item came from, and when that was true.
   *
   * <p>An address, and only an address. Nothing here records why the item was admitted; that is
   * {@link ContextAdmissionReason}'s job and the two must not be collapsed.
   *
   * <p>{@code recordedAt} is when the underlying state was read, not when the pack was assembled —
   * which is what lets an inspector show a stale item as stale.
   */
  public record ContextProvenanceResponse(
      ContextSourceResponse source, UUID projectId, Instant recordedAt) {

    static ContextProvenanceResponse from(ContextProvenance provenance) {
      return new ContextProvenanceResponse(
          ContextSourceResponse.from(provenance.source()),
          provenance.projectId(),
          provenance.recordedAt());
    }
  }

  /**
   * Why one item is in the pack.
   *
   * <p>{@code policyRuleId} is the load-bearing half: a stable handle traces back to a named rule a
   * reader can look up and disagree with. The sentence alone would be a claim with nothing behind
   * it — which is how a collector's improvisation ends up reading like policy.
   *
   * <p>It says nothing about what was <em>not</em> admitted, and it must not be made to. The
   * compiler keeps no record of a refused candidate's text, so there is no half-answer available to
   * smuggle in here — which is the right shape, because a half-answer would look like the answer.
   */
  public record ContextAdmissionReason(String policyRuleId, String explanation) {

    static ContextAdmissionReason from(ContextAdmission admission) {
      return new ContextAdmissionReason(admission.policyRuleId(), admission.explanation());
    }
  }

  /**
   * One unit of context, with the record it came from and the rule that let it in attached.
   *
   * <p>Neither {@code provenance} nor {@code admission} is optional, and neither may be made so.
   * They answer different questions and one does not stand in for the other: provenance says where
   * from, admission says why.
   *
   * <p>The two counts are measured over {@code content} alone — {@code id} and {@code label} are
   * handles for the inspector, not payload. {@code characterCount} is in UTF-16 code units, the
   * unit the budget is measured against, so "😀" counts 2.
   */
  public record ContextItemResponse(
      String id,
      ContextKind kind,
      String label,
      String content,
      ContextProvenanceResponse provenance,
      ContextAdmissionReason admission,
      long characterCount,
      long byteCount) {

    /**
     * Built from an admitted item, never from a bare {@link ContextItem}.
     *
     * <p>The parameter type is what keeps {@code admission} honest: an item that reached a pack
     * without a decision behind it cannot be rendered here, because there is no overload that would
     * accept one.
     */
    static ContextItemResponse from(AdmittedContextItem admitted) {
      ContextItem item = admitted.item();
      return new ContextItemResponse(
          item.id(),
          item.kind(),
          item.label(),
          item.content(),
          ContextProvenanceResponse.from(item.provenance()),
          ContextAdmissionReason.from(admitted.admission()),
          item.characterCount(),
          item.byteCount());
    }
  }

  /**
   * The ceiling a pack was held to, in the dimensions that can be counted exactly.
   *
   * <p>There is deliberately no token dimension: a limit enforced against a heuristic is wrong by
   * an unknown amount while reading as authoritative.
   */
  public record ContextBudgetResponse(int maxItems, long maxCharacters, long maxBytes) {

    static ContextBudgetResponse from(ContextBudget budget) {
      return new ContextBudgetResponse(
          budget.maxItems(), budget.maxCharacters(), budget.maxBytes());
    }
  }

  /**
   * A token figure that is a guess, and says so in three separate ways.
   *
   * <p>This phase calls no provider and runs no tokenizer, so no real token count exists. The
   * number is still worth showing as a warning, so the caveat travels with it: {@code heuristic}
   * states how it was produced and {@code isExact} is {@code false}.
   *
   * <p>{@code isExact} carries an explicit {@code @JsonProperty} because Jackson's bean naming
   * strips an {@code is} prefix from boolean accessors, and the contract asks for a field named
   * {@code isExact} — a reader who saw {@code exact} would have to guess whether the two were the
   * same field.
   */
  public record EstimatedTokenCountResponse(
      long estimatedTokens, String heuristic, @JsonProperty("isExact") boolean isExact) {

    static EstimatedTokenCountResponse from(EstimatedTokenCount estimate) {
      // isExact() is read rather than written as a literal false. It is false today and the domain
      // says it always will be, but copying the value means this DTO cannot become the place where
      // the wire and the domain quietly disagree.
      return new EstimatedTokenCountResponse(
          estimate.estimatedTokens(), estimate.heuristic(), estimate.isExact());
    }
  }

  /**
   * What a pack actually costs.
   *
   * <p>Three measurements and one estimate, and the estimate is in a shape that cannot be mistaken
   * for the other three. A bare {@code estimatedTokens} number sitting beside {@code characters}
   * would read as the same kind of fact, which is exactly the confusion the engine refuses to
   * create.
   */
  public record ContextUsageResponse(
      int items, long characters, long bytes, EstimatedTokenCountResponse estimatedTokenCount) {

    static ContextUsageResponse from(ContextUsage usage) {
      return new ContextUsageResponse(
          usage.items(),
          usage.characters(),
          usage.bytes(),
          EstimatedTokenCountResponse.from(usage.estimatedTokens()));
    }
  }

  /**
   * A finished, immutable snapshot of the context selected for one task.
   *
   * <p>{@code items} arrives in the engine's canonical order and the array index <em>is</em> that
   * order; nothing here re-sorts, because two runs over the same inputs rendering identically is
   * the property the ordering exists for.
   *
   * <p>{@code contentFingerprint} is a content digest and never an identifier. Two packs assembled
   * at different times from unchanged state share it by design. Identity is {@code packId}.
   *
   * <p><b>The policy version is not on this type, and its absence is deliberate.</b> The agreed
   * contract has no field for it and this task may not change the contract. The version is still
   * recorded on the stored row and inside the compiler's digest, so nothing is lost that cannot be
   * recovered; if an inspector needs it on the wire, the contract is where that starts.
   */
  public record ContextPackResponse(
      UUID packId,
      UUID projectId,
      String taskReference,
      Instant assembledAt,
      ContextBudgetResponse budget,
      ContextUsageResponse usage,
      List<ContextItemResponse> items,
      String contentFingerprint) {

    /**
     * Built from a compiled pack, which is the only form that carries every item's admission.
     *
     * <p>{@code usage} and {@code contentFingerprint} come from {@link CompiledContextPack#pack()}
     * — the same {@link ContextPack} the digest was taken over — rather than being recomputed here.
     * A count assembled in a DTO would be this class's opinion of the pack's size, which is a
     * different claim from the pack's own.
     */
    static ContextPackResponse from(CompiledContextPack compiled) {
      ContextPack pack = compiled.pack();
      return new ContextPackResponse(
          compiled.packId(),
          compiled.projectId(),
          compiled.taskReference(),
          compiled.assembledAt(),
          ContextBudgetResponse.from(compiled.budget()),
          ContextUsageResponse.from(pack.usage()),
          compiled.admittedItems().stream().map(ContextItemResponse::from).toList(),
          pack.contentFingerprint());
    }
  }
}

package com.vibecode.context.web;

import com.vibecode.context.application.compiler.ContextPackAssembler;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.infrastructure.persistence.ContextPackEntity;
import com.vibecode.context.infrastructure.persistence.ContextPackRepository;
import com.vibecode.context.web.ContextDtos.AssembleContextRequest;
import com.vibecode.context.web.ContextDtos.ContextPackResponse;
import com.vibecode.project.application.ProjectService;
import com.vibecode.shared.domain.ResourceNotFoundException;
import com.vibecode.shared.web.ApiError;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The HTTP surface for context packs.
 *
 * <p><b>This API knows only admitted, redacted packs.</b> There is no endpoint that returns a
 * candidate before policy saw it, content before redaction rewrote it, an item policy refused, or a
 * trace carrying any of those — and there must not be one. The compiler drops a denied candidate
 * without recording its text anywhere, so such an endpoint could not be written today without first
 * building somewhere for the refused text to live, which is the leak the whole engine exists to
 * prevent. Adding one is not an extension of this controller; it is a reversal of it.
 *
 * <p><b>Ownership.</b> Every route starts with {@link ProjectService#requireReadable}, and a
 * project the caller may not see is reported as not found rather than as forbidden — a 403 would
 * confirm the id belongs to a real project, which is the fact an attacker probing UUIDs is trying
 * to learn. The repository's {@code findByIdAndProjectId} scopes a pack to its project but knows
 * nothing about users, so it is the second half of the check and never the whole of it: without the
 * project gate in front, a valid pack id would read out of another user's project quite happily.
 *
 * <p><b>Why this class talks to a repository directly.</b> The house pattern is a controller over an
 * application service, and there is no read service here to sit on. {@link ContextPackAssembler} is
 * the engine's write path and the engine is settled — adding a read service to
 * {@code context.application} was out of scope for this change. The cost is that the two GET routes
 * do their own authorization; it is paid in one place, at the top of each method, and both are
 * covered by tests that read as another user.
 *
 * <p><b>Errors.</b> No response from here carries SQL, a driver message or a stack trace. The
 * shared {@code ApiExceptionHandler} is the boundary: a missing project or pack is a 404, a value
 * the domain refuses — including a task reference that redaction lengthened past the cap — is a 422
 * carrying the domain's own sentence, and a path variable that is not a UUID is a 400.
 *
 * <p>An earlier version of this paragraph claimed the shared handler "already maps everything
 * thrown from here". It did not. {@code GET /context/compile} — the literal sub-path sitting beside
 * a UUID one, and the easiest URL in this feature to type by hand — produced a 500 and a stack
 * trace in the log, because a malformed path variable reached the last-resort handler. That mapping
 * now exists. The wider claim is not restored: it was the kind of sentence that stops the next
 * reader checking.
 *
 * <p>One residual is stated rather than implied. {@code ContextPackEntity.toCompiled} throws an
 * {@code IllegalStateException} naming every item id when a stored pack's row order is not the
 * canonical one, and that message reaches the client in a 422. It carries ids and no content, and
 * it is reachable only by editing rows behind the API — but it is an internal message on the wire,
 * so it is written down here rather than hidden behind a claim that nothing internal ever escapes.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/context")
public class ContextPackController {

  /**
   * How many packs the list route returns when the caller names no number.
   *
   * <p>A page and not a history. The alternative — every pack a project has ever produced — is
   * unbounded by design, because a pack is never replaced.
   */
  public static final int DEFAULT_LIST_LIMIT = 20;

  /**
   * The smallest page a caller may ask for.
   *
   * <p>Zero is not a smaller page, it is a different request — "tell me nothing" — and answering it
   * with an empty array would be indistinguishable from a project with no packs.
   */
  public static final int MIN_LIST_LIMIT = 1;

  /**
   * The most packs one request may ask for.
   *
   * <p>Not the engine's limit and not a statement about how many packs may exist — only the widest
   * page this API is willing to assemble in one response.
   */
  public static final int MAX_LIST_LIMIT = 100;

  /**
   * The only spelling of {@code limit} this route reads: an optional minus and one to ten ASCII
   * digits. Ten is chosen so that everything the pattern admits fits a {@code long} — see
   * {@link #resolveLimit(String)} for why that turns overflow into a range refusal rather than an
   * exception.
   */
  private static final Pattern DECIMAL = Pattern.compile("-?[0-9]{1,10}");

  private final ProjectService projects;
  private final ContextPackAssembler assembler;
  private final ContextPackRepository packs;

  public ContextPackController(
      ProjectService projects, ContextPackAssembler assembler, ContextPackRepository packs) {
    this.projects = projects;
    this.assembler = assembler;
    this.packs = packs;
  }

  /**
   * Compiles a pack for one task and stores it.
   *
   * <p>Returns 201: a pack is a new record with an id of its own, and a second call over unchanged
   * state produces a second pack rather than replacing the first. That is the engine's design — a
   * pack is a snapshot of a decision, and a snapshot that could be overwritten would stop being
   * evidence of what context looked like.
   *
   * <p>The caller sets the task reference and, optionally, the ceiling. Everything else about the
   * result — which items were admitted, under which rule, how large they are, the fingerprint — is
   * the engine's, and there is no request field that could reach any of it.
   *
   * <p>The ownership check here is redundant with the one the collectors perform, and is kept
   * anyway. The collectors' check is a property of classes this controller does not own; stating
   * the gate at the entry point means a collector that stopped checking would not silently make
   * this route readable.
   */
  @PostMapping("/compile")
  public ResponseEntity<ContextPackResponse> compile(
      @PathVariable UUID projectId, @Valid @RequestBody AssembleContextRequest request) {
    projects.requireReadable(projectId);

    CompiledContextPack compiled =
        assembler.assemble(projectId, request.taskReference(), request.resolvedBudget());
    return ResponseEntity.status(HttpStatus.CREATED).body(ContextPackResponse.from(compiled));
  }

  /**
   * One stored pack, rebuilt from its rows.
   *
   * <p>A pack that does not exist, one belonging to another project, and one belonging to another
   * user all produce the same 404 with the same body. The pairing matters as much as the id: a real
   * pack id under the wrong project is not found here, so a caller cannot use this route to learn
   * that some other project holds it.
   */
  @GetMapping("/{packId}")
  @Transactional(readOnly = true)
  public ContextPackResponse get(@PathVariable UUID projectId, @PathVariable UUID packId) {
    projects.requireReadable(projectId);

    ContextPackEntity entity =
        packs
            .findByIdAndProjectId(packId, projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Context pack not found: " + packId));
    return ContextPackResponse.from(entity.toCompiled());
  }

  /**
   * One bounded page of this project's packs, newest first.
   *
   * <p>The whole pack is returned rather than a summary because the agreed contract defines one
   * shape for a pack and no lighter one; inventing a second shape here would put the API and the
   * contract out of step in a way no test on either side would notice. A request parameter is not a
   * response type, though, so bounding <em>how many</em> of them come back needs no contract change
   * — and it needs doing. Packs are append-only, so an unbounded route grows for the life of a
   * project until the owner's own list request is the most expensive thing the API does. Forty
   * packs measured 130 KB over 42 statements before this bound existed.
   *
   * <p><b>Two queries, and deliberately not one.</b> The ids are paged first and the packs fetched
   * second. {@code ContextPackEntity.items} is {@code EAGER}, so selecting packs directly issues a
   * further statement per pack; and a {@code join fetch} cannot be paged in the database, so
   * combining the two would have Hibernate read every pack and discard most of them in memory.
   * Ids then a fetch join is the shape that bounds both the rows read and the statements issued.
   *
   * <p>The page order comes from the id query and is re-imposed here, because a fetch-joined query
   * returns roots in whatever order the joined rows arrive in. Nothing re-sorts by anything else:
   * "newest first" is the repository's ordering, three keys deep, and this method's job is to not
   * lose it.
   *
   * <p>An empty list is a real answer for a project with no packs, and is only ever reached after
   * the project gate has passed — for a project the caller may not see, the route answers 404
   * before it would have to choose between an empty list and a populated one. An empty list from an
   * unauthorized read would itself confirm the id was real. The limit does not change that: it is
   * applied after the gate, never instead of it.
   *
   * @param limit how many packs to return, newest first, as the caller literally spelled it. Out
   *     of range is refused rather than clamped — a caller who asked for a thousand and silently
   *     received a hundred would have no way to know the answer had been narrowed, and would read a
   *     partial list as a complete one. Absent is the one spelling that means "no number named" and
   *     is answered with {@link #DEFAULT_LIST_LIMIT}; every other spelling this route will not
   *     honour exactly is refused. See {@link #resolveLimit(String)} for the grammar and for why
   *     the parameter is a {@code String}.
   */
  @GetMapping
  @Transactional(readOnly = true)
  public List<ContextPackResponse> list(
      @PathVariable UUID projectId,
      @RequestParam(name = "limit", required = false) String limit) {
    // Ownership first, and before the limit is looked at. Under the old annotations the order was
    // the reverse and not by choice: parameter validation runs before the method body, so a caller
    // with no access to this project learned 400 from a bad limit and 404 from a good one. Neither
    // answer leaks anything on its own, but "the answer depends on the query string" is the shape an
    // oracle grows out of. The gate is now unconditional: an unreadable project is 404 for every
    // spelling of limit, valid or not.
    projects.requireReadable(projectId);

    int resolved = resolveLimit(limit);

    List<UUID> page =
        packs.findPackIdsByProjectNewestFirst(projectId, PageRequest.of(0, resolved));
    if (page.isEmpty()) {
      return List.of();
    }

    Map<UUID, ContextPackEntity> loaded =
        packs.findAllWithItemsByIdIn(page).stream()
            .collect(Collectors.toMap(ContextPackEntity::getId, entity -> entity));

    return page.stream()
        .map(loaded::get)
        .filter(Objects::nonNull)
        .map(ContextPackEntity::toCompiled)
        .map(ContextPackResponse::from)
        .toList();
  }

  /**
   * The caller's {@code limit} as an integer, or a refusal.
   *
   * <p><b>Why this parameter is a {@code String}.</b> Declared as an {@code int} with
   * {@code @Min}/{@code @Max}, one parameter answered in three contracts and one of them was a 200.
   * Spring's converter accepted spellings nobody enumerated — {@code 0x10} became sixteen,
   * {@code +7} became seven, {@code " 5 "} was trimmed to five, the fullwidth digit U+FF12 and the
   * Arabic-Indic U+0665 became two and five — and {@code ?limit=} fell through to the
   * declared {@code defaultValue}, serving a page of twenty to a caller whose variable had
   * interpolated to nothing. Meanwhile a violated {@code @Min}/{@code @Max} on a method parameter
   * raises {@code HandlerMethodValidationException}, which no handler claims, so an out-of-range
   * number rendered as a generic {@code BAD_REQUEST} with an empty {@code violations} array while
   * an unparseable one rendered as {@code VALIDATION_ERROR} with a populated one.
   *
   * <p>Taking the raw text and deciding here is what makes those one contract. It also keeps the
   * fix inside {@code context.web}: a handler for {@code HandlerMethodValidationException} in the
   * shared advice would change the error contract of every module in the application.
   *
   * <p><b>The grammar, chosen rather than inherited.</b> An optional {@code -} followed by one to
   * ten ASCII digits, and nothing else. In particular:
   *
   * <ul>
   *   <li>{@code Integer.decode} is deliberately not used: it reads {@code 0x10} as sixteen and a
   *       leading zero as octal. Neither is a spelling of "how many packs" that this API will
   *       silently honour.
   *   <li>{@code Integer.parseInt} is deliberately not used as the gate either. It accepts any
   *       character {@code Character.digit} recognises, so U+0665 parses as five — the
   *       pattern is what excludes non-ASCII digits, not the parser.
   *   <li>A leading zero is accepted and read as decimal: {@code 01} is one and {@code 020} is
   *       twenty, never sixteen. Rejecting it would refuse a zero-padded value that means exactly
   *       what it looks like.
   *   <li>No sign but {@code -}, no whitespace, no {@code .}, {@code e} or {@code _}. A repeated
   *       {@code ?limit=1&limit=2} arrives here as {@code "1,2"} and is refused rather than one of
   *       the two being picked for the caller.
   *   <li>Ten digits is past {@code Integer.MAX_VALUE} but well within a {@code long}, so
   *       {@code Long.parseLong} cannot overflow on anything the pattern admits and
   *       {@code 2147483648} is a range refusal rather than a parse failure. Anything longer is a
   *       format refusal, which is why a value longer than a {@code long} is not an exception.
   * </ul>
   *
   * <p>Absent — and only absent — means the caller named no number, and is answered with
   * {@link #DEFAULT_LIST_LIMIT}. An empty value is not absent: it is a caller who wrote the
   * parameter and got the value wrong. <b>No invalid spelling is ever converted into the
   * default</b>, which is the whole point: a wrong answer the caller cannot detect is worse than an
   * error.
   *
   * <p>Every refusal is one 400 with one {@code code}, differing only in the violation's sentence.
   * A caller must not have to know which kind of wrong their input was to parse the response.
   */
  static int resolveLimit(String limit) {
    if (limit == null) {
      return DEFAULT_LIST_LIMIT;
    }
    if (!DECIMAL.matcher(limit).matches()) {
      throw new InvalidLimitException(
          "limit must be a decimal integer between "
              + MIN_LIST_LIMIT
              + " and "
              + MAX_LIST_LIMIT);
    }
    long value = Long.parseLong(limit);
    if (value < MIN_LIST_LIMIT) {
      throw new InvalidLimitException("limit must be at least " + MIN_LIST_LIMIT);
    }
    if (value > MAX_LIST_LIMIT) {
      throw new InvalidLimitException("limit may not exceed " + MAX_LIST_LIMIT);
    }
    return (int) value;
  }

  /**
   * A {@code limit} this route will not honour.
   *
   * <p>Deliberately not an {@link IllegalArgumentException}: the shared advice maps that to 422
   * {@code INVALID_STATE}, which is the wrong status for a malformed query parameter and the wrong
   * body for this contract.
   */
  static final class InvalidLimitException extends RuntimeException {

    InvalidLimitException(String message) {
      super(message);
    }
  }

  /**
   * The one body every refused {@code limit} produces.
   *
   * <p>Scoped to this controller by {@code assignableTypes}, so it changes nothing for any other
   * module — which is the constraint this whole approach exists to respect. It is ordered ahead of
   * the shared advice because that advice's last-resort {@code @ExceptionHandler(Exception.class)}
   * would otherwise be free to claim {@link InvalidLimitException} first, depending on the order
   * two unordered advice beans happen to be discovered in. A contract that depends on bean
   * discovery order is not a contract.
   *
   * <p>The shape is the API's existing {@code VALIDATION_ERROR}: same {@code code}, same populated
   * {@code violations}, the field named. The sentence is the only thing that varies between one
   * refused spelling and another, and it is inside the violation where a client parses it, not in
   * the status and not in the {@code code}.
   */
  @RestControllerAdvice(assignableTypes = ContextPackController.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class InvalidLimitAdvice {

    @ExceptionHandler(InvalidLimitException.class)
    ResponseEntity<ApiError> invalidLimit(InvalidLimitException exception) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(
              ApiError.of(
                  HttpStatus.BAD_REQUEST.value(),
                  "VALIDATION_ERROR",
                  "The request could not be read.",
                  List.of(new ApiError.FieldViolation("limit", exception.getMessage()))));
    }
  }
}

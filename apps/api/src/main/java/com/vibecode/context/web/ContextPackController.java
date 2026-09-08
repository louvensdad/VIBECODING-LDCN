package com.vibecode.context.web;

import com.vibecode.context.application.compiler.ContextPackAssembler;
import com.vibecode.context.domain.CompiledContextPack;
import com.vibecode.context.infrastructure.persistence.ContextPackEntity;
import com.vibecode.context.infrastructure.persistence.ContextPackRepository;
import com.vibecode.context.web.ContextDtos.AssembleContextRequest;
import com.vibecode.context.web.ContextDtos.ContextPackResponse;
import com.vibecode.project.application.ProjectService;
import com.vibecode.shared.domain.ResourceNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 * <p>Errors carry no SQL, no exception class and no candidate text. The shared
 * {@code ApiExceptionHandler} is the boundary and already maps everything thrown from here: a
 * missing project or pack is a 404, and a value the domain refuses — including a task reference
 * that redaction lengthened past the cap — is a 422 carrying the domain's own sentence.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/context")
public class ContextPackController {

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
   * Every pack assembled for this project, newest first.
   *
   * <p>The whole pack is returned rather than a summary because the agreed contract defines one
   * shape for a pack and no lighter one; inventing a second shape here would put the API and the
   * contract out of step in a way no test on either side would notice.
   *
   * <p>An empty list is a real answer for a project with no packs, and is only ever reached after
   * the project gate has passed — for a project the caller may not see, the route answers 404
   * before it would have to choose between an empty list and a populated one. An empty list from an
   * unauthorized read would itself confirm the id was real.
   */
  @GetMapping
  @Transactional(readOnly = true)
  public List<ContextPackResponse> list(@PathVariable UUID projectId) {
    projects.requireReadable(projectId);

    return packs.findByProjectIdOrderByAssembledAtDesc(projectId).stream()
        .map(ContextPackEntity::toCompiled)
        .map(ContextPackResponse::from)
        .toList();
  }
}

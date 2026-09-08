package com.vibecode.identity.web;

import com.vibecode.identity.application.IdentityService;
import com.vibecode.identity.application.SecurityEventLogger;
import com.vibecode.identity.domain.CurrentUserProvider;
import com.vibecode.identity.domain.User;
import com.vibecode.identity.infrastructure.AuthenticatedUser;
import com.vibecode.identity.ratelimit.application.AuthenticationRateLimiter;
import com.vibecode.identity.ratelimit.domain.RateLimitScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registration, login, logout and "who am I". */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final IdentityService identity;
  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository contextRepository;
  private final SessionAuthenticationStrategy sessionStrategy;
  private final CurrentUserProvider currentUser;
  private final SecurityEventLogger securityEvents;
  private final AuthenticationRateLimiter rateLimiter;

  public AuthController(
      IdentityService identity,
      AuthenticationManager authenticationManager,
      SecurityContextRepository contextRepository,
      SessionAuthenticationStrategy sessionStrategy,
      CurrentUserProvider currentUser,
      SecurityEventLogger securityEvents,
      AuthenticationRateLimiter rateLimiter) {
    this.identity = identity;
    this.authenticationManager = authenticationManager;
    this.contextRepository = contextRepository;
    this.sessionStrategy = sessionStrategy;
    this.currentUser = currentUser;
    this.securityEvents = securityEvents;
    this.rateLimiter = rateLimiter;
  }

  @PostMapping("/register")
  public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
    // The origin limit already ran in the filter; this bounds repeated attempts against one
    // address, which is what account-spam and enumeration probing look like.
    rateLimiter.checkIdentifier(RateLimitScope.REGISTER_IDENTIFIER, request.email());
    User user =
        identity.register(request.email(), request.password(), request.displayName());
    return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
  }

  /**
   * Authenticates and establishes the session.
   *
   * <p>Every failure — unknown address, wrong password, disabled account — produces the same 401
   * and the same message. Anything more specific would let a caller test which addresses have
   * accounts.
   */
  @PostMapping("/login")
  public ResponseEntity<UserResponse> login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {

    // Before the password is checked, and therefore before any database lookup or bcrypt work.
    // The key is derived from what was typed, so an address with no account consumes an attempt
    // exactly like one that exists.
    rateLimiter.checkIdentifier(RateLimitScope.LOGIN_ACCOUNT, request.email());

    Authentication authentication;
    try {
      authentication =
          authenticationManager.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(
                  request.email(), request.password()));
    } catch (AuthenticationException failure) {
      securityEvents.loginFailed(request.email(), failure.getClass().getSimpleName());
      throw new InvalidCredentialsException();
    }

    // Rotates the session id before the authenticated context is stored, so a session id captured
    // before login is worthless afterwards.
    sessionStrategy.onAuthentication(authentication, httpRequest, httpResponse);

    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contextRepository.saveContext(context, httpRequest, httpResponse);

    AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
    principal.eraseCredentials();
    identity.recordLogin(principal.getId());
    // Clears this account's bucket only. The origin bucket is deliberately left alone: otherwise a
    // valid account could be used to keep resetting the volume allowance between guesses.
    rateLimiter.recordSuccessfulLogin(request.email());
    securityEvents.loginSucceeded(principal.getId(), principal.getUsername());

    return ResponseEntity.ok(UserResponse.from(identity.require(principal.getId())));
  }

  /**
   * Ends the session.
   *
   * <p>An unsafe method on purpose, so CSRF protection covers it: a forced logout is a real
   * nuisance attack.
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
    currentUser.current().ifPresent(user -> securityEvents.loggedOut(user.id()));
    HttpSession session = httpRequest.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().build();
  }

  /** The backend is the source of truth for "am I signed in". */
  @GetMapping("/me")
  public UserResponse me() {
    return UserResponse.from(identity.require(currentUser.require()));
  }

  /**
   * Hands the client a CSRF token.
   *
   * <p>The token also arrives as a readable cookie; this endpoint exists so a freshly loaded page
   * can obtain one deterministically before its first unsafe request.
   */
  @GetMapping("/csrf")
  public CsrfResponse csrf(@RequestAttribute(name = "_csrf") CsrfToken token) {
    return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
  }

  public record RegisterRequest(
      @NotBlank @Email @Size(max = 320) String email,
      @NotBlank @Size(max = 200) String password,
      @Size(max = 80) String displayName) {}

  public record LoginRequest(
      @NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 200) String password) {}

  public record CsrfResponse(String headerName, String parameterName, String token) {}

  /** Carries no hash, no session detail, no authorities beyond the platform role. */
  public record UserResponse(String id, String email, String displayName, String role) {

    static UserResponse from(User user) {
      return new UserResponse(
          user.getId().toString(),
          user.getEmail(),
          user.getDisplayName(),
          user.getRole().name());
    }
  }

  /** Mapped to 401 with a single, deliberately uninformative message. */
  public static class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
      super("Credenciais inválidas.");
    }
  }
}

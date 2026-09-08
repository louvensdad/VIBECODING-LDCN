package com.vibecode.identity.infrastructure;

import com.vibecode.identity.ratelimit.application.AuthenticationRateLimiter;
import com.vibecode.identity.ratelimit.infrastructure.ClientOriginResolver;
import com.vibecode.identity.ratelimit.web.OriginRateLimitFilter;
import com.vibecode.shared.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The request-level security policy: deny by default, session cookie, CSRF on.
 *
 * <p>This chain decides <em>whether a request is allowed in</em>. It never decides whether the
 * caller may touch a particular project — that is {@code ProjectAccessPolicy}, in the application
 * layer. A URL being authenticated says nothing about who owns the id inside it.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

  /**
   * Delegating encoder: bcrypt for new passwords, and the {@code {id}} prefix on every stored hash
   * means the algorithm can be upgraded later without invalidating existing accounts.
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
      throws Exception {
    return configuration.getAuthenticationManager();
  }

  @Bean
  public SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  /** Rotates the session id on login, which is what closes session fixation. */
  @Bean
  public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
    return new ChangeSessionIdAuthenticationStrategy();
  }

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      ObjectMapper objectMapper,
      SecurityContextRepository contextRepository,
      AuthenticationRateLimiter rateLimiter,
      ClientOriginResolver originResolver)
      throws Exception {

    http.csrf(
            csrf ->
                csrf.csrfTokenRepository(cookieCsrfTokenRepository())
                    // The default handler XOR-masks the token per request as BREACH protection,
                    // which a JavaScript client cannot reproduce from the cookie. This handler
                    // compares the raw value instead, and the null attribute name makes the token
                    // resolve eagerly so the cookie is written on every response.
                    .csrfTokenRequestHandler(rawTokenHandler()))
        .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
        // After CSRF, so a request without a token is still rejected as CSRF rather than being
        // counted; before anything that touches the database, so a client already over its limit
        // costs nothing to refuse.
        .addFilterAfter(
            new OriginRateLimitFilter(rateLimiter, originResolver, objectMapper),
            org.springframework.security.web.csrf.CsrfFilter.class)
        .sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(fixation -> fixation.changeSessionId()))
        // No CORS configuration at all: the browser reaches the API through the web app's own
        // origin, so a cross-origin policy would only widen what is reachable.
        .cors(cors -> cors.disable())
        .authorizeHttpRequests(
            authorize ->
                authorize
                    // Public by necessity: you cannot authenticate without them.
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/auth/register",
                        "/api/auth/login")
                    .permitAll()
                    // The client needs a token before it can make its first unsafe request.
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/auth/csrf")
                    .permitAll()
                    .requestMatchers(EndpointRequest.to("health"))
                    .permitAll()
                    // Every other actuator endpoint is closed, including to ordinary users.
                    .requestMatchers(EndpointRequest.toAnyEndpoint())
                    .denyAll()
                    // Deny by default: anything not named above needs a session.
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(new JsonAuthenticationEntryPoint(objectMapper))
                    .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper)))
        .securityContext(context -> context.securityContextRepository(contextRepository))
        // Both are browser-facing flows that would leak a session into a proxy prompt.
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable());

    return http.build();
  }

  private CookieCsrfTokenRepository cookieCsrfTokenRepository() {
    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    // Readable by JavaScript on purpose — the client has to echo it back in a header, which is
    // exactly what a cross-site attacker cannot do. SameSite=Lax keeps it off cross-site requests.
    repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").path("/"));
    return repository;
  }

  private CsrfTokenRequestAttributeHandler rawTokenHandler() {
    CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
    handler.setCsrfRequestAttributeName(null);
    return handler;
  }

  /** Forces the deferred CSRF token to be resolved, so the cookie is actually sent. */
  static final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
      if (token != null) {
        token.getToken();
      }
      chain.doFilter(request, response);
    }
  }

  /** 401 as JSON, in the same error shape as the rest of the API. */
  record JsonAuthenticationEntryPoint(ObjectMapper objectMapper)
      implements org.springframework.security.web.AuthenticationEntryPoint {

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        org.springframework.security.core.AuthenticationException exception)
        throws IOException {
      write(
          response,
          objectMapper,
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Autenticação é necessária.");
    }
  }

  /** 403 as JSON. Reached by a CSRF failure or a denied endpoint, never by project ownership. */
  record JsonAccessDeniedHandler(ObjectMapper objectMapper)
      implements org.springframework.security.web.access.AccessDeniedHandler {

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        org.springframework.security.access.AccessDeniedException exception)
        throws IOException {
      write(response, objectMapper, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Acesso negado.");
    }
  }

  private static void write(
      HttpServletResponse response,
      ObjectMapper objectMapper,
      HttpStatus status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getWriter(), ApiError.of(status.value(), code, message));
  }
}

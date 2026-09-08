package com.vibecode.identity.ratelimit.web;

import com.vibecode.identity.ratelimit.application.AuthenticationRateLimiter;
import com.vibecode.identity.ratelimit.domain.RateLimitExceededException;
import com.vibecode.identity.ratelimit.domain.RateLimitScope;
import com.vibecode.identity.ratelimit.infrastructure.ClientOriginResolver;
import com.vibecode.shared.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stops volume from one client before the request reaches anything expensive.
 *
 * <p>Placed early in the security chain on purpose: a client that is already over its limit is
 * refused without a database lookup and without a password hash being computed, which is what makes
 * the limiter useful rather than merely present.
 *
 * <p>Only the origin dimension is checked here. The identifier lives in the request body, and
 * reading the body in a filter would mean buffering it for every request; that check happens in the
 * controller instead — still before authentication, so still before any hashing.
 */
public class OriginRateLimitFilter extends OncePerRequestFilter {

  private static final String LOGIN_PATH = "/api/auth/login";
  private static final String REGISTER_PATH = "/api/auth/register";

  private final AuthenticationRateLimiter rateLimiter;
  private final ClientOriginResolver originResolver;
  private final ObjectMapper objectMapper;

  public OriginRateLimitFilter(
      AuthenticationRateLimiter rateLimiter,
      ClientOriginResolver originResolver,
      ObjectMapper objectMapper) {
    this.rateLimiter = rateLimiter;
    this.originResolver = originResolver;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!HttpMethod.POST.matches(request.getMethod())) {
      return true;
    }
    String path = pathOf(request);
    return !(LOGIN_PATH.equals(path) || REGISTER_PATH.equals(path));
  }

  /**
   * The request path, independent of servlet mapping.
   *
   * <p>{@code getServletPath()} is empty under some containers and test harnesses, and shifts with
   * the servlet mapping — a filter that silently stops matching is a limiter that silently stops
   * limiting. The request URI minus the context path is the same value everywhere.
   */
  private String pathOf(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null) {
      return "";
    }
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
      uri = uri.substring(contextPath.length());
    }
    return uri;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    RateLimitScope scope =
        LOGIN_PATH.equals(pathOf(request))
            ? RateLimitScope.LOGIN_ORIGIN
            : RateLimitScope.REGISTER_ORIGIN;

    try {
      rateLimiter.checkOrigin(scope, originResolver.resolve(request));
    } catch (RateLimitExceededException refused) {
      // Written here rather than thrown onward: the exception handler sits behind the dispatcher,
      // and the whole point of this filter is to answer before that.
      writeTooManyRequests(response);
      return;
    }

    chain.doFilter(request, response);
  }

  private void writeTooManyRequests(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getWriter(),
        ApiError.of(
            HttpStatus.TOO_MANY_REQUESTS.value(),
            "TOO_MANY_REQUESTS",
            "Muitas tentativas. Tente novamente mais tarde."));
  }
}

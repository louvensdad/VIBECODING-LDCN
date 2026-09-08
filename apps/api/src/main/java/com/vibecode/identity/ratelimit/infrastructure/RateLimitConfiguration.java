package com.vibecode.identity.ratelimit.infrastructure;

import com.vibecode.identity.ratelimit.domain.RateLimitStore;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthRateLimitProperties.class)
public class RateLimitConfiguration {

  /**
   * A single clock for the whole application.
   *
   * <p>Injected rather than read from {@code Instant.now()} at each call site, so a window test can
   * move time forward instead of sleeping through it.
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public RateLimitStore rateLimitStore(AuthRateLimitProperties properties) {
    return new InMemoryRateLimitStore(properties.getMaxTrackedKeys());
  }
}

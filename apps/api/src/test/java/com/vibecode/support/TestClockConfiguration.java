package com.vibecode.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application clock with one the tests control.
 *
 * <p>A plain {@code @Configuration} rather than {@code @TestConfiguration}: the latter is excluded
 * from component scanning and would have to be imported by every test. This one lives under the
 * scanned package, so the whole suite shares it, and only the tests that call {@link
 * MutableClock#advance} notice any difference.
 *
 * <p>One bean, not two. {@link MutableClock} is itself a {@code Clock}, so exposing it a second
 * time under another name would leave two primary candidates and no way to choose.
 */
@Configuration
public class TestClockConfiguration {

  @Bean
  @Primary
  public MutableClock mutableClock() {
    return new MutableClock();
  }
}

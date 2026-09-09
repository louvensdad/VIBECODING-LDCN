package com.vibecode.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vibecode.identity.domain.User;
import com.vibecode.project.application.ProjectService;
import com.vibecode.shared.logging.LogCapture;
import com.vibecode.support.TestIdentity;
import com.vibecode.support.logging.LoggerLevelIsolation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Review finding R4-A: the one defect the fix for R3-A introduced, and the guard against it.
 *
 * <p>Deriving the negotiation decision from the converters is what made the check correct. It also
 * put third-party code on the request path <b>inside an {@code @ExceptionHandler}</b> — the one
 * place Spring will not re-dispatch. Before that change no converter code ran there at all: the
 * writable-type list was derived once, in the constructor, and a converter that misbehaved could
 * only break startup, loudly. Afterwards, a converter whose {@code canWrite} throws would throw out
 * of the handler and escape the resolver, which is exactly the failure this class exists to
 * prevent, reintroduced by the fix for it. Review demonstrated it rather than describing it:
 *
 * <pre>
 *   Accept: application/json;mode=hostile
 *     -&gt; ServletException: … InvalidLimitException: limit must be at least 1
 * </pre>
 *
 * <p>No converter Spring or Boot ships can reach this — they are pure predicates — so it is
 * theoretical today, and it is guarded anyway. {@code callerAcceptsOurRepresentation} already caught
 * the other way that method can throw, two lines above, when the {@code Accept} header will not
 * parse. Catching one and not the other is not a judgement about likelihood; it is an
 * inconsistency, and an inconsistency is what the next person reads as permission.
 *
 * <p><b>Both halves are asserted, because the safe answer is not the silent one.</b> The caller gets
 * the route's real status with no body — the same answer as for an unparseable header. The operator
 * gets one ERROR carrying the throwable, because a converter that throws is a fault in this
 * application and this class's entire rule is that a fault of ours keeps its trace while a caller's
 * mistake does not. A catch that swallowed this would hide a broken converter behind correct-looking
 * 400s forever, which is the failure mode one level down from the one being fixed.
 *
 * <p>The probe converter is scoped to this test's own context by {@code @TestConfiguration}, and it
 * detonates only on a media-type parameter no other test sends. In particular {@code
 * canWrite(clazz, null)} answers false quietly, so the responder's constructor-time derivation is
 * untouched and the context starts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(LoggerLevelIsolation.class)
class HostileConverterEscapeTest {

  /**
   * The header that arms the probe at the responder's call site and nowhere else.
   *
   * <p>A wildcard media <em>range</em> carrying the parameter, and that is not incidental. The
   * responder asks the converters about each acceptable type as the caller wrote it, so it asks
   * with {@code application/*;mode=hostile} and the probe detonates. Spring asks about the type its
   * own selection produced, and {@code getMostSpecificMediaType} discards a wildcard range in
   * favour of the concrete producible type — so Spring asks with a plain {@code application/json},
   * the probe is quiet, and Jackson answers.
   *
   * <p>That is what makes this a measurement of the guard rather than of the framework: the two
   * call sites are given different media types by Spring's own rules, so the escape can only come
   * from ours. See {@link #theGuaranteeStopsWhereSpringsOwnConverterLoopBegins} for the case where
   * they are given the same one, which is a different fact about a different loop.
   */
  private static final String ARMED = "application/*;mode=hostile";

  @Autowired MockMvc mvc;
  @Autowired TestIdentity identity;
  @Autowired ProjectService projects;

  private User caller;
  private UUID project;
  private Logger root;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void signIn() {
    caller = identity.createAndAuthenticate("hostile-converter-caller");
    project = projects.create("Hostile", "A project", "Ship a tool").getId();
    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    captured = new ListAppender<>();
    captured.start();
    root.addAppender(captured);
    root.setLevel(Level.TRACE);
  }

  @AfterEach
  void stopListening() {
    root.detachAppender(captured);
    captured.stop();
  }

  @Test
  @DisplayName("R4-A: a converter that throws from canWrite cannot escape the exception handler")
  void aThrowingConverterCostsTheBodyAndNotTheResponse() {
    // Both advices, because the guard lives in the boundary they share and a fix that only held for
    // one of them would be the CTX-09B-3b shape all over again.
    Map<String, String> paths = new LinkedHashMap<>();
    paths.put("400 refused limit (InvalidLimitAdvice)", "/api/projects/" + project + "/context?limit=0");
    paths.put("404 unknown project (ApiExceptionHandler)", "/api/projects/" + UUID.randomUUID());

    int linesBefore = captured.list.size();
    Map<String, String> answers = new LinkedHashMap<>();
    for (Map.Entry<String, String> path : paths.entrySet()) {
      answers.put(path.getKey(), outcomeOf(path.getValue()));
    }

    assertThat(answers)
        .as(
            "R4-A: before the catch these escaped the resolver as a ServletException, which on a"
                + " real container is a 500 for a client error — the defect this whole task closed,"
                + " reintroduced by the mechanism that closed it")
        .containsExactly(
            Map.entry("400 refused limit (InvalidLimitAdvice)", "400 <empty body>"),
            Map.entry("404 unknown project (ApiExceptionHandler)", "404 <empty body>"));

    // The other half: the broken converter is not swallowed. One ERROR per request, with the
    // throwable, from the boundary itself.
    List<String> reported =
        captured.list.stream()
            .skip(linesBefore)
            .filter(event -> event.getLevel() == Level.ERROR)
            .filter(event -> event.getThrowableProxy() != null)
            .map(LogCapture::lineOf)
            .filter(line -> line.contains("A message converter failed"))
            .toList();
    assertThat(reported)
        .as(
            "a fault in this application keeps its trace; a catch that hid this would leave a"
                + " broken converter behind correct-looking 400s forever")
        .hasSize(2)
        .allSatisfy(line -> assertThat(line).contains("HostileConverterException"));

    // And the control that makes the two assertions above mean something: with the parameter
    // absent, the same routes on the same context carry their whole documented body. If the probe
    // were inert, or the routes broken, both halves would agree for the wrong reason.
    MvcResult healthy =
        perform("/api/projects/" + project + "/context?limit=0", MediaType.APPLICATION_JSON_VALUE);
    assertThat(healthy.getResponse().getStatus()).isEqualTo(400);
    assertThat(bodyOf(healthy)).contains("\"code\":\"VALIDATION_ERROR\"").contains("\"field\":\"limit\"");
  }

  /**
   * The probe fires at all, asserted separately.
   *
   * <p>Without this the test above would pass identically against a converter Spring never
   * consults — an empty {@code getSupportedMediaTypes}, a bean that was not picked up, a parameter
   * Spring strips before the call. "The escape did not happen" is only evidence if the thing that
   * would have caused it was reached.
   */
  @Test
  @DisplayName("The probe is armed: the hostile converter really is asked, and really does throw")
  void theProbeIsNotInert() {
    HostileConverter probe = new HostileConverter();
    assertThat(probe.canWrite(ApiError.class, null))
        .as("quiet for the constructor-time derivation, or the context would not start")
        .isFalse();
    assertThat(probe.canWrite(ApiError.class, MediaType.APPLICATION_JSON))
        .as("quiet for every media type this suite otherwise sends")
        .isFalse();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> probe.canWrite(ApiError.class, MediaType.parseMediaType(ARMED)))
        .isInstanceOf(HostileConverter.HostileConverterException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> probe.canWrite(ApiError.class, MediaType.parseMediaType("application/json;mode=hostile")))
        .isInstanceOf(HostileConverter.HostileConverterException.class);

    // And it is wired into the context this test runs in, not merely instantiable.
    assertThat(converters.stream().anyMatch(HostileConverter.class::isInstance))
        .as("a probe that is not in the converter list proves nothing about the request path")
        .isTrue();
  }

  @Autowired List<HttpMessageConverter<?>> converters;

  private String outcomeOf(String path) {
    return outcomeOf(path, ARMED);
  }

  private String outcomeOf(String path, String accept) {
    try {
      MvcResult result = perform(path, accept);
      String body = bodyOf(result);
      return result.getResponse().getStatus() + (body.isEmpty() ? " <empty body>" : " <body>");
    } catch (Exception escaped) {
      // The failure being measured is an exception escaping the dispatcher, so the measurement has
      // to survive one rather than abort on it.
      return "escaped:" + escaped.getClass().getSimpleName();
    }
  }

  private MvcResult perform(String path, String accept) {
    try {
      return mvc.perform(
              get(java.net.URI.create(path))
                  .with(TestIdentity.as(caller))
                  .header(HttpHeaders.ACCEPT, accept))
          .andReturn();
    } catch (RuntimeException runtime) {
      throw runtime;
    } catch (Exception checked) {
      throw new IllegalStateException(checked);
    }
  }

  private static String bodyOf(MvcResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (Exception unreadable) {
      throw new IllegalStateException(unreadable);
    }
  }

  /**
   * Where the guarantee stops, measured rather than assumed.
   *
   * <p>{@code Accept: application/json;mode=hostile} names a concrete type, so Spring's own
   * selection hands the <em>same</em> media type to its write loop that the responder was given.
   * The responder's catch does its job — the ERROR below is written and it returns "no body" — and
   * then Spring iterates the converters itself, to write a {@code ResponseEntity} whose body is
   * already null, and the probe throws again from inside {@code writeWithMessageConverters}. That
   * second throw is not on any line this application owns.
   *
   * <p>It is asserted rather than left as a sentence, because a claim with no assertion under it is
   * the thing this whole task has been about. What it pins is the shape of the boundary, not an
   * endorsement of it: <b>a converter that throws from {@code canWrite} breaks Spring's write path
   * for every response, not only for error bodies</b> — it violates the {@code HttpMessageConverter}
   * contract, which is a predicate. R4-A is about not being the <em>first</em> place that throws,
   * because that place is inside an {@code @ExceptionHandler} where Spring will not re-dispatch;
   * making a broken converter harmless everywhere is not something a negotiation helper can do, and
   * a class that claimed otherwise would be overstating its reach.
   *
   * <p>The operational half still holds here, and it is the part that matters if this ever happens:
   * the ERROR naming the failing converter is written before anything else goes wrong, so an
   * operator finds the broken converter rather than a mystery.
   *
   * <p>Boot's {@code HttpMessageConverters} places an unmatched custom converter at the front of the
   * list, which is why the probe is reached at all here. A converter registered behind Jackson would
   * never be asked.
   */
  @Test
  @DisplayName("The guarantee stops where Spring's own converter loop begins, and says so")
  void theGuaranteeStopsWhereSpringsOwnConverterLoopBegins() {
    int linesBefore = captured.list.size();
    String outcome = outcomeOf("/api/projects/" + UUID.randomUUID(), "application/json;mode=hostile");

    assertThat(outcome)
        .as(
            "not a defect in the responder: its catch ran and it returned no body. This is Spring"
                + " iterating a converter that violates the HttpMessageConverter contract, on a"
                + " response whose body is already null.")
        .startsWith("escaped:");

    assertThat(
            captured.list.stream()
                .skip(linesBefore)
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getThrowableProxy() != null)
                .map(LogCapture::lineOf)
                .filter(line -> line.contains("A message converter failed"))
                .toList())
        .as("the boundary still names the broken converter first, so an operator is not left guessing")
        .hasSize(1);
  }

  @TestConfiguration
  static class HostileConverterConfiguration {

    /**
     * Ordered last so that Spring's own write loop reaches a working converter first.
     *
     * <p>Deliberate, and it is what keeps this test about the responder. Spring's write path also
     * iterates the converters, so a probe sitting ahead of Jackson would throw from Spring's loop
     * as well and the test could not tell which of the two escapes it had measured. Last in the
     * list, Jackson answers Spring first; the responder still asks every converter, so the probe is
     * still reached from the place under test.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    HttpMessageConverter<ApiError> hostileConverter() {
      return new HostileConverter();
    }
  }

  /** A converter that is a pure predicate for everything except one media-type parameter. */
  static class HostileConverter implements HttpMessageConverter<ApiError> {

    static class HostileConverterException extends RuntimeException {
      HostileConverterException() {
        super("this converter throws from canWrite");
      }
    }

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
      return false;
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
      if (mediaType != null && "hostile".equals(mediaType.getParameter("mode"))) {
        throw new HostileConverterException();
      }
      return false;
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
      return new ArrayList<>();
    }

    @Override
    public ApiError read(Class<? extends ApiError> clazz, HttpInputMessage inputMessage) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void write(ApiError apiError, MediaType contentType, HttpOutputMessage outputMessage) {
      throw new UnsupportedOperationException();
    }
  }
}

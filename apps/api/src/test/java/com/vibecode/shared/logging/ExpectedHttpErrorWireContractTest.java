package com.vibecode.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The one claim in this task that MockMvc cannot settle: what a real container puts on the wire.
 *
 * <p>Everything else here is measured through MockMvc, which is the right tool for counting log
 * events. It is the wrong tool for this one question. When an exception escapes the dispatcher,
 * MockMvc rethrows it out of {@code perform} and the test sees a {@code ServletException}; a real
 * servlet container sees the same escape and turns it into an error page. Those are not the same
 * observation, and the difference is the difference between "noisy" and "a client-chosen header
 * changes the status this API reports".
 *
 * <p>It was worth the Tomcat. Before the fix, on this exact request:
 *
 * <pre>
 *   GET /api/projects/&lt;unknown id&gt;   Accept: application/json  ->  404 + the NOT_FOUND body
 *   GET /api/projects/&lt;unknown id&gt;   Accept: application/xml   ->  500, no body
 * </pre>
 *
 * <p>The 404 handler ran, built its {@code ApiError}, could not serialise it into a type the caller
 * accepts, and Spring gave up on the response entirely — so the caller was told the server had
 * failed. Measured at the parent commit as well as at the first version of this change, so it is
 * pre-existing rather than a regression, and it is what this test now prevents from returning.
 *
 * <p>One Tomcat start is what this costs, and it buys the only assertion in the suite made against
 * a real HTTP client, a real socket and a real error-page pipeline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExpectedHttpErrorWireContractTest {

  @LocalServerPort int port;

  private HttpClient client;
  private String csrfHeader;
  private String csrfToken;

  @BeforeEach
  void signInOverRealHttp() throws Exception {
    client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    refreshCsrf();

    String email = "wire-contract-" + UUID.randomUUID() + "@example.com";
    String password = "correct horse battery staple";
    post(
        "/api/auth/register",
        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"displayName\":\"Wire\"}");
    refreshCsrf();
    post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    refreshCsrf();
  }

  @Test
  @DisplayName("An Accept header the API cannot satisfy does not turn a 404 into a 500 on the wire")
  void theAcceptHeaderDoesNotChangeTheStatus() throws Exception {
    String unknownProject = "/api/projects/" + UUID.randomUUID();

    HttpResponse<String> json = get(unknownProject, "application/json");
    HttpResponse<String> xml = get(unknownProject, "application/xml");

    assertThat(json.statusCode()).isEqualTo(404);
    assertThat(json.body()).contains("\"code\":\"NOT_FOUND\"");

    assertThat(xml.statusCode())
        .as(
            "before this change the same request answered 500 with no body: a header the caller"
                + " chooses was deciding whether this API reported a client error or a server fault")
        .isEqualTo(404);
    assertThat(xml.body())
        .as("there is no representation this caller would accept, so there is no body to send")
        .isEmpty();

    // R2-A on a real socket rather than through MockMvc, because review measured that finding
    // through MockMvc and said so. application/problem+json is a representation Jackson writes, so
    // this caller is owed the whole body and got it before this task; a check comparing against the
    // single literal application/json had stopped sending it.
    HttpResponse<String> problemJson = get(unknownProject, "application/problem+json");
    assertThat(problemJson.statusCode()).isEqualTo(404);
    assertThat(withoutTimestamp(problemJson.body()))
        .as("a +json caller accepts something we can write and must receive it in full")
        .isEqualTo(withoutTimestamp(json.body()));

    // The control that makes the assertion above mean something. If the route were simply broken,
    // or the id were somehow valid, both halves would agree for reasons that have nothing to do
    // with content negotiation. A real project answers 200 to the same client in the same session.
    String created =
        post(
                "/api/projects",
                "{\"name\":\"Wire\",\"description\":\"\",\"originalIdea\":\"Ship a tool\"}")
            .body();
    String id = created.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    assertThat(get("/api/projects/" + id, "application/json").statusCode()).isEqualTo(200);
  }

  /**
   * The same question asked of the one route that was still answering it wrongly, on the same
   * Tomcat.
   *
   * <p>FINDING CTX-09B-3b. {@code ContextPackController.InvalidLimitAdvice} gives {@code limit} its
   * single 400 contract, and it built that response with a body of its own rather than through the
   * boundary the test above exists to protect — because that boundary was a private method on
   * {@code ApiExceptionHandler} and a second advice could not reach it. So the fix above covered
   * every error path in the application except this one, and on this one the identical measurement
   * came back:
   *
   * <pre>
   *   GET /api/projects/&lt;id&gt;/context?limit=0   Accept: application/json  -&gt;  400 + VALIDATION_ERROR
   *   GET /api/projects/&lt;id&gt;/context?limit=0   Accept: application/xml   -&gt;  500, no body
   * </pre>
   *
   * <p>This costs no extra container — it is the Tomcat already running for the test above — and it
   * is the only place the defect can be observed as what it actually is. Through MockMvc the escape
   * surfaces as a {@code ServletException} out of {@code perform}, which is a different observation:
   * it says the dispatcher gave up, not that a caller was told the server had failed. The
   * difference between those two sentences is the whole reason this class exists.
   *
   * <p>The control is the same shape as above and is what makes the 400 mean something: the same
   * request under {@code application/json} carries the documented body, and a <em>valid</em> limit
   * on the same route answers 200 to the same client in the same session. Without the last one, a
   * route that refused everything would satisfy the assertion.
   */
  @Test
  @DisplayName("A refused limit is a 400 on the wire under an Accept header naming no +json type")
  void aRefusedLimitKeepsItsStatusOnTheWire() throws Exception {
    String project =
        post(
                "/api/projects",
                "{\"name\":\"Wire limit\",\"description\":\"\",\"originalIdea\":\"Ship a tool\"}")
            .body();
    String id = project.replaceAll(".*\"id\"\s*:\s*\"([^\"]+)\".*", "$1");
    String refused = "/api/projects/" + id + "/context?limit=0";

    HttpResponse<String> json = get(refused, "application/json");
    assertThat(json.statusCode()).isEqualTo(400);
    assertThat(json.body()).contains("\"code\":\"VALIDATION_ERROR\"").contains("\"field\":\"limit\"");

    HttpResponse<String> xml = get(refused, "application/xml");
    assertThat(xml.statusCode())
        .as(
            "FINDING CTX-09B-3b: this exact request answered 500 with no body before the advice was"
                + " routed through the shared boundary. An Accept header the caller chose must not"
                + " cost this route the 400 it decided on.")
        .isEqualTo(400);
    assertThat(xml.body())
        .as("there is no representation this caller would accept, so there is no body to send")
        .isEmpty();

    // A +json caller is owed the whole body, on this route as on every other.
    HttpResponse<String> problemJson = get(refused, "application/problem+json");
    assertThat(problemJson.statusCode()).isEqualTo(400);
    assertThat(withoutTimestamp(problemJson.body()))
        .isEqualTo(withoutTimestamp(json.body()));

    // The control. If the route simply refused everything, every assertion above would hold for
    // reasons that have nothing to do with content negotiation.
    assertThat(get("/api/projects/" + id + "/context?limit=20", "application/json").statusCode())
        .as("a limit this route honours still answers 200 to the same client")
        .isEqualTo(200);
  }

  /** The one field two identical error bodies legitimately disagree on. */
  private static String withoutTimestamp(String body) {
    return body.replaceAll("\"timestamp\":\"[^\"]+\"", "\"timestamp\":\"<t>\"");
  }

  private void refreshCsrf() throws Exception {
    String body = get("/api/auth/csrf", "application/json").body();
    csrfToken = body.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    csrfHeader = body.replaceAll(".*\"headerName\"\\s*:\\s*\"([^\"]+)\".*", "$1");
  }

  private HttpResponse<String> get(String path, String accept) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base() + path)).header("Accept", accept).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header(csrfHeader, csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String base() {
    return "http://localhost:" + port;
  }
}

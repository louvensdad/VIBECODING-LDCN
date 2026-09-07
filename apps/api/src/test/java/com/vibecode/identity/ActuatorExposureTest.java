package com.vibecode.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vibecode.identity.domain.User;
import com.vibecode.support.TestIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The operational endpoints give away as little as possible. */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorExposureTest {

  @Autowired MockMvc mvc;
  @Autowired TestIdentity identity;

  private User user;

  @BeforeEach
  void signIn() {
    user = identity.createUser("Operator");
  }

  @Test
  @DisplayName("health is public but reports only up or down")
  void healthIsPublicAndMinimal() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").exists())
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.details").doesNotExist())
        // Nothing about the datastore behind it.
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsStringIgnoringCase("postgres"))));
  }

  @Test
  @DisplayName("the dangerous endpoints are not reachable, signed in or not")
  void sensitiveEndpointsAreClosed() throws Exception {
    for (String endpoint :
        java.util.List.of("env", "configprops", "heapdump", "beans", "mappings", "loggers")) {
      mvc.perform(get("/actuator/" + endpoint))
          .andExpect(status().is(org.hamcrest.Matchers.oneOf(401, 403, 404)));

      // An ordinary account is no shortcut either.
      mvc.perform(get("/actuator/" + endpoint).with(TestIdentity.as(user)))
          .andExpect(status().is(org.hamcrest.Matchers.oneOf(401, 403, 404)));
    }
  }
}

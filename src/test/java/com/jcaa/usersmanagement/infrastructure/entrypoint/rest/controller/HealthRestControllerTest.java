package com.jcaa.usersmanagement.infrastructure.entrypoint.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jcaa.usersmanagement.infrastructure.config.OpenApiConfig;
import com.jcaa.usersmanagement.infrastructure.security.JwtAuthenticationFilter;
import com.jcaa.usersmanagement.infrastructure.security.JwtTokenService;
import com.jcaa.usersmanagement.infrastructure.security.RestAccessDeniedHandler;
import com.jcaa.usersmanagement.infrastructure.security.RestAuthenticationEntryPoint;
import com.jcaa.usersmanagement.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = HealthRestControllerTest.TestConfiguration.class,
        properties = {
                "spring.main.web-application-type=servlet",
                "spring.application.name=users-management"
        })
@AutoConfigureMockMvc
class HealthRestControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BuildProperties buildProperties;
    @MockBean
    private JwtTokenService jwtTokenService;

    @Test
    void shouldReturnDeployedNameAndVersionWithoutAuthenticationOrExternalServices() throws Exception {
        assertThat(buildProperties.getVersion()).isNotBlank();

        mockMvc
                .perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("users-management"))
                .andExpect(jsonPath("$.version").value(buildProperties.getVersion()));
    }

    @Test
    void shouldDocumentHealthResponseInOpenApi() throws Exception {
        mockMvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/health'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/health'].get.security").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.HealthRestResponse.properties.name").exists())
                .andExpect(jsonPath("$.components.schemas.HealthRestResponse.properties.version").exists());
    }

    @Test
    void shouldKeepOtherEndpointsProtected() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, RabbitAutoConfiguration.class})
    @Import({
            HealthRestController.class,
            OpenApiConfig.class,
            SecurityConfig.class,
            JwtAuthenticationFilter.class,
            RestAuthenticationEntryPoint.class,
            RestAccessDeniedHandler.class
    })
    static class TestConfiguration {
    }
}

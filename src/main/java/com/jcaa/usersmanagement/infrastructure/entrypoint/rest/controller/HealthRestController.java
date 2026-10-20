package com.jcaa.usersmanagement.infrastructure.entrypoint.rest.controller;

import com.jcaa.usersmanagement.infrastructure.entrypoint.rest.dto.response.HealthRestResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthRestController implements HealthRestControllerDocs {

    private final String applicationName;
    private final BuildProperties buildProperties;

    public HealthRestController(
            @Value("${spring.application.name}") final String applicationName,
            final BuildProperties buildProperties) {
        this.applicationName = applicationName;
        this.buildProperties = buildProperties;
    }

    @Override
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthRestResponse health() {
        return new HealthRestResponse(applicationName, buildProperties.getVersion());
    }
}

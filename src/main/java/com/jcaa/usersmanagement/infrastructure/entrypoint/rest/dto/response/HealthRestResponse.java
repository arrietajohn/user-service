package com.jcaa.usersmanagement.infrastructure.entrypoint.rest.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record HealthRestResponse(
        @Schema(description = "Nombre del microservicio.", example = "users-management") String name,
        @Schema(description = "Version del artefacto desplegado.", example = "0.5.0") String version) {
}

package com.jcaa.usersmanagement.infrastructure.entrypoint.rest.controller;

import com.jcaa.usersmanagement.infrastructure.entrypoint.rest.dto.response.HealthRestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Health", description = "Monitoreo basico de disponibilidad del microservicio.")
public interface HealthRestControllerDocs {

    @Operation(
            summary = "Consultar disponibilidad del microservicio",
            description =
                    "Endpoint publico, sin autenticacion, para monitoreo up/down. "
                            + "Retorna HTTP 200 con el nombre y la version del artefacto desplegado "
                            + "cuando el servicio puede atender solicitudes. "
                            + "No verifica conexiones a la base de datos ni a RabbitMQ.",
            security = {})
    @ApiResponse(
            responseCode = "200",
            description = "Microservicio disponible.",
            content =
            @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = HealthRestResponse.class)))
    HealthRestResponse health();
}

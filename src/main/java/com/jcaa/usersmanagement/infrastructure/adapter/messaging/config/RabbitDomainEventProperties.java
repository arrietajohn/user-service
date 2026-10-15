package com.jcaa.usersmanagement.infrastructure.adapter.messaging.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "rabbitmq.domain")
public record RabbitDomainEventProperties(@NotBlank String exchange) {}

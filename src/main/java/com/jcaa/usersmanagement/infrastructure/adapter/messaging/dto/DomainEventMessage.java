package com.jcaa.usersmanagement.infrastructure.adapter.messaging.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record DomainEventMessage(
    String eventName, LocalDateTime occurredOn, Map<String, String> payload) {}

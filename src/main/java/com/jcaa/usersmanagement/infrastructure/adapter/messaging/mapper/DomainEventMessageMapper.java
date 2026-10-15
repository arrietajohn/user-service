package com.jcaa.usersmanagement.infrastructure.adapter.messaging.mapper;

import com.jcaa.usersmanagement.domain.event.DomainEvent;
import com.jcaa.usersmanagement.infrastructure.adapter.messaging.dto.DomainEventMessage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DomainEventMessageMapper {

  @Mapping(target = "payload", expression = "java(event.payload())")
  DomainEventMessage toMessage(DomainEvent event);
}

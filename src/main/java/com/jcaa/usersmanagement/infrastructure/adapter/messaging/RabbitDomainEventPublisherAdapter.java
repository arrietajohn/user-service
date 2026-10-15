package com.jcaa.usersmanagement.infrastructure.adapter.messaging;

import com.jcaa.usersmanagement.application.exception.DomainEventPublicationException;
import com.jcaa.usersmanagement.application.port.out.PublishDomainEventPort;
import com.jcaa.usersmanagement.domain.event.DomainEvent;
import com.jcaa.usersmanagement.infrastructure.adapter.messaging.config.RabbitDomainEventProperties;
import com.jcaa.usersmanagement.infrastructure.adapter.messaging.mapper.DomainEventMessageMapper;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RabbitDomainEventPublisherAdapter implements PublishDomainEventPort {

  private static final long CONFIRM_TIMEOUT_SECONDS = 10;

  private final RabbitTemplate rabbitTemplate;
  private final RabbitDomainEventProperties properties;
  private final DomainEventMessageMapper mapper;

  @Override
  public void publish(final DomainEvent event) {
    final CorrelationData correlationData = new CorrelationData();
    try {
      rabbitTemplate.convertAndSend(
          properties.exchange(), event.getEventName(), mapper.toMessage(event), correlationData);
      final CorrelationData.Confirm confirmation =
          correlationData.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!confirmation.isAck() || Objects.nonNull(correlationData.getReturned())) {
        throw new DomainEventPublicationException();
      }
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new DomainEventPublicationException(exception);
    } catch (final AmqpException | ExecutionException | TimeoutException exception) {
      throw new DomainEventPublicationException(exception);
    }
  }
}

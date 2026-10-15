package com.jcaa.usersmanagement.infrastructure.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jcaa.usersmanagement.application.exception.DomainEventPublicationException;
import com.jcaa.usersmanagement.domain.enums.UserRole;
import com.jcaa.usersmanagement.domain.enums.UserStatus;
import com.jcaa.usersmanagement.domain.event.DomainEvent;
import com.jcaa.usersmanagement.domain.event.UserCreatedDomainEvent;
import com.jcaa.usersmanagement.domain.event.UserDeletedDomainEvent;
import com.jcaa.usersmanagement.domain.event.UserUpdatedDomainEvent;
import com.jcaa.usersmanagement.domain.model.UserModel;
import com.jcaa.usersmanagement.domain.valueobject.UserEmail;
import com.jcaa.usersmanagement.domain.valueobject.UserId;
import com.jcaa.usersmanagement.domain.valueobject.UserName;
import com.jcaa.usersmanagement.domain.valueobject.UserPassword;
import com.jcaa.usersmanagement.infrastructure.adapter.messaging.config.RabbitDomainEventProperties;
import com.jcaa.usersmanagement.infrastructure.adapter.messaging.dto.DomainEventMessage;
import com.jcaa.usersmanagement.infrastructure.adapter.messaging.mapper.DomainEventMessageMapper;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

@ExtendWith(MockitoExtension.class)
class RabbitDomainEventPublisherAdapterTest {

  private static final String EXCHANGE = "custom.domain.events";
  private static final String REJECTED_REASON = "rejected";
  private static final int NO_ROUTE_CODE = 312;
  private static final String NO_ROUTE_REASON = "NO_ROUTE";

  @Mock private RabbitTemplate rabbitTemplate;

  private RabbitDomainEventPublisherAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new RabbitDomainEventPublisherAdapter(
        rabbitTemplate,
        new RabbitDomainEventProperties(EXCHANGE),
        Mappers.getMapper(DomainEventMessageMapper.class));
  }

  @ParameterizedTest
  @MethodSource("events")
  void shouldPublishCompatibleJsonWithEventNameAsRoutingKey(final DomainEvent event)
      throws Exception {
    // Arrange
    doAnswer(invocation -> {
      final CorrelationData correlationData = invocation.getArgument(3);
      correlationData.getFuture().complete(new CorrelationData.Confirm(true, REJECTED_REASON));
      return new Object();
    }).when(rabbitTemplate).convertAndSend(
        eq(EXCHANGE), eq(event.getEventName()), any(DomainEventMessage.class), any(CorrelationData.class));
    final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);

    // Act
    adapter.publish(event);

    // Assert
    final ArgumentCaptor<DomainEventMessage> messageCaptor =
        ArgumentCaptor.forClass(DomainEventMessage.class);
    verify(rabbitTemplate).convertAndSend(
        eq(EXCHANGE), eq(event.getEventName()), messageCaptor.capture(), any(CorrelationData.class));
    final Message message = converter.toMessage(messageCaptor.getValue(), new MessageProperties());
    final JsonNode json = objectMapper.readTree(message.getBody());
    assertThat(message.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
    assertThat(json.properties()).hasSize(3);
    assertThat(json.path("eventName").asText()).isEqualTo(event.getEventName());
    assertThat(objectMapper.treeToValue(json.path("occurredOn"), event.getOccurredOn().getClass()))
        .isEqualTo(event.getOccurredOn());
    assertThat(json.path("payload")).isEqualTo(objectMapper.valueToTree(event.payload()));
    assertThat(json.toString()).doesNotContain("password", "hash", "userPassword");
  }

  @ParameterizedTest
  @MethodSource("events")
  void shouldRejectNegativeBrokerConfirmation(final DomainEvent event) {
    // Arrange
    doAnswer(invocation -> {
      final CorrelationData correlationData = invocation.getArgument(3);
      correlationData.getFuture().complete(new CorrelationData.Confirm(false, REJECTED_REASON));
      return new Object();
    }).when(rabbitTemplate).convertAndSend(
        eq(EXCHANGE), eq(event.getEventName()), any(DomainEventMessage.class), any(CorrelationData.class));

    // Act
    final DomainEventPublicationException exception =
        assertThrows(DomainEventPublicationException.class, () -> adapter.publish(event));

    // Assert
    assertThat(exception).hasMessage("No se pudo publicar el evento de dominio.");
  }

  @Test
  void shouldRejectMessageReturnedWithoutBinding() {
    // Arrange
    final DomainEvent event = new UserDeletedDomainEvent(new UserId("u-001"));
    doAnswer(invocation -> {
      final CorrelationData correlationData = invocation.getArgument(3);
      correlationData.setReturned(new ReturnedMessage(
          new Message(new byte[0]), NO_ROUTE_CODE, NO_ROUTE_REASON, EXCHANGE, event.getEventName()));
      correlationData.getFuture().complete(new CorrelationData.Confirm(true, REJECTED_REASON));
      return new Object();
    }).when(rabbitTemplate).convertAndSend(
        eq(EXCHANGE), eq(event.getEventName()), any(DomainEventMessage.class), any(CorrelationData.class));

    // Act
    final DomainEventPublicationException exception =
        assertThrows(DomainEventPublicationException.class, () -> adapter.publish(event));

    // Assert
    assertThat(exception).hasMessage("No se pudo publicar el evento de dominio.");
  }

  @Test
  void shouldTranslateBrokerConnectionFailure() {
    // Arrange
    final DomainEvent event = new UserDeletedDomainEvent(new UserId("u-001"));
    final AmqpException failure = new AmqpException("Connection failed");
    doThrow(failure).when(rabbitTemplate).convertAndSend(
        eq(EXCHANGE), eq(event.getEventName()), any(DomainEventMessage.class), any(CorrelationData.class));

    // Act
    final DomainEventPublicationException exception =
        assertThrows(DomainEventPublicationException.class, () -> adapter.publish(event));

    // Assert
    assertThat(exception).hasCause(failure);
  }

  private static Stream<DomainEvent> events() {
    final UserModel user = new UserModel(
        new UserId("u-001"), new UserName("Test User"), new UserEmail("test@example.com"),
        UserPassword.fromHash("$2a$12$abcdefghijklmnopqrstuO"), UserRole.MEMBER, UserStatus.ACTIVE);
    return Stream.of(new UserCreatedDomainEvent(user), new UserUpdatedDomainEvent(user),
        new UserDeletedDomainEvent(user.getId()));
  }
}

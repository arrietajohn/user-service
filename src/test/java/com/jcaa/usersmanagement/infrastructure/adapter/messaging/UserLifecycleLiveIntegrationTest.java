package com.jcaa.usersmanagement.infrastructure.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcaa.usersmanagement.Main;
import com.jcaa.usersmanagement.application.port.out.PublishDomainEventPort;
import com.jcaa.usersmanagement.application.service.CreateUserService;
import com.jcaa.usersmanagement.application.service.DeleteUserService;
import com.jcaa.usersmanagement.application.service.UpdateUserService;
import com.jcaa.usersmanagement.application.service.dto.command.CreateUserCommand;
import com.jcaa.usersmanagement.application.service.dto.command.DeleteUserCommand;
import com.jcaa.usersmanagement.application.service.dto.command.UpdateUserCommand;
import com.jcaa.usersmanagement.domain.model.UserModel;
import com.jcaa.usersmanagement.domain.valueobject.UserId;
import com.jcaa.usersmanagement.infrastructure.adapter.messaging.config.RabbitDomainEventProperties;
import com.jcaa.usersmanagement.infrastructure.adapter.persistence.repository.UserRepositoryMySQL;
import com.rabbitmq.client.Channel;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@EnabledIfEnvironmentVariable(named = "RUN_LIVE_INTEGRATION", matches = "true")
class UserLifecycleLiveIntegrationTest {

  private static final String CREATED_EVENT = "user.created";
  private static final String UPDATED_EVENT = "user.updated";
  private static final String DELETED_EVENT = "user.deleted";
  private static final String EVENT_NAME_FIELD = "eventName";
  private static final String OCCURRED_ON_FIELD = "occurredOn";
  private static final String PAYLOAD_FIELD = "payload";
  private static final String ID_FIELD = "id";
  private static final String NAME_FIELD = "name";
  private static final String EMAIL_FIELD = "email";
  private static final String ROLE_FIELD = "role";
  private static final String STATUS_FIELD = "status";
  private static final String CREATED_NAME = "Integration Test User";
  private static final String UPDATED_NAME = "Integration Test Updated";
  private static final String EMAIL_DOMAIN = "@example.invalid";
  private static final String TEST_PASSWORD = "IntegrationOnly123!";
  private static final String MEMBER_ROLE = "MEMBER";
  private static final String REVIEWER_ROLE = "REVIEWER";
  private static final String ACTIVE_STATUS = "ACTIVE";
  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String EMPTY_QUEUE_NAME = "";
  private static final String LIVE_FAILURE = "Live integration failed during %s (%s). Credentials omitted.";
  private static final String LIVE_SUCCESS = "LIVE_INTEGRATION_OK: Main booted with repo-local .env; HTTP OpenAPI returned 200; create/update/delete; three CloudAMQP events received; rollback verified.";
  private static final int HTTP_OK = 200;
  private static final String OPENAPI_URL_TEMPLATE = "http://localhost:%d/v3/api-docs";
  private static final String OPENAPI_VERSION_FIELD = "openapi";
  private static final long RECEIVE_TIMEOUT_SECONDS = 15;

  @Test
  void shouldPersistUserLifecycleAndReceiveItsThreeEventsFromCloudAmqp() {
    // Arrange
    final UserId testUserId = new UserId(UUID.randomUUID().toString());
    final String email = testUserId.value() + EMAIL_DOMAIN;
    String stage = "configuration and connections";
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Main.class)
        .web(WebApplicationType.SERVLET)
        .properties("spring.main.banner-mode=off", "logging.level.root=OFF")
        .run("--server.port=0", "--spring.config.location=file:./src/main/resources/application.properties")) {
      final int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      final HttpRequest request = HttpRequest.newBuilder(
          URI.create(OPENAPI_URL_TEMPLATE.formatted(port))).GET().build();
      final HttpResponse<String> response = HttpClient.newHttpClient().send(
          request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(HTTP_OK);
      assertThat(response.body()).contains(OPENAPI_VERSION_FIELD);
      final DataSource dataSource = context.getBean(DataSource.class);
      final ConnectionFactory connectionFactory = context.getBean(ConnectionFactory.class);
      final ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
      final PublishDomainEventPort publisher = context.getBean(PublishDomainEventPort.class);
      final String exchange = context.getBean(RabbitDomainEventProperties.class).exchange();
      final BlockingQueue<ReceivedEvent> received = new LinkedBlockingQueue<>();
      try (org.springframework.amqp.rabbit.connection.Connection rabbitConnection =
              connectionFactory.createConnection();
          Channel channel = rabbitConnection.createChannel(false);
          ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
          Connection databaseConnection = dataSource.getConnection()) {
        channel.exchangeDeclarePassive(exchange);
        final String queue = channel.queueDeclare(EMPTY_QUEUE_NAME, false, true, true, Map.of()).getQueue();
        for (final String eventName : new String[] {CREATED_EVENT, UPDATED_EVENT, DELETED_EVENT}) {
          channel.queueBind(queue, exchange, eventName);
        }
        channel.basicConsume(queue, true, (consumerTag, delivery) -> {
          final JsonNode message = objectMapper.readTree(delivery.getBody());
          if (Objects.equals(testUserId.value(), message.path(PAYLOAD_FIELD).path(ID_FIELD).asText())) {
            received.add(new ReceivedEvent(delivery.getEnvelope().getRoutingKey(),
                delivery.getProperties().getContentType(), message));
          }
        }, consumerTag -> { });
        databaseConnection.setAutoCommit(false);
        final UserRepositoryMySQL repository =
            new UserRepositoryMySQL(new SingleConnectionDataSource(databaseConnection, true));
        final Validator validator = validatorFactory.getValidator();
        final CreateUserService create = new CreateUserService(repository, repository, publisher, validator);
        final UpdateUserService update = new UpdateUserService(repository, repository, repository, publisher, validator);
        final DeleteUserService delete = new DeleteUserService(repository, repository, publisher, validator);
        try {
          // Act
          stage = "create and user.created confirmation";
          final UserModel created = create.execute(new CreateUserCommand(
              testUserId.value(), CREATED_NAME, email, TEST_PASSWORD, MEMBER_ROLE)).user();
          // Assert
          assertThat(repository.getById(testUserId)).isPresent();
          assertUserEvent(received, CREATED_EVENT, created);

          // Act
          stage = "update and user.updated confirmation";
          final UserModel updated = update.execute(new UpdateUserCommand(
              testUserId.value(), UPDATED_NAME, email, TEST_PASSWORD, REVIEWER_ROLE, ACTIVE_STATUS));
          // Assert
          assertThat(repository.getById(testUserId)).get()
              .extracting(user -> user.getName().value()).isEqualTo(UPDATED_NAME);
          assertUserEvent(received, UPDATED_EVENT, updated);

          // Act
          stage = "delete of test fixture and user.deleted confirmation";
          delete.execute(new DeleteUserCommand(testUserId.value()));
          // Assert
          assertThat(repository.getById(testUserId)).isEmpty();
          final JsonNode deletedPayload = assertEvent(received, DELETED_EVENT);
          assertThat(deletedPayload.size()).isEqualTo(1);
          assertThat(deletedPayload.path(ID_FIELD).asText()).isEqualTo(testUserId.value());
        } finally {
          databaseConnection.rollback();
        }
      }
      // Assert
      stage = "rollback verification";
      assertThat(new UserRepositoryMySQL(dataSource).getById(testUserId)).isEmpty();
      System.out.println(LIVE_SUCCESS);
    } catch (final Exception exception) {
      final String technicalCauses = Stream.iterate(exception, Objects::nonNull, Throwable::getCause)
          .map(cause -> cause.getClass().getSimpleName()
              + (cause instanceof BeanCreationException beanFailure ? " bean=" + beanFailure.getBeanName() : "")
              + (cause instanceof SQLException sqlFailure ? " sqlState=" + sqlFailure.getSQLState() + " code=" + sqlFailure.getErrorCode() : ""))
          .collect(Collectors.joining(" -> "));
      fail(LIVE_FAILURE.formatted(stage, technicalCauses));
    }
  }

  private static void assertUserEvent(final BlockingQueue<ReceivedEvent> received,
      final String eventName, final UserModel user) throws InterruptedException {
    final JsonNode payload = assertEvent(received, eventName);
    assertThat(payload.size()).isEqualTo(5);
    assertThat(payload.path(ID_FIELD).asText()).isEqualTo(user.getId().value());
    assertThat(payload.path(NAME_FIELD).asText()).isEqualTo(user.getName().value());
    assertThat(payload.path(EMAIL_FIELD).asText()).isEqualTo(user.getEmail().value());
    assertThat(payload.path(ROLE_FIELD).asText()).isEqualTo(user.getRole().name());
    assertThat(payload.path(STATUS_FIELD).asText()).isEqualTo(user.getStatus().name());
  }

  private static JsonNode assertEvent(final BlockingQueue<ReceivedEvent> received,
      final String eventName) throws InterruptedException {
    final ReceivedEvent event = received.poll(RECEIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(event).as("Expected domain event was received from CloudAMQP").isNotNull();
    assertThat(event.routingKey()).isEqualTo(eventName);
    assertThat(event.contentType()).isEqualTo(JSON_CONTENT_TYPE);
    assertThat(event.message().size()).isEqualTo(3);
    assertThat(event.message().path(EVENT_NAME_FIELD).asText()).isEqualTo(eventName);
    assertThat(event.message().path(OCCURRED_ON_FIELD).asText()).isNotBlank();
    assertThat(event.message().toString()).doesNotContain("password", TEST_PASSWORD);
    return event.message().path(PAYLOAD_FIELD);
  }

  private record ReceivedEvent(String routingKey, String contentType, JsonNode message) {}

}

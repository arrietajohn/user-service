package com.jcaa.usersmanagement.application.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.times;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jcaa.usersmanagement.application.port.out.GetUserByEmailPort;
import com.jcaa.usersmanagement.application.port.out.SaveUserPort;
import com.jcaa.usersmanagement.application.port.out.PublishDomainEventPort;
import com.jcaa.usersmanagement.domain.event.DomainEvent;
import com.jcaa.usersmanagement.domain.event.UserCreatedDomainEvent;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import com.jcaa.usersmanagement.application.service.dto.command.CreateUserCommand;
import com.jcaa.usersmanagement.domain.enums.UserRole;
import com.jcaa.usersmanagement.domain.enums.UserStatus;
import com.jcaa.usersmanagement.domain.exception.UserAlreadyExistsException;
import com.jcaa.usersmanagement.domain.model.UserModel;
import com.jcaa.usersmanagement.domain.valueobject.UserEmail;
import com.jcaa.usersmanagement.domain.valueobject.UserId;
import com.jcaa.usersmanagement.domain.valueobject.UserName;
import com.jcaa.usersmanagement.domain.valueobject.UserPassword;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests para CreateUserService.
 *
 * <p>Cubre: validación del command, email duplicado y flujo feliz (save + notificación).
 */
@DisplayName("CreateUserService")
@ExtendWith(MockitoExtension.class)
class CreateUserServiceTest {

  @Mock private SaveUserPort saveUserPort;
  @Mock private GetUserByEmailPort getUserByEmailPort;
  @Mock private PublishDomainEventPort publishDomainEventPort;

  private CreateUserService service;

  @BeforeEach
  void setUp() {
    try (final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
      service =
          new CreateUserService(
              saveUserPort,
              getUserByEmailPort,
              publishDomainEventPort,
              validatorFactory.getValidator());
    }
  }

  // ── flujo feliz

  @Test
  @DisplayName("execute() guarda el usuario y envía notificación cuando el email no existe")
  void shouldSaveUserAndNotifyWhenEmailIsNew() {
    // Arrange
    final CreateUserCommand command =
        new CreateUserCommand("u-01", "John Arrieta", "john@example.com", "Pass1234", "ADMIN");

    final UserModel savedUser =
        new UserModel(
            new UserId("u-01"),
            new UserName("John Arrieta"),
            new UserEmail("john@example.com"),
            UserPassword.fromPlainText("Pass1234"),
            UserRole.ADMIN,
            UserStatus.PENDING);

    when(getUserByEmailPort.getByEmail(any())).thenReturn(Optional.empty());
    when(saveUserPort.save(any())).thenReturn(savedUser);

    // Act
    final UserModel result = service.execute(command);

    // Assert
    assertAll(
        "flujo feliz de CreateUserService",
        () -> assertNotNull(result, "resultado no debe ser null"),
        () -> assertEquals("u-01", result.getId().value(), "id del usuario guardado"));

    verify(saveUserPort).save(any(UserModel.class));
    final ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
    final InOrder order = inOrder(saveUserPort, publishDomainEventPort);
    order.verify(saveUserPort).save(any(UserModel.class));
    order.verify(publishDomainEventPort).publish(eventCaptor.capture());
    final UserCreatedDomainEvent event = assertInstanceOf(UserCreatedDomainEvent.class, eventCaptor.getValue());
    assertEquals(savedUser.getId().value(), event.payload().get("id"));
    assertEquals(savedUser.getEmail().value(), event.payload().get("email"));
  }

  // ── email duplicado

  @Test
  void shouldNotPublishWhenSaveFails() {
    // Arrange
    final CreateUserCommand command =
        new CreateUserCommand("u-01", "John Arrieta", "john@example.com", "Pass1234", "ADMIN");
    when(getUserByEmailPort.getByEmail(any())).thenReturn(Optional.empty());
    when(saveUserPort.save(any())).thenThrow(new IllegalStateException("Persistence failed"));

    // Act
    final IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> service.execute(command));

    // Assert
    assertEquals("Persistence failed", exception.getMessage());
    verifyNoInteractions(publishDomainEventPort);
  }

  @Test
  @DisplayName("execute() lanza UserAlreadyExistsException cuando el email ya está registrado")
  void shouldThrowWhenEmailAlreadyExists() {
    // Arrange
    final CreateUserCommand command =
        new CreateUserCommand("u-02", "Jane Doe", "jane@example.com", "Pass5678", "MEMBER");

    final UserModel existing =
        new UserModel(
            new UserId("u-99"),
            new UserName("Jane Doe"),
            new UserEmail("jane@example.com"),
            UserPassword.fromPlainText("OtraPass1"),
            UserRole.MEMBER,
            UserStatus.ACTIVE);

    when(getUserByEmailPort.getByEmail(any())).thenReturn(Optional.of(existing));

    // Act & Assert
    assertThrows(UserAlreadyExistsException.class, () -> service.execute(command));
    verify(saveUserPort, never()).save(any());
    verifyNoInteractions(publishDomainEventPort);
  }

  // ── validación del command

  @Test
  @DisplayName(
      "execute() lanza ConstraintViolationException cuando el command tiene campos inválidos")
  void shouldThrowWhenCommandIsInvalid() {
    // Arrange — id en blanco y email inválido
    final CreateUserCommand command =
        new CreateUserCommand("", "Jo", "not-an-email", "short", "ADMIN");

    // Act & Assert
    assertThrows(ConstraintViolationException.class, () -> service.execute(command));
    verifyNoInteractions(saveUserPort, getUserByEmailPort, publishDomainEventPort);
  }

  @Test
  void shouldReturnExistingUserOnRetryWithoutSavingOrPublishingAgain() {
    final CreateUserCommand command = retryCommand();
    final UserModel user = retryUser();
    when(getUserByEmailPort.getByEmail(any()))
        .thenReturn(Optional.empty(), Optional.of(user));
    when(saveUserPort.save(any())).thenReturn(user);

    assertSame(user, service.execute(command));
    assertSame(user, service.execute(command));

    verify(saveUserPort, times(1)).save(any());
    verify(publishDomainEventPort, times(1)).publish(any(UserCreatedDomainEvent.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"id", "name", "password", "role"})
  void shouldRejectDifferentDataForExistingEmail(String changedField) {
    final CreateUserCommand command = new CreateUserCommand(
        changedField.equals("id") ? "another-id" : "u-retry",
        changedField.equals("name") ? "Other Name" : "Jane Doe",
        "jane@example.com",
        changedField.equals("password") ? "Different123" : "Pass5678",
        changedField.equals("role") ? "ADMIN" : "MEMBER");
    when(getUserByEmailPort.getByEmail(any())).thenReturn(Optional.of(retryUser()));

    assertThrows(UserAlreadyExistsException.class, () -> service.execute(command));
    verifyNoInteractions(saveUserPort, publishDomainEventPort);
  }

  @Test
  void shouldResolveDuplicateFromConcurrentIdenticalCreationWithoutAnotherEvent() {
    final UserModel user = retryUser();
    when(getUserByEmailPort.getByEmail(any()))
        .thenReturn(Optional.empty(), Optional.of(user));
    when(saveUserPort.save(any()))
        .thenThrow(UserAlreadyExistsException.becauseIdOrEmailAlreadyExists());

    assertSame(user, service.execute(retryCommand()));
    verify(saveUserPort, times(1)).save(any());
    verifyNoInteractions(publishDomainEventPort);
  }

  @Test
  void shouldKeepConflictWhenDuplicateIdBelongsToDifferentEmail() {
    when(getUserByEmailPort.getByEmail(any())).thenReturn(Optional.empty());
    when(saveUserPort.save(any()))
        .thenThrow(UserAlreadyExistsException.becauseIdOrEmailAlreadyExists());

    assertThrows(UserAlreadyExistsException.class, () -> service.execute(retryCommand()));
    verifyNoInteractions(publishDomainEventPort);
  }

  @Test
  void shouldKeepConflictWhenConcurrentCreationUsesDifferentData() {
    when(getUserByEmailPort.getByEmail(any()))
        .thenReturn(Optional.empty(), Optional.of(retryUser()));
    when(saveUserPort.save(any()))
        .thenThrow(UserAlreadyExistsException.becauseIdOrEmailAlreadyExists());
    final CreateUserCommand command =
        new CreateUserCommand("other-id", "Jane Doe", "jane@example.com", "Pass5678", "MEMBER");

    assertThrows(UserAlreadyExistsException.class, () -> service.execute(command));
    verifyNoInteractions(publishDomainEventPort);
  }

  private CreateUserCommand retryCommand() {
    return new CreateUserCommand("u-retry", "Jane Doe", "jane@example.com", "Pass5678", "MEMBER");
  }

  private UserModel retryUser() {
    return new UserModel(new UserId("u-retry"), new UserName("Jane Doe"),
        new UserEmail("jane@example.com"), UserPassword.fromPlainText("Pass5678"),
        UserRole.MEMBER, UserStatus.ACTIVE);
  }

}

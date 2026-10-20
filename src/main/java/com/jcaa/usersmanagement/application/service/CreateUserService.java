package com.jcaa.usersmanagement.application.service;

import com.jcaa.usersmanagement.application.port.in.CreateUserUseCase;
import com.jcaa.usersmanagement.application.port.out.GetUserByEmailPort;
import com.jcaa.usersmanagement.application.port.out.PublishDomainEventPort;
import com.jcaa.usersmanagement.application.port.out.SaveUserPort;
import com.jcaa.usersmanagement.application.service.dto.command.CreateUserCommand;
import com.jcaa.usersmanagement.application.service.mapper.UserApplicationMapper;
import com.jcaa.usersmanagement.domain.event.UserCreatedDomainEvent;
import com.jcaa.usersmanagement.domain.exception.UserAlreadyExistsException;
import com.jcaa.usersmanagement.domain.model.UserModel;
import com.jcaa.usersmanagement.domain.valueobject.UserEmail;
import com.jcaa.usersmanagement.domain.valueobject.UserId;
import com.jcaa.usersmanagement.domain.valueobject.UserName;
import com.jcaa.usersmanagement.domain.enums.UserRole;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreateUserService implements CreateUserUseCase {

  private final SaveUserPort saveUserPort;
  private final GetUserByEmailPort getUserByEmailPort;
  private final PublishDomainEventPort publishDomainEventPort;
  private final Validator validator;

  @Override
  public UserModel execute(final CreateUserCommand command) {
    validateCommand(command);

    final UserEmail email = new UserEmail(command.email());
    final Optional<UserModel> existing = getUserByEmailPort.getByEmail(email);
    if (existing.isPresent()) {
      return resolveExisting(existing.get(), command);
    }

    final UserModel userToSave = UserApplicationMapper.fromCreateCommandToModel(command);
    final UserModel savedUser;
    try {
      savedUser = saveUserPort.save(userToSave);
    } catch (final UserAlreadyExistsException exception) {
      // Otra solicitud pudo insertar el usuario despues de la consulta inicial.
      final Optional<UserModel> concurrentUser = getUserByEmailPort.getByEmail(email);
      if (concurrentUser.isPresent()) {
        return resolveExisting(concurrentUser.get(), command);
      }
      throw exception;
    }

    publishDomainEventPort.publish(new UserCreatedDomainEvent(savedUser));

    return savedUser;
  }

  private void validateCommand(final CreateUserCommand command) {
    final Set<ConstraintViolation<CreateUserCommand>> violations = validator.validate(command);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
  }

  private UserModel resolveExisting(final UserModel existing, final CreateUserCommand command) {
    if (existing.getId().equals(new UserId(command.id()))
        && existing.getEmail().equals(new UserEmail(command.email()))
        && existing.getName().equals(new UserName(command.name()))
        && existing.getRole() == UserRole.fromString(command.role())
        && existing.getPassword().verifyPlain(command.password())) {
      return existing;
    }
    throw UserAlreadyExistsException.becauseIdOrEmailAlreadyExists();
  }
}

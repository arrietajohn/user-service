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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
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
    ensureEmailIsNotTaken(email);

    final UserModel userToSave = UserApplicationMapper.fromCreateCommandToModel(command);
    final UserModel savedUser = saveUserPort.save(userToSave);

    publishDomainEventPort.publish(new UserCreatedDomainEvent(savedUser));

    return savedUser;
  }

  private void validateCommand(final CreateUserCommand command) {
    final Set<ConstraintViolation<CreateUserCommand>> violations = validator.validate(command);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
  }

  private void ensureEmailIsNotTaken(final UserEmail email) {
    getUserByEmailPort
        .getByEmail(email)
        .ifPresent(
            ignored -> {
              throw UserAlreadyExistsException.becauseEmailAlreadyExists(email.value());
            });
  }
}

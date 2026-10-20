package com.jcaa.usersmanagement.infrastructure.entrypoint.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jcaa.usersmanagement.application.port.in.*;
import com.jcaa.usersmanagement.application.port.out.GetUserByEmailPort;
import com.jcaa.usersmanagement.application.port.out.SaveUserPort;
import com.jcaa.usersmanagement.application.service.CreateUserService;
import com.jcaa.usersmanagement.application.port.out.PublishDomainEventPort;
import com.jcaa.usersmanagement.domain.event.UserCreatedDomainEvent;
import com.jcaa.usersmanagement.domain.enums.UserRole;
import com.jcaa.usersmanagement.domain.enums.UserStatus;
import com.jcaa.usersmanagement.domain.exception.UserAlreadyExistsException;
import com.jcaa.usersmanagement.domain.model.UserModel;
import com.jcaa.usersmanagement.domain.valueobject.*;
import com.jcaa.usersmanagement.infrastructure.adapter.persistence.exception.PersistenceException;
import com.jcaa.usersmanagement.infrastructure.entrypoint.rest.advice.GlobalExceptionHandler;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CreateUserIdempotencyTest {

  private static final String REQUEST = """
      {"id":"u-retry", "name":"Jane Doe", "email":"jane@example.com",
       "password":"Pass5678", "role":"MEMBER"}
      """;

  private final SaveUserPort saveUserPort = mock(SaveUserPort.class);
  private final GetUserByEmailPort getUserByEmailPort = mock(GetUserByEmailPort.class);
  private final PublishDomainEventPort events = mock(PublishDomainEventPort.class);
  private ValidatorFactory validatorFactory;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    final CreateUserService service = new CreateUserService(
        saveUserPort, getUserByEmailPort, events, validatorFactory.getValidator());
    final UserRestController controller = new UserRestController(service,
        mock(UpdateUserUseCase.class), mock(DeleteUserUseCase.class),
        mock(GetUserByIdUseCase.class), mock(GetAllUsersUseCase.class));
    mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @AfterEach
  void tearDown() {
    validatorFactory.close();
  }

  @Test
  void shouldReturnSameUserForRepeatedPostAndPerformSideEffectsOnce() throws Exception {
    final UserModel user = new UserModel(new UserId("u-retry"), new UserName("Jane Doe"),
        new UserEmail("jane@example.com"), UserPassword.fromPlainText("Pass5678"),
        UserRole.MEMBER, UserStatus.PENDING);
    when(getUserByEmailPort.getByEmail(any()))
        .thenReturn(Optional.empty(), Optional.of(user));
    when(saveUserPort.save(any())).thenReturn(user);

    final String firstBody = mvc.perform(post("/api/users")
            .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.password").doesNotExist())
        .andReturn().getResponse().getContentAsString();
    mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
        .andExpect(status().isCreated()).andExpect(content().json(firstBody));

    verify(saveUserPort, times(1)).save(any());
    verify(events, times(1)).publish(any(UserCreatedDomainEvent.class));
  }

  @Test
  void shouldReturn409WhenIdAlreadyBelongsToAnotherEmail() throws Exception {
    when(getUserByEmailPort.getByEmail(any())).thenReturn(Optional.empty());
    when(saveUserPort.save(any()))
        .thenThrow(UserAlreadyExistsException.becauseIdOrEmailAlreadyExists());

    mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    verifyNoInteractions(events);
  }

  @Test
  void shouldPreserve500ForDatabaseOutages() throws Exception {
    when(getUserByEmailPort.getByEmail(any())).thenReturn(Optional.empty());
    when(saveUserPort.save(any())).thenThrow(PersistenceException.becauseSaveFailed(
        "u-retry", new SQLException("Connection lost")));

    mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("Error de persistencia."));
    verifyNoInteractions(events);
  }
}

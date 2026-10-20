package com.jcaa.usersmanagement.application.service.dto.result;

import com.jcaa.usersmanagement.domain.model.UserModel;

public record CreateUserResult(UserModel user, boolean created) {

  public static CreateUserResult created(UserModel user) {
    return new CreateUserResult(user, true);
  }

  public static CreateUserResult existing(UserModel user) {
    return new CreateUserResult(user, false);
  }
}

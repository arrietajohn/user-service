package com.jcaa.usersmanagement.application.exception;

public final class DomainEventPublicationException extends RuntimeException {

  private static final String MESSAGE = "No se pudo publicar el evento de dominio.";

  public DomainEventPublicationException() {
    super(MESSAGE);
  }

  public DomainEventPublicationException(final Throwable cause) {
    super(MESSAGE, cause);
  }
}

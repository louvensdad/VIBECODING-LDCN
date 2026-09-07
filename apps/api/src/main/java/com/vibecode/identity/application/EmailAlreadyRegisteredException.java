package com.vibecode.identity.application;

/** Raised when registration targets an address that already has an account. */
public class EmailAlreadyRegisteredException extends RuntimeException {

  public EmailAlreadyRegisteredException() {
    super("Este email já está em uso.");
  }
}

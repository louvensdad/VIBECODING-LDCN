package com.vibecode.terminal.domain;

/**
 * The boundary behind which any command execution must happen.
 *
 * <p>There is no implementation, and the API server must never gain one: commands belong in an
 * isolated sandbox container with its own filesystem and no access to platform credentials.
 * Declaring the port now keeps that requirement visible instead of letting an execution path grow
 * into the main process by accident.
 */
public interface TerminalExecutionGateway {

  TerminalExecutionResult execute(TerminalCommand command);
}

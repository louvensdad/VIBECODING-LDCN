package com.vibecode.terminal.domain;

import java.util.List;
import java.util.UUID;

/** A command the user wants run, described rather than executed. */
public record TerminalCommand(UUID projectId, String command, List<String> arguments) {

  public TerminalCommand {
    arguments = List.copyOf(arguments);
  }
}

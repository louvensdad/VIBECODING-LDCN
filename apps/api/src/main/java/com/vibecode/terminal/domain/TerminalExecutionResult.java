package com.vibecode.terminal.domain;

import java.time.Duration;

/** The outcome of a sandboxed execution, shaped so it can be fed straight to the Output Analyzer. */
public record TerminalExecutionResult(
    int exitCode, String stdout, String stderr, Duration elapsed, boolean timedOut) {}

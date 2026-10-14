package com.bank.notification.log.port.out.log.impl;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hand-written SLF4J logger that records every event instead of writing it.
 * Only INFO can be switched off; the other levels are always enabled.
 */
class RecordingLogger extends LegacyAbstractLogger {

  private static final long serialVersionUID = 1L;

  /** One recorded logging call. */
  record Event(Level level, String message, List<Object> arguments, Throwable throwable) {
  }

  private final transient List<Event> events = new ArrayList<>();

  boolean infoEnabled = true;
  RuntimeException toThrow;
  /** Every direct invocation of {@code info(String)}, even when INFO is disabled. */
  int infoCalls;
  /** Every invocation of {@code isInfoEnabled()}. */
  int isInfoEnabledCalls;

  List<Event> events() {
    return events;
  }

  @Override
  public void info(String msg) {
    infoCalls++;
    super.info(msg);
  }

  @Override
  public boolean isTraceEnabled() {
    return true;
  }

  @Override
  public boolean isDebugEnabled() {
    return true;
  }

  @Override
  public boolean isInfoEnabled() {
    isInfoEnabledCalls++;
    return infoEnabled;
  }

  @Override
  public boolean isWarnEnabled() {
    return true;
  }

  @Override
  public boolean isErrorEnabled() {
    return true;
  }

  @Override
  public String getName() {
    return "RecordingLogger";
  }

  @Override
  protected String getFullyQualifiedCallerName() {
    return null;
  }

  @Override
  protected void handleNormalizedLoggingCall(
      Level level, Marker marker, String messagePattern, Object[] arguments, Throwable throwable) {
    if (toThrow != null) {
      throw toThrow;
    }
    List<Object> args = arguments == null ? List.of() : Arrays.asList(arguments);
    String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
    events.add(new Event(level, message, args, throwable));
  }
}

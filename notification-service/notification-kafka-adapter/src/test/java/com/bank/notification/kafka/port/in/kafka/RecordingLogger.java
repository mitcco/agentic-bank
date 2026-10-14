package com.bank.notification.kafka.port.in.kafka;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hand-written SLF4J logger that records every event instead of writing it. All levels are
 * enabled. Independent copy of the one of the log adapter.
 */
class RecordingLogger extends LegacyAbstractLogger {

  private static final long serialVersionUID = 1L;

  /** One recorded logging call. */
  record Event(Level level, String message, List<Object> arguments, Throwable throwable) {
  }

  private final transient List<Event> events = new ArrayList<>();

  List<Event> events() {
    return events;
  }

  List<Event> events(Level level) {
    return events.stream().filter(event -> event.level() == level).toList();
  }

  /**
   * Every text carried by the recorded events: formatted message, arguments, and the message
   * of each throwable of the cause chain.
   */
  List<String> texts() {
    List<String> texts = new ArrayList<>();
    for (Event event : events) {
      texts.add(event.message());
      for (Object argument : event.arguments()) {
        texts.add(String.valueOf(argument));
      }
      Throwable throwable = event.throwable();
      while (throwable != null) {
        texts.add(throwable.getClass().getName() + ": " + throwable.getMessage());
        throwable = throwable.getCause();
      }
    }
    return texts;
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
    return true;
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
    List<Object> args = arguments == null ? List.of() : Arrays.asList(arguments);
    String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
    events.add(new Event(level, message, args, throwable));
  }
}

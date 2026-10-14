package com.bank.notification.kafka.port.in.kafka;

import java.util.regex.Pattern;

/**
 * Chooses a transfer id that is safe to write in a log: only a value shaped like a UUID is
 * accepted, so an IBAN, a line break or free text never reaches the log.
 */
public final class LoggableTransferId {

  private static final String UNKNOWN = "unknown";
  private static final Pattern UUID_PATTERN = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private LoggableTransferId() {
  }

  /**
   * Returns the first value shaped like a UUID, or {@code unknown}.
   *
   * @param primary preferred value, possibly null
   * @param fallback value used when the primary is not a UUID, possibly null
   * @return a UUID-shaped string or {@code unknown}
   */
  public static String of(String primary, String fallback) {
    if (isUuid(primary)) {
      return primary;
    }
    if (isUuid(fallback)) {
      return fallback;
    }
    return UNKNOWN;
  }

  private static boolean isUuid(String value) {
    return value != null && UUID_PATTERN.matcher(value).matches();
  }
}

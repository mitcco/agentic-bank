package com.bank.notification.log.port.out.log.impl;

import com.bank.notification.application.port.out.NotificationLogOutPort;
import com.bank.notification.domain.TransferNotification;
import com.bank.notification.domain.TransferOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Writes one INFO log line for every saved notification.
 *
 * <p>The line never contains an IBAN: it is built only from the transfer id, the outcome, the
 * amount, the currency, the occurrence instant and the reasons, and any IBAN-like text found in
 * the free-text fields is replaced with {@code [IBAN]}. Free-text fields are escaped so that a
 * notification always produces a single line.
 *
 * <p>Exactly one log call is made for each notification. No exception is caught: a failure of the
 * logger propagates to the caller.
 *
 * <p>The format is stable and can be parsed by log analysis tools:
 * {@code Transfer notification transferId=<id> outcome=<COMPLETED|REJECTED> amount=<amount>
 * currency=<XXX> occurredAt=<ISO-8601> reasons=["<m1>", "<m2>"]}, where {@code reasons} is present
 * only for rejected notifications.
 */
public class NotificationLogOutPortImpl implements NotificationLogOutPort {

  private static final Pattern IBAN_PATTERN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
  private static final String IBAN_PLACEHOLDER = "[IBAN]";
  // U+2028 LINE SEPARATOR e U+2029 PARAGRAPH SEPARATOR
  private static final char LINE_SEPARATOR = 0x2028;
  private static final char PARAGRAPH_SEPARATOR = 0x2029;

  private final Logger logger;

  public NotificationLogOutPortImpl() {
    this(LoggerFactory.getLogger(NotificationLogOutPortImpl.class));
  }

  NotificationLogOutPortImpl(Logger logger) {
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  @Override
  public void log(TransferNotification notification) {
    Objects.requireNonNull(notification, "notification");
    if (!logger.isInfoEnabled()) {
      return;
    }
    logger.info(line(notification));
  }

  static String line(TransferNotification n) {
    StringBuilder line = new StringBuilder("Transfer notification");
    line.append(" transferId=").append(sanitize(n.transferId()));
    line.append(" outcome=").append(n.outcome());
    line.append(" amount=").append(formatAmount(n.amount().amount()));
    line.append(" currency=").append(n.amount().currency());
    line.append(" occurredAt=").append(DateTimeFormatter.ISO_INSTANT.format(n.occurredAt()));
    if (n.outcome() == TransferOutcome.REJECTED) {
      line.append(" reasons=").append(formatReasons(n.reasons()));
    }
    return String.valueOf(line);
  }

  private static String formatAmount(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private static String formatReasons(List<String> reasons) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < reasons.size(); i++) {
      if (i > 0) {
        out.append(", ");
      }
      out.append('"').append(sanitize(reasons.get(i))).append('"');
    }
    return String.valueOf(out.append(']'));
  }

  private static String sanitize(String text) {
    String redacted = IBAN_PATTERN.matcher(text).replaceAll(IBAN_PLACEHOLDER);
    StringBuilder out = new StringBuilder(redacted.length());
    for (int i = 0; i < redacted.length(); i++) {
      char c = redacted.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\r' -> out.append("\\r");
        case '\n' -> out.append("\\n");
        case '\t' -> out.append("\\t");
        default -> {
          if (Character.isISOControl(c) || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return String.valueOf(out);
  }
}

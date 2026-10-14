package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.application.port.out.NotificationOutPort;
import com.bank.transfer.postgres.repository.SpringDataOutboxRepository;
import com.bank.transfer.postgres.repository.model.OutboxEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JpaOutboxNotificationOutPortImpl implements NotificationOutPort {

  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

  private static final JsonMapper JSON = JsonMapper.builder()
    .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
    .build();

  private final SpringDataOutboxRepository repository;
  private final Clock clock;

  JpaOutboxNotificationOutPortImpl(SpringDataOutboxRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(TransferNotificationDto notification) {
    Objects.requireNonNull(notification);
    if (!CURRENCY.matcher(notification.currency()).matches()) {
      throw new IllegalArgumentException("currency must be three uppercase letters");
    }
    BigDecimal amount;
    try {
      amount = notification.amount().setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("amount must have at most two decimal places");
    }
    String occurredAt = DateTimeFormatter.ISO_INSTANT
      .format(Instant.now(clock).truncatedTo(ChronoUnit.SECONDS));
    List<String> reasons = notification.reasons().isEmpty() ? null : notification.reasons();
    OutboxPayload payload = new OutboxPayload(notification.transferId(), notification.sourceIban(),
      notification.destIban(), amount, notification.currency(), notification.status().name(),
      reasons, occurredAt);
    repository.save(new OutboxEntity(notification.transferId(), JSON.writeValueAsString(payload)));
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record OutboxPayload(
    String transferId,
    String sourceIban,
    String destinationIban,
    BigDecimal amount,
    String currency,
    String status,
    List<String> reasons,
    String occurredAt) {
  }
}

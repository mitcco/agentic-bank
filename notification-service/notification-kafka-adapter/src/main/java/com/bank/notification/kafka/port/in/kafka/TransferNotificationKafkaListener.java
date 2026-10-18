package com.bank.notification.kafka.port.in.kafka;

import com.bank.notification.application.command.NotifyTransferCommand;
import com.bank.notification.application.port.in.NotifyTransferInPort;
import com.bank.notification.domain.exception.InvalidNotificationException;
import com.bank.notification.kafka.dto.TransferNotificationMessage;
import com.bank.notification.kafka.mapper.InvalidMessageFormatException;
import com.bank.notification.kafka.mapper.TransferNotificationMessageMapper;
import com.bank.notification.kafka.mapper.TransferNotificationMessageReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.Objects;

/**
 * Consumer of the notification topic. It is not a Spring stereotype: the bootstrap registers it.
 *
 * <p>An invalid message is logged at ERROR and dropped (normal return, so the offset is
 * acknowledged). Any other failure is logged at WARN with the class of the cause only and
 * rethrown unchanged, so the error handler of the container retries. IBANs and texts of the JSON
 * library never reach the log.
 */
public class TransferNotificationKafkaListener {

  private final NotifyTransferInPort notifyTransfer;
  private final Logger logger;
  private final TransferNotificationMessageReader reader = new TransferNotificationMessageReader();
  private final TransferNotificationMessageMapper mapper = new TransferNotificationMessageMapper();

  public TransferNotificationKafkaListener(NotifyTransferInPort notifyTransfer) {
    this(notifyTransfer, LoggerFactory.getLogger(TransferNotificationKafkaListener.class));
  }

  TransferNotificationKafkaListener(NotifyTransferInPort notifyTransfer, Logger logger) {
    this.notifyTransfer = Objects.requireNonNull(notifyTransfer, "notifyTransfer");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  /**
   * Entry point called by the Kafka container.
   *
   * @param record the received record
   */
  @KafkaListener(topics = "${notification.topic}")
  public void onMessage(ConsumerRecord<String, String> record) {
    handle(record.key(), record.value());
  }

  void handle(String key, String value) {
    String bodyTransferId = null;
    try {
      TransferNotificationMessage message = reader.read(value);
      bodyTransferId = message.transferId();
      NotifyTransferCommand command = mapper.toCommand(message);
      notifyTransfer.notifyTransfer(command);
    } catch (InvalidMessageFormatException | InvalidNotificationException e) {
      logger.error("Transfer notification discarded transferId={} reason={}",
          LoggableTransferId.of(bodyTransferId, key), e.getMessage());
    } catch (RuntimeException e) {
      logger.warn("Transfer notification attempt failed transferId={} cause={}",
          LoggableTransferId.of(bodyTransferId, key), e.getClass().getName());
      throw e;
    }
  }
}

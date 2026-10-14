package com.bank.transfer.kafka.config;

import java.util.regex.Pattern;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.support.ProducerListener;

/**
 * Replaces the default logging listener of the library: a failed send is logged with topic, key, whole payload and
 * the exception message, with every IBAN replaced by the fixed marker "[IBAN]". Never throws.
 */
public class IbanRedactingProducerListener implements ProducerListener<String, String> {

  private static final Log LOG = LogFactory.getLog(IbanRedactingProducerListener.class);
  private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
  private static final String MARKER = "[IBAN]";

  public IbanRedactingProducerListener() {
  }

  @Override
  public void onError(ProducerRecord<String, String> record, RecordMetadata recordMetadata, Exception exception) {
    try {
      // key and value are read as Object: the template may hand over non-String types
      ProducerRecord<?, ?> raw = record;
      String topic = raw == null ? null : raw.topic();
      Object key = raw == null ? null : raw.key();
      Object payload = raw == null ? null : raw.value();
      String message = exception == null ? null : exception.getMessage();
      LOG.error("Failed to send notification: topic=" + redact(topic) + ", key=" + redact(key == null ? null : String.valueOf(key))
        + ", payload=" + redact(payload == null ? null : String.valueOf(payload)) + ", cause=" + (exception == null ? "null" : exception.getClass().getSimpleName())
        + ", message=" + redact(message));
    } catch (RuntimeException ignored) {
      // a logging problem must never break the send callback
    }
  }

  @Override
  public void onSuccess(ProducerRecord<String, String> record, RecordMetadata recordMetadata) {
    // nothing to log on success
  }

  private static String redact(String text) {
    return text == null ? null : IBAN.matcher(text).replaceAll(MARKER);
  }
}

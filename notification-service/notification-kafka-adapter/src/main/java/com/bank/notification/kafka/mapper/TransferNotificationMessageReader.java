package com.bank.notification.kafka.mapper;

import com.bank.notification.kafka.dto.TransferNotificationMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Reads the JSON body of a message into a {@link TransferNotificationMessage}.
 *
 * <p>Types are strict: a string is not accepted as a number and a number or a boolean is not
 * accepted as a string. Unknown fields are ignored and text after the document is refused. The
 * exceptions carry fixed texts and no cause, so nothing of the document leaks into the logs.
 */
public final class TransferNotificationMessageReader {

  private final JsonMapper mapper;

  public TransferNotificationMessageReader() {
    this.mapper = JsonMapper.builder()
        .withCoercionConfig(LogicalType.Float,
            config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail))
        .withCoercionConfig(LogicalType.Textual, config -> {
          config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
          config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
          config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        })
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();
  }

  /**
   * Reads the body of a message.
   *
   * @param json the body as received, possibly null
   * @return the message, never null
   * @throws InvalidMessageFormatException if the body is empty, not valid JSON or has fields of
   *     the wrong type
   */
  public TransferNotificationMessage read(String json) {
    if (json == null || json.isBlank()) {
      throw new InvalidMessageFormatException("message is empty");
    }
    TransferNotificationMessage message;
    try {
      message = mapper.readValue(json, TransferNotificationMessage.class);
    } catch (StreamReadException e) {
      throw new InvalidMessageFormatException("malformed JSON");
    } catch (JacksonException e) {
      throw new InvalidMessageFormatException("invalid field type");
    }
    if (message == null) {
      throw new InvalidMessageFormatException("message is empty");
    }
    return message;
  }
}

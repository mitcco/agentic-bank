package com.bank.fraud.web.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.math.BigDecimal;

/** Accetta solo token numerici (niente stringhe) e conserva la scala originale. */
public class StrictDecimalDeserializer extends ValueDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT
                || parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getDecimalValue();
        }
        return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
    }
}

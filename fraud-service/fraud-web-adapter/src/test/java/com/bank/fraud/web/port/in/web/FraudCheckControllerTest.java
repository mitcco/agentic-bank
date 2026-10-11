package com.bank.fraud.web.port.in.web;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.application.dto.FraudCheckResponseDto;
import com.bank.fraud.application.exception.InvalidFraudCheckRequestException;
import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import com.bank.fraud.domain.exception.UnsupportedCurrencyException;
import com.bank.fraud.web.mapper.FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapperImpl;
import com.bank.fraud.web.mapper.FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapperImpl;
import com.bank.fraud.web.port.in.web.exception.WebExceptionHandler;
import com.bank.fraud.web.servicedto.internal.FraudCheckServiceDtoImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FraudCheckControllerTest {

    private static final String PATH = "/fraud-check";
    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";

    private final FakeCheckTransferInPort fake = new FakeCheckTransferInPort();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        FraudCheckServiceDtoImpl serviceDto = new FraudCheckServiceDtoImpl(
                fake,
                new FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapperImpl(),
                new FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapperImpl());
        mvc = MockMvcBuilders.standaloneSetup(new FraudCheckController(serviceDto))
                .setControllerAdvice(new WebExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private static MockMvc mvcFor(FakeCheckTransferInPort port) {
        return MockMvcBuilders.standaloneSetup(new FraudCheckController(
                        new FraudCheckServiceDtoImpl(port,
                                new FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapperImpl(),
                                new FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapperImpl())))
                .setControllerAdvice(new WebExceptionHandler())
                .build();
    }

    private static String body(String amount) {
        return body(IBAN_1, IBAN_2, amount, "\"EUR\"");
    }

    private static String body(String source, String destination, String amount, String currencyJson) {
        return "{\"transferId\":\"" + ID + "\",\"sourceIban\":\"" + source + "\",\"destinationIban\":\""
                + destination + "\",\"amount\":" + amount + ",\"currency\":" + currencyJson + "}";
    }

    private ResultActions postJson(String content) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private static FraudCheckResponseDto rejected(String... reasons) {
        return new FraudCheckResponseDto(ID, "REJECTED", List.of(reasons));
    }

    // ---- successo ----

    // W1 (caso 1)
    @Test
    void richiestaAllowed_200ConStatusAllowedESenzaReasons() throws Exception {
        postJson(body("1000.25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(ID))
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.reasons").doesNotExist());
    }

    // W2 (casi 3-7)
    @Test
    void richiestaRejected_200ConMotiviNellOrdineDellaPorta() throws Exception {
        fake.responding(rejected("Amount must not exceed 10000 EUR", "Source IBAN is locked"));

        postJson(body("20000.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(ID))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons.length()").value(2))
                .andExpect(jsonPath("$.reasons[0]").value("Amount must not exceed 10000 EUR"))
                .andExpect(jsonPath("$.reasons[1]").value("Source IBAN is locked"));
    }

    // W3
    @Test
    void importoDecimale_laPortaRiceveLaScalaOriginaleEICinqueCampiIdentici() throws Exception {
        postJson(body("100.25")).andExpect(status().isOk());

        assertThat(fake.requests()).hasSize(1);
        FraudCheckRequestDto received = fake.requests().get(0);
        assertThat(received.transferId()).isEqualTo(ID);
        assertThat(received.sourceIban()).isEqualTo(IBAN_1);
        assertThat(received.destinationIban()).isEqualTo(IBAN_2);
        assertThat(received.currency()).isEqualTo("EUR");
        assertThat(received.amount()).isEqualByComparingTo("100.25");
        assertThat(received.amount().scale()).isEqualTo(2);
    }

    @Test
    void importoIntero_arrivaAllaPortaComeNumero() throws Exception {
        postJson(body("100")).andExpect(status().isOk());

        assertThat(fake.requests().get(0).amount()).isEqualByComparingTo(new BigDecimal("100"));
    }

    // W4 (caso 15)
    @Test
    void stessaRichiestaDueVolte_corpiDiRispostaIdentici() throws Exception {
        fake.responding(rejected("Amount must not exceed 10000 EUR"));

        String first = postJson(body("10000.01")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = postJson(body("10000.01")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(first).isEqualTo(second);
        assertThat(first).contains("REJECTED");
    }

    // W16
    @Test
    void campoSconosciuto_ignoratoE200() throws Exception {
        String content = "{\"transferId\":\"" + ID + "\",\"sourceIban\":\"" + IBAN_1
                + "\",\"destinationIban\":\"" + IBAN_2 + "\",\"amount\":100.25,\"currency\":\"EUR\","
                + "\"extra\":\"x\"}";

        postJson(content).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ALLOWED"));
        assertThat(fake.requests()).hasSize(1);
    }

    // ---- errori ----

    // W5 (caso 13)
    @ParameterizedTest
    @ValueSource(strings = {"testo qualunque", "{\"transferId\":", "", "[]"})
    void corpoNonLeggibileComeJson_400MalformedRequestSenzaDetailsEPortaNonChiamata(String content) throws Exception {
        postJson(content)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is not valid JSON"))
                .andExpect(jsonPath("$.details").doesNotExist());
        assertThat(fake.requests()).isEmpty();
    }

    // W6 (caso 10)
    @Test
    void currencyMancante_400InvalidRequestConDettaglio() throws Exception {
        String content = "{\"transferId\":\"" + ID + "\",\"sourceIban\":\"" + IBAN_1
                + "\",\"destinationIban\":\"" + IBAN_2 + "\",\"amount\":100.25}";

        postJson(content)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.details").value(new ArrayList<>(List.of("currency: must not be null"))));
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void currencyNulla_400InvalidRequestConDettaglio() throws Exception {
        postJson(body(IBAN_1, IBAN_2, "100.25", "null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details").value(new ArrayList<>(List.of("currency: must not be null"))));
        assertThat(fake.requests()).isEmpty();
    }

    // W7
    @Test
    void oggettoVuoto_400ConCinqueDettagliInOrdineAlfabetico() throws Exception {
        postJson("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details").value(new ArrayList<>(List.of(
                        "amount: must not be null",
                        "currency: must not be null",
                        "destinationIban: must not be null",
                        "sourceIban: must not be null",
                        "transferId: must not be null"))));
        assertThat(fake.requests()).isEmpty();
    }

    // W8
    @ParameterizedTest
    @ValueSource(strings = {"\"100.25\"", "\"abc\""})
    void importoComeStringa_400InvalidRequestAmountMustBeANumber(String amountJson) throws Exception {
        postJson(body(amountJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.details").value(new ArrayList<>(List.of("amount: must be a number"))));
        assertThat(fake.requests()).isEmpty();
    }

    // derivato dall'ambiguita 5/8: tipo errato su un campo stringa
    @Test
    void currencyComeOggetto_400InvalidRequestCurrencyMustBeAString() throws Exception {
        postJson(body(IBAN_1, IBAN_2, "100.25", "{\"a\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details").value(new ArrayList<>(List.of("currency: must be a string"))));
        assertThat(fake.requests()).isEmpty();
    }

    // W9 (casi 8, 9, 11)
    @Test
    void portaLanciaInvalidFraudCheckRequest_400InvalidRequestSenzaDetails() throws Exception {
        fake.throwing(new InvalidFraudCheckRequestException(new InvalidFraudRequestException("x")));

        postJson(body("0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    // W10 (caso 12)
    @Test
    void portaLanciaUnsupportedCurrency_400UnsupportedCurrency() throws Exception {
        fake.throwing(new UnsupportedCurrencyException("Only EUR is supported"));

        postJson(body(IBAN_1, IBAN_2, "100", "\"USD\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"))
                .andExpect(jsonPath("$.message").value("Only EUR is supported"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    // W11 (caso 14)
    @Test
    void contentTypeTextPlain_415UnsupportedMediaTypeEPortaNonChiamata() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(body("100.25")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("Content-Type must be application/json"));
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void contentTypeAssente_415UnsupportedMediaTypeEPortaNonChiamata() throws Exception {
        mvc.perform(post(PATH).content(body("100.25")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        assertThat(fake.requests()).isEmpty();
    }

    // W12
    @Test
    void runtimeExceptionConDatiSensibiliNelMessaggio_500InternalErrorGenerico() throws Exception {
        fake.throwing(new RuntimeException(IBAN_1 + " 1234.56"));

        postJson(body("1234.56"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal error"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void nullPointerException_500InternalError() throws Exception {
        fake.throwing(new NullPointerException());

        postJson(body("100.25"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal error"));
    }

    // W13 (vincolo fraud-02)
    @Test
    void portaLanciaLaClasseBaseInvalidFraudRequest_500NonQuattrocento() throws Exception {
        fake.throwing(new InvalidFraudRequestException("invariante violata"));

        postJson(body("100.25"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal error"));
    }

    // W14
    @Test
    void ogniErrore_ilCorpoNonContieneIbanImportoNeDettagliInterni() throws Exception {
        String secretMsg = IBAN_1 + " " + IBAN_2 + " 1234.56";
        List<String> responses = new ArrayList<>();

        // corpo non JSON che contiene dati sensibili
        responses.add(postJson("{\"sourceIban\":\"" + IBAN_1 + "\",\"destinationIban\":\"" + IBAN_2
                + "\",\"amount\":1234.56,").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString());
        // campo mancante con dati sensibili negli altri campi
        responses.add(postJson("{\"sourceIban\":\"" + IBAN_1 + "\",\"destinationIban\":\"" + IBAN_2
                + "\",\"amount\":1234.56}").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString());
        // importo come stringa
        responses.add(postJson(body(IBAN_1, IBAN_2, "\"1234.56\"", "\"EUR\""))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        // 415 con corpo sensibile
        responses.add(mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(body("1234.56")))
                .andExpect(status().isUnsupportedMediaType()).andReturn().getResponse().getContentAsString());

        // eccezioni dalla porta con messaggi sensibili
        for (RuntimeException e : List.of(
                new InvalidFraudCheckRequestException(new InvalidFraudRequestException(secretMsg)),
                new UnsupportedCurrencyException(secretMsg),
                new InvalidFraudRequestException(secretMsg),
                new RuntimeException(secretMsg),
                new NullPointerException(secretMsg))) {
            MockMvc localMvc = mvcFor(new FakeCheckTransferInPort().throwing(e));
            responses.add(localMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content(body("1234.56")))
                    .andReturn().getResponse().getContentAsString());
        }

        assertThat(responses).hasSize(9).allSatisfy(r -> assertThat(r)
                .isNotBlank()
                .doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("1234.56")
                .doesNotContain("Exception").doesNotContain("at com."));
    }

    // W15 (specifica cambiata, caso 16): IBAN uguali = 400 INVALID_REQUEST.
    // La regola sta nel dominio: il controller non la applica, inoltra alla porta e traduce l'errore.
    @Test
    void ibanUguali_ilControllerInoltraAllaPortaELaRispostaE400InvalidRequest() throws Exception {
        fake.throwing(new InvalidFraudCheckRequestException(new InvalidFraudRequestException("x")));

        postJson(body(IBAN_1, IBAN_1, "100.25", "\"EUR\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request validation failed"));

        assertThat(fake.requests()).hasSize(1);
        assertThat(fake.requests().get(0).sourceIban()).isEqualTo(fake.requests().get(0).destinationIban());
    }
}

package com.bank.fraud.rest.port.in.rest;

import com.bank.fraud.application.port.out.BlockedIbanOutPort;
import com.bank.fraud.application.service.CheckTransferServiceImpl;
import com.bank.fraud.domain.Iban;
import com.bank.fraud.rest.mapper.FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapperImpl;
import com.bank.fraud.rest.mapper.FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapperImpl;
import com.bank.fraud.rest.port.in.rest.exception.WebExceptionHandler;
import com.bank.fraud.rest.servicedto.internal.FraudCheckServiceDtoImpl;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scenari della sezione 11 del contratto fraud-check lato HTTP, con il service reale
 * (soglia 10000) e una lambda come BlockedIbanOutPort. MockMvc standalone, nessun contesto Spring.
 */
class FraudCheckContractScenariosTest {

    private static final String PATH = "/fraud-check";
    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";
    private static final String BLOCKED = "IT60X0542811101000000000099";

    private static final String AMOUNT_MSG = "Amount must not exceed 10000 EUR";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvcWithBlocked(Set.of(new Iban(BLOCKED)));
    }

    private void mvcWithBlocked(Set<Iban> blocked) {
        BlockedIbanOutPort port = ibans -> ibans.stream().filter(blocked::contains).collect(Collectors.toSet());
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new FraudCheckController(new FraudCheckServiceDtoImpl(
                        new CheckTransferServiceImpl(port, new BigDecimal("10000")),
                        new FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapperImpl(),
                        new FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapperImpl())))
                .setControllerAdvice(new WebExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private ResultActions send(String source, String destination, String amount, String currency) throws Exception {
        String content = "{\"transferId\":\"" + ID + "\",\"sourceIban\":\"" + source
                + "\",\"destinationIban\":\"" + destination + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\"}";
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    // ---- casi 1-7 ----

    @Test
    void caso1_casoFelice_200Allowed() throws Exception {
        send(IBAN_1, IBAN_2, "1000.25", "EUR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(ID))
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.reasons").doesNotExist());
    }

    @Test
    void caso2_importoUgualeAllaSoglia_200Allowed() throws Exception {
        send(IBAN_1, IBAN_2, "10000.00", "EUR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOWED"));
    }

    // CS5
    @Test
    void caso3_importoOltreLaSoglia_200RejectedConMotivoImporto() throws Exception {
        send(IBAN_1, IBAN_2, "10000.01", "EUR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons").value(new ArrayList<>(List.of(AMOUNT_MSG))));
    }

    @Test
    void caso4_mittenteBloccato_200RejectedConMotivoMittente() throws Exception {
        send(BLOCKED, IBAN_2, "100", "EUR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons").value(new ArrayList<>(List.of("Source IBAN is locked"))));
    }

    @Test
    void caso5_destinatarioBloccato_200RejectedConMotivoDestinatario() throws Exception {
        send(IBAN_1, BLOCKED, "100", "EUR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons").value(new ArrayList<>(List.of("Destination IBAN is locked"))));
    }

    // CS6
    @Test
    void caso6_entrambiBloccati_200RejectedMittentePoiDestinatario() throws Exception {
        mvcWithBlocked(Set.of(new Iban(BLOCKED), new Iban(IBAN_2)));

        send(BLOCKED, IBAN_2, "100", "EUR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons").value(new ArrayList<>(List.of("Source IBAN is locked", "Destination IBAN is locked"))));
    }

    @Test
    void caso7_sogliaEMittenteBloccato_200RejectedImportoPoiMittente() throws Exception {
        send(BLOCKED, IBAN_2, "20000.00", "EUR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons").value(new ArrayList<>(List.of(AMOUNT_MSG, "Source IBAN is locked"))));
    }

    // ---- casi di errore ----

    // CS1
    @Test
    void caso8_importoZero_400InvalidRequest() throws Exception {
        send(IBAN_1, IBAN_2, "0", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request validation failed"));
    }

    // CS2
    @Test
    void caso9_troppiDecimali_400InvalidRequest() throws Exception {
        send(IBAN_1, IBAN_2, "10.001", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // CS3
    @Test
    void caso11_ibanMittenteVuoto_400InvalidRequest() throws Exception {
        send("", IBAN_2, "100", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // CS4
    @Test
    void caso12_valutaUsd_400UnsupportedCurrency() throws Exception {
        send(IBAN_1, IBAN_2, "100", "USD")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"))
                .andExpect(jsonPath("$.message").value("Only EUR is supported"));
    }

    @Test
    void caso15_idempotenza_stessaRichiestaDueVolteStessaRisposta() throws Exception {
        String first = send(IBAN_1, IBAN_2, "10000.01", "EUR").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = send(IBAN_1, IBAN_2, "10000.01", "EUR").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(first).contains("REJECTED").contains(AMOUNT_MSG);
    }

    // ---- caso 16: IBAN uguali (specifica cambiata) ----

    @Test
    void caso16_ibanUguali_400InvalidRequest() throws Exception {
        send(IBAN_1, IBAN_1, "100", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {" it60 x054 2811 1010 0000 0000 001 ", "it60x0542811101000000000001"})
    void caso16_ibanUgualiScrittiConSpaziOMinuscoleDiverse_400InvalidRequest(String destination) throws Exception {
        send(IBAN_1, destination, "100", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void caso16_ibanUgualiEBloccati_400InvalidRequestENonRejected() throws Exception {
        send(BLOCKED, BLOCKED, "100", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void caso16_ibanUgualiConValutaUsd_400InvalidRequestENonUnsupportedCurrency() throws Exception {
        send(IBAN_1, IBAN_1, "100", "USD")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request validation failed"));
    }

    @Test
    void caso16_ibanUgualiScrittiConSpaziEMinuscoleDiverseConValutaUsd_400InvalidRequest() throws Exception {
        send(IBAN_1, " it60 x054 2811 1010 0000 0000 001 ", "100", "USD")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void caso16_ilCorpoDellErroreNonContieneGliIban()throws Exception {
        String response = send(IBAN_1, IBAN_1, "1234.56", "EUR").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(IBAN_1).doesNotContain("1234.56")
                .doesNotContain("Exception").doesNotContain("at com.");
    }
}

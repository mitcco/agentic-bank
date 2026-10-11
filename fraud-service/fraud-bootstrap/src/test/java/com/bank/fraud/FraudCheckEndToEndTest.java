package com.bank.fraud;

import com.bank.fraud.application.port.in.CheckTransferInPort;
import com.bank.fraud.application.port.out.BlockedIbanOutPort;
import com.bank.fraud.application.service.CheckTransferServiceImpl;
import com.bank.fraud.persistence.port.out.persistence.impl.inmemory.InMemoryBlockedIbanOutPortImpl;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Casi 1-16 della sezione 11 del contratto fraud-check sulla configurazione reale
 * (piano fraud-06, I1-I22). Ambiente MOCK, nessuna porta, nessun Docker.
 * La stampa di MockMvc e' disattivata: un fallimento non deve stampare IBAN o importi.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class FraudCheckEndToEndTest {

    private static final String PATH = "/fraud-check";
    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";
    private static final String BLOCKED = "IT60X0542811101000000000099";
    private static final String AMOUNT_MSG = "Amount must not exceed 10000 EUR";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    private static String body(String transferId, String source, String destination, String amount,
            String currency) {
        return "{\"transferId\":\"" + transferId + "\",\"sourceIban\":\"" + source
                + "\",\"destinationIban\":\"" + destination + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\"}";
    }

    private static ResultActions sendRaw(MockMvc mvc, MediaType type, String content) throws Exception {
        return mvc.perform(post(PATH).contentType(type).content(content));
    }

    private ResultActions send(String source, String destination, String amount, String currency)
            throws Exception {
        return sendRaw(mvc, MediaType.APPLICATION_JSON, body(ID, source, destination, amount, currency));
    }

    // ---- casi 1-7 ----

    @Test
    void i1_casoFelice_200AllowedSenzaReasonsConTransferIdEcheggiato() throws Exception {
        send(IBAN_1, IBAN_2, "1000.25", "EUR")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transferId").value(ID))
                .andExpect(jsonPath("$.status").value("ALLOWED"))
                .andExpect(jsonPath("$.reasons").doesNotExist());
    }

    @Test
    void i2_importoUgualeAllaSoglia_200Allowed() throws Exception {
        send(IBAN_1, IBAN_2, "10000.00", "EUR")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ALLOWED"));
    }

    @Test
    void i3_importoOltreLaSoglia_200RejectedConMotivoImporto() throws Exception {
        send(IBAN_1, IBAN_2, "10000.01", "EUR")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons.length()").value(1))
                .andExpect(jsonPath("$.reasons[0]").value(AMOUNT_MSG));
    }

    @Test
    void i4_mittenteBloccato_200RejectedConMotivoMittente() throws Exception {
        send(BLOCKED, IBAN_2, "100", "EUR")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons.length()").value(1))
                .andExpect(jsonPath("$.reasons[0]").value("Source IBAN is locked"));
    }

    @Test
    void i5_destinatarioBloccato_200RejectedConMotivoDestinatario() throws Exception {
        send(IBAN_1, BLOCKED, "100", "EUR")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons.length()").value(1))
                .andExpect(jsonPath("$.reasons[0]").value("Destination IBAN is locked"));
    }

    @Nested
    @SpringBootTest(properties = "fraud.blocked-ibans=" + BLOCKED + "," + IBAN_2)
    @AutoConfigureMockMvc(print = MockMvcPrint.NONE)
    class ConDueIbanBloccati {

        @Autowired
        private MockMvc nestedMvc;

        @Test
        void i6_entrambiBloccati_200RejectedMittentePoiDestinatario() throws Exception {
            sendRaw(nestedMvc, MediaType.APPLICATION_JSON, body(ID, BLOCKED, IBAN_2, "100", "EUR"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value("REJECTED"))
                    .andExpect(jsonPath("$.reasons.length()").value(2))
                    .andExpect(jsonPath("$.reasons[0]").value("Source IBAN is locked"))
                    .andExpect(jsonPath("$.reasons[1]").value("Destination IBAN is locked"));
        }
    }

    @Test
    void i7_sogliaEMittenteBloccato_200RejectedImportoPoiMittente() throws Exception {
        send(BLOCKED, IBAN_2, "20000.00", "EUR")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reasons.length()").value(2))
                .andExpect(jsonPath("$.reasons[0]").value(AMOUNT_MSG))
                .andExpect(jsonPath("$.reasons[1]").value("Source IBAN is locked"));
    }

    // ---- casi di errore 8-14, 16, 17 ----

    @Test
    void i8_importoZero_400InvalidRequest() throws Exception {
        send(IBAN_1, IBAN_2, "0", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void i9_troppiDecimali_400InvalidRequest() throws Exception {
        send(IBAN_1, IBAN_2, "10.001", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void i10_currencyMancante_400InvalidRequestConDettaglioDelCampo() throws Exception {
        String noCurrency = "{\"transferId\":\"" + ID + "\",\"sourceIban\":\"" + IBAN_1
                + "\",\"destinationIban\":\"" + IBAN_2 + "\",\"amount\":100}";

        sendRaw(mvc, MediaType.APPLICATION_JSON, noCurrency)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0]").value("currency: must not be null"));
    }

    @Test
    void i11_sourceIbanVuoto_400InvalidRequest() throws Exception {
        send("", IBAN_2, "100", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void i12_valutaUsd_400UnsupportedCurrency() throws Exception {
        send(IBAN_1, IBAN_2, "100", "USD")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"))
                .andExpect(jsonPath("$.message").value("Only EUR is supported"));
    }

    @Test
    void i13_corpoNonJson_400MalformedRequest() throws Exception {
        sendRaw(mvc, MediaType.APPLICATION_JSON, "testo qualunque")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void i14_contentTypeTextPlain_415UnsupportedMediaType() throws Exception {
        sendRaw(mvc, MediaType.TEXT_PLAIN, body(ID, IBAN_1, IBAN_2, "100", "EUR"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void i15_stessaRichiestaDelCaso3DueVolte_corpiIdentici() throws Exception {
        String first = send(IBAN_1, IBAN_2, "10000.01", "EUR").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = send(IBAN_1, IBAN_2, "10000.01", "EUR").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(first).contains("REJECTED").contains(AMOUNT_MSG);
    }

    @Test
    void i16_ibanUguali_400InvalidRequest() throws Exception {
        send(IBAN_1, IBAN_1, "100", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void i16_ibanUgualiScrittiConSpaziEMinuscoleDiverse_400InvalidRequest() throws Exception {
        send(IBAN_1, " it60x0542811101000000000001 ", "100", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void i17_amountStringa_400InvalidRequestConDettaglioAmountMustBeANumber() throws Exception {
        send(IBAN_1, IBAN_2, "\"100.25\"", "EUR")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0]").value("amount: must be a number"));
    }

    // ---- I19: i corpi di errore non contengono dati sensibili ----

    static Stream<Arguments> errorRequests() {
        String noCurrency = "{\"transferId\":\"" + ID + "\",\"sourceIban\":\"" + IBAN_1
                + "\",\"destinationIban\":\"" + IBAN_2 + "\",\"amount\":1234.56}";
        return Stream.of(
                Arguments.of("i8 importo zero", MediaType.APPLICATION_JSON, body(ID, IBAN_1, IBAN_2, "0", "EUR"), "\"amount\":0"),
                Arguments.of("i9 troppi decimali", MediaType.APPLICATION_JSON,
                        body(ID, IBAN_1, IBAN_2, "1234.567", "EUR"), "1234.567"),
                Arguments.of("i10 currency mancante", MediaType.APPLICATION_JSON, noCurrency, "1234.56"),
                Arguments.of("i11 iban vuoto", MediaType.APPLICATION_JSON,
                        body(ID, "", IBAN_2, "1234.56", "EUR"), "1234.56"),
                Arguments.of("i12 valuta USD", MediaType.APPLICATION_JSON,
                        body(ID, IBAN_1, IBAN_2, "1234.56", "USD"), "1234.56"),
                Arguments.of("i13 corpo non json", MediaType.APPLICATION_JSON, "testo qualunque", "testo qualunque"),
                Arguments.of("i14 text/plain", MediaType.TEXT_PLAIN,
                        body(ID, IBAN_1, IBAN_2, "1234.56", "EUR"), "1234.56"),
                Arguments.of("i16 iban uguali", MediaType.APPLICATION_JSON,
                        body(ID, IBAN_1, IBAN_1, "1234.56", "EUR"), "1234.56"),
                Arguments.of("i16 iban bloccati uguali", MediaType.APPLICATION_JSON,
                        body(ID, BLOCKED, BLOCKED, "1234.56", "EUR"), "1234.56"),
                Arguments.of("i17 amount stringa", MediaType.APPLICATION_JSON,
                        body(ID, IBAN_1, IBAN_2, "\"1234.56\"", "EUR"), "1234.56"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorRequests")
    void i18_i19_ogniErrore_eJsonESenzaIbanImportoNeStackTrace(String name, MediaType type, String content,
            String sentAmount) throws Exception {
        String response = sendRaw(mvc, type, content)
                .andReturn().getResponse().getContentAsString();

        assertThat(response)
                .doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain(BLOCKED)
                .doesNotContain(sentAmount)
                .doesNotContain("Exception").doesNotContain("at com.");
        sendRaw(mvc, type, content)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    // ---- I20: output ----

    @Test
    void i20_durante_leRichieste_nelloOutputNonFinisconoIbanNeImporti(CapturedOutput output) throws Exception {
        String before = output.getAll();

        send(IBAN_1, IBAN_2, "1000.25", "EUR");
        send(IBAN_1, IBAN_2, "10000.01", "EUR");
        send(BLOCKED, IBAN_2, "20000.00", "EUR");
        send(IBAN_1, IBAN_1, "100", "EUR");
        send(IBAN_1, IBAN_2, "100", "USD");
        sendRaw(mvc, MediaType.APPLICATION_JSON, "testo qualunque");
        sendRaw(mvc, MediaType.TEXT_PLAIN, body(ID, IBAN_1, IBAN_2, "1000.25", "EUR"));

        String delta = output.getAll().substring(before.length());
        assertThat(delta)
                .doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain(BLOCKED)
                .doesNotContain("1000.25").doesNotContain("10000.01").doesNotContain("20000.00");
    }

    // ---- I21 / I22 ----

    @Test
    void i21_transferIdNonUuid_vieneRestituitoIdentico() throws Exception {
        sendRaw(mvc, MediaType.APPLICATION_JSON, body("abc", IBAN_1, IBAN_2, "100", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("abc"))
                .andExpect(jsonPath("$.status").value("ALLOWED"));
    }

    @Test
    void i22_configurazioneDiDefault_soglia10000EIbanBloccatoDiProva() {
        assertThat(environment.getProperty("fraud.threshold")).isEqualTo("10000");
        assertThat(environment.getProperty("fraud.blocked-ibans")).isEqualTo(BLOCKED);
    }

    @Test
    void i22_nessunDataSource() {
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
    }

    @Test
    void contestoReale_unSoloBeanPerPortaEPerService() {
        assertThat(context.getBeansOfType(CheckTransferInPort.class)).hasSize(1);
        assertThat(context.getBeansOfType(CheckTransferServiceImpl.class)).hasSize(1);
        assertThat(context.getBeansOfType(BlockedIbanOutPort.class)).hasSize(1);
        assertThat(context.getBeansOfType(InMemoryBlockedIbanOutPortImpl.class)).hasSize(1);
    }
}

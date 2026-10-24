package com.bank.fraud;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Metriche di business sulla configurazione reale (piano fraud-07, I1-I6). Ambiente MOCK,
 * nessun Docker. Il contesto e' condiviso con altri test: le asserzioni sui contatori
 * sono a delta, mai sul valore assoluto.
 *
 * I5: l'annotazione di Boot 4 che abilita l'export nei test (AutoConfigureMetrics, modulo
 * spring-boot-micrometer-metrics-test) non e' nel classpath di fraud-bootstrap e non si
 * aggiunge. Senza di essa l'export non viene disabilitato nei test, quindi
 * /actuator/prometheus risponde con il registry Prometheus reale.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class FraudMetricsIntegrationTest {

    private static final String PATH = "/fraud-check";
    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";
    private static final String BLOCKED = "IT60X0542811101000000000099";

    private static final String ASSESSMENTS = "bank.fraud.assessments";
    private static final String REASONS = "bank.fraud.reasons";
    private static final Pattern ENUM_NAME = Pattern.compile("^[A-Z_]+$");
    private static final Pattern IBAN_LIKE = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry registry;

    private static String body(String source, String destination, String amount, String currency) {
        return "{\"transferId\":\"" + ID + "\",\"sourceIban\":\"" + source
                + "\",\"destinationIban\":\"" + destination + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\"}";
    }

    private ResultActions send(String source, String destination, String amount, String currency)
            throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(body(source, destination, amount, currency)));
    }

    private double statusCount(String status) {
        return registry.get(ASSESSMENTS).tag("status", status).counter().count();
    }

    private double reasonCount(String reason) {
        return registry.get(REASONS).tag("reason", reason).counter().count();
    }

    private static final Pattern SAMPLE_LINE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)\\})? (\\S+)(?: \\S+)?$");
    private static final Pattern TAG = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"([^\"]*)\"");

    /** Vero se una riga dell'export ha il nome dato, tutti i tag richiesti (ordine libero, tag extra ammessi) e un valore numerico. */
    private static boolean hasSample(List<String> lines, String name, Map<String, String> requiredTags) {
        for (String line : lines) {
            Matcher m = SAMPLE_LINE.matcher(line);
            if (!m.matches() || !m.group(1).equals(name)) {
                continue;
            }
            Map<String, String> tags = new HashMap<>();
            if (m.group(2) != null) {
                Matcher t = TAG.matcher(m.group(2));
                while (t.find()) {
                    tags.put(t.group(1), t.group(2));
                }
            }
            if (!tags.entrySet().containsAll(requiredTags.entrySet())) {
                continue;
            }
            try {
                Double.parseDouble(m.group(3));
                return true;
            } catch (NumberFormatException e) {
                // valore non numerico: la riga non vale
            }
        }
        return false;
    }

    private List<Meter> businessMeters() {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("bank.fraud."))
                .toList();
    }

    // ---- I1, I2 ----

    @Test
    void i1_casoFelice_assessmentsAllowedAumentaDiUno() throws Exception {
        double allowedBefore = statusCount("ALLOWED");
        double rejectedBefore = statusCount("REJECTED");

        send(IBAN_1, IBAN_2, "1000.25", "EUR").andExpect(status().isOk());

        assertThat(statusCount("ALLOWED")).isEqualTo(allowedBefore + 1);
        assertThat(statusCount("REJECTED")).isEqualTo(rejectedBefore);
    }

    @Test
    void i2_sogliaEMittenteBloccato_rejectedEDueMotiviAumentanoDiUno() throws Exception {
        double rejectedBefore = statusCount("REJECTED");
        double allowedBefore = statusCount("ALLOWED");
        double amountBefore = reasonCount("AMOUNT_OVER_THRESHOLD");
        double sourceBefore = reasonCount("SOURCE_IBAN_LOCKED");
        double destinationBefore = reasonCount("DESTINATION_IBAN_LOCKED");

        send(BLOCKED, IBAN_2, "20000.00", "EUR").andExpect(status().isOk());

        assertThat(statusCount("REJECTED")).isEqualTo(rejectedBefore + 1);
        assertThat(statusCount("ALLOWED")).isEqualTo(allowedBefore);
        assertThat(reasonCount("AMOUNT_OVER_THRESHOLD")).isEqualTo(amountBefore + 1);
        assertThat(reasonCount("SOURCE_IBAN_LOCKED")).isEqualTo(sourceBefore + 1);
        assertThat(reasonCount("DESTINATION_IBAN_LOCKED")).isEqualTo(destinationBefore);
    }

    @Test
    void i2_importoUgualeAllaSoglia_contaAllowedENessunMotivo() throws Exception {
        double allowedBefore = statusCount("ALLOWED");
        double amountBefore = reasonCount("AMOUNT_OVER_THRESHOLD");

        send(IBAN_1, IBAN_2, "10000.00", "EUR").andExpect(status().isOk());

        assertThat(statusCount("ALLOWED")).isEqualTo(allowedBefore + 1);
        assertThat(reasonCount("AMOUNT_OVER_THRESHOLD")).isEqualTo(amountBefore);
    }

    // ---- I3 ----

    @Test
    void i3_richiesteNonValide_nessunContatoreCambia() throws Exception {
        double allowedBefore = statusCount("ALLOWED");
        double rejectedBefore = statusCount("REJECTED");
        double amountBefore = reasonCount("AMOUNT_OVER_THRESHOLD");
        double sourceBefore = reasonCount("SOURCE_IBAN_LOCKED");
        double destinationBefore = reasonCount("DESTINATION_IBAN_LOCKED");

        send(IBAN_1, IBAN_2, "0", "EUR").andExpect(status().isBadRequest());
        send(IBAN_1, IBAN_2, "100", "USD").andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("testo qualunque"))
                .andExpect(status().isBadRequest());
        send(IBAN_1, IBAN_1, "100", "EUR").andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(body(IBAN_1, IBAN_2, "100", "EUR")))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(statusCount("ALLOWED")).isEqualTo(allowedBefore);
        assertThat(statusCount("REJECTED")).isEqualTo(rejectedBefore);
        assertThat(reasonCount("AMOUNT_OVER_THRESHOLD")).isEqualTo(amountBefore);
        assertThat(reasonCount("SOURCE_IBAN_LOCKED")).isEqualTo(sourceBefore);
        assertThat(reasonCount("DESTINATION_IBAN_LOCKED")).isEqualTo(destinationBefore);
    }

    // ---- registrazione a zero all'avvio ----

    @Test
    void contestoReale_leCinqueSerieEsistonoGiaAllAvvio() {
        assertThat(registry.find(ASSESSMENTS).counters()).hasSize(2);
        assertThat(registry.find(REASONS).counters()).hasSize(3);
    }

    // ---- I4 ----

    @Test
    void i4_dopoLeRichieste_ituttiIMeterBusinessHannoApplicationFraudESoloTagEnum() throws Exception {
        send(IBAN_1, IBAN_2, "1000.25", "EUR").andExpect(status().isOk());
        send(BLOCKED, IBAN_2, "20000.00", "EUR").andExpect(status().isOk());
        send(IBAN_1, IBAN_2, "0", "EUR").andExpect(status().isBadRequest());

        List<Meter> meters = businessMeters();
        assertThat(meters).hasSize(5);
        for (Meter meter : meters) {
            List<Tag> tags = meter.getId().getTags();
            assertThat(meter.getId().getTag("application")).isEqualTo("fraud");
            assertThat(tags).extracting(Tag::getKey).isSubsetOf("application", "status", "reason");
            for (Tag tag : tags) {
                if (!tag.getKey().equals("application")) {
                    assertThat(tag.getValue()).matches(ENUM_NAME);
                }
                assertThat(IBAN_LIKE.matcher(tag.getValue()).find())
                        .as("il tag %s non contiene un IBAN", tag.getValue()).isFalse();
                assertThat(tag.getValue())
                        .doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain(BLOCKED)
                        .doesNotContain("1000.25").doesNotContain("20000.00").doesNotContain(ID);
            }
        }
    }

    // ---- I5 ----

    @Test
    void i5_actuatorPrometheus_200ConLeRigheDelleMetricheBusinessESenzaDatiDelBonifico() throws Exception {
        send(IBAN_1, IBAN_2, "1000.25", "EUR").andExpect(status().isOk());
        send(BLOCKED, IBAN_2, "20000.00", "EUR").andExpect(status().isOk());
        send(IBAN_1, IBAN_2, "0", "EUR").andExpect(status().isBadRequest());

        String scrape = mvc.perform(get("/actuator/prometheus").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> lines = scrape.lines().toList();
        assertThat(hasSample(lines, "bank_fraud_assessments_total", Map.of("application", "fraud", "status", "ALLOWED")))
                .as("assessments ALLOWED").isTrue();
        assertThat(hasSample(lines, "bank_fraud_assessments_total", Map.of("application", "fraud", "status", "REJECTED")))
                .as("assessments REJECTED").isTrue();
        assertThat(hasSample(lines, "bank_fraud_reasons_total", Map.of("application", "fraud", "reason", "AMOUNT_OVER_THRESHOLD")))
                .as("reason AMOUNT_OVER_THRESHOLD").isTrue();
        assertThat(hasSample(lines, "bank_fraud_reasons_total", Map.of("application", "fraud", "reason", "SOURCE_IBAN_LOCKED")))
                .as("reason SOURCE_IBAN_LOCKED").isTrue();
        assertThat(hasSample(lines, "bank_fraud_reasons_total", Map.of("application", "fraud", "reason", "DESTINATION_IBAN_LOCKED")))
                .as("reason DESTINATION_IBAN_LOCKED").isTrue();

        List<String> bankLines = scrape.lines().filter(line -> line.startsWith("bank_")).toList();
        assertThat(bankLines).isNotEmpty();
        assertThat(bankLines).noneMatch(line -> line.contains(IBAN_1) || line.contains(IBAN_2)
                || line.contains(BLOCKED) || line.contains("1000.25") || line.contains("20000.00")
                || line.contains(ID));
        assertThat(bankLines).noneMatch(line -> IBAN_LIKE.matcher(line).find());
    }

    // ---- I6 ----

    @Test
    void i6_durante_leRichieste_nelloOutputNonFinisconoIbanNeImporti(CapturedOutput output) throws Exception {
        String before = output.getAll();

        send(IBAN_1, IBAN_2, "1000.25", "EUR").andExpect(status().isOk());
        send(BLOCKED, IBAN_2, "20000.00", "EUR").andExpect(status().isOk());
        send(IBAN_1, IBAN_2, "10000.01", "EUR").andExpect(status().isOk());
        send(IBAN_1, IBAN_1, "100", "EUR").andExpect(status().isBadRequest());
        mvc.perform(get("/actuator/prometheus").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk());

        String delta = output.getAll().substring(before.length());
        assertThat(delta)
                .doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain(BLOCKED)
                .doesNotContain("1000.25").doesNotContain("20000.00").doesNotContain("10000.01");
    }
}

package com.bank.fraud.micrometer.port.out.micrometer.impl;

import com.bank.fraud.domain.FraudReasonEnum;
import com.bank.fraud.domain.FraudStatusEnum;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter Micrometer della porta delle metriche (piano fraud-07, D4 e D8): due contatori,
 * uno per esito e uno per motivo, registrati a zero all'avvio, con tag solo enum.
 */
class MicrometerMetricsOutPortImplTest {

    private static final String ASSESSMENTS = "bank.fraud.assessments";
    private static final String REASONS = "bank.fraud.reasons";
    private static final Pattern ENUM_NAME = Pattern.compile("^[A-Z_]+$");
    private static final Pattern IBAN_LIKE = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");
    private static final Pattern NUMBER_LIKE = Pattern.compile(".*\\d.*");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerMetricsOutPortImpl port = new MicrometerMetricsOutPortImpl(registry);

    private double assessments(FraudStatusEnum status) {
        Counter counter = registry.find(ASSESSMENTS).tag("status", status.name()).counter();
        assertThat(counter).as("counter %s{status=%s}", ASSESSMENTS, status).isNotNull();
        return counter.count();
    }

    private double reasons(FraudReasonEnum reason) {
        Counter counter = registry.find(REASONS).tag("reason", reason.name()).counter();
        assertThat(counter).as("counter %s{reason=%s}", REASONS, reason).isNotNull();
        return counter.count();
    }

    private List<Meter> businessMeters() {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("bank.fraud."))
                .toList();
    }

    // ---- registrazione a zero all'avvio (D8) ----

    @Test
    void registryVuoto_dopoLaCostruzione_duecontatoriAssessmentsEtreContatoriReasonsTuttiAZero() {
        assertThat(registry.find(ASSESSMENTS).counters()).hasSize(2);
        assertThat(registry.find(REASONS).counters()).hasSize(3);
        for (FraudStatusEnum status : FraudStatusEnum.values()) {
            assertThat(assessments(status)).isZero();
        }
        for (FraudReasonEnum reason : FraudReasonEnum.values()) {
            assertThat(reasons(reason)).isZero();
        }
    }

    @Test
    void dopoLaCostruzione_ISoliMeterBusinessSonoICinqueContatori() {
        assertThat(businessMeters()).hasSize(5).allMatch(meter -> meter instanceof Counter);
    }

    @Test
    void nomiSenzaSuffissoTotal_ilSuffissoLoAggiungeIlRegistryPrometheus() {
        assertThat(businessMeters()).extracting(meter -> meter.getId().getName())
                .containsOnly(ASSESSMENTS, REASONS)
                .noneMatch(name -> name.endsWith(".total") || name.endsWith("_total"));
    }

    // ---- incrementi ----

    @Test
    void allowedSenzaMotivi_incrementaSoloAssessmentsAllowed() {
        port.assessmentCompleted(FraudStatusEnum.ALLOWED, List.of());

        assertThat(assessments(FraudStatusEnum.ALLOWED)).isEqualTo(1.0);
        assertThat(assessments(FraudStatusEnum.REJECTED)).isZero();
        for (FraudReasonEnum reason : FraudReasonEnum.values()) {
            assertThat(reasons(reason)).isZero();
        }
    }

    @Test
    void rejectedConDueMotivi_incrementaRejectedEIDueMotiviMaNonIlTerzo() {
        port.assessmentCompleted(FraudStatusEnum.REJECTED,
                List.of(FraudReasonEnum.AMOUNT_OVER_THRESHOLD, FraudReasonEnum.SOURCE_IBAN_LOCKED));

        assertThat(assessments(FraudStatusEnum.REJECTED)).isEqualTo(1.0);
        assertThat(assessments(FraudStatusEnum.ALLOWED)).isZero();
        assertThat(reasons(FraudReasonEnum.AMOUNT_OVER_THRESHOLD)).isEqualTo(1.0);
        assertThat(reasons(FraudReasonEnum.SOURCE_IBAN_LOCKED)).isEqualTo(1.0);
        assertThat(reasons(FraudReasonEnum.DESTINATION_IBAN_LOCKED)).isZero();
    }

    @Test
    void rejectedConDueMotivi_contaUnaSolaValutazioneNonDue() {
        port.assessmentCompleted(FraudStatusEnum.REJECTED,
                List.of(FraudReasonEnum.SOURCE_IBAN_LOCKED, FraudReasonEnum.DESTINATION_IBAN_LOCKED));

        double totalAssessments = registry.find(ASSESSMENTS).counters().stream().mapToDouble(Counter::count).sum();
        double totalReasons = registry.find(REASONS).counters().stream().mapToDouble(Counter::count).sum();
        assertThat(totalAssessments).isEqualTo(1.0);
        assertThat(totalReasons).isEqualTo(2.0);
    }

    @Test
    void dueChiamateRejected_ivaloriSiCumulano() {
        List<FraudReasonEnum> reasons =
                List.of(FraudReasonEnum.AMOUNT_OVER_THRESHOLD, FraudReasonEnum.SOURCE_IBAN_LOCKED);

        port.assessmentCompleted(FraudStatusEnum.REJECTED, reasons);
        port.assessmentCompleted(FraudStatusEnum.REJECTED, reasons);

        assertThat(assessments(FraudStatusEnum.REJECTED)).isEqualTo(2.0);
        assertThat(reasons(FraudReasonEnum.AMOUNT_OVER_THRESHOLD)).isEqualTo(2.0);
        assertThat(reasons(FraudReasonEnum.SOURCE_IBAN_LOCKED)).isEqualTo(2.0);
        assertThat(reasons(FraudReasonEnum.DESTINATION_IBAN_LOCKED)).isZero();
    }

    @Test
    void allowedERejectedAlternati_ogniEsitoHaIlSuoContatore() {
        port.assessmentCompleted(FraudStatusEnum.ALLOWED, List.of());
        port.assessmentCompleted(FraudStatusEnum.REJECTED, List.of(FraudReasonEnum.DESTINATION_IBAN_LOCKED));
        port.assessmentCompleted(FraudStatusEnum.ALLOWED, List.of());

        assertThat(assessments(FraudStatusEnum.ALLOWED)).isEqualTo(2.0);
        assertThat(assessments(FraudStatusEnum.REJECTED)).isEqualTo(1.0);
        assertThat(reasons(FraudReasonEnum.DESTINATION_IBAN_LOCKED)).isEqualTo(1.0);
    }

    @Test
    void dopoLeChiamate_nonSiCreanoNuoveSerie() {
        port.assessmentCompleted(FraudStatusEnum.REJECTED, List.of(
                FraudReasonEnum.AMOUNT_OVER_THRESHOLD,
                FraudReasonEnum.SOURCE_IBAN_LOCKED,
                FraudReasonEnum.DESTINATION_IBAN_LOCKED));
        port.assessmentCompleted(FraudStatusEnum.ALLOWED, List.of());

        assertThat(businessMeters()).hasSize(5);
    }

    // ---- tag: solo enum, mai dati del bonifico ----

    @Test
    void qualsiasiSequenza_iTagHannoSoloChiaviStatusEReasonEValoriNomeDiEnum() {
        port.assessmentCompleted(FraudStatusEnum.ALLOWED, List.of());
        port.assessmentCompleted(FraudStatusEnum.REJECTED, List.of(
                FraudReasonEnum.AMOUNT_OVER_THRESHOLD,
                FraudReasonEnum.SOURCE_IBAN_LOCKED,
                FraudReasonEnum.DESTINATION_IBAN_LOCKED));

        assertThat(businessMeters()).isNotEmpty();
        for (Meter meter : businessMeters()) {
            List<Tag> tags = meter.getId().getTags();
            assertThat(tags).isNotEmpty();
            assertThat(tags).extracting(Tag::getKey).isSubsetOf("status", "reason");
            for (Tag tag : tags) {
                assertThat(tag.getValue())
                        .matches(ENUM_NAME)
                        .doesNotMatch(NUMBER_LIKE);
                assertThat(IBAN_LIKE.matcher(tag.getValue()).find())
                        .as("il tag %s non contiene un IBAN", tag.getValue()).isFalse();
            }
        }
    }

    @Test
    void ivaloriDeiTagCoincidonoConGliEnumDelDominioEBasta() {
        Set<String> statuses = Set.of(
                Arrays.stream(FraudStatusEnum.values()).map(Enum::name).toArray(String[]::new));
        Set<String> reasons = Set.of(
                Arrays.stream(FraudReasonEnum.values()).map(Enum::name).toArray(String[]::new));

        assertThat(registry.find(ASSESSMENTS).counters())
                .extracting(counter -> counter.getId().getTag("status"))
                .containsExactlyInAnyOrderElementsOf(statuses);
        assertThat(registry.find(REASONS).counters())
                .extracting(counter -> counter.getId().getTag("reason"))
                .containsExactlyInAnyOrderElementsOf(reasons);
    }

    @Test
    void imotiviDeiContatoriReasonsNonSonoIlTestoDelMessaggio() {
        assertThat(registry.find(REASONS).counters())
                .extracting(counter -> counter.getId().getTag("reason"))
                .noneMatch(value -> value.contains(" ") || value.contains("EUR"));
    }

    // ---- null ----

    @Test
    void registryNull_lanciaNullPointerException() {
        assertThatThrownBy(() -> new MicrometerMetricsOutPortImpl(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void statusNull_lanciaNullPointerExceptionENessunContatoreCambia() {
        assertThatThrownBy(() -> port.assessmentCompleted(null, List.of()))
                .isInstanceOf(NullPointerException.class);

        assertThat(registry.find(ASSESSMENTS).counters()).allMatch(counter -> counter.count() == 0.0);
        assertThat(registry.find(REASONS).counters()).allMatch(counter -> counter.count() == 0.0);
    }

    @Test
    void listaMotiviConElementoNull_lanciaNullPointerExceptionENessunContatoreCambia() {
        List<FraudReasonEnum> reasonsWithNull = Arrays.asList(FraudReasonEnum.AMOUNT_OVER_THRESHOLD, null);

        assertThatThrownBy(() -> port.assessmentCompleted(FraudStatusEnum.REJECTED, reasonsWithNull))
                .isInstanceOf(NullPointerException.class);

        assertThat(registry.find(ASSESSMENTS).counters()).allMatch(counter -> counter.count() == 0.0);
        assertThat(registry.find(REASONS).counters()).allMatch(counter -> counter.count() == 0.0);
    }

    @Test
    void listaMotiviNull_lanciaNullPointerExceptionENessunContatoreCambia() {
        assertThatThrownBy(() -> port.assessmentCompleted(FraudStatusEnum.REJECTED, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(registry.find(ASSESSMENTS).counters()).allMatch(counter -> counter.count() == 0.0);
        assertThat(registry.find(REASONS).counters()).allMatch(counter -> counter.count() == 0.0);
    }
}

package com.bank.fraud.config;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.application.port.in.CheckTransferInPort;
import com.bank.fraud.application.port.out.MetricsOutPort;
import com.bank.fraud.micrometer.port.out.micrometer.impl.MicrometerMetricsOutPortImpl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring della porta delle metriche in FraudConfig (piano fraud-07): il service deve usare
 * la porta reale e non quella che non fa nulla (rischio di D2 a).
 */
class FraudMetricsConfigTest {

    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
    private static final String BLOCKED = "IT60X0542811101000000000099";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FraudConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("fraud.threshold=10000", "fraud.blocked-ibans=" + BLOCKED);

    private static double count(MeterRegistry registry, String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    @Test
    void contestoAvviato_unSoloBeanMetricsOutPortDiTipoMicrometer() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(MetricsOutPort.class)).hasSize(1);
            assertThat(context.getBean(MetricsOutPort.class)).isInstanceOf(MicrometerMetricsOutPortImpl.class);
        });
    }

    @Test
    void contestoAvviato_ContatoriGiaRegistratiAZeroNelRegistry() {
        runner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(registry.find("bank.fraud.assessments").counters()).hasSize(2);
            assertThat(registry.find("bank.fraud.reasons").counters()).hasSize(3);
            assertThat(registry.find("bank.fraud.assessments").counters()).allMatch(c -> c.count() == 0.0);
            assertThat(registry.find("bank.fraud.reasons").counters()).allMatch(c -> c.count() == 0.0);
        });
    }

    @Test
    void checkTransferAllowed_ilServiceUsaLaPortaRealeEIlContatoreAllowedVale1() {
        runner.run(context -> {
            context.getBean(CheckTransferInPort.class).checkTransfer(
                    new FraudCheckRequestDto(ID, IBAN_1, IBAN_2, new BigDecimal("1000.25"), "EUR"));

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(count(registry, "bank.fraud.assessments", "status", "ALLOWED")).isEqualTo(1.0);
            assertThat(count(registry, "bank.fraud.assessments", "status", "REJECTED")).isZero();
        });
    }

    @Test
    void checkTransferRejected_ilServiceUsaLaPortaRealeEIncrementaEsitoEMotivi() {
        runner.run(context -> {
            context.getBean(CheckTransferInPort.class).checkTransfer(
                    new FraudCheckRequestDto(ID, BLOCKED, IBAN_2, new BigDecimal("20000.00"), "EUR"));

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(count(registry, "bank.fraud.assessments", "status", "REJECTED")).isEqualTo(1.0);
            assertThat(count(registry, "bank.fraud.reasons", "reason", "AMOUNT_OVER_THRESHOLD")).isEqualTo(1.0);
            assertThat(count(registry, "bank.fraud.reasons", "reason", "SOURCE_IBAN_LOCKED")).isEqualTo(1.0);
            assertThat(count(registry, "bank.fraud.reasons", "reason", "DESTINATION_IBAN_LOCKED")).isZero();
        });
    }

    @Test
    void senzaMeterRegistry_avvioFallitoNonSiUsaUnaPortaCheNonFaNulla() {
        new ApplicationContextRunner()
                .withUserConfiguration(FraudConfig.class)
                .withPropertyValues("fraud.threshold=10000", "fraud.blocked-ibans=" + BLOCKED)
                .run(context -> assertThat(context).hasFailed());
    }
}

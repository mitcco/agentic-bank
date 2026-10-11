package com.bank.fraud.config;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.application.dto.FraudCheckResponseDto;
import com.bank.fraud.application.port.in.CheckTransferInPort;
import com.bank.fraud.application.port.out.BlockedIbanOutPort;
import com.bank.fraud.application.service.CheckTransferServiceImpl;
import com.bank.fraud.domain.Iban;
import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configurazione dei bean di fraud-bootstrap (piano fraud-06, C1-C7 ed E1-E6).
 * Contesto minimo con la sola FraudConfig e proprieta' esplicite.
 */
class FraudConfigTest {

    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
    private static final String BLOCKED = "IT60X0542811101000000000099";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";
    private static final String BLOCKED_MESSY = " it60 x054 2811 1010 0000 0000 099 ";
    private static final String SHORT_IBAN = "IT60X054281110100000000009"; // 26 caratteri

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FraudConfig.class);

    private ApplicationContextRunner with(String threshold, String blocked) {
        return runner.withPropertyValues("fraud.threshold=" + threshold, "fraud.blocked-ibans=" + blocked);
    }

    private static Set<Iban> blockedAmong(ApplicationContext context, String... ibans) {
        Set<Iban> input = new java.util.LinkedHashSet<>();
        for (String iban : ibans) {
            input.add(new Iban(iban));
        }
        return context.getBean(BlockedIbanOutPort.class).findBlocked(input);
    }

    private static FraudCheckResponseDto check(ApplicationContext context, String amount) {
        return context.getBean(CheckTransferInPort.class)
                .checkTransfer(new FraudCheckRequestDto(ID, IBAN_1, IBAN_2, new BigDecimal(amount), "EUR"));
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static List<String> messages(Throwable t) {
        List<String> result = new ArrayList<>();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            result.add(String.valueOf(c.getMessage()));
        }
        return result;
    }

    // ---- C: configurazioni valide ----

    @Test
    void c1_conSogliaEIbanBloccatoDiDefault_unSoloBeanPerPortaEBloccaSoloLIbanConfigurato() {
        with("10000", BLOCKED).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(BlockedIbanOutPort.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(CheckTransferInPort.class)).hasSize(1);
            assertThat(blockedAmong(context, BLOCKED, IBAN_1)).containsExactly(new Iban(BLOCKED));
        });
    }

    @Test
    void c2_listaSeparataDaVirgolaESpazio_bloccaEntrambiGliIban() {
        with("10000", BLOCKED + ", " + IBAN_2).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(blockedAmong(context, BLOCKED, IBAN_1, IBAN_2))
                    .containsExactlyInAnyOrder(new Iban(BLOCKED), new Iban(IBAN_2));
        });
    }

    @Test
    void c3_listaVuota_avvioOkENessunIbanBloccato() {
        with("10000", "").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(blockedAmong(context, BLOCKED)).isEmpty();
        });
    }

    @Test
    void c4_vociVuoteOSoloSpazi_vengonoScartateEResta_soloLIbanValido() {
        with("10000", "," + BLOCKED + ",,   ,").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(blockedAmong(context, BLOCKED, IBAN_1)).containsExactly(new Iban(BLOCKED));
        });
    }

    @Test
    void c5_ibanScrittoConSpaziEMinuscole_vieneNormalizzatoEBloccato() {
        with("10000", BLOCKED_MESSY).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(blockedAmong(context, BLOCKED, IBAN_1)).containsExactly(new Iban(BLOCKED));
        });
    }

    @Test
    void c6_sogliaConfigurata5000_importo6000EuroRifiutatoConSogliaNelMotivo() {
        with("5000", BLOCKED).run(context -> {
            FraudCheckResponseDto response = check(context, "6000.00");

            assertThat(response.status()).isEqualTo("REJECTED");
            assertThat(response.reasons()).containsExactly("Amount must not exceed 5000 EUR");
        });
    }

    @Test
    void c7_sogliaConDueDecimali_ammetteLaSogliaERifiutaUnCentesimoInPiu_motivoSenzaZeriFinali() {
        with("10000.00", BLOCKED).run(context -> {
            assertThat(check(context, "10000.00").status()).isEqualTo("ALLOWED");

            FraudCheckResponseDto over = check(context, "10000.01");
            assertThat(over.status()).isEqualTo("REJECTED");
            assertThat(over.reasons()).containsExactly("Amount must not exceed 10000 EUR");
        });
    }

    // ---- E: configurazioni non valide, fail closed ----

    @Test
    void e1_ibanBloccatoNonValido_avvioFallitoPerInvalidFraudRequest() {
        with("10000", "NOT-AN-IBAN").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(InvalidFraudRequestException.class);
        });
    }

    @Test
    void e2_ibanBloccatoTroppoCorto_avvioFallitoENessunMessaggioContieneLIban() {
        with("10000", BLOCKED + "," + SHORT_IBAN).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(InvalidFraudRequestException.class);
            assertThat(messages(context.getStartupFailure())).noneMatch(m -> m.contains(SHORT_IBAN));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10.001"})
    void e3_sogliaNonPositivaOConTroppiDecimali_avvioFallitoPerInvalidFraudRequest(String threshold) {
        with(threshold, BLOCKED).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).isInstanceOf(InvalidFraudRequestException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", ""})
    void e4_sogliaNonNumericaOVuota_avvioFallito(String threshold) {
        with(threshold, BLOCKED).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void e5_sogliaAssente_avvioFallito() {
        runner.withPropertyValues("fraud.blocked-ibans=" + BLOCKED)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void e5_ibanBloccatiAssenti_avvioFallito() {
        runner.withPropertyValues("fraud.threshold=10000")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void e6_contestoAvviato_esattamenteUnBeanCheckTransferServiceImpl() {
        with("10000", BLOCKED).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(CheckTransferServiceImpl.class)).hasSize(1);
            assertThat(context.getBean(CheckTransferInPort.class)).isInstanceOf(CheckTransferServiceImpl.class);
        });
    }
}

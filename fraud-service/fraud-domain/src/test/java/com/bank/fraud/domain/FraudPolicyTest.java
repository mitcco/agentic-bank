package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudPolicyTest {

    private static final String BLOCKED = "IT60X0542811101000000000099";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";
    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";

    private final FraudPolicy policy = new FraudPolicy(eur("10000"));
    private final Predicate<Iban> blockedOnly = new Iban(BLOCKED)::equals;

    private static Money eur(String amount) {
        return new Money(new BigDecimal(amount), "EUR");
    }

    private static TransferToAssess transfer(String id, String source, String destination, String amount) {
        return new TransferToAssess(id, new Iban(source), new Iban(destination), eur(amount));
    }

    @Test
    void casoFelice_importoOrdinarioEIbanOrdinari_allowed() {
        FraudAssessment a = policy.assess(transfer(ID, IBAN_1, IBAN_2, "1000.25"), blockedOnly);

        assertThat(a.status()).isEqualTo(FraudStatusEnum.ALLOWED);
        assertThat(a.reasons()).isEmpty();
    }

    @Test
    void importoUgualeAllaSoglia_allowed() {
        FraudAssessment a = policy.assess(transfer(ID, IBAN_1, IBAN_2, "10000.00"), blockedOnly);

        assertThat(a.status()).isEqualTo(FraudStatusEnum.ALLOWED);
    }

    @Test
    void importoOltreLaSogliaDiUnCentesimo_rejectedPerImporto() {
        FraudAssessment a = policy.assess(transfer(ID, IBAN_1, IBAN_2, "10000.01"), blockedOnly);

        assertThat(a.status()).isEqualTo(FraudStatusEnum.REJECTED);
        assertThat(a.reasons()).containsExactly(FraudReasonEnum.AMOUNT_OVER_THRESHOLD);
    }

    @Test
    void mittenteBloccato_rejectedPerMittente() {
        FraudAssessment a = policy.assess(transfer(ID, BLOCKED, IBAN_2, "100"), blockedOnly);

        assertThat(a.status()).isEqualTo(FraudStatusEnum.REJECTED);
        assertThat(a.reasons()).containsExactly(FraudReasonEnum.SOURCE_IBAN_LOCKED);
    }

    @Test
    void destinatarioBloccato_rejectedPerDestinatario() {
        FraudAssessment a = policy.assess(transfer(ID, IBAN_1, BLOCKED, "100"), blockedOnly);

        assertThat(a.status()).isEqualTo(FraudStatusEnum.REJECTED);
        assertThat(a.reasons()).containsExactly(FraudReasonEnum.DESTINATION_IBAN_LOCKED);
    }

    @Test
    void entrambiBloccati_motiviMittentePoiDestinatario() {
        Predicate<Iban> both = i -> i.equals(new Iban(IBAN_1)) || i.equals(new Iban(IBAN_2));

        FraudAssessment a = policy.assess(transfer(ID, IBAN_1, IBAN_2, "100"), both);

        assertThat(a.reasons()).containsExactly(
                FraudReasonEnum.SOURCE_IBAN_LOCKED, FraudReasonEnum.DESTINATION_IBAN_LOCKED);
    }

    @Test
    void sogliaEMittenteBloccato_motiviImportoPoiMittente() {
        FraudAssessment a = policy.assess(transfer(ID, BLOCKED, IBAN_2, "20000.00"), blockedOnly);

        assertThat(a.reasons()).containsExactly(
                FraudReasonEnum.AMOUNT_OVER_THRESHOLD, FraudReasonEnum.SOURCE_IBAN_LOCKED);
    }

    @Test
    void tutteLeRegoleScattano_treMotiviInOrdine() {
        Predicate<Iban> both = i -> i.equals(new Iban(IBAN_1)) || i.equals(new Iban(IBAN_2));

        FraudAssessment a = policy.assess(transfer(ID, IBAN_1, IBAN_2, "20000.00"), both);

        assertThat(a.reasons()).containsExactly(
                FraudReasonEnum.AMOUNT_OVER_THRESHOLD,
                FraudReasonEnum.SOURCE_IBAN_LOCKED,
                FraudReasonEnum.DESTINATION_IBAN_LOCKED);
    }

    @Test
    void stessaRichiestaDueVolte_stessoEsito() {
        TransferToAssess t = transfer(ID, IBAN_1, IBAN_2, "10000.01");

        FraudAssessment first = policy.assess(t, blockedOnly);
        FraudAssessment second = policy.assess(t, blockedOnly);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void transferIdDiversi_ognunoRestituisceIlProprioEStessaDecisione() {
        FraudAssessment a = policy.assess(transfer("id-A", BLOCKED, IBAN_2, "100"), blockedOnly);
        FraudAssessment b = policy.assess(transfer("id-B", BLOCKED, IBAN_2, "100"), blockedOnly);

        assertThat(a.transferId()).isEqualTo("id-A");
        assertThat(b.transferId()).isEqualTo("id-B");
        assertThat(a.status()).isEqualTo(b.status());
        assertThat(a.reasons()).isEqualTo(b.reasons());
    }

    @Test
    void transferIdRestituitoIdenticoAncheSeNonUuid() {
        FraudAssessment a = policy.assess(transfer("  abc ", IBAN_1, IBAN_2, "100"), blockedOnly);

        assertThat(a.transferId()).isEqualTo("  abc ");
    }

    @Test
    void nessunIbanBloccato_allowed() {
        FraudAssessment a = policy.assess(transfer(ID, BLOCKED, IBAN_2, "100"), i -> false);

        assertThat(a.status()).isEqualTo(FraudStatusEnum.ALLOWED);
    }

    @Test
    void mittenteConSpaziEMinuscole_vieneRiconosciutoComeBloccato() {
        TransferToAssess t = new TransferToAssess(ID, new Iban(" it60 x054 2811 1010 0000 0000 099 "),
                new Iban(IBAN_2), eur("100"));

        FraudAssessment a = policy.assess(t, blockedOnly);

        assertThat(a.reasons()).containsExactly(FraudReasonEnum.SOURCE_IBAN_LOCKED);
    }

    @Test
    void mittenteUgualeADestinatarioBloccato_nonEPiuCostruibileEQuindiNonValutabile() {
        // Specifica cambiata (caso 16): prima P13 attendeva due motivi (Source poi Destination).
        // Ora il TransferToAssess con IBAN uguali non esiste: la policy non puo' riceverlo.
        assertThatThrownBy(() -> transfer(ID, BLOCKED, BLOCKED, "100"))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void sogliaConfigurata5000_importo6000RifiutatoETestoUsaLaSoglia() {
        FraudPolicy p = new FraudPolicy(eur("5000"));

        FraudAssessment a = p.assess(transfer(ID, IBAN_1, IBAN_2, "6000"), blockedOnly);

        assertThat(a.reasons()).containsExactly(FraudReasonEnum.AMOUNT_OVER_THRESHOLD);
        assertThat(a.reasons().get(0).message(p.threshold())).isEqualTo("Amount must not exceed 5000 EUR");
    }

    @Test
    void sogliaConfigurata5000_importoUgualeAllaSogliaAllowed() {
        FraudPolicy p = new FraudPolicy(eur("5000"));

        assertThat(p.assess(transfer(ID, IBAN_1, IBAN_2, "5000.00"), blockedOnly).status())
                .isEqualTo(FraudStatusEnum.ALLOWED);
    }

    @Test
    void thresholdRestituisceLaSogliaConfigurata() {
        assertThat(policy.threshold().amount()).isEqualByComparingTo("10000");
        assertThat(policy.threshold().currency()).isEqualTo("EUR");
    }

    @Test
    void messaggioConSoglia10000() {
        assertThat(FraudReasonEnum.AMOUNT_OVER_THRESHOLD.message(policy.threshold()))
                .isEqualTo("Amount must not exceed 10000 EUR");
    }

    @Test
    void ilPredicateEInvocatoSoloInLetturaEPerSourceEDestination() {
        List<Iban> asked = new ArrayList<>();
        Predicate<Iban> recording = i -> {
            asked.add(i);
            return false;
        };

        policy.assess(transfer(ID, IBAN_1, IBAN_2, "100"), recording);

        assertThat(asked).containsExactlyInAnyOrder(new Iban(IBAN_1), new Iban(IBAN_2));
    }

    @Test
    void sogliaNullaERifiutata() {
        assertThatThrownBy(() -> new FraudPolicy(null)).isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void sogliaZeroERifiutata() {
        assertThatThrownBy(() -> new FraudPolicy(eur("0"))).isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void sogliaInValutaDiversaDaEurERifiutata() {
        assertThatThrownBy(() -> new FraudPolicy(new Money(new BigDecimal("100"), "USD")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void transferNulloERifiutato() {
        assertThatThrownBy(() -> policy.assess(null, blockedOnly))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void predicateNulloERifiutato() {
        assertThatThrownBy(() -> policy.assess(transfer(ID, IBAN_1, IBAN_2, "100"), null))
                .isInstanceOf(InvalidFraudRequestException.class);
    }
}

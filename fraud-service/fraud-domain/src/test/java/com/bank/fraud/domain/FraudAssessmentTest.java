package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudAssessmentTest {

    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";

    @Test
    void allowedHaStatoAllowedESenzaMotivi() {
        FraudAssessment a = FraudAssessment.allowed(ID);

        assertThat(a.transferId()).isEqualTo(ID);
        assertThat(a.status()).isEqualTo(FraudStatusEnum.ALLOWED);
        assertThat(a.reasons()).isEmpty();
    }

    @Test
    void rejectedConservaMotiviNellOrdineDato() {
        FraudAssessment a = FraudAssessment.rejected(ID,
                List.of(FraudReasonEnum.SOURCE_IBAN_LOCKED, FraudReasonEnum.DESTINATION_IBAN_LOCKED));

        assertThat(a.status()).isEqualTo(FraudStatusEnum.REJECTED);
        assertThat(a.reasons()).containsExactly(
                FraudReasonEnum.SOURCE_IBAN_LOCKED, FraudReasonEnum.DESTINATION_IBAN_LOCKED);
    }

    @Test
    void rejectedCopiaLaListaRicevuta() {
        List<FraudReasonEnum> input = new ArrayList<>(List.of(FraudReasonEnum.SOURCE_IBAN_LOCKED));
        FraudAssessment a = FraudAssessment.rejected(ID, input);

        input.add(FraudReasonEnum.DESTINATION_IBAN_LOCKED);

        assertThat(a.reasons()).containsExactly(FraudReasonEnum.SOURCE_IBAN_LOCKED);
    }

    @Test
    void listaDeiMotiviNonEModificabile() {
        FraudAssessment a = FraudAssessment.rejected(ID, List.of(FraudReasonEnum.SOURCE_IBAN_LOCKED));

        assertThatThrownBy(() -> a.reasons().add(FraudReasonEnum.DESTINATION_IBAN_LOCKED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectedConListaVuotaERifiutato() {
        assertThatThrownBy(() -> FraudAssessment.rejected(ID, List.of()))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void allowedConMotiviERifiutato() {
        assertThatThrownBy(() -> new FraudAssessment(ID, FraudStatusEnum.ALLOWED,
                List.of(FraudReasonEnum.SOURCE_IBAN_LOCKED)))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaTransferIdNullo() {
        assertThatThrownBy(() -> FraudAssessment.allowed(null))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaTransferIdBlank() {
        assertThatThrownBy(() -> FraudAssessment.allowed("  "))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaStatusNullo() {
        assertThatThrownBy(() -> new FraudAssessment(ID, null, List.of()))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaMotiviNulli() {
        assertThatThrownBy(() -> new FraudAssessment(ID, FraudStatusEnum.ALLOWED, null))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rejectedConMotiviNulliERifiutato() {
        assertThatThrownBy(() -> FraudAssessment.rejected(ID, null))
                .isInstanceOf(InvalidFraudRequestException.class);
    }
}

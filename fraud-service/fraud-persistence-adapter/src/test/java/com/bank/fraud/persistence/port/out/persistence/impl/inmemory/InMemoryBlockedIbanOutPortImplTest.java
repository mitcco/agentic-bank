package com.bank.fraud.persistence.port.out.persistence.impl.inmemory;

import com.bank.fraud.domain.Iban;
import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryBlockedIbanOutPortImplTest {

    private static final String BLOCKED = "IT60X0542811101000000000099";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";
    private static final String BLOCKED_MESSY = " it60 x054 2811 1010 0000 0000 099 ";
    private static final String TOO_SHORT = "IT60X054281110100000000009";

    private static Set<Iban> ibans(String... values) {
        Set<Iban> result = new HashSet<>();
        for (String v : values) {
            result.add(new Iban(v));
        }
        return result;
    }

    // ---- Funzionamento ----

    @Test
    void returnsOnlyTheBlockedIbanAmongCandidates() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED));

        Set<Iban> result = port.findBlocked(ibans(BLOCKED, IBAN_1));

        assertThat(result).containsExactly(new Iban(BLOCKED));
    }

    @Test
    void returnsEmptySetWhenNoCandidateIsBlocked() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED));

        assertThat(port.findBlocked(ibans(IBAN_1, IBAN_2))).isEmpty();
    }

    @Test
    void returnsEmptySetWhenCandidatesAreEmpty() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED));

        assertThat(port.findBlocked(Set.of())).isEmpty();
    }

    @Test
    void emptyConfigurationBlocksNothingAndDoesNotFail() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of());

        assertThat(port.findBlocked(ibans(BLOCKED, IBAN_1))).isEmpty();
    }

    @Test
    void configuredIbanWithSpacesAndLowercaseMatchesNormalizedCandidate() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED_MESSY));

        Set<Iban> result = port.findBlocked(Set.of(new Iban(BLOCKED)));

        assertThat(result).containsExactly(new Iban(BLOCKED));
    }

    @Test
    void sameIbanConfiguredTwiceInDifferentFormatsIsAccepted() {
        assertThatCode(() -> new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED, BLOCKED_MESSY)))
                .doesNotThrowAnyException();

        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED, BLOCKED_MESSY));

        assertThat(port.findBlocked(ibans(BLOCKED, IBAN_1))).hasSize(1);
    }

    @Test
    void returnsAllBlockedCandidatesWhenSeveralAreConfigured() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED, IBAN_2));

        Set<Iban> result = port.findBlocked(ibans(BLOCKED, IBAN_1, IBAN_2));

        assertThat(result).containsExactlyInAnyOrder(new Iban(BLOCKED), new Iban(IBAN_2));
    }

    @Test
    void changingTheConfigurationCollectionAfterConstructionHasNoEffect() {
        List<String> config = new ArrayList<>(List.of(BLOCKED));
        var port = new InMemoryBlockedIbanOutPortImpl(config);

        config.clear();
        config.add(IBAN_1);

        assertThat(port.findBlocked(ibans(BLOCKED, IBAN_1))).containsExactly(new Iban(BLOCKED));
    }

    @Test
    void nonEmptyResultIsUnmodifiable() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED));
        Set<Iban> result = port.findBlocked(ibans(BLOCKED, IBAN_1));

        assertThatThrownBy(() -> result.add(new Iban(IBAN_1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.remove(new Iban(BLOCKED)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(result::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyResultIsUnmodifiable() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED));
        Set<Iban> result = port.findBlocked(ibans(IBAN_1));

        assertThatThrownBy(() -> result.add(new Iban(IBAN_1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void findBlockedIsRepeatableAndDoesNotModifyTheCandidateSet() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED));
        Set<Iban> candidates = ibans(BLOCKED, IBAN_1);

        Set<Iban> first = port.findBlocked(candidates);
        Set<Iban> second = port.findBlocked(candidates);

        assertThat(first).isEqualTo(second).containsExactly(new Iban(BLOCKED));
        assertThat(candidates).containsExactlyInAnyOrder(new Iban(BLOCKED), new Iban(IBAN_1));
    }

    // ---- Errori ----

    @Test
    void constructionFailsWhenConfiguredValueIsNotAnIban() {
        assertThatThrownBy(() -> new InMemoryBlockedIbanOutPortImpl(List.of("NOT-AN-IBAN")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void constructionFailsWhenOneEntryIsInvalidEvenAfterValidOnes() {
        assertThatThrownBy(() -> new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED, TOO_SHORT)))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void constructionFailsWhenEntryIsEmpty() {
        assertThatThrownBy(() -> new InMemoryBlockedIbanOutPortImpl(List.of("")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void constructionFailsWhenEntryIsOnlyWhitespace() {
        assertThatThrownBy(() -> new InMemoryBlockedIbanOutPortImpl(List.of("   ")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void constructionFailsWithDomainExceptionNotNpeWhenEntryIsNull() {
        assertThatThrownBy(() -> new InMemoryBlockedIbanOutPortImpl(Arrays.asList(BLOCKED, null)))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void constructionFailsWithNullPointerWhenConfigurationIsNull() {
        assertThatThrownBy(() -> new InMemoryBlockedIbanOutPortImpl(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructionErrorMessageDoesNotLeakTheConfiguredValue() {
        assertThatThrownBy(() -> new InMemoryBlockedIbanOutPortImpl(List.of(TOO_SHORT)))
                .isInstanceOf(InvalidFraudRequestException.class)
                .message()
                .doesNotContain(TOO_SHORT)
                .doesNotContain(TOO_SHORT.substring(0, 4))
                .doesNotContain(TOO_SHORT.substring(0, 8));
    }

    @Test
    void findBlockedFailsWithNullPointerWhenCandidatesAreNull() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED));

        assertThatThrownBy(() -> port.findBlocked(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findBlockedFailsWithNullPointerWhenCandidatesContainNull() {
        var port = new InMemoryBlockedIbanOutPortImpl(List.of(BLOCKED));
        Set<Iban> candidates = new HashSet<>();
        candidates.add(new Iban(BLOCKED));
        candidates.add(null);

        assertThatThrownBy(() -> port.findBlocked(candidates))
                .isInstanceOf(NullPointerException.class);
    }
}

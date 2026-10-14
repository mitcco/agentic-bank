package com.bank.fraud.memory.port.out.memory.impl;

import com.bank.fraud.application.port.out.BlockedIbanOutPort;
import com.bank.fraud.domain.Iban;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class InMemoryBlockedIbanOutPortImpl implements BlockedIbanOutPort {

    private final Set<Iban> blocked;

    public InMemoryBlockedIbanOutPortImpl(Collection<String> blockedIbans) {
        Objects.requireNonNull(blockedIbans, "blockedIbans");
        Set<Iban> converted = new HashSet<>();
        for (String value : blockedIbans) {
            converted.add(new Iban(value));
        }
        this.blocked = Set.copyOf(converted);
    }

    @Override
    public Set<Iban> findBlocked(Set<Iban> ibans) {
        Objects.requireNonNull(ibans, "ibans");
        return ibans.stream()
                .map(iban -> Objects.requireNonNull(iban, "iban"))
                .filter(blocked::contains)
                .collect(Collectors.toUnmodifiableSet());
    }
}

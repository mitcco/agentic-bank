package com.bank.fraud.application.service;

import com.bank.fraud.application.port.out.BlockedIbanOutPort;
import com.bank.fraud.domain.Iban;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class FakeBlockedIbanOutPort implements BlockedIbanOutPort {

    private final Set<Iban> blocked = new HashSet<>();
    private final List<Set<Iban>> calls = new ArrayList<>();
    private boolean returnNull;
    private RuntimeException toThrow;

    FakeBlockedIbanOutPort blocking(String... ibans) {
        for (String iban : ibans) {
            blocked.add(new Iban(iban));
        }
        return this;
    }

    FakeBlockedIbanOutPort returningNull() {
        this.returnNull = true;
        return this;
    }

    FakeBlockedIbanOutPort throwing(RuntimeException e) {
        this.toThrow = e;
        return this;
    }

    List<Set<Iban>> calls() {
        return calls;
    }

    @Override
    public Set<Iban> findBlocked(Set<Iban> ibans) {
        calls.add(new HashSet<>(ibans));
        if (toThrow != null) {
            throw toThrow;
        }
        if (returnNull) {
            return null;
        }
        return new HashSet<>(blocked);
    }
}

# Piano fraud-03 — application del fraud-service

> Piano CONDIZIONATO alle ipotesi A1-A4: l'implementazione non parte finché l'utente non le conferma.

## Obiettivo
Implementare in `fraud-application` la porta `CheckTransferInPort`, la porta `BlockedIbanOutPort`, i DTO e il service `CheckTransferServiceImpl`. Il service converte la richiesta in `TransferToAssess`, chiede alla porta gli IBAN bloccati, applica `FraudPolicy` e rende i motivi con il testo del contratto (sezioni 4, 5, 6; casi 1-7 e 15 della sezione 11).

## Ambiguità (ipotesi da confermare)
1. **(Blocca) Controllo "solo EUR" ed eccezione dedicata.** L'issue chiede controllo nel service con `UnsupportedCurrencyException` dedicata. Nel dominio esistono già `com.bank.fraud.domain.exception.UnsupportedCurrencyException` e il controllo in `TransferToAssess` ("Only EUR is supported", test T8/T9 verdi). Duplicare = due eccezioni omonime o un controllo che non scatta mai; spostare = modificare test di dominio (vietato). **Ipotesi:** nessun controllo né eccezione nuovi in application; il service si affida a `TransferToAssess`; i test verificano che l'eccezione di dominio si propaghi senza chiamare la porta.
2. **(Blocca) Forma di `BlockedIbanOutPort`.** Issue: "dato un insieme di IBAN, restituisce quali sono bloccati"; `FraudPolicy.assess` vuole un `Predicate<Iban>`. **Ipotesi:** `Set<Iban> findBlocked(Set<Iban> ibans)`, chiamata una sola volta con {source, destination}; il service passa `blocked::contains`. L'insieme va costruito con `new LinkedHashSet<>(List.of(source, destination))`, NON `Set.of` (che lancia IAE se source = destination, caso ammesso). Alternativa: `Set<String>` di IBAN normalizzati.
3. **(Blocca) Soglia come parametro del service.** **Ipotesi:** costruttore `CheckTransferServiceImpl(BlockedIbanOutPort, BigDecimal threshold)`; costruisce `new FraudPolicy(new Money(threshold, TransferToAssess.SUPPORTED_CURRENCY))` e usa `policy.threshold()` per i testi. Alternative: ricevere `Money`, oppure un `FraudPolicy` già costruito (bean in bootstrap).
4. **(Blocca) `@Service` contro build verde.** `FraudApplication` scansiona `com.bank.fraud.application.service`; con `@Service` mancano il bean `BlockedIbanOutPort` e la soglia → `FraudApplicationTests.contextLoads` diventa rosso. `@Value` è vietato (CLAUDE.md radice: solo `@Service`/`@Transactional`); escludere il package dalla scansione richiede di toccare bootstrap. Opzioni: (a) niente `@Service`, bean dichiarato da una `@Configuration` in `fraud-bootstrap` nell'issue di configurazione (come `FraudConfig` nel transfer); (b) `@Service` e bootstrap rosso fino all'issue successiva; (c) allargare il perimetro con config minima in bootstrap. **Ipotesi:** (a), in conflitto con "usa `@Service`" dell'issue → serve l'ok dell'utente.
5. **DTO o command in ingresso.** Root CLAUDE.md/transfer usano un command. **Ipotesi:** si segue l'issue: `FraudCheckRequestDto` in `application.dto`, nessun command. Alternativa: `CheckTransferCommand`.
6. **MapStruct.** Il pom ha già `mapstruct` e processor (nessuna dipendenza nuova). La conversione della risposta richiede la soglia (`message(Money)`), scomoda in MapStruct. **Ipotesi:** nessun mapper; conversioni in metodi privati del service; pom invariato.
7. **`reasons` con ALLOWED.** **Ipotesi:** DTO con lista sempre non nulla (vuota se ALLOWED); l'omissione nel JSON è del web adapter.
8. **`status` nel DTO.** **Ipotesi:** `String` "ALLOWED"/"REJECTED" (`FraudStatusEnum.name()`).
9. **Logging.** **Ipotesi:** nessun log nel service in questa issue.
10. **Porta che restituisce `null` o lancia.** **Ipotesi:** fail closed. `null` → `NullPointerException` (`Objects.requireNonNull`); eccezione della porta propagata così com'è. Mai ALLOWED, mai `InvalidFraudRequestException`/`UnsupportedCurrencyException` (così il web adapter produrrà 500).
11. **Nome file.** Issue in `docs/plans/fraud-application.md`, piano in `docs/plans/fraud-03-application.md`.

## Modifiche per modulo
**`fraud-application`** (unico modulo toccato; `pom.xml` invariato). Sorgenti in `fraud-service/fraud-application/src/main/java/`.

| File (package) | Responsabilità |
|---|---|
| `com.bank.fraud.application.dto.FraudCheckRequestDto` | `record (String transferId, String sourceIban, String destinationIban, BigDecimal amount, String currency)`. Nessuna validazione (la fa il dominio). `toString()` espone solo `transferId`. |
| `com.bank.fraud.application.dto.FraudCheckResponseDto` | `record (String transferId, String status, List<String> reasons)`. `List.copyOf(reasons)`. |
| `com.bank.fraud.application.port.in.CheckTransferInPort` | `FraudCheckResponseDto checkTransfer(FraudCheckRequestDto request)`. |
| `com.bank.fraud.application.port.out.BlockedIbanOutPort` | `Set<Iban> findBlocked(Set<Iban> ibans)`: sottoinsieme bloccato (A2). Solo interfaccia; contratto (sottoinsieme, mai `null`) nel Javadoc. |
| `com.bank.fraud.application.service.CheckTransferServiceImpl` | `implements CheckTransferInPort`. Costruttore `(BlockedIbanOutPort, BigDecimal)` (A3): `requireNonNull` sulla porta, costruisce `FraudPolicy`. Flusso: 1) `new TransferToAssess(transferId, new Iban(src), new Iban(dst), new Money(amount, currency))` (eccezioni di dominio non tradotte); 2) `findBlocked` con {source, destination} in `LinkedHashSet`; 3) `policy.assess(transfer, blocked::contains)`; 4) risposta con `transferId` invariato, `status.name()`, `reason.message(policy.threshold())` nell'ordine del dominio. Nessun log. `@Service` secondo A4. |

Test in `fraud-service/fraud-application/src/test/java/com/bank/fraud/application/`: `dto/FraudCheckResponseDtoTest`, `dto/FraudCheckRequestDtoTest`, `service/CheckTransferServiceImplTest`, `service/FakeBlockedIbanOutPort` (package-private; registra chiamate e argomenti; configurabile per restituire `null` o lanciare). Niente Mockito. Nessun altro modulo toccato.

## Test attesi
JUnit 5 + AssertJ, stesso package della classe testata. Dati: `BLOCKED = "IT60X0542811101000000000099"`, `IBAN_1 = "...0001"`, `IBAN_2 = "...0002"`, `ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93"`, soglia `10000` salvo diversa indicazione.

**Ordine TDD:** 1) DTO test (rossi) → record; 2) interfacce + `FakeBlockedIbanOutPort`; 3) `CheckTransferServiceImplTest` (rosso) → service. A ogni passo `.\mvnw.cmd test -pl fraud-application -am`; alla fine `.\mvnw.cmd test` da `fraud-service/`.

**FraudCheckResponseDtoTest**: D1 lista mutabile poi modificata → `reasons()` invariata; D2 `reasons().add` → `UnsupportedOperationException`.
**FraudCheckRequestDtoTest**: D3 `toString()` contiene `ID`, non IBAN_1 né `1234.56`.

**CheckTransferServiceImplTest** (tra parentesi caso sezione 11)
- S1 (1) `1000.25 EUR`, nessun bloccato → `ID`, "ALLOWED", reasons vuota
- S2 (2) `10000.00` → ALLOWED; S3 (3) `10000.01` → REJECTED `["Amount must not exceed 10000 EUR"]`
- S4 (4) mittente BLOCKED → `["Source IBAN is locked"]`; S5 (5) destinatario → `["Destination IBAN is locked"]`
- S6 (6) entrambi → source, destination; S7 (7) `20000.00` + mittente → importo, mittente; S8 tre motivi in ordine
- S9 (15) stessa richiesta due volte → risposte uguali; S10 transferId `"abc"` e `" x "` restituiti identici; S11 transferId diversi → stessi status/motivi
- S12 soglia `5000`, importo `6000` → "Amount must not exceed 5000 EUR"; S13 soglia `10000.00`, importo `10000.01` → "…10000 EUR"
- S14 mittente `" it60 x054 2811 1010 0000 0000 099 "` bloccato → SOURCE locked, il fake riceve l'`Iban` normalizzato
- S15 porta chiamata una sola volta con {source, destination}; S16 mittente = destinatario = BLOCKED → nessuna eccezione, due motivi, porta chiamata con insieme di 1 elemento; S17 il fake restituisce IBAN estranei → ALLOWED

**Errori**
- E1 (12) `USD` → `UnsupportedCurrencyException` di dominio "Only EUR is supported", porta mai chiamata; E2 `eur` → idem
- E3 (8) amount `0`; E4 (9) `10.001`; E5 (11) sourceIban `""`; E6 (10, lato dominio) uno alla volta `null` (transferId, sourceIban, destinationIban, amount, currency) → `InvalidFraudRequestException`, porta mai chiamata
- E7 messaggio senza IBAN né `1234.56`; E8 `0 USD` → `InvalidFraudRequestException`
- E9 il fake lancia `RuntimeException("boom")` → propagata; E10 il fake restituisce `null` → eccezione che non è `InvalidFraudRequestException` né `UnsupportedCurrencyException`, mai ALLOWED

**Costruttore**: C1 soglia `null`, `0`, `-1`, `10.001` → `InvalidFraudRequestException`; C2 porta `null` → `NullPointerException`.

I casi 13 e 14 sono del web adapter; i casi 8-12 qui solo come propagazione.

## Criteri di accettazione
- `.\mvnw.cmd test` da `fraud-service/`, senza Docker, BUILD SUCCESS, compreso `FraudApplicationTests` (dipende da A4).
- Nessun `pom.xml` modificato, nessuna dipendenza nuova.
- Nel service solo import di `com.bank.fraud.application.*`, `com.bank.fraud.domain.*`, `java.*`, eventualmente `org.springframework.stereotype.Service`; nessun `com.bank.transfer`.
- Porta in ingresso e DTO non espongono tipi di dominio; `BlockedIbanOutPort` usa `Iban` (A2).
- Testi dei motivi come sezione 6 del contratto, ordine importo/mittente/destinatario, soglia senza zeri finali; importo = soglia → ALLOWED.
- `transferId` identico e ininfluente sulla decisione.
- Nessuna eccezione nuova in application, nessun duplicato di `UnsupportedCurrencyException`.
- Nessun log, nessun `toString`/messaggio con IBAN o importi.
- Test di dominio e bootstrap non modificati; domain, adapter, bootstrap, transfer-service non toccati.

## Rischi e fuori perimetro
**Rischi**: A4 (bootstrap non si avvia con `@Service`); `Set.of` con duplicati (S16); contratto della porta da documentare nel Javadoc; `InvalidFraudRequestException` lanciata anche per soglia errata (C1) — errore di avvio, non di richiesta, ma da tenere presente per il vincolo del web adapter; MapStruct nel pom non usato; dipendenze nuove: nessuna.
**Fuori perimetro**: web adapter, `POST /fraud-check`, `WebExceptionHandler`, mappatura codici sezione 7; implementazione reale di `BlockedIbanOutPort`; configurazione bean e `application.properties`; test di integrazione; logging/audit; modifiche a dominio, contratto, transfer-service.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. Nessun controllo/eccezione valuta in application (ipotesi confermata).
2. `Set<Iban> findBlocked(Set<Iban>)` con `LinkedHashSet` (ipotesi confermata).
3. Costruttore `(BlockedIbanOutPort, BigDecimal threshold)` (confermata).
4. **`@Service` MANTENUTO (opzione b).** Conseguenza accettata: `FraudApplicationTests.contextLoads` resterà rosso (manca il bean `BlockedIbanOutPort`/soglia) finché non arriva la issue di configurazione. NON modificare bootstrap né i suoi test in questa fase; il criterio "BUILD SUCCESS completo" si applica a `-pl fraud-application -am`.
5-10. Ipotesi del piano confermate (nessuna obiezione): `FraudCheckRequestDto`, nessun mapper, reasons sempre non nulla, status String, nessun log, fail closed.

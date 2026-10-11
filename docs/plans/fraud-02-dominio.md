# Piano fraud-02 — dominio del fraud-service

## Obiettivo
Creare in `fraud-domain` (package `com.bank.fraud.domain`) i value object `Iban` e `Money`, l'oggetto di input `TransferToAssess`, l'esito `FraudAssessment`, gli enum e le eccezioni, più la regola pura `FraudPolicy`. La regola applica la sezione 6 del contratto `fraud-check.md` e copre i casi 1-7 e 15 della sezione 11, più la parte di dominio dei casi 8, 9, 11 e 12. Tutto in Java puro e con TDD.

## Ambiguità (ipotesi da confermare prima dell'implementazione)
1. **"Massimo 2 decimali" e zeri finali.** Ipotesi: regola letterale, si rifiuta ogni importo con `scale() > 2`; `10.100` è rifiutato. Il caso 9 (`10.001`) è rifiutato in ogni caso.
2. **Valuta in minuscolo (`eur`).** Ipotesi: `Money` non normalizza e non controlla il formato della valuta (solo non nulla/non vuota). `eur`, `USD`, `EURO` danno `UnsupportedCurrencyException` (`UNSUPPORTED_CURRENCY`), non `INVALID_REQUEST`.
3. **Più errori di input insieme.** Ipotesi: in `TransferToAssess` prima la validità dei campi (`IllegalArgumentException`), poi la valuta (`UnsupportedCurrencyException`).
4. **`transferId` di soli spazi.** Ipotesi: rifiutato con `isBlank()`; mai normalizzato né trimmato.
5. **Mittente uguale al destinatario.** Ipotesi: accettato e valutato normalmente; se bloccato, due motivi (Source e Destination).
6. **Come `FraudPolicy` riceve soglia e IBAN bloccati.** Ipotesi: soglia nel costruttore `new FraudPolicy(Money threshold)`; IBAN bloccati come `Set<Iban>` in `assess(...)`. Alternativa: `Predicate<Iban>` (scala meglio con un futuro adapter su DB). Fissa la forma di `BlockedIbanOutPort` nella prossima issue.
7. **Testi dei motivi nel dominio.** Ipotesi: `FraudReasonEnum` contiene il testo letterale del contratto (`message()`). Testo soglia fisso "Amount must not exceed 10000 EUR", indipendente dalla soglia configurata.
8. **`toString()` dei record.** Ipotesi: `Iban`, `Money`, `TransferToAssess` sovrascrivono `toString()` con valori mascherati (regola "nei log mai IBAN né importi"). Se non voluto, si tolgono i test M-T, I-T, T-T.
9. **Tipo di eccezione per input non validi.** Ipotesi: ogni input non valido (null compreso) lancia `IllegalArgumentException` (transfer-domain usa `requireNonNull` → NPE). Valuta con eccezione dedicata.

## Decisioni dell'utente (prevalgono sulle ipotesi sopra)
1. Si rifiuta ogni importo con `scale() > 2` (`10.100` rifiutato).
2-4. Ipotesi del piano confermate implicitamente (nessuna obiezione).
5. Mittente = destinatario: controllo di competenza del Transfer. Il fraud non lo gestisce in modo speciale.
6. `FraudPolicy.assess` riceve un `Predicate<Iban>` per gli IBAN bloccati (non `Set<Iban>`). Soglia nel costruttore.
7. Il testo del motivo soglia dipende dalla soglia configurata. Scelta A: contratto riga 71 aggiornato. Testo `"Amount must not exceed <soglia> EUR"`, soglia = `threshold.amount().stripTrailingZeros().toPlainString()` (10000 → "10000"). `FraudReasonEnum` non può più portare un testo fisso per la soglia: `FraudAssessment` porta i motivi come testo/oggetto motivo costruito da `FraudPolicy` (da definire dal tester/implementer nel rispetto del piano), il test P14 diventa: soglia 5000 e importo 6000 → "Amount must not exceed 5000 EUR".
8. `toString()` mascherato: confermato (test I-T, M-T, T-T).
9. Confermato. `UnsupportedCurrencyException` e `DifferentCurrencyException` restano tipi indipendenti. Si introduce `InvalidFraudRequestException` di dominio al posto di `IllegalArgumentException`; i `requireNonNull` sono sostituiti dove necessario, così ogni input non valido (null compreso) dà questa eccezione.

## Modifiche per modulo
**`fraud-domain`** (unico modulo toccato; `pom.xml` invariato). File nuovi sotto `fraud-service/fraud-domain/src/main/java/`.

| File (package) | Responsabilità |
|---|---|
| `com.bank.fraud.domain.Iban` | `record Iban(String value)`. Costruttore compatto: rifiuta `null`/blank, toglie tutti gli spazi (`\\s+`), maiuscolo (`Locale.ROOT`), esattamente 27 caratteri, formato `[A-Z]{2}[0-9]{2}[A-Z0-9]+`. Messaggi di errore senza il valore ricevuto. `toString()` mascherato. |
| `com.bank.fraud.domain.Money` | `record Money(BigDecimal amount, String currency)`. Rifiuta `amount` nullo, negativo o con `scale() > 2`; `currency` nulla/blank, senza normalizzare. Zero ammesso (il vincolo "> 0" è di `TransferToAssess`). `boolean isGreaterThan(Money other)` con `compareTo`; valute diverse → `DifferentCurrencyException`. `equals`/`hashCode` su `compareTo`/`stripTrailingZeros`. `toString()` mascherato. |
| `com.bank.fraud.domain.TransferToAssess` | `record TransferToAssess(String transferId, Iban source, Iban destination, Money amount)`. `SUPPORTED_CURRENCY = "EUR"`. Controlli in ordine: campi non nulli, `transferId` non blank e invariato, `amount > 0` (IAE), currency `equals "EUR"` (`UnsupportedCurrencyException`). `toString()` espone solo `transferId`. |
| `com.bank.fraud.domain.FraudStatusEnum` | `ALLOWED`, `REJECTED`. |
| `com.bank.fraud.domain.FraudReasonEnum` | `AMOUNT_OVER_THRESHOLD("Amount must not exceed 10000 EUR")`, `SOURCE_IBAN_LOCKED("Source IBAN is locked")`, `DESTINATION_IBAN_LOCKED("Destination IBAN is locked")`; `String message()`. Ordine = quello del contratto. |
| `com.bank.fraud.domain.FraudAssessment` | `record FraudAssessment(String transferId, FraudStatusEnum status, List<FraudReasonEnum> reasons)`. `transferId` non blank, `status` non nullo, `reasons` non nulla e `List.copyOf`. `ALLOWED` → lista vuota; `REJECTED` → non vuota. Factory `allowed(String)` e `rejected(String, List)`. |
| `com.bank.fraud.domain.FraudPolicy` | `final class` senza stato mutabile. `FraudPolicy(Money threshold)`: non nulla, EUR, > 0. `FraudAssessment assess(TransferToAssess, Set<Iban> blockedIbans)`: funzione pura, regole in ordine soglia, mittente, destinatario, accumula i motivi; nessuno → `allowed`, altrimenti `rejected`. `transferId` solo copiato. `transfer`/`blockedIbans` nulli → IAE. Set solo in lettura. |
| `com.bank.fraud.domain.exception.UnsupportedCurrencyException` | `extends RuntimeException`, `(String message)`. Messaggio fisso "Only EUR is supported". Il web adapter la mapperà su `400 UNSUPPORTED_CURRENCY`. |
| `com.bank.fraud.domain.exception.DifferentCurrencyException` | `extends RuntimeException`, `(String message)`. Usata in `Money.isGreaterThan`. |

Test nuovi in `fraud-service/fraud-domain/src/test/java/com/bank/fraud/domain/`: `IbanTest`, `MoneyTest`, `TransferToAssessTest`, `FraudReasonEnumTest`, `FraudAssessmentTest`, `FraudPolicyTest`. Gli altri moduli non vengono toccati.

## Test attesi
JUnit 5 + AssertJ, stesso package della classe testata, importi con `isEqualByComparingTo`. Dati: `BLOCKED = "IT60X0542811101000000000099"`, `IBAN_1 = "IT60X0542811101000000000001"`, `IBAN_2 = "IT60X0542811101000000000002"`, `ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93"`, soglia `new Money(new BigDecimal("10000"), "EUR")`.

**Ordine TDD:** 1) IbanTest→Iban; 2) MoneyTest→Money+DifferentCurrencyException; 3) FraudReasonEnumTest→enum; 4) TransferToAssessTest→TransferToAssess+UnsupportedCurrencyException; 5) FraudAssessmentTest→FraudAssessment; 6) FraudPolicyTest→FraudPolicy. A ogni passo: rosso, verde, `.\mvnw.cmd test -pl fraud-domain`.

**IbanTest**
- I1 `" it60 x054 2811 1010 0000 0000 001 "` → `value()` = IBAN_1
- I2 IBAN_1 e forma con spazi/minuscole → uguali, stesso hashCode
- I3 tab/a capo interni → rimossi, valido
- I4 `null`, I5 `""` (caso 11), I6 `"   "`, I7 26 caratteri, I8 28 caratteri, I9 `"IT60-0542811101000000000001"`, I10 `"1T60X0542811101000000000001"`, I11 `"ITX0X0542811101000000000001"` → `IllegalArgumentException`
- I12 IBAN non valido di 26 caratteri → messaggio senza il valore
- I-T `toString()` non contiene IBAN_1

**MoneyTest**
- M1 `2.0 EUR` = `2.00 EUR` (hashCode uguale); M2 `2.00` ≠ `2.01`; M3 EUR ≠ USD
- M4 `0 EUR` valido; M5 `10.01 EUR` e `10000 EUR` validi; M6 `"eur"`, `"USD"` validi e invariati
- M7 `10000.01` vs `10000` → `isGreaterThan` true; M8 `10000.00` → false; M9 `9999.99` → false
- M10 null, M11 `-1`, M12 `10.001` (caso 9), M13 `10.100`, M14 currency null/`""`/`"  "` → IAE
- M15 `1 EUR` vs `1 USD` → `DifferentCurrencyException`
- M16 messaggi senza importo; M-T `1234.56 EUR` `toString()` non contiene `1234.56`

**FraudReasonEnumTest**: R1, R2, R3 — `message()` pari ai testi del contratto.

**TransferToAssessTest**
- T1 creazione valida con `1000.25 EUR`; T2 `transferId` `"abc"` valido; T3 spazi iniziali conservati; T4 source = destination valido
- T5 `transferId` null/`""`/`"   "`, T6 source/destination/amount null, T7 `0 EUR` (caso 8) → IAE
- T8 `100 USD` (caso 12) → `UnsupportedCurrencyException` "Only EUR is supported"; T9 `100 eur` → idem; T10 `0 USD` → IAE (vince l'input)
- T-T `toString()` contiene transferId, non IBAN né importo

**FraudAssessmentTest**
- A1 `allowed(ID)`; A2 `rejected` con `[SOURCE_IBAN_LOCKED, DESTINATION_IBAN_LOCKED]` in ordine; A3 lista mutabile copiata; A4 `reasons().add` → `UnsupportedOperationException`
- A5 REJECTED con lista vuota, A6 ALLOWED con motivi, A7 transferId blank/null, status null, reasons null → IAE

**FraudPolicyTest** (tra parentesi caso sezione 11)
- P1 (1) `1000.25 EUR` → ALLOWED; P2 (2) `10000.00` → ALLOWED; P3 (3) `10000.01` → `[AMOUNT_OVER_THRESHOLD]`
- P4 (4) source bloccato; P5 (5) destination bloccato; P6 (6) entrambi in ordine source, destination
- P7 (7) `20000.00` + source bloccato → `[AMOUNT_OVER_THRESHOLD, SOURCE_IBAN_LOCKED]`; P8 tre motivi in ordine
- P9 (15) stesso input due volte → esiti uguali; P10 transferId diversi → ognuno il proprio; P11 blocked vuoto → ALLOWED
- P12 source con spazi/minuscole → SOURCE_IBAN_LOCKED; P13 source = destination bloccati → due motivi
- P14 soglia `5000` con `6000` → testo comunque "Amount must not exceed 10000 EUR"; P15 set blocked non modificato
- P16 soglia null/`0 EUR`/`100 USD` → IAE; P17 transfer o blocked null → IAE

Casi 10, 13, 14 della sezione 11: solo web adapter, nessun test di dominio. Test end-to-end (HTTP 400/415) nelle issue web adapter e bootstrap.

## Criteri di accettazione
- `.\mvnw.cmd test` da `fraud-service/`, senza Docker, BUILD SUCCESS; `FraudApplicationTests` resta verde.
- `fraud-domain/pom.xml` invariato; nessuna nuova dipendenza in nessun pom.
- Nessun import di `org.springframework`, `jakarta`, `com.fasterxml`, `com.bank.transfer` in `fraud-domain`.
- Testi di `FraudReasonEnum` identici al contratto (sezione 6).
- Ordine motivi: soglia, mittente, destinatario. Importo = soglia → ALLOWED. `BigDecimal` solo con `compareTo`.
- `FraudPolicy` senza configurazione, stato mutabile o I/O; `transferId` non influisce sulla decisione.
- Nessun messaggio di eccezione né `toString()` contiene IBAN o importi.
- Test esistenti non modificati; transfer-service non toccato.

## Rischi e fuori perimetro
**Rischi**
- `Set<Iban>` vs `Predicate<Iban>` (amb. 6): fissa `BlockedIbanOutPort`; con DB il set intero non scala.
- Scala decimali (amb. 1): `10.10` inviato come `10.100` → 400; cambierebbero solo M13 e una riga di `Money`.
- Mapping eccezioni: `IllegalArgumentException` è generica, un bug interno uscirebbe come 400 invece di 500. Mitigazione: il web adapter cattura solo le eccezioni di costruzione del dominio, oppure si introduce `InvalidFraudRequestException` (da decidere).
- Duplicazione con il Transfer di `Iban`/`Money` (voluta); il riferimento resta il contratto.
- Dipendenze nuove: nessuna.

**Fuori perimetro**: porte, service, command, DTO di `fraud-application`; `POST /fraud-check`, `WebExceptionHandler`, codici sezione 7; lista IBAN bloccati in memoria, configurazione, bean `FraudPolicy` in bootstrap; logging, audit, persistenza; modifiche a transfer-service o al contratto.

## Aggiornamento — opzione 2 (motivi)
- `FraudReasonEnum` resta il tipo dei motivi in `FraudAssessment` (`List<FraudReasonEnum>`).
- Il metodo `message()` è sostituito da `String message(Money threshold)`: `AMOUNT_OVER_THRESHOLD` → `"Amount must not exceed " + threshold.amount().stripTrailingZeros().toPlainString() + " EUR"`; `SOURCE_IBAN_LOCKED` → "Source IBAN is locked"; `DESTINATION_IBAN_LOCKED` → "Destination IBAN is locked" (la soglia è ignorata).
- `FraudPolicy` espone la soglia con `Money threshold()`, così il chiamante rende i testi.
- `assess(TransferToAssess, Predicate<Iban> isBlocked)`; il test P15 (set non modificato) è sostituito da: il predicate è invocato solo in lettura.
- Test R1-R3 diventano `message(threshold)`; aggiungere: soglia 5000 → "Amount must not exceed 5000 EUR"; soglia `10000.00` → "…10000 EUR". P14 verifica `message(policy.threshold())`.
- Ogni input non valido (null compreso) → `InvalidFraudRequestException` (al posto di `IllegalArgumentException` in tutto il piano).

## Vincolo per la issue del web adapter (da review giro 1, punto 3)
`InvalidFraudRequestException` è lanciata sia per input del client non valido sia per errori interni (`FraudPolicy`: soglia nulla, non EUR o <= 0; `FraudAssessment`: invarianti violate). Il `WebExceptionHandler` NON deve mapparla in blocco su `400 INVALID_REQUEST`: il 400 va prodotto solo catturando le eccezioni lanciate durante la costruzione di `TransferToAssess` dalla request (Iban, Money, TransferToAssess). Le eccezioni di `FraudPolicy` / `FraudAssessment` non vengono catturate e producono `500 INTERNAL_ERROR` (contratto, sezione 7). `UnsupportedCurrencyException` → `400 UNSUPPORTED_CURRENCY`.

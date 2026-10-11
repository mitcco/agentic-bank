# Piano fraud-04 — web adapter del fraud-service

> Piano CONDIZIONATO alle ambiguità 1, 2 e 3: l'implementazione non parte finché l'utente non le conferma.

## Obiettivo
Implementare `fraud-web-adapter`: un endpoint `POST` che chiama `CheckTransferInPort` attraverso il livello `servicedto`, con DTO HTTP propri, mapper MapStruct e un solo `@RestControllerAdvice` che produce i codici della sezione 7 del contratto. Copre i casi 1-14 della sezione 11 lato HTTP.

## Ambiguità (ipotesi da confermare)
1. **(Blocca) Come distinguere 400 da 500.** Il dominio non usa `IllegalArgumentException`: ogni input non valido lancia `com.bank.fraud.domain.exception.InvalidFraudRequestException` (in `Iban`, `Money`, `TransferToAssess`). La stessa eccezione la lanciano `FraudPolicy` (soglia, `assess` con argomenti nulli) e `FraudAssessment` (invarianti): errori interni. `CheckTransferServiceImpl.checkTransfer` costruisce `TransferToAssess` al proprio interno, quindi l'adapter riceve un solo tipo e non sa in quale fase è nato. Il vincolo di fraud-02 vieta la mappatura in blocco; nel solo adapter non esiste una soluzione pulita (analizzare lo stack trace è da scartare).
   - **Ipotesi (opzione A):** nuova `com.bank.fraud.application.exception.InvalidFraudCheckRequestException extends InvalidFraudRequestException`, messaggio fisso `"Fraud check request is not valid"`, causa conservata. Il service mette in `try/catch (InvalidFraudRequestException)` SOLO la costruzione di `new Iban`, `new Money`, `new TransferToAssess` e rilancia la sottoclasse. `UnsupportedCurrencyException` è un tipo diverso e passa così com'è. L'handler mappa solo la sottoclasse su 400; la classe base finisce nel catch-all → 500. I test esistenti di application restano verdi (controllano `isInstanceOf(InvalidFraudRequestException.class)`).
   - **Conflitti:** contraddice "Nessuna eccezione nuova in application" del piano 03 e allarga il perimetro dell'issue a `fraud-application`. Serve l'ok dell'utente.
   - **Alternative:** (B) come A più il campo errato deciso dal service (ambiguo per `Money`); (C) campo/tipo nelle eccezioni del dominio (modifica più pesante); (D) mappare in blocco: scartata, viola il vincolo.
2. **(Blocca) Percorso.** L'issue dice `POST /fraud-check`; il contratto (sezioni 3 e 10) e `fraud-service/CLAUDE.md` dicono `POST /fraud-check`. **Ipotesi:** `/fraud-check` (il contratto è la fonte di verità). Per `/fraud-check` va modificato il contratto.
3. **(Blocca) "IBAN uguali" tra i test di errore dell'issue.** Decisione 5 di fraud-02: mittente = destinatario è competenza del Transfer; il contratto non prevede questo errore. **Ipotesi:** nessun 400; il test diventa: con IBAN uguali la richiesta arriva alla porta e la risposta è `200`.
4. **`details` per gli errori di dominio.** Con l'opzione A il campo errato non si conosce e il messaggio del dominio non va usato. **Ipotesi:** `400 INVALID_REQUEST`, `message` = "Request validation failed", `details` omesso (facoltativo da contratto). `details` presente solo per Bean Validation e tipi errati.
5. **Testi dei messaggi (fissi).** MALFORMED_REQUEST → "Request body is not valid JSON"; INVALID_REQUEST → "Request validation failed"; UNSUPPORTED_CURRENCY → "Only EUR is supported"; UNSUPPORTED_MEDIA_TYPE → "Content-Type must be application/json"; INTERNAL_ERROR → "Internal error". Dettagli: `"<campo>: must not be null"`, `"amount: must be a number"`, `"<campo>: must be a string"`.
6. **Bean Validation e lingua.** Il messaggio predefinito di Hibernate Validator dipende dalla lingua. **Ipotesi:** `@NotNull(message = "must not be null")` esplicito su ogni campo.
7. **`amount` come stringa (`"100.25"`).** Jackson converte di default stringa → `BigDecimal`. **Ipotesi:** solo per `amount`, rifiuto con deserializzatore `@JsonDeserialize(using=...)` in `com.bank.fraud.web.dto.request`: accetta solo token numerici, usa `getDecimalValue()` (conserva la scala). Risultato `400 INVALID_REQUEST`, `["amount: must be a number"]`. Alternativa: tollerare anche `amount` stringa e documentarlo.
8. **Tipo errato e corpo non leggibile.** `HttpMessageNotReadableException` con causa `MismatchedInputException` e percorso verso un campo del DTO → `INVALID_REQUEST` con dettaglio; ogni altro caso (sintassi, corpo vuoto, array al posto dell'oggetto) → `MALFORMED_REQUEST`.
9. **Campi sconosciuti.** Ignorati, `@JsonIgnoreProperties(ignoreUnknown = true)` sul DTO.
10. **Catch-all.** Handler su `RuntimeException` → 500. Non `Exception` (ingoierebbe 405/406/404 di Spring MVC). `503` non prodotto in questa issue.
11. **Log.** Nessun log nell'adapter. Alternativa: log ERROR del solo nome della classe dell'eccezione nel catch-all.
12. **Casi 8, 9, 11, 12 "veri".** In più, una classe di test con `CheckTransferServiceImpl` reale e una lambda come `BlockedIbanOutPort`, sempre MockMvc standalone senza contesto Spring. Se non voluta, si toglie.
13. **Nomi.** DTO HTTP `FraudCheckHttpRequestDto` / `FraudCheckHttpResponseDto`; errore `ErrorResponseDto`.

## Modifiche per modulo
**`fraud-application`** (solo se l'opzione A è confermata; `pom.xml` invariato)

| File (package) | Responsabilità |
|---|---|
| `com.bank.fraud.application.exception.InvalidFraudCheckRequestException` (nuovo) | `extends InvalidFraudRequestException`; costruttore `(InvalidFraudRequestException cause)`, messaggio fisso, causa conservata. |
| `com.bank.fraud.application.service.CheckTransferServiceImpl` (modifica) | Il `try/catch` copre solo la costruzione di `TransferToAssess` e rilancia `InvalidFraudCheckRequestException`. `findBlocked`, `assess`, costruttore (soglia) restano fuori dal catch. |

**`fraud-web-adapter`** (`pom.xml` invariato: ha già `fraud-application`, `mapstruct` + processor, `spring-boot-starter-webmvc`, `-validation` e starter di test). Sorgenti in `fraud-service/fraud-web-adapter/src/main/java/`.

| File (package) | Responsabilità |
|---|---|
| `com.bank.fraud.web.dto.request.FraudCheckHttpRequestDto` | `record (String transferId, String sourceIban, String destinationIban, BigDecimal amount, String currency)`. `@NotNull(message="must not be null")` su ogni campo, `@JsonIgnoreProperties(ignoreUnknown=true)`, `@JsonDeserialize(using=StrictDecimalDeserializer.class)` su `amount`. `toString()` espone solo `transferId`. Nessuna regola di formato. |
| `com.bank.fraud.web.dto.request.StrictDecimalDeserializer` | Deserializzatore Jackson 3 (`tools.jackson.databind`, transitivo con webmvc). Solo token numerici, conserva la scala; altrimenti `MismatchedInputException`. |
| `com.bank.fraud.web.dto.response.FraudCheckHttpResponseDto` | `record (String transferId, String status, List<String> reasons)`, `@JsonInclude(NON_EMPTY)` su `reasons`. |
| `com.bank.fraud.web.dto.response.ErrorResponseDto` | `record (String code, String message, List<String> details)`, `@JsonInclude(NON_EMPTY)` su `details`. |
| `com.bank.fraud.web.mapper.FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapper` | `@Mapper(componentModel="spring")`, 1:1. |
| `com.bank.fraud.web.mapper.FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapper` | `@Mapper(componentModel="spring")`, 1:1. |
| `com.bank.fraud.web.servicedto.FraudCheckServiceDto` | `FraudCheckHttpResponseDto checkTransfer(FraudCheckHttpRequestDto request)`. |
| `com.bank.fraud.web.servicedto.internal.FraudCheckServiceDtoImpl` | `@Service`: mapper → `CheckTransferInPort.checkTransfer` → mapper. Eccezioni non tradotte. |
| `com.bank.fraud.web.port.in.web.FraudCheckController` | `@RestController`, `@PostMapping(path="/fraud-check", consumes=APPLICATION_JSON_VALUE, produces=APPLICATION_JSON_VALUE)`, `@Valid @RequestBody`. Dipende solo da `FraudCheckServiceDto`; nessun import di dominio. |
| `com.bank.fraud.web.port.in.web.exception.WebExceptionHandler` | `@RestControllerAdvice`, `ResponseEntity<ErrorResponseDto>`. `HttpMessageNotReadableException` → 400 MALFORMED/INVALID (amb. 8); `MethodArgumentNotValidException` → 400 INVALID_REQUEST, `details` = `campo + ": " + defaultMessage` in ordine alfabetico (mai `rejectedValue`); `InvalidFraudCheckRequestException` → 400 INVALID_REQUEST; `UnsupportedCurrencyException` → 400 UNSUPPORTED_CURRENCY; `HttpMediaTypeNotSupportedException` → 415; `RuntimeException` → 500 INTERNAL_ERROR. Mai `getMessage()`, nessuna stack trace, nessun log. |

Test in `fraud-service/fraud-web-adapter/src/test/java/com/bank/fraud/web/`: `servicedto/internal/FraudCheckServiceDtoImplTest`, `port/in/web/FraudCheckControllerTest`, `port/in/web/FakeCheckTransferInPort` (package-private; registra le richieste, configurabile per rispondere o lanciare), `port/in/web/FraudCheckContractScenariosTest` (amb. 12).
Test in application: `service/CheckTransferServiceImplValidationTest` (classe nuova, i test esistenti non vengono toccati).

## Test attesi
Dati: `ID`, `IBAN_1 = …0001`, `IBAN_2 = …0002`, `BLOCKED = …0099`. Importi con `isEqualByComparingTo`.

**Ordine TDD:** 1) `CheckTransferServiceImplValidationTest` (rosso) → eccezione + modifica del service; `.\mvnw.cmd test -pl fraud-application -am`. 2) `FraudCheckServiceDtoImplTest` → DTO HTTP, mapper, servicedto. 3) `FraudCheckControllerTest` casi di successo → controller. 4) `FraudCheckControllerTest` casi di errore → `WebExceptionHandler`, poi deserializzatore. 5) `FraudCheckContractScenariosTest`. A ogni passo `.\mvnw.cmd test -pl fraud-web-adapter -am`.

**CheckTransferServiceImplValidationTest (application)**
- AV1 sourceIban `""` / IBAN di 26 caratteri → `InvalidFraudCheckRequestException` (anche `instanceof InvalidFraudRequestException`), porta mai chiamata
- AV2 amount `0`, `10.001`, `10.100`; transferId `" "`; currency `""` → `InvalidFraudCheckRequestException`
- AV3 currency `USD` → `UnsupportedCurrencyException`, NON `InvalidFraudCheckRequestException`
- AV4 porta che lancia `new InvalidFraudRequestException("x")` → propagata con tipo esatto, non la sottoclasse
- AV5 amount `1234.567`, IBAN_1 → messaggio fisso, senza IBAN né importo

**FraudCheckServiceDtoImplTest**
- SD1 la porta riceve gli stessi 5 valori, amount `10.10` con scala 2 conservata
- SD2 REJECTED con 2 motivi → stessi transferId, status, motivi, stesso ordine
- SD3 ALLOWED → `reasons` vuota
- SD4 la porta lancia `UnsupportedCurrencyException` / `RuntimeException` → propagata così com'è

**FraudCheckControllerTest** (MockMvc standalone + `WebExceptionHandler` + `FraudCheckServiceDtoImpl` reale + mapper `*Impl` generati + `FakeCheckTransferInPort`)
- W1 (1) ALLOWED → 200, `$.reasons` assente
- W2 (3-7) REJECTED `["Amount must not exceed 10000 EUR","Source IBAN is locked"]` → 200, ordine rispettato
- W3 `amount: 100.25` → il fake riceve `100.25` scala 2 e i 5 campi identici
- W4 (15) stessa richiesta ×2 → corpi identici
- W5 (13) `testo qualunque` / `{"transferId":` / corpo vuoto → 400 `MALFORMED_REQUEST`, nessun `details`, fake mai chiamato
- W6 (10) senza `currency` o `null` → 400 `INVALID_REQUEST`, `["currency: must not be null"]`
- W7 `{}` → 400, 5 dettagli in ordine alfabetico
- W8 `"amount": "100.25"` / `"abc"` → 400 `INVALID_REQUEST`, `["amount: must be a number"]`
- W9 (8, 9, 11) il fake lancia `InvalidFraudCheckRequestException` → 400 INVALID_REQUEST, "Request validation failed"
- W10 (12) il fake lancia `UnsupportedCurrencyException` → 400 UNSUPPORTED_CURRENCY, "Only EUR is supported"
- W11 (14) `text/plain` / header assente → 415 `UNSUPPORTED_MEDIA_TYPE`, fake mai chiamato
- W12 il fake lancia `RuntimeException("IT60X…0001 1234.56")` / NPE → 500 `INTERNAL_ERROR`, "Internal error"
- W13 (vincolo fraud-02) il fake lancia la base `InvalidFraudRequestException` → 500, NON 400
- W14 ogni errore W5-W13 con IBAN_1, IBAN_2, `1234.56` nella richiesta o nel messaggio → corpo risposta senza IBAN_1, IBAN_2, `1234.56`, "Exception", "at com."
- W15 (amb. 3) sourceIban = destinationIban → arriva al fake, 200
- W16 campo sconosciuto → 200

**FraudCheckContractScenariosTest** (`CheckTransferServiceImpl` reale, soglia 10000, porta = lambda che restituisce {BLOCKED} ∩ input)
- CS1 (8) amount `0` → 400 INVALID_REQUEST; CS2 (9) `10.001` → 400; CS3 (11) sourceIban `""` → 400; CS4 (12) `USD` → 400 UNSUPPORTED_CURRENCY
- CS5 (3) `10000.01` → 200 REJECTED "Amount must not exceed 10000 EUR"; CS6 (6) entrambi bloccati → 200, Source poi Destination

## Criteri di accettazione
- `.\mvnw.cmd test -pl fraud-web-adapter -am` (da `fraud-service/`) in BUILD SUCCESS, senza Docker, compresi domain e application.
- `FraudApplicationTests` resta rosso come prima (decisione 4 del piano 03). Non si modifica bootstrap né i suoi test.
- Nessun `pom.xml` modificato.
- Il controller importa solo `com.bank.fraud.web.*`, Spring e jakarta.validation; nessun `com.bank.fraud.domain`, nessun `com.bank.transfer`.
- L'handler non ha handler per `IllegalArgumentException` né per `InvalidFraudRequestException` (classe base); nessuna chiamata a `getMessage()`; nessun logger.
- Percorso e campi come da contratto (sezioni 3-5). Errori `{code, message, details?}` con i codici della sezione 7. REJECTED risponde sempre 200.
- Test di domain e application esistenti non modificati e verdi.

## Rischi e fuori perimetro
**Rischi**: l'opzione A tocca `fraud-application` e contraddice il piano 03; deserializzatore e `MismatchedInputException` dipendono da Jackson 3 (`tools.jackson`, Boot 4.1.1) transitivo — verificare con `.\mvnw.cmd dependency:tree -pl fraud-web-adapter` prima del codice (se fosse Jackson 2 cambiano solo gli import); MockMvc standalone usa il proprio convertitore, non l'ObjectMapper di Boot (comportamenti nelle annotazioni del DTO); il catch-all su `RuntimeException` lascia 405/406 al framework senza corpo conforme; importi con esponente (`1e2`) accettati con scala negativa; nessun log per i 500; i componenti dell'adapter sono scansionati da `FraudApplication` (contesto comunque rosso fino alla issue di configurazione); dipendenze nuove: nessuna.
**Fuori perimetro**: `fraud-bootstrap` (bean, soglia, `BlockedIbanOutPort`, porta 8081, test di integrazione), adapter persistence, `503 SERVICE_UNAVAILABLE`, configurazione globale di Jackson, logging/audit, modifiche a dominio, contratto, transfer-service.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **Opzione A confermata**: `InvalidFraudCheckRequestException` in application; l'handler mappa su 400 solo la sottoclasse. La classe base `InvalidFraudRequestException` → 500. Vale il rischio documentato in fraud-03 (soglia errata/invarianti = errore di avvio o bug interno).
2. Percorso `POST /fraud-check` (issue e piano corretti).
3. **Opzione b: IBAN uguali = `400 INVALID_REQUEST`** (nuovo caso 16 del contratto, riga in sezione 7 e vincolo in sezione 4, già aggiornati). Sostituisce l'ipotesi della ambiguità 3. Conseguenze:
   - Regola nel dominio: `TransferToAssess` rifiuta `source.equals(destination)` (dopo normalizzazione di `Iban`) con `InvalidFraudRequestException`. Cade la decisione 5 della fase 02 ("mittente = destinatario accettato").
   - Test esistenti che affermano il comportamento opposto vanno AGGIORNATI dal tester perché la specifica è cambiata (non per farli passare): `TransferToAssessTest` T4, `FraudPolicyTest` P13, `CheckTransferServiceImplTest` S16 (e il commento su `LinkedHashSet`/insieme di 1 elemento perde senso: il caso non arriva più alla porta), e il W15 del piano 04 diventa: IBAN uguali → 400 INVALID_REQUEST.
   - Nuovi test: dominio (IBAN uguali, anche con spazi/minuscole diverse → `InvalidFraudRequestException`), application (IBAN uguali → `InvalidFraudCheckRequestException`, porta mai chiamata), web (caso 16 end-to-end in `FraudCheckContractScenariosTest`: 400 INVALID_REQUEST).
4-13. Ipotesi del piano confermate (nessuna obiezione).

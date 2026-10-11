# Piano transfer-01 — adapter HTTP verso il fraud-service

> In `docs/plans` non c'erano piani `transfer-NN`: NN=01. Le ipotesi segnate **(conferma)** vanno approvate prima della pipeline; nessuna è bloccante.

## Obiettivo
`FraudCheckOutPort` viene implementata da un adapter HTTP (`POST {fraud.base-url}/fraud-check`), che diventa il default. Comportamento fail closed, che porta a `503` sull'API del Transfer. `StubFraudCheck` resta disponibile solo con il profilo `inmemory`. I motivi viaggiano come lista fino all'API pubblica.

## Ambiguità (con ipotesi)
1. **Numerazione del piano.** `transfer-01-fraud-adapter.md`.
2. **Codice HTTP del fail closed (conferma).** `503 Service Unavailable` e non `502` (il 502 descriverebbe solo "risposta non valida"; timeout e connessione rifiutata sono indisponibilità; un codice unico non rivela dettagli interni). Corpo `ProblemDetail` come l'handler esistente: `status=503`, `title="Service Unavailable"`, `detail="Fraud check is temporarily unavailable: the transfer was not executed"` (testo fisso, senza `getMessage()`). Niente IBAN, importo, `transferId` o causa. Niente `Retry-After`.
3. **`transferId` nel corpo del 503 (conferma).** Non lo mettiamo: il bonifico non viene salvato e l'id non sarebbe ritrovabile. Solo nei log.
4. **Forma dei motivi nell'API pubblica (conferma).** `TransferResponseDto.reason` (String) diventa `reasons` (array JSON), sempre presente: `[]` per `COMPLETED`, uno o più elementi per `REJECTED`. Modifica incompatibile di `POST /transfers`, accettata con la decisione 2; oggi nessun consumatore (il frontend non esiste).
5. **Firma della porta.** 5 parametri: `checkFraud(String transferId, String sourceIban, String destIban, BigDecimal amount, String currency)`, senza un command nuovo. L'id esiste già: `Transfer.create` genera un UUID prima della chiamata (riga 46 di `CreateTransferServiceImpl`), si passa `transfer.id()`.
6. **Esiti non elencati nella sezione 8 del contratto (conferma).** Trattati come "non disponibile": codice 2xx diverso da 200 (201, 204), 3xx, `transferId` di risposta mancante o diverso da quello inviato, corpo che non è un oggetto JSON, `Content-Type` non JSON, `reasons` di `REJECTED` vuota dopo aver tolto elementi null o vuoti. **Tollerato**: `ALLOWED` con `reasons` presente (vuota o no) → `ALLOWED`, motivi scartati. `status` confrontato in modo esatto, maiuscolo (`allowed` = sconosciuto).
7. **Causa dell'eccezione (conferma).** `FraudCheckUnavailableException` **non** incatena la causa (i messaggi di Spring/Jackson possono riportare parti del corpo). Nel log solo il nome semplice della classe della causa e una categoria.
8. **Selezione per profilo (conferma).** Il profilo `inmemory` lega insieme persistence in memoria e stub antifrode (decisione 6). La selezione la fa `FraudConfig` con `@Profile` sui due `@Bean`, non con annotazioni sulle classi dell'adapter (lo stub ha bisogno di soglia e IBAN letti in configurazione, costruiti nel bootstrap).
9. **Testi dello stub (conferma).** Oggi lo stub restituisce un solo motivo con testi diversi dal contratto (`"Source IBAN is blocked"`, `"Amount must not exceed 10000"`). Ipotesi: allineare al contratto, testi alla lettera, motivi accumulati nell'ordine importo, mittente, destinatario, `ALLOWED` con lista vuota. Proprietà `fraud.stub.*` e valori invariati. Alternativa: solo la lista, testi invariati.
10. **Default delle nuove proprietà.** Default solo in `application.properties`, `@Value` senza fallback: chiave mancante o vuota = avvio fallito (fail closed, come fraud-06). Lo stub ha già fallback: non lo tocco.
11. **Strumento per il Fraud finto.** `MockRestServiceServer` per i test unitari (decisione 5). Per timeout di lettura, connessione rifiutata ed end-to-end serve un socket vero (`MockRestServiceServer` sostituisce la request factory): server HTTP del JDK (`com.sun.net.httpserver.HttpServer`), nessuna dipendenza.
12. **Timeout di connessione.** Non riproducibile in modo affidabile in un test: simulato con `MockRestServiceServer.withException(new HttpConnectTimeoutException(..))`. Il valore configurato (1s) si verifica solo in `FraudConfigTest`.
13. **Numerazione duplicata nel contratto.** Il 16 compare sia lato Fraud sia lato Transfer. Qui i casi lato Transfer sono T16..T21. Correzione del contratto fuori perimetro.

## Modifiche per modulo

**Dipendenze** (autorizzate, decisione 4). Versioni dal BOM Spring Boot 4.1.1, nessuna scritta nei pom. Verificato in `~/.m2`: `spring-framework.version=7.0.9` (`spring-web-7.0.9.jar`, `spring-test-7.0.9.jar`), `jackson-bom.version=3.1.5` (`tools/jackson/core/jackson-databind/3.1.5`; `jackson-annotations` 2.21 transitivo). Di `spring-boot-restclient`, `spring-boot-starter-restclient` e `-restclient-test` 4.1.1 ci sono **solo i POM, senza jar**: non proposti. L'autoconfigurazione di Boot non serve (il client lo costruisce l'adapter).

| pom | Aggiunta | Scope | Motivo |
|---|---|---|---|
| `transfer-service/transfer-fraud-adapter/pom.xml` | `org.springframework:spring-web` | compile | `RestClient`, `JdkClientHttpRequestFactory`, `RestClientException`; porta `commons-logging` per i log |
| idem | `tools.jackson.core:jackson-databind` | compile | convertitore JSON (Jackson 3) rilevato da `RestClient.builder()` |
| idem | `org.springframework.boot:spring-boot-starter-test` | test | `MockRestServiceServer`, matcher `content().json`/`jsonPath`, `OutputCaptureExtension` (stesso artefatto di `transfer-application`) |
| `transfer-application`, `transfer-web-adapter`, `transfer-bootstrap`, pom padre | nessuna | — | `spring-web` già nel bootstrap via webmvc, `spring-test`/MockMvc via `spring-boot-starter-webmvc-test`, H2 via persistence |

**`transfer-application`**
| File (package `com.bank.transfer.application.…`) | Responsabilità |
|---|---|
| `port.out.FraudCheckOutPort` (modifica) | `FraudCheckResultDto checkFraud(String transferId, String sourceIban, String destIban, BigDecimal amount, String currency)`. Javadoc: lancia `FraudCheckUnavailableException` per ogni esito "non disponibile". |
| `dto.FraudCheckResultDto` (modifica) | `record (FraudStatusEnum decision, List<String> reasons)`. Compatto: `requireNonNull(decision)`, `reasons` null → `List.of()`, altrimenti `List.copyOf`. |
| `dto.TransferResultDto` (modifica) | `record (String transferId, String transferStatus, List<String> reasons)`, stesso trattamento di `reasons`. |
| `exception.FraudCheckUnavailableException` (nuova) | `extends RuntimeException` (NON `IllegalArgumentException`, che l'handler mappa a 400). Costruttore `(String transferId)`, messaggio fisso `"Fraud check unavailable for transfer " + transferId`, getter `transferId()`, nessuna causa. |
| `service.CreateTransferServiceImpl` (modifica) | Chiama `checkFraud(transfer.id(), source.value(), dest.value(), money.amount(), money.currency())` e copia `reasons()` nel `TransferResultDto`. Non cattura `FraudCheckUnavailableException`: la chiamata avviene prima di `transactionTemplate.execute`, quindi niente transazione e niente salvataggi. |

**`transfer-fraud-adapter`** (package base esistente `com.bank.transfer.port.out.fraud`, non spostato)
| File | Responsabilità |
|---|---|
| `impl.StubFraudCheck` (modifica) | Nuova firma con `transferId` (ignorato). Restituisce la lista dei motivi (amb. 9). Nessuna annotazione Spring. |
| `impl.HttpFraudCheckOutPortImpl` (nuova) | `implements FraudCheckOutPort`, costruttore `(RestClient)`. `POST /fraud-check`, `contentType`/`accept` `application/json`. `onStatus(s -> s.value() != 200, …)` copre anche 2xx e 3xx. Il corpo diventa `FraudCheckHttpResponseDto` ed è validato (amb. 6). Ogni `RuntimeException` del giro HTTP/mapping, o risposta non valida, → `FraudCheckUnavailableException(transferId)`. Log via `org.apache.commons.logging`: INFO `transferId`, status, motivi; WARN `transferId`, categoria (`HTTP_STATUS <n>`, `IO_ERROR`, `INVALID_BODY`, `UNKNOWN_STATUS`, `REJECTED_WITHOUT_REASONS`, `TRANSFER_ID_MISMATCH`) e nome semplice della classe della causa. Mai IBAN, importo o corpo. |
| `impl.FraudRestClientFactory` (nuova) | `static RestClient create(String baseUrl, Duration connectTimeout, Duration readTimeout)`: `HttpClient` JDK con `connectTimeout`, `Version.HTTP_1_1`, `Redirect.NEVER`; `JdkClientHttpRequestFactory` con `setReadTimeout`; `RestClient.builder().baseUrl(..).requestFactory(..)`. Valida `baseUrl` non vuoto e durate positive con messaggi fissi. |
| `dto.request.FraudCheckHttpRequestDto` (nuova) | `record (String transferId, String sourceIban, String destinationIban, BigDecimal amount, String currency)` (nomi del contratto). `toString()` solo `transferId`. Factory `of(...)`: scala negativa → 0 (`1E+3` → `1000`), altrimenti scala invariata (`10.10` resta `10.10`). |
| `dto.response.FraudCheckHttpResponseDto` (nuova) | `record (String transferId, String status, List<String> reasons)` con `@JsonIgnoreProperties(ignoreUnknown = true)`. `status` String (non enum) così un valore sconosciuto è un caso gestito e non un errore di Jackson. |

**`transfer-web-adapter`**
| File | Responsabilità |
|---|---|
| `com.bank.transfer.web.dto.response.TransferResponseDto` (modifica) | `record (String transferId, String transferStatus, List<String> reasons)` |
| `com.bank.transfer.web.mapper.TransferResultDtoToTransferResponseDtoMapper` | Invariato: MapStruct mappa `reasons` per nome; lo copre un test. |
| `com.bank.transfer.web.port.in.web.exception.WebExceptionHandler` (modifica) | Nuovo `@ExceptionHandler(FraudCheckUnavailableException.class)` → `ProblemDetail` 503 con testo fisso (amb. 2). Handler esistenti invariati. |

**`transfer-bootstrap`**
| File | Responsabilità |
|---|---|
| `com.bank.transfer.config.FraudConfig` (modifica) | `@Bean @Profile("inmemory") FraudCheckOutPort stubFraudCheckOutPort(...)`: corpo attuale, `fraud.stub.*` con i fallback attuali. `@Bean @Profile("!inmemory") FraudCheckOutPort httpFraudCheckOutPort(@Value("${fraud.base-url}") String, @Value("${fraud.connect-timeout}") Duration, @Value("${fraud.read-timeout}") Duration)` → `new HttpFraudCheckOutPortImpl(FraudRestClientFactory.create(..))`. |
| `src/main/resources/application.properties` (modifica) | Aggiunge `fraud.base-url=${FRAUD_BASE_URL:http://localhost:8081}`, `fraud.connect-timeout=1s`, `fraud.read-timeout=3s`. `fraud.stub.*` resta (usato solo con `inmemory`). |
| `src/test/java/com/bank/transfer/config/FraudConfigTest.java` (nuovo) | `ApplicationContextRunner` con `FraudConfig`. |
| `src/test/java/com/bank/transfer/FraudCheckEndToEndTest.java` (nuovo) | `@SpringBootTest` su H2 (come il test di concorrenza) con `@AutoConfigureMockMvc(print = NONE)` (package Boot 4 `org.springframework.boot.webmvc.test.autoconfigure`, da verificare), Fraud finto JDK su porta 0 via `@DynamicPropertySource`, `OutputCaptureExtension`. |
| `src/test/java/com/bank/transfer/CreateTransferConcurrencyTest.java` (**modifica per cambio di specifica**) | Oggi passa solo perché lo stub è il default (`fraud.stub.threshold=1000000`); con l'HTTP di default chiamerebbe `localhost:8081`. Si toglie la proprietà `fraud.stub.threshold` e si aggiunge una `@TestConfiguration` annidata con `@Bean @Primary FraudCheckOutPort` che restituisce sempre `ALLOWED` (lambda thread-safe). Il profilo `inmemory` non va bene (renderebbe in memoria anche la persistence). Resta senza Docker (H2). |
| `TransferApplicationTests` | **Non modificato.** Resta verde (la creazione del bean non apre connessioni). Richiede PostgreSQL (Docker), come oggi. |

## Test attesi
Dati: `ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93"`, `IBAN_1 = IT60X0542811101000000000001`, `IBAN_2 = …0002`, importi con `isEqualByComparingTo`.

**Ordine TDD** (da `transfer-service/`): 1) application: test (rossi) poi codice, `.\mvnw.cmd test -pl transfer-application -am` (fraud-adapter, web e bootstrap non compilano finché non si fanno i passi 2-4). 2) pom del fraud-adapter, `StubFraudCheckTest`, stub, `HttpFraudCheckOutPortImplTest`, DTO e impl, `HttpFraudCheckOutPortImplNetworkTest`, factory; `-pl transfer-fraud-adapter -am`. 3) web: `TransferControllerTest` e `TransferServiceDtoImplTest`, poi DTO e handler; `-pl transfer-web-adapter -am`. 4) bootstrap: `FraudConfigTest`, poi `FraudConfig` e properties; adattamento di `CreateTransferConcurrencyTest`; `FraudCheckEndToEndTest`. 5) `.\mvnw.cmd test` completo; Docker solo per `TransferApplicationTests` e i test JPA di persistence.

**`application.dto.FraudCheckResultDtoTest`** (nuovo): reasons `null` → `reasons()` vuota; lista mutabile modificata dopo la costruzione → DTO invariato e `reasons()` immutabile; decision `null` → `NullPointerException`.

**`application.service.CreateTransferServiceImplTest`** (aggiornamento per cambio di specifica: firma della porta e `reasons`). Cambiano `givenFraudDecision(decision, List<String>)` con 5 matcher, `order.verify(fraudCheck).checkFraud(anyString(), SOURCE_IBAN, DEST_IBAN, new BigDecimal("30"), "EUR")` e il `result.reason()` del test "rejected" → `result.reasons()` con `containsExactly(..)`. Nuovi:
- conti esistenti, Fraud ALLOWED → il `transferId` passato alla porta = id del `Transfer` salvato = `result.transferId()`; `reasons()` vuota
- Fraud REJECTED `["Amount must not exceed 10000 EUR","Source IBAN is locked"]` → `reasons` identici e in ordine; bonifico salvato REJECTED; conti non toccati
- la porta lancia `FraudCheckUnavailableException(id)` → propagata (stesso tipo); `transferRepository` mai chiamato; `accountRepository.save` e `findByIbanForUpdate` mai chiamati; `verifyNoInteractions(transactionManager)`
- `new FraudCheckUnavailableException(ID)` → messaggio con ID e senza IBAN o importo; `getCause()` null

**`port.out.fraud.impl.StubFraudCheckTest`** (nuovo, amb. 9): importo ≤ soglia, IBAN liberi → ALLOWED, lista vuota; importo = soglia → ALLOWED; importo > soglia con mittente e destinatario bloccati → REJECTED con 3 motivi del contratto in ordine importo, mittente, destinatario; qualsiasi `transferId` → stesso esito.

**`port.out.fraud.impl.HttpFraudCheckOutPortImplTest`** (`MockRestServiceServer.bindTo(RestClient.builder().baseUrl("http://fraud.test"))`, `OutputCaptureExtension`)
- H1 200 `{"transferId":ID,"status":"ALLOWED"}` → ALLOWED, lista vuota; richiesta `POST http://fraud.test/fraud-check`, `Content-Type: application/json`
- H2 come H1 → corpo `content().json(..., strict)` con i 5 campi del contratto (`destinationIban`, non `destIban`); corpo grezzo contiene `"amount":100.25`
- H3 importi `10.10`, `30`, `new BigDecimal("1E+3")` → `"amount":10.10`, `"amount":30`, `"amount":1000` (mai `E`)
- H4 200 REJECTED con 2 motivi → REJECTED, stessi motivi e ordine
- H5 200 ALLOWED con campi sconosciuti (`"score":0.9`, oggetto annidato) → ALLOWED
- H6 200 ALLOWED con `reasons:["x"]` o `[]` → ALLOWED, lista vuota (amb. 6)
- H7 500 `INTERNAL_ERROR`; 503; 400 `INVALID_REQUEST`; 400 `UNSUPPORTED_CURRENCY`; 415; 404 → `FraudCheckUnavailableException`
- H8 201 e 204 con corpo ALLOWED; 302 → `FraudCheckUnavailableException`
- H9 200 corpo `testo qualunque`; corpo vuoto; `[]`; `Content-Type: text/plain` → `FraudCheckUnavailableException`
- H10 200 `status` `"BLOCKED"`, `"allowed"`, assente, `null` → `FraudCheckUnavailableException`
- H11 200 REJECTED senza `reasons`; `reasons:[]`; `reasons:[null," "]` → `FraudCheckUnavailableException`
- H12 200 ALLOWED con `transferId` diverso da ID, o assente → `FraudCheckUnavailableException`
- H13 `withException(new SocketTimeoutException())`, `HttpConnectTimeoutException("x")`, `ConnectException()` → `FraudCheckUnavailableException`
- H14 ogni errore H7-H13 (il corpo di errore contiene anche IBAN_1 e `100.25`) → messaggio con ID, senza IBAN_1, IBAN_2, `100.25`; `getCause()` null; output catturato senza IBAN_1, IBAN_2, `100.25` e con ID
- H15 H1 e H4 → output con ID ed esito (ed eventuali motivi), senza IBAN o importo
- H16 `FraudCheckHttpRequestDto` con dati reali, `toString()` → solo ID

**`port.out.fraud.impl.HttpFraudCheckOutPortImplNetworkTest`** (`HttpServer` JDK su porta 0, client da `FraudRestClientFactory`)
- N1 il server risponde ALLOWED JSON in 50 ms, read 3s → ALLOWED (factory reale + Jackson 3)
- N2 il server dorme 1s, read 200 ms → `FraudCheckUnavailableException` in meno di ~900 ms
- N3 porta presa da una `ServerSocket` e chiusa (connessione rifiutata) → `FraudCheckUnavailableException`
- N4 `baseUrl` vuoto; durata zero o negativa → `IllegalArgumentException` con messaggio fisso

**`web.port.in.web.TransferControllerTest`** (aggiornamento per cambio di specifica: `returnsTransferResult` usa `List.of()` e verifica `$.reasons` array vuoto)
- il servizio restituisce REJECTED con 2 motivi → 200, `$.reasons[0]`, `$.reasons[1]` in ordine; `$.reason` assente
- il servizio lancia `FraudCheckUnavailableException("t-1")` → 503, `application/problem+json`, `$.status`=503, `$.detail` = testo fisso; corpo senza "t-1", IBAN_1, IBAN_2, `10.00`, "Exception"
- test esistenti 404/400/422 invariati e verdi

**`web.servicedto.internal.TransferServiceDtoImplTest`** (nuovo, mapper `*Impl` generati): la porta restituisce REJECTED `["a","b"]` → `reasons` `["a","b"]`; la porta lancia `FraudCheckUnavailableException` → propagata così com'è.

**`config.FraudConfigTest`** (`ApplicationContextRunner`, `withUserConfiguration(FraudConfig.class)`; per `Duration` va impostato `ApplicationConversionService.getSharedInstance()` sul bean factory, vedi Rischi)
- nessun profilo, `fraud.base-url`, `1s`, `3s` → un solo `FraudCheckOutPort`, di tipo `HttpFraudCheckOutPortImpl`
- `spring.profiles.active=inmemory` → un solo bean, di tipo `StubFraudCheck`
- nessun profilo, `fraud.base-url` assente o vuoto → avvio fallito; `fraud.read-timeout=abc` → avvio fallito

**`FraudCheckEndToEndTest`** (bootstrap; H2 `jdbc:h2:mem:fraude2e`, conti creati dal test con IBAN propri, read-timeout 500 ms; `POST /transfers`)
- T16 ALLOWED → 200 COMPLETED, `reasons` `[]`; saldi aggiornati; il finto ha ricevuto `transferId` = quello della risposta, `amount` numero, `destinationIban`
- T17 REJECTED con 2 motivi → 200 REJECTED con i 2 motivi; riga `transfer` REJECTED (via `JdbcTemplate`); saldi invariati
- T18 500 → 503; righe di `transfer` invariate; saldi invariati
- T19 risponde dopo 1s → come T18; T20 `status` sconosciuto → come T18; T21 REJECTED senza motivi → come T18; T22 server fermato (connessione rifiutata) → come T18
- T23 per T18-T22: corpo 503 e output catturato senza IBAN né importi inviati
- T24 `Environment`: `fraud.connect-timeout`=`1s`, `fraud.read-timeout`=`3s`, `fraud.base-url` default `http://localhost:8081` (letto senza l'override dinamico, oppure da un test separato)

## Criteri di accettazione
- I moduli application, fraud-adapter e web-adapter verdi senza Docker (`.\mvnw.cmd test -pl <modulo> -am`). Nel bootstrap, `FraudConfigTest`, `FraudCheckEndToEndTest` e `CreateTransferConcurrencyTest` verdi senza Docker (H2). `TransferApplicationTests` verde con PostgreSQL attivo, non modificato.
- Nei pom solo quanto indicato in tabella (3 dipendenze in `transfer-fraud-adapter`), senza versioni scritte a mano.
- Senza profilo il bean `FraudCheckOutPort` è `HttpFraudCheckOutPortImpl`; con `inmemory` è `StubFraudCheck`. Mai entrambi.
- Nessun esito "non disponibile" del contratto diventa ALLOWED o REJECTED; sull'API diventa `503` con corpo fisso, e nulla viene salvato.
- Richiesta conforme alle sezioni 3-4 del contratto (5 campi, `amount` numero JSON senza esponente). Campi sconosciuti della risposta ignorati.
- Né i log dell'adapter né i messaggi di eccezione contengono IBAN o importi (H14, H15, T23).
- Nessun import `com.bank.fraud` nel transfer-service. Il dominio non cambia. In application solo porte e DTO, nessuna nuova annotazione Spring.
- I test esistenti cambiano solo nei punti elencati (`CreateTransferServiceImplTest`, `TransferControllerTest.returnsTransferResult`, `CreateTransferConcurrencyTest`), e solo perché cambia la specifica.

## Rischi e fuori perimetro
**Rischi**
- L'API pubblica di `POST /transfers` cambia in modo incompatibile (`reason` → `reasons`).
- I bonifici non EUR restano sempre fermi (503, decisione 7). Vale anche per importi con più di 2 decimali, che oggi il dominio Transfer accetta: il Fraud risponde 400 e il Transfer 503, non 400.
- `@Value` con `Duration` (`1s`) richiede il `ConversionService` di Boot; in `ApplicationContextRunner` va impostato a mano (stesso problema di fraud-06). Ripiego: proprietà in millisecondi.
- `MockRestResponseCreators.withException(IOException)` e `MockRestServiceServer.bindTo(RestClient.Builder)` vanno verificati sulla 7.0.9 prima di scrivere H13 (in `.m2` c'è `spring-test-7.0.9-sources.jar`).
- Il package di `@AutoConfigureMockMvc` in Boot 4.1.1 va verificato.
- Il timeout di connessione reale è coperto solo per simulazione.
- I test con socket reali sono sensibili ai tempi della macchina: margini larghi.
- `JdkClientHttpRequestFactory` forzato a HTTP/1.1 evita l'upgrade h2c.
- `RestClient` costruito fuori da Boot: non usa il `JsonMapper` né l'osservabilità di Boot.
- Il profilo `inmemory` lega insieme persistence e antifrode (amb. 8).
- Emerso dalla lettura, fuori perimetro: `CreateTransferServiceImpl.getAccountsMap` mette l'IBAN nel messaggio di `AccountNotFoundException` e `WebExceptionHandler` lo restituisce con `getMessage()`; `CreateTransferServiceImpl` usa `TransactionTemplate` di Spring oltre a `@Service`/`@Transactional` ammessi dal CLAUDE.md radice; il package base del fraud-adapter (`com.bank.transfer.port.out.fraud`) non segue lo schema del persistence (`com.bank.transfer.persistence.port.out.persistence`); i motivi non vengono salvati (`TransferEntity` non ha la colonna: esistono solo nella risposta).

**Fuori perimetro**: modifiche al fraud-service e al contratto (compresa la numerazione duplicata), nuovi tentativi automatici, circuit breaker, autenticazione, audit e salvataggio dei motivi (servirebbe una nuova migration Flyway), `/v2/fraud-check`, `transfer-notification-adapter`, pulizia di `fraud.stub.*`, correzione dei punti segnalati sopra, commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
Tutte le ipotesi del piano confermate, comprese: 503 (non 502) con corpo fisso, `reason` → `reasons` nell'API pubblica, esiti extra della sezione 8 come "non disponibile", eccezione senza causa, `@Profile` in `FraudConfig`, testi dello stub allineati al contratto, proprietà senza fallback, `HttpServer` del JDK per i test di rete. Le tre dipendenze in `transfer-fraud-adapter` sono autorizzate: quella di test (`spring-boot-starter-test`) la aggiunge il tester, quelle compile (`spring-web`, `tools.jackson.core:jackson-databind`) l'implementer.

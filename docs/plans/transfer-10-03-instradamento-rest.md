# transfer-10-03: Instradamento e REST (piano di dettaglio della Fase 3)

Stato: **in attesa di approvazione**. Fase 3 del piano v3.1 `transfer-10-saga-minima.md` (la sezione v3.1 prevale: 202 per SENT **e** UNKNOWN, slf4j, UNKNOWN nel perimetro). Fasi 1, E0 e 2 completate e approvate (526 test verdi, reviewer APPROVED), nel working tree, non committate.

## Fatti verificati dal planner
- `CreateTransferServiceImpl`: `@Service public class CreateTransferServiceImpl implements CreateTransferInPort` (riga 27), import alla riga 8, costruttore a 5 argomenti.
- `ExternalTransferSagaServiceImpl`: `@Service`, implementa solo `CreateExternalTransferInPort` e `ProcessClearingOutcomesInPort`; restituisce `"SENT"`, `"UNKNOWN"` o `"REJECTED"` (con "Clearing house unavailable" dopo la compensazione, oppure con i motivi dell'antifrode).
- `TransferController`: un solo `@PostMapping("/transfers")` che restituisce `TransferResponseDto`; costruttore con il solo `TransferServiceDto`.
- `TransferControllerTest`: costruisce `new TransferController(service)`, verifica solo `status().isOk()` per COMPLETED e REJECTED, `jsonPath` e gli errori 503, 404, 400, 422: il passaggio a `ResponseEntity` lo lascia verde senza modifiche.
- `TransferResponseDto`: `record(transferId, transferStatus, reasons)`. `WebExceptionHandler` mappa 404, 422, 503, 400 e non cambia.
- Chi dipende dal tipo concreto `CreateTransferServiceImpl`: `CreateTransferServiceImplTest` (lo costruisce con `new`, invariato) e **`ExternalTransferSagaIntegrationTest.wiringHasOneBeanPerPortAndNoScheduler` (righe 138-139), che DEVE cambiare**.
- Inietta `CreateTransferInPort` per tipo: `CreateTransferConcurrencyTest`, `OutboxPublisherIntegrationTest`, `OutboxPublisherKafkaWiringIntegrationTest`, `OutboxRollbackIntegrationTest` (solo l'interfaccia, IBAN ABI `05428`: riceveranno il router e seguiranno il flusso interno identico); `TransferServiceDtoImpl` riceve il router; `TransferServiceDtoImplTest` usa lambda ed e invariato.
- I `@Primary` dei test (`FraudCheckOutPort`, `NotificationOutPort`, `NotificationSenderOutPort`, `PendingNotificationOutPort`) sostituiscono porte **out**: il router non ne usa e i due service le ricevono per scansione come oggi. Il nuovo cablaggio non li tocca.
- `BankCode` rifiuta tutto cio che non e `[A-Z0-9]{5}`.
- Stile config/proprieta: `ClearingConfig` e `ClearingConfigTest` usano `@Value` senza default (il default sta in `application.properties`), con `ApplicationContextRunner` + `PropertyPlaceholderAutoConfiguration` + conversion service di Boot.
- Stile end-to-end: `FraudCheckEndToEndTest` usa `@SpringBootTest` con H2 dedicato, `@AutoConfigureMockMvc(print = MockMvcPrint.NONE)`, `@ExtendWith(OutputCaptureExtension.class)`, `JdbcTemplate`, `MvcResult`.
- In `docs/` ci sono solo `log-agenti.md`, `plans/` e `contracts/`; alla radice `README.md`.

## Obiettivo
Rendere raggiungibile la Saga da `POST /transfers` con instradamento per ABI: un router `@Service` sceglie il flusso interno o la Saga. Risposta 202 per SENT e UNKNOWN, 200 negli altri casi. Flusso interno identico. Documentazione in `transfer-service/CLAUDE.md` (passo da autorizzare).

## Ambiguita (nessuna blocca; il piano segue le raccomandazioni R)
1. **IBAN destinatario con ABI diverso da `05428` che pero e un conto interno. R: e comunque esterno** (il router non consulta i conti: e la regola decisa). Oggi `DemoDataSeeder` e tutti i test usano solo `05428`. Va documentato nei limiti. Alternativa: il router cerca prima il conto (contraddice la decisione e reintroduce l'ambiguita di t29).
2. **IBAN non a 27 caratteri. R: 400 come oggi** (`new Iban(...)` lancia `IllegalArgumentException("Malformed IBAN, it has to be 27 characters")`). Gli IBAN esteri sono fuori perimetro.
3. **Ordine di validazione nel router. R:** prima `new Iban(sourceIban)` poi `new Iban(destIban)`, come la prima riga di `CreateTransferServiceImpl`: con due IBAN non validi eccezione e messaggio restano quelli di oggi. Command null: NPE (come oggi). Il service scelto rivalida: costo trascurabile.
4. **`transfer.bank-code` assente. R:** `@Value("${transfer.bank-code}")` senza default nel codice; il default `05428` sta in `application.properties` come `${TRANSFER_BANK_CODE:05428}` (stile di `ClearingConfig`). Proprieta assente o non valida: l'avvio fallisce. Il test del default legge `application.properties`.
5. **Mappatura dello stato in 202. R:** in `TransferController`, `Set.of("SENT", "UNKNOWN").contains(body.transferStatus())` -> `HttpStatus.ACCEPTED`, altrimenti 200; confronto esatto sul testo. `TransferServiceDto` invariato.
6. **Modifica ammessa ed esplicita di un test esistente.** `ExternalTransferSagaIntegrationTest.wiringHasOneBeanPerPortAndNoScheduler` (Fase 2), righe 138-139: resta `assertThat(context.getBeansOfType(CreateTransferInPort.class)).hasSize(1);`; diventano
   `assertThat(context.getBean(CreateTransferInPort.class)).isInstanceOf(TransferRoutingServiceImpl.class);`, piu nuove `assertThat(context.getBeansOfType(CreateInternalTransferInPort.class)).hasSize(1);`, `assertThat(context.getBean(CreateInternalTransferInPort.class)).isInstanceOf(CreateTransferServiceImpl.class);`,
   `assertThat(context.getBean(BankCode.class)).isEqualTo(new BankCode("05428"));`. Gli import si adeguano (`TransferRoutingServiceImpl`, `CreateInternalTransferInPort`, `BankCode`). Il resto del test e della classe resta invariato. **E l'unica modifica di un test esistente in questa fase.**
7. **Documenti in `docs/`. R: nessun file nuovo.** Il racconto della Saga e gia in `docs/plans/transfer-10-*.md`; le istruzioni operative vanno in `transfer-service/CLAUDE.md`. Non serve un contratto (stanza simulata e interna). Alternativa: un paragrafo "Demo bonifico esterno" nel `README.md` radice (variabili d'ambiente e un esempio di POST), da approvare insieme a CLAUDE.md.
8. **Antifrode nell'end-to-end. R:** `FraudCheckOutPort` `@Primary` scritto a mano, decisione in un `AtomicReference` e contatore di chiamate (stile di `OutboxRollbackIntegrationTest` e `CreateTransferConcurrencyTest`). Niente server HTTP finto (l'integrazione con il Fraud e gia provata da `FraudCheckEndToEndTest`).
9. **`transfer-service/CLAUDE.md`: passo finale che richiede l'autorizzazione esplicita dell'utente** (annunciata nella v3, non ancora data). Il codice non dipende da questo passo.

## Modifiche per modulo

**transfer-application** (`com.bank.transfer.application`)
| File | Tipo | Responsabilita |
|---|---|---|
| `port/in/CreateInternalTransferInPort.java` | nuovo | `TransferResultDto executeTransfer(CreateTransferCommand command)`. **Non estende** `CreateTransferInPort` (due bean dello stesso tipo). Javadoc: "internal flow (destination of this bank); only the router calls it" |
| `service/CreateTransferServiceImpl.java` | modificato | **solo** `implements CreateTransferInPort` -> `implements CreateInternalTransferInPort` e l'import. `@Service`, costruttore e logica invariati (`@Override` valido: stessa firma) |
| `service/TransferRoutingServiceImpl.java` | nuovo | `@Service`, `implements CreateTransferInPort`. Costruttore `(BankCode ourBankCode, CreateInternalTransferInPort internal, CreateExternalTransferInPort external)` con `requireNonNull`. `executeTransfer`: `new Iban(source)`, `new Iban(dest)`; se `dest.bankCode().equals(ourBankCode)` -> `internal.executeTransfer(command)`, altrimenti `external.executeExternalTransfer(command)`. Restituisce il risultato cosi com'e e propaga le eccezioni invariate. Log slf4j DEBUG facoltativo con il solo "internal/external", nessun IBAN |

**transfer-rest-adapter** (`com.bank.transfer.rest`)
| File | Tipo | Responsabilita |
|---|---|---|
| `port/in/rest/TransferController.java` | modificato | `public ResponseEntity<TransferResponseDto> executeTransfer(@RequestBody TransferRequestDto)`: chiama `transferServiceDto` come oggi; 202 se lo stato e in `{SENT, UNKNOWN}`, altrimenti 200, stesso corpo. Costruttore e mapping invariati |

`TransferServiceDto`, `TransferServiceDtoImpl`, i mapper, `TransferResponseDto` e `WebExceptionHandler`: invariati.

**transfer-bootstrap** (`com.bank.transfer`)
| File | Tipo | Responsabilita |
|---|---|---|
| `config/TransferRoutingConfig.java` | nuovo | `@Configuration` package-private; `@Bean BankCode bankCode(@Value("${transfer.bank-code}") String value)` -> `new BankCode(value)`. Nient'altro: router e service sono `@Service` |
| `resources/application.properties` | modificato | aggiunge `transfer.bank-code=${TRANSFER_BANK_CODE:05428}` in un blocco `#ROUTING` |
| test `ExternalTransferSagaIntegrationTest.java` | **modificato (ambiguita 6)** | solo le asserzioni di wiring |

Ordine di avvio: `BankCode` (config), `CreateTransferServiceImpl` e `ExternalTransferSagaServiceImpl` (porte out) non dipendono dal router; il router dipende da questi tre: **nessun ciclo**. Un solo bean per `CreateTransferInPort` (router), `CreateInternalTransferInPort` (service interno), `CreateExternalTransferInPort` (saga).

**`transfer-service/CLAUDE.md`** (passo finale, **da autorizzare**)
- Tabella dei moduli: application con router e saga; bootstrap con `config/TransferRoutingConfig`, `config/Clearing*` e il package `clearing.port.out.clearing.impl`.
- Sezione "Bonifici esterni (Saga)": criterio ABI (`transfer.bank-code`, `TRANSFER_BANK_CODE`, default `05428`, solo IBAN a 27 caratteri); stati `SENT`, `UNKNOWN`, `COMPLETED`, `REJECTED`; risposta 202 per SENT e UNKNOWN; compensazione solo per invio non ricevuto; UNKNOWN senza compensazione, chiuso da un esito tardivo applicato da `processOutcomes()`; outbox solo COMPLETED e REJECTED; tabella `external_transfer` (V3).
- Sezione "Stanza di compensazione simulata": nel bootstrap (**deviazione documentata** dalla struttura standard); modi `ACCEPT`, `REJECT`, `UNAVAILABLE`, `TIMEOUT_THEN_ACCEPT`, `TIMEOUT_THEN_REJECT` (`CLEARING_SIMULATOR_MODE`); `CLEARING_OUTCOMES_ENABLED` (default `false`, con WARN).
- Limiti didattici: un conto con ABI diverso e trattato come esterno; niente riconciliazione attiva ne scadenze per UNKNOWN; esito consumato e poi fallito; crash tra SENT e `submit`.
- Test su H2: aggiungere `ExternalTransferSagaIntegrationTest` ed `ExternalTransferEndToEndTest` alla riga dei comandi. Log: slf4j nelle classi nuove.

## Test attesi (prima del codice, stesso package della classe)

**`TransferRoutingServiceImplTest`** (nuovo, `...application.service`; le due porte in come lambda che registrano le chiamate; `BankCode("05428")`; sorgente `IT60X0542811101000000000001`):
`ourBankCodeGoesToTheInternalFlowOnly` (destinatario `IT60X0542811101000000000002`: `internal` una volta con lo stesso command, `external` mai, risultato tale e quale); `anotherBankCodeGoesToTheExternalFlowOnly` (`IT60X0306909606100000012345`);
`missingInternalDestinationIsTheInternalFlow404` (ABI `05428`, `internal` lancia `AccountNotFoundException("Destination Account not found")`: stessa istanza propagata, `external` mai); `blockedDemoIbanStaysInternal` (`...0099`);
`destinationIsNormalizedBeforeComparing` (`"it60 x054 2811 1010 0000 0000 002"` -> `internal`); `invalidDestinationIsRefusedBeforeCallingAnyFlow` (26 caratteri: `IllegalArgumentException("Malformed IBAN, it has to be 27 characters")`, nessuna porta chiamata);
`invalidSourceIsReportedFirst` (sorgente con carattere non valido e destinatario di 26 caratteri: messaggio "Malformed IBAN, invalid characters"); `externalFlowExceptionsArePropagatedAsTheyAre` (`FraudCheckUnavailableException`); `nullCommandIsRefused` (NPE, nessuna porta); `nullCollaboratorsAreRefused`;
`internalPortDoesNotExtendTheEntryPort` (riflessione: `CreateTransferInPort.class.isAssignableFrom(CreateInternalTransferInPort.class)` e false).

**`CreateTransferServiceImplTest`:** invariato.

**`TransferControllerTest`** (solo test nuovi; i 7 esistenti invariati): `returns202WhenTheTransferIsSent` (`("t-1","SENT",[])`: 202, id, `SENT`, `reasons` vuoto); `returns202WhenTheOutcomeIsUnknown`; `returns200ForARejectionAfterTheCompensation` (`REJECTED` con "Clearing house unavailable"); `onlyTheExactStatusNamesAnswer202` (`"sent"`: 200).

**`TransferRoutingConfigTest`** (nuovo, `com.bank.transfer.config`; `ApplicationContextRunner` + `PropertyPlaceholderAutoConfiguration` + `TransferRoutingConfig`, come `ClearingConfigTest`): `theConfiguredBankCodeIsTheBean` (`03069`); `aMissingBankCodeFailsTheStartup`; `anInvalidBankCodeFailsTheStartup` (parametrico: `ABC`, `abcde`, `05428 `, `""`).

**`RoutingDefaultPropertiesTest`** (nuovo, `com.bank.transfer`, stile `FraudDefaultPropertiesTest`): `ourBankCodeIs05428ByDefault`.

**`ExternalTransferSagaIntegrationTest`** (modifica ammessa, ambiguita 6): `wiringHasOneBeanPerPortAndNoScheduler` con le nuove asserzioni; nessun altro cambio.

**`ExternalTransferEndToEndTest`** (nuovo, `com.bank.transfer`): `@SpringBootTest` su `jdbc:h2:mem:externale2e`, `@AutoConfigureMockMvc(print = MockMvcPrint.NONE)`, `@ExtendWith(OutputCaptureExtension.class)`; `@TestConfiguration` con `FraudCheckOutPort` `@Primary` (decisione in `AtomicReference`, default ALLOWED, `AtomicInteger` per le chiamate);
si iniettano `MockMvc`, `ProcessClearingOutcomesInPort`, `SimulatedClearingHouseOutPortImpl`, `AccountRepositoryOutPort`, `JdbcTemplate`. `@BeforeEach`: modo ACCEPT, coda svuotata con `pollOutcomes()`, antifrode ALLOWED, contatore a zero, conti propri a 1000 con ABI `05428`. Destinatario esterno `IT60X0306909606100000012345`, importo `123.45`.
- `acceptedExternalTransferAnswers202SentAndIsCompletedByTheOutcome`: ACCEPT, POST -> **202** `SENT`, `reasons` vuoto; saldo 876.55; riga `external_transfer` SENT; 0 righe in `transfer` e in outbox; poi `processOutcomes()` = 1, COMPLETED, 1 riga outbox con `"status":"COMPLETED"` e `"destinationIban":"IT60X0306909606100000012345"`, saldo 876.55.
- `rejectedExternalTransferIsRefunded`: REJECT, POST (202 SENT), poi `processOutcomes()` -> REJECTED, `reason` = `REJECT_REASON`, saldo `isEqualByComparingTo("1000")`, 1 riga outbox REJECTED con il motivo.
- `unavailableClearingAnswers200RejectedAndRefundsAtOnce`: UNAVAILABLE -> **200** `REJECTED`, `reasons` `["Clearing house unavailable"]`, saldo 1000, 1 riga outbox REJECTED.
- `timeoutThenAcceptAnswers202UnknownThenCompletes`: **202** `UNKNOWN`, saldo 876.55, 0 righe outbox; poi COMPLETED, 1 riga outbox. `timeoutThenRejectAnswers202UnknownThenRefunds`: 202 `UNKNOWN`; poi REJECTED, saldo 1000, 1 riga outbox REJECTED.
- `fraudRejectionOfAnExternalTransferAnswers200AndMovesNoMoney`: antifrode REJECTED `["A","B"]` -> 200 `REJECTED` `["A","B"]`; saldo 1000; `external_transfer` REJECTED con reason `"A; B"`; 1 riga outbox REJECTED; `processOutcomes()` = 0.
- `insufficientFundsOnAnExternalTransferAnswers422AndSavesNothing`: importo 2000 -> 422; saldo 1000; 0 righe in `external_transfer` per il mittente; `processOutcomes()` = 0.
- `missingInternalDestinationAnswers404AsBefore`: destinatario `IT60X0542811101000000N00001` mai creato (come t29) -> **404** "Destination Account not found"; 0 chiamate all'antifrode; 0 righe in `transfer`, `external_transfer`, outbox per il mittente.
- `internalTransferStillAnswers200Completed`: destinatario `05428` esistente -> **200** `COMPLETED`; 1 riga in `transfer`; 0 in `external_transfer`.
- `destinationOfTheWrongLengthAnswers400`: 26 caratteri -> 400, nessuna riga, 0 chiamate all'antifrode.
- `logsNeverContainIbansNorAmounts`: i casi esterni in sequenza; l'output non contiene l'IBAN sorgente, il destinatario esterno ne `"123.45"`.

## Criteri di accettazione
- `.\mvnw.cmd test` da `transfer-service/` verde: i **526 test esistenti invariati** piu i nuovi. Unica eccezione: `ExternalTransferSagaIntegrationTest.wiringHasOneBeanPerPortAndNoScheduler` modificato come nell'ambiguita 6. Solo `TransferApplicationTests` richiede Postgres.
- In ogni contesto del bootstrap un solo bean per `CreateTransferInPort` (router), `CreateInternalTransferInPort` (`CreateTransferServiceImpl`), `CreateExternalTransferInPort` (saga) e `BankCode`.
- `CreateTransferServiceImpl` cambia solo la riga `implements` e l'import. `CreateTransferServiceImplTest`, `TransferControllerTest` (esistenti) e `TransferServiceDtoImplTest` invariati.
- Con ABI `05428` il comportamento di `POST /transfers` e identico: 200, 400, 404, 422, 503, stessi corpi e messaggi; t29 verde. Il 202 compare solo per SENT e UNKNOWN.
- `TransferServiceDto`, `CreateTransferInPort`, `FraudCheckOutPort` e `WebExceptionHandler` invariati.
- Nessuna dipendenza nuova, nessuna modifica ai pom, nessuna migration.
- `transfer-service/CLAUDE.md` aggiornato solo dopo l'autorizzazione esplicita.

## Rischi e fuori perimetro
**Rischi**
- Passo atomico: il cambio di `implements` senza il router (o senza il bean `BankCode`) fa fallire tutti i contesti ("No qualifying bean of type CreateTransferInPort" / "BankCode"). Vanno nello **stesso passo** (S1).
- `CreateInternalTransferInPort` che estende `CreateTransferInPort`: due bean dello stesso tipo, "expected single matching bean" in 4 test e in `TransferServiceDtoImpl`. Coperto da `internalPortDoesNotExtendTheEntryPort` e dal test di wiring.
- Variabile d'ambiente `TRANSFER_BANK_CODE` impostata sulla macchina: cambierebbe l'instradamento di tutti i test del bootstrap. Basso; da citare in CLAUDE.md.
- Conto interno con ABI diverso: trattato come esterno, con i soldi che escono verso la stanza (limite documentato).
- Doppia validazione degli IBAN (router piu service): nessun effetto funzionale.
- Concorrenza: `CreateTransferConcurrencyTest` passa ora dal router; il router non apre transazioni ne prende lock, quindi la semantica dei lock e invariata.
- Simulatore singleton condiviso: reset in `@BeforeEach` e H2 dedicato.
- Restano dalle fasi precedenti: UNKNOWN senza esito tardivo; esito consumato e poi fallito; crash tra SENT e `submit`; scheduler spento nella demo.

**Ordine dei passi (ogni passo lascia la build verde):** S1 atomico: test del router, `CreateInternalTransferInPort`, cambio di `implements`, `TransferRoutingServiceImpl`, `TransferRoutingConfig`, `transfer.bank-code`, `TransferRoutingConfigTest`, `RoutingDefaultPropertiesTest`, modifica del test di wiring;
S2: nuovi test di `TransferController`, poi il 202; S3: `ExternalTransferEndToEndTest`; S4 (da autorizzare): `transfer-service/CLAUDE.md`.

**Fuori perimetro:** GET dei bonifici, interrogazione attiva della stanza, scadenze per UNKNOWN, RETURNED e RESERVED, IBAN non a 27 caratteri, nuovi documenti in `docs/` (salvo ambiguita 7), README (salvo approvazione), notification-service, fraud-service, contratti, `docker-compose.yaml`, commit.

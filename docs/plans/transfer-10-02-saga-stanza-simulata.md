# transfer-10-02 (rev. 2): Saga e stanza simulata, con UNKNOWN e slf4j

Stato: **in attesa di approvazione** (rev. 2). Fase 2 del piano v3.1 `transfer-10-saga-minima.md`. La Fase 1 (`transfer-10-01-dominio-persistenza.md`) e completata (449 test verdi, reviewer APPROVED) ed e nel working tree, non committata.
Recepisce la risposta dell'utente alla rev. 1: "implementa lo stato unknown, usa slf4j, 3 ok".

## Cosa cambia rispetto alla rev. 1
- **UNKNOWN entra nel perimetro.** Si compensa solo se l'invio e *certamente* non ricevuto (`ClearingHouseUnavailableException`). Per un timeout o qualunque altro errore di `submit` il bonifico passa a UNKNOWN, senza compensazione: i fondi restano addebitati. Un esito tardivo e applicato da `processOutcomes()`. Sostituisce la decisione 2 della rev. 1 (compensare su qualunque `RuntimeException`).
- **Log con slf4j** al posto di `System.Logger`. Richiede una modifica di pom (decisione A1, da approvare).
- **Decisione 3 confermata:** dopo la compensazione immediata il risultato e REJECTED "Clearing house unavailable".
- Restano le raccomandazioni della rev. 1 su modo del simulatore esatto e test di integrazione in Fase 2.
- **Prima del lavoro di Fase 2 c'e un passo E0 che emenda la Fase 1** (working tree, non committata).

## Fatti verificati dal planner
- `transfer-application/pom.xml` contiene solo `transfer-domain`, `mapstruct`, `spring-context`, `spring-tx` e, in test, `spring-boot-starter-test`. **Nessun `slf4j-api`.** Nessuna classe main del progetto usa slf4j: fraud-adapter, kafka-adapter e bootstrap usano commons-logging.
- Il pom padre eredita da `spring-boot-starter-parent` 4.1.1, il cui BOM gestisce la versione di `org.slf4j:slf4j-api`: la dipendenza si aggiunge **senza `<version>`**.
- Il bootstrap ha gia slf4j in modo transitivo (`spring-boot-starter-actuator` -> `spring-boot-starter` -> `spring-boot-starter-logging` -> Logback e slf4j): le nuove classi del bootstrap possono usare slf4j senza toccare il suo pom.
- Catturare i log nei test dell'application: precedente in `transfer-fraud-adapter` (stesse dipendenze di test), `HttpFraudCheckOutPortImplTest` usa `OutputCaptureExtension`. Quindi **nessuna dipendenza di test nuova**; confermare Logback nel classpath di test con `.\mvnw.cmd -pl transfer-application dependency:tree` all'inizio di P2.
- I test di Fase 1 non toccano UNKNOWN: `TransferTest` verifica solo SENT, PENDING, COMPLETED, REJECTED; nessun `@EnumSource` o `values()`. Estendere `settle()` e `rejectAfterSent()` a UNKNOWN non rompe nulla (compreso `invalidTransitionMessageNamesTheCurrentStatus`: "Transfer is already SENT").
- La guardia di `TransferNotificationDto` (`status != COMPLETED && status != REJECTED`) rifiuta gia UNKNOWN.
- `external_transfer.status` e `VARCHAR(20)` e l'entita ha `length = 20`: `UNKNOWN` ci sta, **nessuna nuova migration**.
- Fatti della rev. 1 ancora validi: `Transfer` usa `requireStatus` (`IllegalStateException("Transfer is already X")`); `ClearingOutcomeDto` (REJECTED vuole un motivo, ACCEPTED vuole null); `ClearingInstructionDto` ha solo `requireNonNull` e il suo `toString()` di record stampa IBAN e importo;
  `PendingNotificationDto` ridefinisce `toString()` con il test `toStringNeverShowsThePayloadNorTheIban`; Mockito, Awaitility, `ApplicationContextRunner`, `OutputCaptureExtension` gia disponibili nei test; 4 test del bootstrap piu `TransferServiceDtoImpl` iniettano `CreateTransferInPort`;
  `Transfer` e `Account` hanno un `toString` con IBAN e importi: mai loggati ne usati come messaggi.

## Obiettivo
Implementare la Saga dei bonifici esterni con quattro esiti dell'invio: accettato, rifiutato, non ricevuto (compensazione immediata), incerto (UNKNOWN, senza compensazione, chiuso da un esito tardivo). Aggiungere la stanza simulata e uno scheduler spento di default.
Restano invariati il comportamento REST e l'unico bean `CreateTransferInPort`.

## Ambiguita (nessuna blocca; A1 va approvata esplicitamente)
1. **A1, modifica di pom (CLAUDE.md la vieta senza chiedere). R:** aggiungere a `transfer-application/pom.xml` solo `org.slf4j:slf4j-api`, scope compile, versione dal BOM di Boot. E la richiesta dell'utente ("usa slf4j"). Solo API, nessun binding nell'application (nel runtime il binding e Logback del bootstrap). Nessuna dipendenza di test nuova.
2. **A2, slf4j anche nelle nuove classi del bootstrap. R: si** (`ClearingConfig`, `ClearingOutcomesScheduler`), senza modifiche al pom del bootstrap. Le classi esistenti restano con commons-logging, senza refactoring: nel modulo convivono due API di log. Alternativa: commons-logging nel bootstrap, slf4j solo nel service.
3. **Transizioni verso UNKNOWN. R:** `markUnknown()` da SENT a UNKNOWN; `settle()` e `rejectAfterSent()` si **estendono** a "da SENT **o** UNKNOWN" (regola didattica unica: un esito della stanza si applica finche il bonifico non e concluso). Alternativa: metodi separati (piu API, nessun valore didattico).
   Messaggio sempre "Transfer is already X" (helper `requireStatusIn(allowed...)`).
4. **Distinguere "non ricevuto" da "esito incerto". R:** nuova `ClearingHouseTimeoutException(transferId)`, `RuntimeException` sorella e **non sottoclasse** di `ClearingHouseUnavailableException`. `ClearingHouseUnavailableException`: certamente non ricevuto -> compensazione immediata.
   `ClearingHouseTimeoutException` **o qualunque altra `RuntimeException`**: esito incerto -> UNKNOWN, nessuna compensazione. Principio: si compensa solo quando si e certi che l'effetto esterno non e avvenuto. Il javadoc di `ClearingHouseOutPort.submit` viene aggiornato.
5. **Risposta di `executeExternalTransfer` con esito incerto. R:** `TransferResultDto(id, <stato letto nella transazione di markUnknown>, List.of())`, normalmente `"UNKNOWN"`. Se un esito e gia stato applicato in concorrenza (scheduler acceso) lo stato e COMPLETED o REJECTED e i motivi restano vuoti (limite accettato).
   **Fase 3:** `TransferController` risponde **202 per SENT e per UNKNOWN**, 200 negli altri casi.
6. **Riconciliazione. R: solo passiva.** `processOutcomes()` applica gli esiti tardivi ai bonifici SENT o UNKNOWN con le stesse regole (lock del bonifico poi del conto, idempotenza, outbox). Fuori perimetro e dichiarati nei limiti didattici: interrogazione attiva della stanza, job di riconciliazione, timeout o scadenze automatiche per UNKNOWN.
7. **Simulatore. R: cinque modi.** `ACCEPT`, `REJECT`, `UNAVAILABLE` come prima; `TIMEOUT_THEN_ACCEPT` e `TIMEOUT_THEN_REJECT`: l'istruzione *e stata ricevuta*, il simulatore mette in coda l'esito e poi lancia `ClearingHouseTimeoutException`. Si vede l'arco intero: UNKNOWN, esito tardivo, COMPLETED o REJECTED con rimborso.
   Alternativa: sesto modo `TIMEOUT_LOST` (UNKNOWN per sempre). R: no, si documenta.
8. **Notifiche. R: nessuna per UNKNOWN.** Guardia di `TransferNotificationDto` invariata (rifiuta gia UNKNOWN; si aggiunge un test). Contratto `transfer-notification.md` invariato.
9. **Restano dalla rev. 1:** risultato REJECTED dopo la compensazione immediata; conto mittente sparito durante il rimborso -> `IllegalStateException` senza IBAN, rollback; nessun troncamento del motivo; modo del simulatore esatto con avvio fallito su valore sconosciuto; due WARN all'avvio; test di integrazione in Fase 2; `toString()` di `ClearingInstructionDto` con il solo id.

## Modifiche per modulo

### Passo E0: emendamento della Fase 1 (prima di tutto il resto)
**transfer-domain** (`com.bank.transfer.domain`)
| File | Tipo | Responsabilita |
|---|---|---|
| `TransferStatusEnum.java` | modificato | aggiunge `UNKNOWN` |
| `Transfer.java` | modificato | `markUnknown()` da SENT a UNKNOWN. `settle()` e `rejectAfterSent()` accettano SENT **o** UNKNOWN. Helper privato `requireStatusIn(TransferStatusEnum... allowed)` che lancia `IllegalStateException("Transfer is already " + status)`; `requireStatus` lo riusa. `complete()`, `reject()` e `markSent()` invariati (solo da PENDING) |

**transfer-application** (`com.bank.transfer.application`)
| File | Tipo | Responsabilita |
|---|---|---|
| `exception/ClearingHouseTimeoutException.java` | nuovo | `RuntimeException`, non sottoclasse di `ClearingHouseUnavailableException`; campo `transferId` e accessor; messaggio "Clearing house did not confirm the instruction for transfer " + id; nessuna causa |
| `port/out/ClearingHouseOutPort.java` | modificato (solo javadoc) | `submit` lancia `ClearingHouseUnavailableException` se l'istruzione **non e stata ricevuta**, oppure `ClearingHouseTimeoutException` se **l'esito e incerto** (l'istruzione puo essere stata ricevuta: l'esito arrivera, se arriva, da `pollOutcomes`) |

**transfer-postgres-adapter:** nessuna modifica al codice ne alle migration.

### Fase 2
**transfer-application** (`com.bank.transfer.application`)
| File | Tipo | Responsabilita |
|---|---|---|
| `pom.xml` | **modificato (A1, da approvare)** | `<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>` senza versione |
| `dto/ClearingInstructionDto.java` | modificato (P0) | `toString()` = `"ClearingInstructionDto[transferId=...]"`, javadoc "IBANs and amount are never printed" |
| `port/in/CreateExternalTransferInPort.java` | nuovo | `TransferResultDto executeExternalTransfer(CreateTransferCommand)` |
| `port/in/ProcessClearingOutcomesInPort.java` | nuovo | `int processOutcomes()` |
| `service/ExternalTransferSagaServiceImpl.java` | nuovo | `@Service`; implementa **solo** le due porte in sopra |

Dettagli di `ExternalTransferSagaServiceImpl`:
- `private static final Logger LOG = LoggerFactory.getLogger(...)` (slf4j). Costruttore `(AccountRepositoryOutPort, ExternalTransferRepositoryOutPort, FraudCheckOutPort, NotificationOutPort, ClearingHouseOutPort, TransactionTemplate)`. Costanti `CLEARING_UNAVAILABLE_REASON = "Clearing house unavailable"`, `SOURCE_NOT_FOUND = "Source Account not found"`.
- `executeExternalTransfer`, passi 1-5: (1) `new Iban(source)`, `new Iban(dest)`, `new Money(...)`, `Transfer.create` (le `IllegalArgumentException` si propagano); (2) `findByIban(source)` vuoto: `AccountNotFoundException(SOURCE_NOT_FOUND)`, il destinatario non si cerca mai; (3) antifrode fuori transazione, eccezioni propagate;
  (4) se non `ALLOWED`: una transazione con `reject()`, `save(rejected, String.join("; ", reasons))`, `publish(REJECTED, reasons)`, risultato REJECTED con i motivi; (5) se `ALLOWED`: una transazione con `findByIbanForUpdate(source)` (`orElseThrow` `SOURCE_NOT_FOUND`), `save(account.charge(money))`, `markSent()`, `save(sent, null)`, commit, INFO "External transfer <id> SENT".
- Passo 6, fuori transazione: `submit(new ClearingInstructionDto(...))`.
  - `catch ClearingHouseUnavailableException`: WARN, poi `applyOutcome(REJECTED, "Clearing house unavailable")`, risultato REJECTED con quel motivo.
  - `catch RuntimeException` (timeout o altro): WARN "outcome unknown for transfer <id>: <SimpleName>", poi `markUnknownIfSent(id)`, risultato `TransferResultDto(id, stato, List.of())`.
  - Nessuna eccezione: risultato SENT.
- `markUnknownIfSent(id)` (privato): una transazione con `findByIdForUpdate`. Se SENT: `save(markUnknown(), null)`, restituisce `"UNKNOWN"`; nessun accredito, nessuna notifica. Altrimenti INFO "already concluded" e restituisce lo stato corrente.
- `processOutcomes()`: `pollOutcomes()` (un'eccezione si propaga); per ogni esito `try applyOutcome` / `catch RuntimeException` (WARN con id e nome semplice, si continua); restituisce il conteggio degli esiti applicati.
- `applyOutcome` (privato, boolean), una transazione per esito: `findByIdForUpdate(id)` (vuoto: WARN "unknown", false); stato diverso da **SENT o UNKNOWN**: INFO "duplicate outcome ignored", false; `ACCEPTED`: `settle()`, `save(completed, null)`, `publish(COMPLETED, List.of())`;
  `REJECTED`: `findByIbanForUpdate(transfer.source())` (lock dopo quello del bonifico), `save(account.credit(money))`, `rejectAfterSent()`, `save(rejected, reason)`, `publish(REJECTED, List.of(reason))`; INFO con id e stato finale; true.
- Helper privato `toNotification` uguale a quello di `CreateTransferServiceImpl` (duplicato voluto).
- Regole di log: parametri `{}` di slf4j; solo `transferId`, stato e `getClass().getSimpleName()`; mai un `Throwable`, un `Transfer`, un `Account` o un DTO di istruzione come argomento.

**transfer-bootstrap** (`com.bank.transfer`)
| File | Tipo | Responsabilita |
|---|---|---|
| `clearing/port/out/clearing/impl/SimulatedClearingModeEnum.java` | nuovo | `ACCEPT`, `REJECT`, `UNAVAILABLE`, `TIMEOUT_THEN_ACCEPT`, `TIMEOUT_THEN_REJECT` |
| `clearing/port/out/clearing/impl/SimulatedClearingHouseOutPortImpl.java` | nuovo | Java puro, senza annotazioni ne log; implementa `ClearingHouseOutPort`; costruttore `(SimulatedClearingModeEnum initialMode)` (NPE se null); `volatile mode`; `setMode` (NPE se null) e `mode()`; `ConcurrentLinkedQueue<ClearingOutcomeDto>`; costante pubblica `REJECT_REASON = "Rejected by the clearing house"` |
| `config/ClearingConfig.java` | nuovo | `@Configuration` package-private; bean `SimulatedClearingHouseOutPortImpl simulatedClearingHouseOutPort(@Value("${clearing.simulator.mode}") SimulatedClearingModeEnum mode, @Value("${clearing.outcomes.enabled:false}") String outcomesEnabled)` (tipo di ritorno concreto); due WARN (slf4j) |
| `config/ClearingOutcomesScheduler.java` | nuovo | package-private; costruttore `(ProcessClearingOutcomesInPort)` (NPE se null); `@Scheduled(fixedDelayString = "${clearing.outcomes.fixed-delay}", initialDelayString = "${clearing.outcomes.initial-delay}") void processOutcomes()`; non lancia mai: INFO "Clearing outcomes applied: n" se n > 0; WARN "Clearing outcomes round failed: " + nome semplice dell'eccezione (slf4j) |
| `config/ClearingOutcomesSchedulingConfig.java` | nuovo | `@Configuration @EnableScheduling @ConditionalOnProperty(name = "clearing.outcomes.enabled", havingValue = "true")`; bean `ClearingOutcomesScheduler` |
| `resources/application.properties` | modificato | blocco `#CLEARING (simulated)`: `clearing.simulator.mode=${CLEARING_SIMULATOR_MODE:ACCEPT}`, `clearing.outcomes.enabled=${CLEARING_OUTCOMES_ENABLED:false}`, `clearing.outcomes.fixed-delay=2s`, `clearing.outcomes.initial-delay=5s` |

`SimulatedClearingHouseOutPortImpl.submit`: NPE se l'istruzione e null; `ACCEPT` mette in coda `(id, ACCEPTED, null)`; `REJECT` mette in coda `(id, REJECTED, REJECT_REASON)`; `UNAVAILABLE` lancia `ClearingHouseUnavailableException(id)` senza mettere nulla in coda;
`TIMEOUT_THEN_ACCEPT` / `TIMEOUT_THEN_REJECT` mettono in coda l'esito (ACCEPTED senza motivo / REJECTED con `REJECT_REASON`) **e poi** lanciano `ClearingHouseTimeoutException(id)`. `pollOutcomes`: svuota la coda in una lista immutabile. L'esito non arriva mai da `submit`, solo dal poll successivo.

Lo scheduler non tocca i test esistenti: con il default `false` il bean non esiste e `@EnableScheduling` non si attiva; nessun test esistente imposta `clearing.outcomes.enabled`; saga, simulatore e config sono bean nuovi che nessun test esistente chiama.

**Nessuna modifica:** transfer-postgres-adapter, transfer-rest-adapter, pom diversi da `transfer-application`, `CreateTransferServiceImpl`, `transfer-service/CLAUDE.md` (Fase 3).

**Ordine dei passi (ogni passo lascia la build verde):** E0 emendamento Fase 1; P0 `toString()`; P1 simulatore + `ClearingConfig` + proprieta (il bean `ClearingHouseOutPort` esiste prima di chi lo usa); P2 pom (A1), porte in, saga; P3 scheduler; P4 test di integrazione.

## Test attesi (prima del codice, stesso package della classe)

### E0: aggiunte ai test di Fase 1 (nessun test di Fase 1 viene modificato o rimosso)
**`TransferTest`** (aggiunte): `markUnknownMovesASentTransferToUnknownKeepingIdAndMoney` (l'originale resta SENT); `settleCompletesAnUnknownTransfer`; `rejectAfterSentRejectsAnUnknownTransfer`; `onlyASentTransferCanBecomeUnknown` (PENDING, UNKNOWN, COMPLETED, REJECTED: `IllegalStateException`);
`unknownTransferCannotBeSentNorCompletedNorRejectedAsPending` (`markSent()`/`complete()`/`reject()`); `invalidMarkUnknownMessageNamesTheCurrentStatus` (COMPLETED -> "Transfer is already COMPLETED").
**`TransferNotificationDtoTest`** (aggiunta): `unknownStatusIsRefused`.
**`ClearingHouseTimeoutExceptionTest`** (nuovo, `...application.exception`): `messageHasTheTransferIdButNoCause`; `isNeitherAnUnavailableNorAnIllegalArgumentException`.
**`JpaExternalTransferRepositoryOutPortImplTest`** (aggiunta): `savesAndReadsAnUnknownTransfer` (SENT poi `markUnknown()`, save, flush/clear, find: UNKNOWN, una riga).

### Fase 2
**`ClearingInstructionDtoTest`** (aggiunte, P0): `toStringShowsOnlyTheTransferId`; `toStringNeverShowsTheIbansNorTheAmount` (IBAN sorgente e destinatario, `100.25`).

**`ExternalTransferSagaServiceImplTest`** (nuovo, `...application.service`; Mockito su tutte le porte out + `@Mock PlatformTransactionManager` in `new TransactionTemplate(...)`; `@ExtendWith(OutputCaptureExtension.class)` per i log; sorgente `IT60X0542811101000000000001`, destinatario `IT60X0306909606100000012345`):
- `doesNotImplementTheCreateTransferInPort` (riflessione: `isAssignableFrom` false).
- `missingSourceAccountIsRefusedWithoutAskingTheFraudCheck`: `AccountNotFoundException("Source Account not found")`; nessuna interazione con antifrode, repository esterno, stanza, transaction manager.
- `destinationIsNeverLookedUpAmongTheAccounts`; `sameIbanOrThreeDecimalsAreRefusedBeforeTouchingAnything`; `unavailableFraudCheckIsPropagatedAndNothingIsSaved`.
- `fraudRejectionSavesRejectedWithTheJoinedReasonsAndPublishesIt`: REJECTED `["A","B"]` -> `save(REJECTED, "A; B")`, `publish` REJECTED `[A, B]`, conto mai bloccato ne salvato, nessun `submit`, risultato REJECTED `[A, B]`, commit.
- `insufficientFundsRollsBackAndNeverSubmits`; `differentCurrencyRollsBackAndNeverSubmits`.
- `happyPathDebitsSavesSentCommitsAndOnlyThenSubmits`: in ordine antifrode, `findByIbanForUpdate(source)`, save del conto a 70, `save(SENT, null)`, commit, `submit` con lo stesso id; risultato SENT con `reasons` vuoto; nessun `publish`. `submittedInstructionCarriesTheTransferData`.
- `unavailableClearingIsCompensatedAtOnce`: `submit` lancia `ClearingHouseUnavailableException` -> seconda transazione: `findByIdForUpdate` SENT, conto riaccreditato a 100, `save(REJECTED, "Clearing house unavailable")`, `publish` REJECTED; risultato REJECTED con quel motivo; 2 commit.
- `timeoutLeavesTheTransferUnknownWithoutCompensating`: `submit` lancia `ClearingHouseTimeoutException`; `find` restituisce SENT -> `save(UNKNOWN, null)`; **mai** accredito ne `findByIbanForUpdate` dopo l'addebito; nessun `publish`; risultato UNKNOWN con `reasons` vuoto; 2 commit.
- `unexpectedSubmitFailureIsTreatedAsUnknown` (`IllegalStateException`: come sopra); `outcomeAppliedBeforeMarkUnknownIsKept` (timeout, `find` restituisce COMPLETED: nessun save, risultato COMPLETED).
- `acceptedOutcomeCompletesTheTransferAndPublishesCompleted`; `rejectedOutcomeRefundsTheSourceWithLocksInOrder` (lock bonifico, lock conto, save del conto a 100, `save(REJECTED, "R")`, `publish` REJECTED `[R]`; 1).
- `lateAcceptedOutcomeCompletesAnUnknownTransfer` (UNKNOWN + ACCEPTED: `save(COMPLETED, null)`, un `publish` COMPLETED, conto non toccato; 1); `lateRejectedOutcomeRefundsAnUnknownTransfer` (UNKNOWN + REJECTED "R": lock bonifico, lock conto, accredito, `save(REJECTED, "R")`, `publish` REJECTED `[R]`; 1).
- `outcomeForAnAlreadyConcludedTransferIsIgnored` (COMPLETED o REJECTED: nessun effetto; 0); `sameRejectedOutcomeTwiceRefundsOnce`; `outcomeForAnUnknownTransferIsIgnored` (transferId sconosciuto); `aFailingOutcomeIsRolledBackAndTheNextOneIsStillApplied`; `noOutcomesMeansNoTransaction`; `failingPollIsPropagated`.
- `unknownTransferIsNeverNotified` (percorso del timeout: `publish` mai chiamato).
- `logsNeverContainIbansNorAmounts`: tutti i percorsi (SENT, compensazione, timeout, esito tardivo, ACCEPTED, REJECTED, duplicato, sconosciuto, errore) con `OutputCaptureExtension`; contengono il transferId (e "UNKNOWN" nel caso del timeout), mai i due IBAN ne l'importo.

**`SimulatedClearingHouseOutPortImplTest`** (nuovo, bootstrap, `com.bank.transfer.clearing.port.out.clearing.impl`): `pollWithoutSubmissionsIsEmpty`; `acceptedOutcomeArrivesOnlyAtTheNextPollAndOnce`; `rejectModeProducesARejectedOutcomeWithTheFixedReason`; `unavailableModeThrowsAndQueuesNothing`;
`timeoutThenAcceptThrowsTimeoutButQueuesTheAcceptedOutcome`; `timeoutThenRejectThrowsTimeoutButQueuesTheRejectedOutcome`; `outcomesKeepTheSubmissionOrder`; `changingTheModeAppliesToTheNextSubmission`; `nullsAreRefused`; `polledListIsImmutable`.

**`ClearingConfigTest`** (nuovo, `com.bank.transfer.config`, `ApplicationContextRunner` con `PropertyPlaceholderAutoConfiguration`, conversion service di Boot, `OutputCaptureExtension`): `theSimulatorIsTheOnlyClearingHousePortWithTheConfiguredMode`; `startupWarnsThatTheClearingHouseIsSimulated`; `startupWarnsWhenOutcomesProcessingIsDisabled`;
`noDisabledWarnWhenEnabled`; `unknownOrMissingModeFailsTheStartup` (`MAYBE`, assente); `timeoutModesAreAccepted` (`TIMEOUT_THEN_ACCEPT`, `TIMEOUT_THEN_REJECT`).

**`ClearingOutcomesSchedulingConfigTest`** (nuovo, porta in finta a mano con contatore): `noSchedulerWithoutTheProperty`; `anyValueOtherThanTrueLeavesItOff` (`""`, `no`, `false`); `enabledSchedulerCallsThePortRepeatedly` (50ms, Awaitility almeno 2 chiamate entro 5 s);
`aFailingRoundDoesNotEscapeAndOnlyTheClassNameIsLogged` (`IllegalStateException("SENSITIVE")`: output con "IllegalStateException", senza "SENSITIVE"; il giro successivo funziona); `missingOrUnreadableDelayFailsTheStartupWhenEnabled`.

**`ClearingDefaultPropertiesTest`** (nuovo, `com.bank.transfer`, stile `FraudDefaultPropertiesTest`): `clearingIsSimulatedAcceptingAndOutcomesProcessingIsOffByDefault` (`ACCEPT`, `false`, `2s`, `5s`).

**`ExternalTransferSagaIntegrationTest`** (nuovo, `com.bank.transfer`, `@SpringBootTest` su H2 `jdbc:h2:mem:sagaintegration`, antifrode `@Primary` sempre ALLOWED come in `CreateTransferConcurrencyTest`, scheduler spento; si iniettano le due porte in, il simulatore, `AccountRepositoryOutPort`, `JdbcTemplate`; il modo torna ACCEPT in `@BeforeEach`; conti propri ABI `05428`):
- `wiringHasOneBeanPerPortAndNoScheduler`: `CreateTransferInPort` 1 (`CreateTransferServiceImpl`); le due porte in nuove puntano alla stessa saga; `ClearingHouseOutPort` e il simulatore; nessun `ClearingOutcomesScheduler`.
- `acceptedExternalTransferIsDebitedThenCompleted` (saldo 1000, 123.45: SENT, saldo 876.55, riga SENT, 0 righe outbox; `processOutcomes()` = 1, COMPLETED, 1 riga outbox `"status":"COMPLETED"`, saldo 876.55).
- `rejectedExternalTransferIsRefunded` (REJECTED, `reason` = `REJECT_REASON`, saldo `isEqualByComparingTo("1000")`, 1 riga outbox REJECTED); `secondRoundDoesNotRefundTwice`; `unavailableClearingIsCompensatedImmediately` (REJECTED "Clearing house unavailable", saldo 1000, 1 riga outbox); `noTransferRowIsWrittenForExternalTransfers`.
- `timeoutThenAcceptGoesUnknownAndIsCompletedByTheLateOutcome` (`TIMEOUT_THEN_ACCEPT`, saldo 1000, 123.45: UNKNOWN, saldo 876.55, riga `external_transfer` UNKNOWN, **0 righe outbox**; `processOutcomes()` = 1, COMPLETED, 1 riga outbox COMPLETED, saldo 876.55).
- `timeoutThenRejectGoesUnknownAndIsRefundedByTheLateOutcome` (prima UNKNOWN e saldo 876.55; poi REJECTED con `REJECT_REASON`, saldo `isEqualByComparingTo("1000")`, 1 riga outbox REJECTED).

## Criteri di accettazione
- `.\mvnw.cmd test` da `transfer-service/` verde: i **449 test esistenti invariati** piu i nuovi. Eccezione: solo le **aggiunte** di E0 ai test di Fase 1, elencate sopra (nessuna modifica o rimozione). Solo `TransferApplicationTests` richiede Postgres.
- Ogni passo (E0, P0, P1, P2, P3, P4) lascia la build verde.
- L'unica modifica di pom e `slf4j-api` senza versione in `transfer-application/pom.xml`, solo dopo l'approvazione di A1. Nessuna dipendenza di test nuova.
- Un solo bean `CreateTransferInPort` (ancora `CreateTransferServiceImpl`); la saga non lo implementa.
- Si compensa **solo** su `ClearingHouseUnavailableException`. Timeout o altri errori portano a UNKNOWN, con fondi addebitati e nessuna notifica. Gli esiti tardivi si applicano a SENT e UNKNOWN in modo idempotente.
- L'outbox riceve solo COMPLETED e REJECTED. Nessuna nuova migration.
- Nessun IBAN ne importo nei log ne nei `toString()` dei DTO della stanza.

## Rischi e fuori perimetro
**Rischi**
- UNKNOWN senza esito tardivo (stanza che non risponde mai): fondi addebitati a tempo indeterminato. Serve una riconciliazione attiva, fuori perimetro e dichiarata nei limiti didattici.
- Ordine dei `catch`: `ClearingHouseTimeoutException` non deve mai estendere `ClearingHouseUnavailableException`, altrimenti si compenserebbe un invio forse ricevuto (doppio movimento). Coperto da `isNeitherAnUnavailableNorAnIllegalArgumentException`.
- Corsa tra il timeout e lo scheduler: l'esito puo essere applicato prima di `markUnknownIfSent`; gestita con lock e controllo dello stato; la risposta riporta lo stato reale (con `reasons` vuoto se REJECTED).
- Due API di log nel bootstrap (commons-logging esistente e slf4j nuovo): convivono, stile non uniforme.
- `OutputCaptureExtension` nell'application dipende da Logback nel classpath di test (via `spring-boot-starter-test`, come nel fraud-adapter): verificare con `dependency:tree` all'inizio di P2.
- Dalla rev. 1: ordine dei passi rispetto ai bean; secondo `CreateTransferInPort`; esito consumato e poi fallito; crash tra SENT e `submit`; simulatore singleton e mutabile (reset in `@BeforeEach`); scheduler spento nella demo.

**Fuori perimetro:** riconciliazione attiva (interrogare la stanza), job di riconciliazione, scadenze o timeout automatici per UNKNOWN, stati RETURNED e RESERVED, modo `TIMEOUT_LOST`, router, `CreateInternalTransferInPort`, `BankCode` come bean, 202 e end-to-end HTTP (Fase 3), notification-service, fraud-service, contratti, commit.

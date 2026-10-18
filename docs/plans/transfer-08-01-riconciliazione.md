# transfer-08-01: Riconciliazione attiva (piano di dettaglio della Fase 1)

Stato: **in attesa di approvazione**. Fase 1 del piano `transfer-08-seed-demo.md` (rev. 2, approvato). Si lavora su `main`; il commit lo fa l'utente. 559 test verdi del transfer-service di partenza.

La riconciliazione si fa senza nuova migration e senza cambiare costruttori ne test esistenti. Due condizioni: `created_at` va mappata con `insertable = true` e `updatable = false`, valorizzata in UTC dal costruttore dell'entita; la nuova porta di `ClearingHouseOutPort` va introdotta nello stesso passo del simulatore, altrimenti la build non compila.

## Fatti verificati nel codice
**Saga**
- `ExternalTransferSagaServiceImpl` ha un costruttore a 6 argomenti, invariato. `applyOutcome` e privato: in una transazione fa `findByIdForUpdate`, poi agisce solo se lo stato e SENT o UNKNOWN; ACCEPTED -> `settle` e outbox COMPLETED; REJECTED -> lock del conto, `credit`, `rejectAfterSent`, outbox REJECTED.
- `markUnknownIfSent` restituisce lo stato corrente se il bonifico e gia concluso. Log slf4j con id, stato e nome semplice dell'eccezione.

**Porte e implementazioni**
- `ClearingHouseOutPort` (`submit`, `pollOutcomes`): unica implementazione il simulatore; nei test solo come `@Mock`. `ExternalTransferRepositoryOutPort` (`save`, `findByIdForUpdate`): unica implementazione quella JPA; nei test solo come `@Mock`. Aggiungere metodi astratti rompe la compilazione solo delle implementazioni, che vanno aggiornate nello stesso passo.

**`ExternalTransferEntity`**
- Il costruttore pubblico a 7 argomenti e usato **solo** da `JpaExternalTransferRepositoryOutPortImpl.toEntity`; nessun test lo costruisce (`JpaExternalTransferRepositoryOutPortImplTest` lo nomina solo in una JPQL). `created_at` non e mappata.

**Comportamento di `save`**
- `JpaExternalTransferRepositoryOutPortImpl.save` chiama `repository.save(new entity)`. Con `@Id` assegnato e senza `@Version`, Spring Data considera l'entita non nuova e fa `em.merge`: `SELECT` della riga esistente, copia dello stato nell'entita gestita, `UPDATE` solo delle colonne aggiornabili oppure `INSERT`. **Una colonna `updatable = false` non entra mai nell'`UPDATE`.**

**V3 e test di schema**
- `created_at TIMESTAMP NOT NULL DEFAULT now()`. `ExternalTransferMigrationTest` usa `ddl-auto=validate` e inserisce righe via JDBC affidandosi al default. `LocalDateTime` corrisponde a `TIMESTAMP` per Hibernate: `validate` passa. **Il default `now()` su H2 usa il fuso della JVM (Windows, CET)**: per questo il valore lo imposta l'applicazione in UTC.

**Altri**
- `org.springframework.data.domain.Limit` e gia usato in `SpringDataOutboxRepository.findAllByOrderByIdAsc(Limit)`: disponibile.
- Simulatore: `ConcurrentLinkedQueue`, nessuna memoria dopo il poll; i suoi test (`SimulatedClearingHouseOutPortImplTest`, `ClearingConfigTest`) lo costruiscono con il solo modo.
- `ClearingOutcomesSchedulingConfig` ha `@ConditionalOnProperty(clearing.outcomes.enabled=true)` e crea `ClearingOutcomesScheduler(ProcessClearingOutcomesInPort)`; `ClearingOutcomesSchedulingConfigTest` usa un `FakeProcessPort` e carica solo la sua config: non va toccato.
- `ClockConfig` produce `Clock.systemUTC()`. `application.properties` ha gia `clearing.*`; `ClearingDefaultPropertiesTest` verifica solo le chiavi esistenti.

## Ambiguita residue (il piano segue le raccomandazioni R)
1. **Mappatura di `created_at`. R:** `@Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt`, insertable di default. Il valore lo imposta il costruttore pubblico (firma invariata) con `LocalDateTime.now(ZoneOffset.UTC)`; il costruttore protetto vuoto non lo imposta (lo carica Hibernate). `insertable = false` lascerebbe agire il `DEFAULT now()`, con il fuso sbagliato su H2: scartato.
   Nota: dopo un merge l'entita gestita ha in memoria il `createdAt` "nuovo" (non salvato) fino al clear; la query della soglia e JPQL, eseguita sul database dopo l'auto-flush, quindi usa il valore salvato. Nessuno legge `createdAt` dall'entita gestita.
2. **Tipo della soglia. R:** `Instant` nelle porte (in e out); la conversione `LocalDateTime.ofInstant(threshold, ZoneOffset.UTC)` sta nell'adapter.
3. **Forma della query. R: query derivata con `Limit`** (come per l'outbox): `List<ExternalTransferEntity> findByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(Collection<TransferStatusEnum> statuses, LocalDateTime threshold, Limit limit)`; l'adapter restituisce gli id. Alternativa: `@Query` con proiezione sull'id (piu efficiente ma meno sicura sul supporto di `Limit` con query scritte a mano).
4. **Validazione dei parametri.** Porta out: `requireNonNull(threshold)`, `IllegalArgumentException` se `max < 1`. Saga: stessi controlli. Scheduler: `staleAfter` positivo (`> 0`) e `batchSize >= 1`, altrimenti l'avvio fallisce. Nei test d'integrazione si chiama la porta in direttamente con una soglia futura.
5. **Motivo del rimborso per "non trovato". R:** costante `INSTRUCTION_NOT_FOUND_REASON = "Instruction not found at the clearing house"` nella saga.
6. **Test nuovi in classi nuove. R: si**, per non toccare i file di test esistenti.

## Modifiche per file (in ordine di passo; ogni passo lascia la build verde)

**Passo 1: porta out del repository e adapter postgres**
| File | Tipo | Responsabilita |
|---|---|---|
| application `port/out/ExternalTransferRepositoryOutPort` | modificato | aggiunge `List<String> findInProgressIdsCreatedBefore(Instant threshold, int max)`; javadoc: SENT o UNKNOWN con `created_at` precedente alla soglia, i piu vecchi prima, al massimo `max` |
| postgres `repository/model/ExternalTransferEntity` | modificato | campo `createdAt` (ambiguita 1) valorizzato nel costruttore pubblico (firma invariata); getter `getCreatedAt()` |
| postgres `repository/SpringDataExternalTransferRepository` | modificato | aggiunge la query derivata dell'ambiguita 3; `findByIdForUpdate` invariato |
| postgres `port/out/postgres/impl/jpa/JpaExternalTransferRepositoryOutPortImpl` | modificato | implementa il nuovo metodo (validazione, conversione UTC, `Limit.of(max)`, `EnumSet.of(SENT, UNKNOWN)`, mappa sugli id); costruttore, `save` e `findByIdForUpdate` invariati |

**Passo 2: porta della stanza e simulatore** (insieme: la porta da sola non compila)
| File | Tipo | Responsabilita |
|---|---|---|
| application `port/out/ClearingHouseOutPort` | modificato | aggiunge `Optional<ClearingOutcomeDto> queryStatus(String transferId)`; javadoc: restituisce l'esito deciso per un'istruzione ricevuta; **vuoto significa che la stanza conferma di non averla mai ricevuta** (risposta autorevole), quindi compensare e sicuro |
| bootstrap `clearing/port/out/clearing/impl/SimulatedClearingHouseOutPortImpl` | modificato | `ConcurrentHashMap<String, ClearingOutcomeDto> received`; in `submit` si registra l'esito per `ACCEPT`, `REJECT`, `TIMEOUT_THEN_ACCEPT`, `TIMEOUT_THEN_REJECT` (prima di lanciare il timeout); `UNAVAILABLE` non registra. `queryStatus`: NPE se null, poi `Optional.ofNullable(received.get(id))`. Coda, `pollOutcomes`, `setMode` e costruttore invariati. In memoria: un riavvio lo svuota (voluto) |

**Passo 3: porta in e saga**
| File | Tipo | Responsabilita |
|---|---|---|
| application `port/in/ReconcileExternalTransfersInPort` | nuovo | `int reconcileStale(Instant createdBefore, int maxTransfers)` |
| application `service/ExternalTransferSagaServiceImpl` | modificato | `implements ..., ReconcileExternalTransfersInPort`; costruttore e metodi esistenti invariati |

`reconcileStale`: (1) NPE se la soglia e null, `IllegalArgumentException` se `max < 1`; (2) `ids = findInProgressIdsCreatedBefore(createdBefore, max)` fuori transazione; (3) per ogni id, `try`: `queryStatus(id)` **fuori transazione**; esito presente: si applica; vuoto: INFO "Transfer {} not found at the clearing house" e `new ClearingOutcomeDto(id, REJECTED, INSTRUCTION_NOT_FOUND_REASON)`;
`applyOutcome(outcome)`, che **ricontrolla sotto lock** (prima il bonifico, poi il conto) che lo stato sia ancora SENT o UNKNOWN; si contano solo gli esiti applicati; (4) `catch RuntimeException`: WARN con id e nome semplice dell'eccezione, poi si continua; (5) restituisce il conteggio. Log solo con id e stato.

**Passo 4: scheduler della riconciliazione**
| File | Tipo | Responsabilita |
|---|---|---|
| bootstrap `config/ClearingReconciliationScheduler` | nuovo | package-private. Costruttore `(ReconcileExternalTransfersInPort, Clock, Duration staleAfter, int batchSize)` con NPE e `IllegalArgumentException`. `@Scheduled(fixedDelayString = "${clearing.reconcile.fixed-delay}", initialDelayString = "${clearing.reconcile.initial-delay}") void reconcile()`: soglia = `clock.instant().minus(staleAfter)`, poi la porta. Non lancia mai; INFO "Clearing reconciliation applied: n" se n > 0; WARN col solo nome semplice dell'eccezione |
| bootstrap `config/ClearingReconciliationSchedulingConfig` | nuovo | `@Configuration @EnableScheduling @ConditionalOnProperty(name = "clearing.outcomes.enabled", havingValue = "true")`; bean con `@Value` di `clearing.reconcile.stale-after` e `clearing.reconcile.batch-size` |
| bootstrap `resources/application.properties` | modificato | `clearing.reconcile.stale-after=5m`, `clearing.reconcile.fixed-delay=30s`, `clearing.reconcile.initial-delay=10s`, `clearing.reconcile.batch-size=50` |

**Passo 5:** test d'integrazione (sotto).

## Punti di verifica
- (a) `created_at`: `validate` passa; l'insert usa il valore UTC dell'entita; i merge successivi non lo cambiano grazie a `updatable = false`; le righe inserite via JDBC (`ExternalTransferMigrationTest`) usano ancora il default.
- (b) Soglia: `Instant` nelle porte, conversione in `LocalDateTime` UTC nell'adapter. (c) `Limit`: disponibile.
- (d) Simulatore: `queryStatus` ricorda l'esito per `ACCEPT`, `REJECT`, `TIMEOUT_*` anche dopo il poll; vuoto per `UNAVAILABLE` e per un id mai visto; thread-safe; i test esistenti invariati.
- (e) Idempotenza: `queryStatus` fuori transazione, `applyOutcome` ricontrolla sotto lock; un bonifico concluso nel frattempo da "nessun effetto"; ordine dei lock invariato.
- (f) I due scheduler insieme: entrambi passano da `applyOutcome` (lock pessimistico e controllo dello stato): chi arriva secondo trova COMPLETED o REJECTED e non fa nulla; nel simulatore l'esito in coda e quello in memoria coincidono.
- (g) Test esistenti che toccano le classi modificate, tutti **invariati**: `ExternalTransferSagaServiceImplTest`, `ExternalTransferSagaIntegrationTest`, `ExternalTransferEndToEndTest`, `JpaExternalTransferRepositoryOutPortImplTest`, `ExternalTransferMigrationTest`, `SimulatedClearingHouseOutPortImplTest`, `ClearingConfigTest`, `ClearingOutcomesSchedulingConfigTest`, `ClearingDefaultPropertiesTest`.

**Eccezione approvata dall'utente (opzione A):** in `ExternalTransferSagaServiceImplTest` il metodo `implementsOnlyTheTwoSagaInPorts` e stato modificato in `implementsOnlyTheThreeSagaInPorts` (la saga implementa anche `ReconcileExternalTransfersInPort`; resta la guardia che non implementa `CreateTransferInPort` ne `CreateInternalTransferInPort`). Tutti gli altri test esistenti restano invariati.

## Test attesi (prima del codice, stesso package, solo classi nuove)

**Passo 1: `JpaExternalTransferStaleQueryTest`** (`com.bank.transfer.postgres.port.out.postgres.impl.jpa`, `@DataJpaTest`, `@Import` di `JpaExternalTransferRepositoryOutPortImpl` e `JpaAccountRepositoryOutPortImpl`, `EntityManager`, `JdbcTemplate`; le eta si impostano con `UPDATE external_transfer SET created_at = ?` via JDBC):
`returnsOnlySentAndUnknownCreatedBeforeTheThreshold` (4 righe SENT, UNKNOWN, COMPLETED, REJECTED create un'ora fa, soglia = ora: solo SENT e UNKNOWN); `excludesRowsCreatedAfterTheThreshold`; `oldestFirstAndLimitedToMax` (3 SENT con eta 3h, 2h, 1h, max 2: le due piu vecchie in ordine);
`createdAtIsSetInUtcOnInsert` (entro pochi secondi da `LocalDateTime.now(ZoneOffset.UTC)`); `createdAtNeverChangesOnLaterSaves` (valore fisso impostato, poi save UNKNOWN e COMPLETED: invariato); `rejectsInvalidArguments` (soglia null: NPE; `max` 0: `IllegalArgumentException`).

**Passo 2: `SimulatedClearingHouseQueryStatusTest`** (`com.bank.transfer.clearing.port.out.clearing.impl`): `acceptedOutcomeIsRememberedAfterThePoll`; `rejectedOutcomeIsRememberedWithTheReason` (`REJECT_REASON`); `timeoutModesRememberTheQueuedOutcome`; `unavailableIsNotFound`; `neverSubmittedIsNotFound`;
`pollStillReturnsEachOutcomeOnce` (la query non consuma la coda); `nullIdIsRefused`.

**Passo 3: `ExternalTransferSagaReconciliationTest`** (`com.bank.transfer.application.service`, Mockito con lo stesso setup di `ExternalTransferSagaServiceImplTest`, `OutputCaptureExtension`):
`staleSentWithAnAcceptedStatusIsCompleted` (`save(COMPLETED, null)`, un outbox COMPLETED, nessun accredito; 1); `staleUnknownWithARejectedStatusIsRefunded` (in ordine: lock del bonifico, lock del conto, accredito, `save(REJECTED, "R")`, outbox REJECTED);
`instructionNotFoundIsRefundedWithTheReason` (`queryStatus` vuoto: accredito, `save(REJECTED, "Instruction not found at the clearing house")`, outbox REJECTED); `transferConcludedMeanwhileIsIgnored` (`queryStatus` ACCEPTED, `find` restituisce REJECTED: nessun save e nessun outbox; 0);
`queryStatusIsCalledOutsideTheTransaction` (`queryStatus` prima di `getTransaction`); `aFailingQuerySkipsOnlyThatTransfer` (due id, il primo lancia: il secondo applicato; 1); `thresholdAndMaxArePassedToTheRepository` (soglia T, max 7); `nothingStaleMeansNoQueryAndNoTransaction` (0, nessuna interazione);
`invalidArgumentsAreRefused` (NPE; `IllegalArgumentException`; nessuna interazione); `logsNeverContainIbansNorAmounts` (id presente; mai IBAN ne importo).

**Passo 4: `ClearingReconciliationSchedulingConfigTest`** (`com.bank.transfer.config`, `ApplicationContextRunner` con `PropertyPlaceholderAutoConfiguration` e conversion service di Boot, come `ClearingConfigTest`; fake della porta e `Clock` fisso nella config del test): `noSchedulerWithoutTheOutcomesSwitch`;
`enabledWritesTheSchedulerWithTheSettings` (`stale-after=5m`, `batch-size=7`, ritardi 1h: un giro manuale, la porta riceve soglia = clock fisso - 5m e max 7); `schedulerRunsRepeatedlyWhenEnabled` (`fixed-delay=50ms`, `initial-delay=0`, Awaitility almeno 2 chiamate entro 5 s);
`aFailingRoundDoesNotEscape` (`IllegalStateException("SENSITIVE")`: nell'output "IllegalStateException" e non "SENSITIVE"); `invalidSettingsFailTheStartup` (`batch-size` 0 o `abc`; `stale-after` `0s` o `abc`; proprieta mancanti).
**`ClearingReconcileDefaultPropertiesTest`** (`com.bank.transfer`): valori `5m`, `30s`, `10s`, `50`.

**Passo 5: `ExternalTransferReconciliationIntegrationTest`** (`com.bank.transfer`, `@SpringBootTest` su `jdbc:h2:mem:reconciliation`, antifrode `@Primary` sempre ALLOWED, scheduler spento; si iniettano `CreateExternalTransferInPort`, `ReconcileExternalTransfersInPort`, `ProcessClearingOutcomesInPort`, il simulatore, `AccountRepositoryOutPort`, `ExternalTransferRepositoryOutPort`, `JdbcTemplate`; modo e coda reimpostati in `@BeforeEach`; **verifiche per id**):
`unknownIsClosedByQueryingTheClearingHouse` (`TIMEOUT_THEN_ACCEPT`, execute: UNKNOWN, saldo 876.55; `reconcileStale(now + 1s, 100)`: COMPLETED, 1 riga outbox, saldo 876.55; poi `processOutcomes()`: quell'esito e ignorato, sempre 1 riga outbox); `unknownWithALateRejectionIsRefunded` (`TIMEOUT_THEN_REJECT`: REJECTED `REJECT_REASON`, saldo 1000, 1 riga outbox);
`sentNeverReceivedIsRefunded` (conto addebitato e riga SENT salvata con la porta, mai inviata: REJECTED "Instruction not found at the clearing house", saldo ripristinato, 1 riga outbox REJECTED); `freshTransfersAreLeftAlone` (`reconcileStale(now - 1h, 100)`: stato invariato); `secondRoundDoesNotRefundTwice`.

## Criteri di accettazione
- `.\mvnw.cmd test` da `transfer-service/` verde: **i 559 test esistenti invariati** piu i nuovi. Nessun test esistente modificato.
- Nessuna migration, dipendenza o nuovo modulo. Costruttori invariati di `ExternalTransferSagaServiceImpl`, `ExternalTransferEntity`, `JpaExternalTransferRepositoryOutPortImpl` e `SimulatedClearingHouseOutPortImpl`.
- `created_at` impostato in UTC all'insert e mai cambiato dai salvataggi successivi; `validate` passa su H2 (e su Postgres con `TransferApplicationTests`).
- La riconciliazione chiude solo i SENT e UNKNOWN piu vecchi della soglia, applica gli esiti trovati con le regole di `applyOutcome`, rimborsa solo su "non trovato", ed e idempotente.
- L'outbox riceve solo COMPLETED e REJECTED. Scheduler spento di default, acceso da `clearing.outcomes.enabled=true`. Nessun IBAN ne importo nei log.

## Rischi
- **Race tra il commit di SENT e il `submit`:** una riconciliazione che interroga prima del `submit` riceve "non trovato" e rimborsa; poi il `submit` arriva e la stanza accetta: doppio movimento (`processOutcomes` ignorerebbe l'esito, il denaro sarebbe gia uscito). Mitigato da `stale-after = 5m`, che non va mai impostato vicino a zero in esecuzione (lo scheduler rifiuta `0s`).
- **Fuso orario:** le righe nuove hanno `created_at` in UTC dall'applicazione; le righe vecchie del Postgres locale hanno il `now()` del server (Docker in UTC: coerente); con un server in un altro fuso la soglia slitta di ore, ma solo per le righe vecchie. Su H2 nessun problema.
- **Riavvio del simulatore uguale a "non trovato":** dopo un riavvio tutti i SENT e UNKNOWN vecchi vengono rimborsati; voluto e didattico (in una stanza reale "non trovato" deve essere una risposta autorevole).
- **Memoria del simulatore che cresce:** la mappa non si svuota durante l'esecuzione; trascurabile in una demo.
- **Ordine dei passi:** `queryStatus` senza l'implementazione del simulatore non compila: vanno nello stesso passo (passo 2).

## Fuori perimetro
Stato PENDING o ritardi della stanza; persistenza del simulatore; V4 o `updated_at`; scadenza senza interrogare la stanza; endpoint per la riconciliazione manuale; seed dei dati (Fase 2); `transfer-service/CLAUDE.md` (Fase 3); commit (a cura dell'utente).

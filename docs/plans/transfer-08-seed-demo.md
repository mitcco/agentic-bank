# transfer-08 (rev. 2): Seed demo coerente e riconciliazione attiva della Saga

Stato: **in attesa di approvazione** (rev. 2). Si lavora su `main` (PR #6 della saga gia unita, working tree pulito) e il commit lo fa l'utente. Piano prodotto dal planner, 559 test verdi del transfer-service di partenza.

## Cosa cambia rispetto alla rev. 1
- **Branch:** si lavora su `main`, il commit lo fa l'utente. D4 non c'e piu.
- **Un solo seeder:** `DemoDataSeeder` viene esteso e unificato (autorizzato dall'utente); `DemoDataSeederTest` cambia (dettaglio sotto).
- **Riconciliazione attiva minima (Fase 1)** per chiudere SENT e UNKNOWN anche dopo un riavvio, senza nuova migration.
- **Dati coerenti:** saldi calcolati dalla storia dei bonifici, compresi `ac1..ac3` e `tr1..tr3` (che cambiano valori).
- **Proprieta `transfer.demo-data.enabled`:** approvata, con il comportamento descritto sotto.

## Fatti verificati nel codice
- `ExternalTransferEntity` mappa 7 colonne; **`created_at` (V3, `DEFAULT now()`) non e mappata**. Il costruttore pubblico ha 7 argomenti e i test lo usano.
- `SimulatedClearingHouseOutPortImpl` tiene gli esiti solo in una `ConcurrentLinkedQueue`, consumata da `pollOutcomes()`: dopo il poll o un riavvio non ricorda nulla. `UNAVAILABLE` non mette niente in coda.
- Saga: `applyOutcome` e privato, applica solo a SENT o UNKNOWN, prende il lock del bonifico e poi del conto, scrive l'outbox solo per COMPLETED e REJECTED. **`ExternalTransferSagaServiceImplTest` costruisce la saga con 6 argomenti**: il costruttore non deve cambiare.
- `ClearingHouseOutPort` ed `ExternalTransferRepositoryOutPort` compaiono nei test solo come `@Mock` o come implementazioni reali: aggiungere metodi non rompe nulla. **`ProcessClearingOutcomesInPort` ha un fake scritto a mano** in `ClearingOutcomesSchedulingConfigTest`: non va toccata (per questo serve uno scheduler nuovo).
- `ClockConfig` produce un `Clock` UTC. `JpaOutboxNotificationOutPortImpl` usa un Clock e i suoi test importano un `FixedClockConfig`. `JpaExternalTransferRepositoryOutPortImplTest` non ha un Clock: **iniettare un Clock nell'implementazione JPA romperebbe quel test**.
- H2 calcola `now()` con il fuso della JVM (su Windows CET), Postgres con quello del server: una soglia basata su `created_at` impostata dal database sarebbe sfasata di ore su H2.
- Chi legge i dati di `DemoDataSeeder`: solo `DemoDataSeederTest`. Gli altri usi di `...0001`/`...0002` sono unit test con valori propri o payload di stringhe; i saldi 1000.00, 250.50, 0.00 compaiono solo li (le occorrenze in `AccountControllerTest` e `AccountServiceDtoImplTest` sono mock locali).
- I dati attuali sono incoerenti: con `tr2` (accredito di 50.25) e saldo finale 0, `ac3` avrebbe dovuto partire da -50.25; `tr3` e REJECTED con 5000.00, sotto la soglia antifrode di 10000.
- Invariato dalla rev. 1: IBAN dei test con lettera piu 5 cifre (nessuna collisione con un seed numerico); nessun test conta righe a livello di tabella intera tranne il publisher sull'outbox (il seed non scrive nell'outbox); `transfer` non ha la colonna `reason`; `Iban` non verifica il check digit.

## Obiettivo
1. Riconciliare in modo attivo i bonifici esterni fermi in SENT o UNKNOWN interrogando la stanza simulata, che ora ricorda gli esiti.
2. Popolare il database con circa 50 conti e circa 50 bonifici deterministici, idempotenti e contabilmente coerenti, in un solo seeder.

## Proprieta `transfer.demo-data.enabled` (formulazione da usare nel codice e in CLAUDE.md)
- Il seeder e un bean creato **solo se** `transfer.demo-data.enabled` vale `true` (`@ConditionalOnProperty(name = "transfer.demo-data.enabled", havingValue = "true", matchIfMissing = true)`).
- **Default `true`:** in `application.properties` c'e `transfer.demo-data.enabled=${TRANSFER_DEMO_DATA_ENABLED:true}`; anche se la proprieta manca il seeder e attivo.
- Per spegnerlo: variabile d'ambiente `TRANSFER_DEMO_DATA_ENABLED=false`; `-Dtransfer.demo-data.enabled=false` sulla JVM; `--transfer.demo-data.enabled=false` come argomento; in IntelliJ, nella Run Configuration, "Environment variables" o "VM options".
- Spegnerlo non cancella nulla: il seeder non gira e i dati gia presenti restano. Acceso, non modifica mai righe esistenti: inserisce solo i conti (per IBAN) e i bonifici (per id) che mancano.

## Ambiguita residue (il piano segue le raccomandazioni R)
1. **Come risolvere SENT e UNKNOWN fermi. R: alternativa A, riconciliazione attiva minima.**
   - A: la stanza risponde a `queryStatus(transferId)` e un giro periodico interroga la stanza per i SENT e UNKNOWN piu vecchi di una soglia. Esito trovato: si applica con le regole di `applyOutcome`. **Istruzione non trovata:** la stanza conferma di non averla, quindi la compensazione e sicura: rimborso, REJECTED "Instruction not found at the clearing house", outbox REJECTED.
     Dopo un riavvio il simulatore non ricorda nulla: i SENT e UNKNOWN vecchi (anche quelli seminati e quelli del Postgres locale) risultano non trovati e vengono rimborsati: e il caso didattico di una stanza che conferma di non aver mai ricevuto l'istruzione.
   - B, scadenza senza interrogare la stanza: **sconsigliata** (se la stanza ha regolato l'istruzione, il rimborso fa uscire il denaro due volte: e l'errore che UNKNOWN evita).
   - C, non seminare SENT e UNKNOWN: non risolve il problema, nasconde il limite.
2. **Dove sta la riconciliazione. R: un metodo nuovo della saga**, `ReconcileExternalTransfersInPort.reconcileStale(Instant createdBefore, int maxTransfers)`, implementato da `ExternalTransferSagaServiceImpl`: riusa `applyOutcome` (privato) e i collaboratori; **costruttore invariato** (`ExternalTransferSagaServiceImplTest` invariato). La soglia (`clock - stale-after`) la calcola lo scheduler del bootstrap con il `Clock` esistente: il service resta senza Clock e senza `@Value`. Alternativa: service separato con Clock (duplica `applyOutcome` o richiede una porta in "applica esito").
3. **Ora di riferimento e V4. R: niente V4.** Si usa `created_at` (V3), mappato come `LocalDateTime createdAt` con `@Column(name = "created_at", nullable = false, updatable = false)`, impostato dal **costruttore pubblico dell'entita** con `LocalDateTime.now(ZoneOffset.UTC)` (firma invariata, nessun Clock nell'implementazione JPA, test esistenti invariati). `updatable = false` impedisce che un salvataggio successivo lo cambi. Soglia in UTC su H2 come su Postgres. Alternativa: V4 con `updated_at` (serve solo per misurare dall'ultimo cambio di stato).
4. **Scheduler della riconciliazione. R: scheduler e config nuovi** (`ClearingReconciliationScheduler`, `ClearingReconciliationSchedulingConfig`), attivi con **la stessa proprieta** `clearing.outcomes.enabled=true` (un solo interruttore, spento di default). Nuove proprieta: `clearing.reconcile.stale-after=5m`, `clearing.reconcile.fixed-delay=30s`, `clearing.reconcile.initial-delay=10s`, `clearing.reconcile.batch-size=50`. Alternativa: interruttore separato `clearing.reconcile.enabled`.
5. **Fase o issue separata. R: Fase 1 di questo piano** (piano di dettaglio a parte, `transfer-08-01-riconciliazione.md`), **prima** del seed: i SENT e UNKNOWN seminati hanno senso solo se si risolvono da soli (con lo scheduler acceso, entro `stale-after` diventano REJECTED con rimborso, e i saldi restano coerenti: bonus dimostrativo). Alternativa: issue distinta `transfer-11`.
6. **Seeder unico. R: estendere `DemoDataSeeder`** (autorizzato): carica `ac1..ac3`, `tr1..tr3` e il catalogo, tutti con controllo riga per riga. Nessuna classe seeder nuova; i dati stanno nel catalogo puro `DemoDataCatalog`.
7. **Coerenza di `ac1..ac3` e `tr1..tr3`. R: ricalcolarli con la stessa regola.** Saldi: `ac1` 1900.00, `ac2` 2049.75, `ac3` 2050.25; `tr3` passa da 5000.00 a **15000.00** (REJECTED coerente con la soglia antifrode di 10000). Impatto: cambia solo `DemoDataSeederTest`. **Le righe gia presenti non si toccano mai:** sul Postgres locale `...0001` resta a 20619.65 e `tr3` resta a 5000; la coerenza vale per un database vuoto e per i conti e bonifici nuovi.
8. **SENT e UNKNOWN seminati. R: si, 1 SENT e 1 UNKNOWN.** Con la Fase 1 si chiudono da soli.

## Regola di coerenza dei saldi (deterministica, calcolata dal catalogo)
**Saldo seminato = 2000.00 EUR - addebiti + accrediti**, contando solo i bonifici che muovono denaro:
| Bonifico | Movimento |
|---|---|
| interno COMPLETED | addebito alla sorgente, accredito al destinatario |
| interno REJECTED (importo > 10000, antifrode) | nessuno |
| esterno COMPLETED | addebito alla sorgente |
| esterno SENT o UNKNOWN | addebito alla sorgente (fondi trattenuti) |
| esterno REJECTED dalla stanza o per "Clearing house unavailable" | netto zero (addebito e rimborso) |
| esterno REJECTED dall'antifrode (importo > 10000) | nessuno |

- Nessun saldo negativo, per costruzione: ogni conto e sorgente di al piu 1 bonifico interno che muove denaro (importo <= 900.00) e di al piu 1 esterno che addebita (importo <= 900.00): totale addebiti per conto <= 1800.00 < 2000.00, in qualunque ordine e anche senza contare gli accrediti.
- Rifiuti: gli interni REJECTED (6) e l'esterno rifiutato dall'antifrode hanno importi tra 10500.00 e 15500.00 (oltre la soglia) e non influenzano i saldi.
- Dopo la riconciliazione i SENT e UNKNOWN seminati diventano REJECTED con rimborso e la regola resta vera.

## Catalogo (deterministico)
- **50 conti:** `demo-acc-101..150`, IBAN da `IT60X0542811101000000000101` a `...000000000150` (ABI `05428`).
- **40 interni:** `demo-tr-001..040`, sorgente conto i, destinatario conto (i+7) mod 50; 34 COMPLETED con importi da 10.00 a 900.00, 6 REJECTED con importi da 10500.00 a 15500.00.
- **10 esterni:** `demo-ext-001..010`, sorgenti tra i conti 141 e 150 (distinte), destinatari con ABI `03069`, `02008`, `05034`, `01030`: 4 COMPLETED; 2 REJECTED "Rejected by the clearing house"; 1 REJECTED "Clearing house unavailable"; 1 REJECTED "Amount must not exceed 10000 EUR" (12000.00); 1 SENT e 1 UNKNOWN (importi <= 900.00).
- **Dati storici in testa al catalogo:** `ac1..ac3` e `tr1..tr3` con i valori del punto 7.

## Modifiche per modulo

### Fase 1: riconciliazione
| Modulo / file | Tipo | Responsabilita |
|---|---|---|
| application `port/in/ReconcileExternalTransfersInPort` | nuovo | `int reconcileStale(Instant createdBefore, int maxTransfers)` |
| application `port/out/ClearingHouseOutPort` | modificato | aggiunge `Optional<ClearingOutcomeDto> queryStatus(String transferId)`. Javadoc: vuoto significa "istruzione mai ricevuta", risposta autorevole, quindi compensare e sicuro |
| application `port/out/ExternalTransferRepositoryOutPort` | modificato | aggiunge `List<String> findInProgressIdsCreatedBefore(Instant threshold, int max)` (SENT o UNKNOWN, i piu vecchi prima) |
| application `service/ExternalTransferSagaServiceImpl` | modificato | implementa anche `ReconcileExternalTransfersInPort`; costruttore e metodi esistenti invariati |
| postgres `repository/model/ExternalTransferEntity` | modificato | campo `createdAt` come nell'ambiguita 3, getter |
| postgres `repository/SpringDataExternalTransferRepository` | modificato | `@Query("select t.id from ExternalTransferEntity t where t.status in :statuses and t.createdAt < :threshold order by t.createdAt") List<String> findIds(..., Limit limit)` |
| postgres `port/out/postgres/impl/jpa/JpaExternalTransferRepositoryOutPortImpl` | modificato | implementa il nuovo metodo; costruttore invariato |
| bootstrap `clearing/.../SimulatedClearingHouseOutPortImpl` | modificato | `ConcurrentHashMap<String, ClearingOutcomeDto> received`, popolata da `submit` per `ACCEPT`, `REJECT` e i due `TIMEOUT_*` (non per `UNAVAILABLE`); `queryStatus` restituisce `Optional.ofNullable`. Coda e `pollOutcomes` invariati. In memoria: un riavvio la svuota (voluto) |
| bootstrap `config/ClearingReconciliationScheduler` | nuovo | costruttore `(ReconcileExternalTransfersInPort, Clock, Duration staleAfter, int batchSize)` con validazione. `@Scheduled` sulle proprieta `clearing.reconcile.*`; non lancia mai; slf4j col solo conteggio o nome semplice dell'eccezione |
| bootstrap `config/ClearingReconciliationSchedulingConfig` | nuovo | `@Configuration @EnableScheduling @ConditionalOnProperty(name = "clearing.outcomes.enabled", havingValue = "true")` |
| bootstrap `application.properties` | modificato | le 4 proprieta `clearing.reconcile.*` |

`reconcileStale(createdBefore, max)`: NPE se la soglia e null; `IllegalArgumentException` se `max < 1`. Per ogni id restituito da `findInProgressIdsCreatedBefore`, `try`: `queryStatus(id)` fuori transazione; esito presente: si applica; vuoto: `applyOutcome(new ClearingOutcomeDto(id, REJECTED, "Instruction not found at the clearing house"))`; si contano solo gli esiti applicati.
`catch RuntimeException`: WARN con id e nome semplice dell'eccezione, poi si continua. `applyOutcome` ricontrolla lo stato sotto lock, quindi un bonifico gia concluso da "nessun effetto".

### Fase 2: seed
| File | Tipo | Responsabilita |
|---|---|---|
| postgres `impl/DemoDataCatalog` | nuovo | Java puro: `accounts()` (53, saldi **calcolati** dai bonifici con la regola), `internalTransfers()` (43, con `tr1..tr3`), `externalTransfers()` (10, `record ExternalSeed(Transfer, String reason)`), costante `INITIAL_BALANCE = 2000.00` |
| postgres `impl/DemoDataSeeder` | modificato | `@ConditionalOnProperty` (vedi sopra). Costruttore `(AccountRepositoryOutPort, TransferRepositoryOutPort, ExternalTransferRepositoryOutPort, SpringDataTransferRepository, SpringDataExternalTransferRepository)`. `@Transactional run`: conti assenti per IBAN, poi bonifici assenti per id (`existsById`); mai `NotificationOutPort`; log slf4j col solo numero di righe inserite. Il controllo globale su `ACC1` sparisce |
| bootstrap `application.properties` | modificato | `transfer.demo-data.enabled=${TRANSFER_DEMO_DATA_ENABLED:true}` |

### Fase 3
`transfer-service/CLAUDE.md` (**da autorizzare**): sezione "Dati demo" (proprieta, regola dei saldi, IBAN `...0101`-`...0150` riservati, righe esistenti mai toccate); sezione Saga: riconciliazione, `queryStatus`, `stale-after`, rimborso per "non trovato", riavvio del simulatore; in "Limiti e rischi": tolto "UNKNOWN senza esito tardivo", aggiunte la race sulla soglia e le variabili d'ambiente.

## Test attesi (prima del codice, stesso package)

### Fase 1 (solo classi di test nuove; nessun test esistente cambia)
**`ExternalTransferSagaReconciliationTest`** (application; Mockito con lo stesso setup di `ExternalTransferSagaServiceImplTest`): `staleSentWithAnAcceptedStatusIsCompleted` (COMPLETED, un outbox COMPLETED, nessun accredito; 1); `staleUnknownWithARejectedStatusIsRefunded` (lock bonifico poi conto, accredito, REJECTED "R", outbox REJECTED);
`instructionNotFoundIsRefundedWithTheReason` (`queryStatus` vuoto: accredito, REJECTED "Instruction not found at the clearing house"); `transferConcludedMeanwhileIsIgnored` (0); `aFailingQuerySkipsOnlyThatTransfer` (il secondo applicato; 1); `thresholdAndMaxArePassedToTheRepository` (soglia T, max 7); `nothingStaleMeansNoQuery`; `invalidArgumentsAreRefused` (NPE; `IllegalArgumentException`); `logsNeverContainIbansNorAmounts`.

**`JpaExternalTransferStaleQueryTest`** (postgres, `@DataJpaTest`): `returnsOnlySentAndUnknownCreatedBeforeTheThreshold`; `excludesRowsCreatedAfterTheThreshold`; `oldestFirstAndLimited`; `createdAtIsSetInUtcAndNeverUpdated` (save SENT poi UNKNOWN: `created_at` via JDBC vicino a `now` UTC e invariato).

**`SimulatedClearingHouseQueryStatusTest`** (bootstrap): `remembersTheOutcomeEvenAfterThePoll`; `rejectAndTimeoutModesAreRemembered`; `unavailableOrNeverSubmittedIsNotFound`; `nullIdIsRefused`.

**`ClearingReconciliationSchedulingConfigTest`** (`ApplicationContextRunner`, `Clock` fisso, fake della porta): `noSchedulerUnlessOutcomesAreEnabled`; `callsThePortWithClockMinusStaleAfterAndTheBatchSize` (`stale-after=5m`, `batch-size=7`: soglia = clock - 5m, max 7); `aFailingRoundDoesNotEscape` (solo il nome della classe nell'output); `invalidSettingsFailTheStartup` (`batch-size=0`, durata illeggibile).

**`ClearingReconcileDefaultPropertiesTest`:** valori `5m`, `30s`, `10s`, `50`.

**`ExternalTransferReconciliationIntegrationTest`** (bootstrap, H2 dedicato; si verifica sempre **per id**, perche il seed del contesto contiene altri SENT e UNKNOWN): `unknownIsClosedByQueryingTheClearingHouse` (`TIMEOUT_THEN_ACCEPT`, execute: UNKNOWN; `reconcileStale(now + 1s, 100)`: quel bonifico e COMPLETED, 1 outbox; un `processOutcomes()` successivo lo ignora);
`sentNeverReceivedIsRefunded` (riga SENT salvata direttamente con l'addebito, mai inviata: REJECTED "Instruction not found at the clearing house", saldo ripristinato, 1 outbox REJECTED).

### Fase 2
**`DemoDataCatalogTest`** (nuovo, puro): conteggi, unicita, ABI, IBAN riservati, determinismo, mix; piu `balancesAreReplayedFromTheHistory` (ricostruzione indipendente nel test: 2000 - addebiti + accrediti, uguale a `accounts()` per ogni conto con `isEqualByComparingTo`); `noBalanceIsNegativeInAnyOrder` (addebiti per conto <= 1800.00);
`rejectedByFraudAreAboveTheThresholdAndMoveNoMoney` (> 10000); `legacyDataIsCoherent` (`ac1..ac3` = 1900.00, 2049.75, 2050.25; `tr3` = 15000.00 REJECTED).

**`DemoDataSeederTest`** (esistente, **modifica autorizzata**): l'`@Import` aggiunge `JpaExternalTransferRepositoryOutPortImpl.class`; **`loadsDemoAccountsAndTransfers`: cambiano i valori attesi** (`ac1` 1900.00, `ac2` 2049.75, `ac3` 2050.25; `tr3` 15000.00 REJECTED; `tr1` e `tr2` invariati);
**`doesNotOverwriteExistingDataWhenRunAgain`: invariato** (`ac1` salvato a 1.00, secondo `run`, resta 1.00). Nuovi metodi: `loadsTheWholeCatalog` (53 conti, 43 righe in `transfer`, 10 in `external_transfer`); `storedBalancesMatchTheCatalog`; `runningTwiceChangesNothing`;
`fillsOnlyTheMissingRowsOfAPartiallySeededDatabase` (si cancella un bonifico demo e si modifica un conto, poi `run`: il bonifico torna, il conto non viene sovrascritto); `writesNoOutboxRows`.

**`DemoDataSeedingDisabledTest`** (nuovo, `@DataJpaTest(properties = "transfer.demo-data.enabled=false")`): nessun bean `DemoDataSeeder`, tabelle vuote. **`DemoDataDefaultPropertiesTest`** (bootstrap): `transfer.demo-data.enabled` = `true`.

**`DemoDataReconciliationIntegrationTest`** (nuovo, bootstrap, H2): `seededSentAndUnknownAreRefundedByTheReconciliation` (contesto avviato con il seed; `reconcileStale(now + 1s, 100)`: `demo-ext` SENT e UNKNOWN diventano REJECTED "Instruction not found at the clearing house"; il saldo delle loro sorgenti torna a 2000.00; 2 righe outbox REJECTED).

## Criteri di accettazione
- `.\mvnw.cmd test` da `transfer-service/` verde: **i 559 test esistenti senza modifiche**, tranne **`DemoDataSeederTest`**: cambiano l'`@Import` e i valori attesi di `loadsDemoAccountsAndTransfers`, mentre `doesNotOverwriteExistingDataWhenRunAgain` resta invariato. Piu i nuovi test.
- Nessun nuovo modulo, nessuna dipendenza, **nessuna migration** (V1-V3 intatte).
- Costruttori invariati di `ExternalTransferSagaServiceImpl`, `JpaExternalTransferRepositoryOutPortImpl` e `ExternalTransferEntity`.
- Riconciliazione: chiude SENT e UNKNOWN solo dopo `stale-after`; rimborsa solo su "istruzione non trovata"; applica gli esiti trovati con le regole esistenti; idempotente; outbox solo COMPLETED e REJECTED; spenta di default.
- Seed su database vuoto: 53 conti e 53 bonifici (43 interni, 10 esterni), saldi uguali alla ricostruzione dalla storia, nessuna riga nell'outbox. Su database popolato aggiunge solo le righe mancanti.
- Verifica manuale sul Postgres locale: (1) avvio con `CLEARING_OUTCOMES_ENABLED=true`: i SENT e UNKNOWN delle prove manuali vengono rimborsati al primo giro; (2) compaiono i dati demo; (3) dopo circa 5 minuti si chiudono anche SENT e UNKNOWN seminati; (4) `...0001` resta 20619.65 (piu gli eventuali rimborsi dei suoi bonifici fermi).

## Rischi
- **Race tra il commit di SENT e il `submit`:** se la riconciliazione interroga prima del `submit`, risponde "non trovato" e rimborsa, poi il `submit` arriva e la stanza accetta: doppio movimento. Mitigata da `stale-after` (5 minuti, molto piu del tempo di `submit`). Non impostarla vicino a zero in esercizio; nei test va bene perche sono sequenziali.
- Riavvio del simulatore uguale a "non trovato": voluto; in una stanza reale "non trovato" deve essere una risposta autorevole e non un vuoto di memoria (limite dichiarato).
- Fuso orario del Postgres: le righe create prima della Fase 1 hanno `created_at` dal `now()` del server (Docker in UTC: coerente); con un server in un altro fuso la soglia slitta di ore, ma riguarda solo righe vecchie.
- `TRANSFER_DEMO_DATA_ENABLED=false` sulla macchina spegne il seed anche nei test (`DemoDataSeederTest` fallirebbe); da documentare come `TRANSFER_BANK_CODE`.
- Seed in ogni contesto del bootstrap: circa 100 righe e 2 bonifici esterni "appesi" per contesto; nessun test esistente ne risente (scheduler spento, nessun conteggio globale). I nuovi test devono verificare per id.
- Commit diretto su `main`: niente PR di revisione. Si consiglia un commit per fase, con test verdi prima di ciascuno.

## Fuori perimetro
Stato PENDING della stanza e ritardi simulati; persistenza del simulatore; nuove migration (V4 `updated_at`); scadenza senza interrogare la stanza (B); interfaccia o endpoint per la riconciliazione manuale; titolari e anagrafiche; notifiche per i dati demo; fraud-service, notification-service, contratti, docker-compose; commit (a cura dell'utente).

## Fasi
| Fase | Contenuto | Dipende da | Rischio |
|---|---|---|---|
| 1. Riconciliazione attiva | porte (`queryStatus`, `findInProgressIdsCreatedBefore`, `ReconcileExternalTransfersInPort`), `reconcileStale` nella saga, `createdAt` nell'entita, query, memoria del simulatore, scheduler e proprieta, test. Piano di dettaglio a parte | - | medio (soglia, race, idempotenza) |
| 2. Seed coerente | `DemoDataCatalog`, `DemoDataSeeder` unificato, proprieta, `DemoDataSeederTest` modificato e test nuovi, integrazione seed piu riconciliazione | 1 | basso-medio |
| 3. Documentazione e verifica | `transfer-service/CLAUDE.md` (da autorizzare); verifica manuale sul Postgres locale | 1, 2 | basso |

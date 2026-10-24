# transfer-14: Metriche di business dei bonifici e dell'outbox (piano)

Stato: **in attesa di approvazione, CONDIZIONATO a D1, D2, D3 e D4**. Si lavora sul branch `feature/business-metrics`, dopo fraud-07. Il commit lo fa l'utente. Nessuna migration. Notification-service non si tocca.

## Fatti verificati nel codice reale
- **Service** (tutti `@Service`, un solo costruttore, scansionati da `TransferApplication` in `com.bank.transfer`):
  - `CreateTransferServiceImpl(AccountRepositoryOutPort, TransferRepositoryOutPort, FraudCheckOutPort, NotificationOutPort, TransactionTemplate)`: flusso interno. Esito `COMPLETED` o `REJECTED` (solo antifrode), restituito da `transactionTemplate.execute(...)`. Conto mancante, antifrode non disponibile, fondi insufficienti e valute diverse finiscono in eccezione, con rollback.
  - `ExternalTransferSagaServiceImpl(AccountRepositoryOutPort, ExternalTransferRepositoryOutPort, FraudCheckOutPort, NotificationOutPort, ClearingHouseOutPort, TransactionTemplate)` implementa `CreateExternalTransferInPort`, `ProcessClearingOutcomesInPort` e `ReconcileExternalTransfersInPort`. Punti di uscita:
    - rifiuto antifrode: `REJECTED`, senza addebito;
    - `submit` riuscito: `SENT`;
    - `ClearingHouseUnavailableException`: `applyOutcome(REJECTED, "Clearing house unavailable")` con riaccredito;
    - altra `RuntimeException`: `markUnknownIfSent`, che restituisce `UNKNOWN` oppure lo stato già concluso;
    - `processOutcomes()` e `reconcileStale(Instant, int)` passano entrambi da `applyOutcome(ClearingOutcomeDto)` (privato, `boolean`). Quel metodo ignora i bonifici non trovati e quelli non più `SENT`/`UNKNOWN`; con `ACCEPTED` fa `settle`, altrimenti riaccredito più `rejectAfterSent`.
    - In `reconcileStale` una risposta vuota di `queryStatus` diventa `REJECTED "Instruction not found at the clearing house"`; le eccezioni per singolo id vengono loggate e saltate.
  - `TransferRoutingServiceImpl(BankCode, CreateInternalTransferInPort, CreateExternalTransferInPort)`: smista e basta, non si tocca.
  - `PublishPendingNotificationsServiceImpl(PendingNotificationOutPort, NotificationSenderOutPort)`. Per ogni riga: `send`, poi `sent++`, poi `delete`. Si ferma al primo errore (`PublishFailureStageEnum.SEND` / `DELETE`).
- **Outbox:** la riga si **cancella** dopo la conferma del broker (contratto `transfer-notification.md` §5). Non esiste una colonna "pubblicata": la tabella contiene solo le righe in attesa. L'outbox riceve solo `COMPLETED` e `REJECTED`.
  - `SpringDataOutboxRepository extends JpaRepository<OutboxEntity, Long>`: `count()` è ereditato, nessun metodo nuovo.
  - `JpaPendingNotificationOutPortImpl` (`@Repository`, `postgres.port.out.postgres.impl.jpa`) implementa `PendingNotificationOutPort`.
- **Costruttori nei test esistenti:**
  - `CreateTransferServiceImplTest:75`, `ExternalTransferSagaServiceImplTest:132`, `ExternalTransferSagaReconciliationTest:115`, `PublishPendingNotificationsServiceImplTest:43` (Mockito, `TransactionTemplate` su un `PlatformTransactionManager` simulato);
  - `TransferRoutingServiceImplTest`, non toccato.
- **Enum e DTO già esistenti:**
  - `TransferStatusEnum` (dominio): `COMPLETED, REJECTED, PENDING, SENT, UNKNOWN`; `PENDING` non viene mai salvato;
  - `TransferTypeEnum` (application, da transfer-13): `INTERNAL, EXTERNAL`;
  - `PublishFailureStageEnum`, `ClearingDecisionEnum`.
- **Bootstrap:**
  - gli scheduler stanno in `config/*Scheduler` + `*SchedulingConfig` (`@EnableScheduling`, `@ConditionalOnProperty`, mai eccezioni, log con il solo `getSimpleName()`);
  - `transfer-bootstrap/pom.xml` ha actuator, `micrometer-registry-prometheus` e, in test, `spring-boot-starter-actuator-test` e `spring-boot-starter-webmvc-test`;
  - `application.properties`: exposure `health,prometheus`, tag `application=${spring.application.name}` (cioè `transfer`).
  - Prometheus raccoglie ogni 15 secondi (`monitoring/prometheus.yml`). Grafana: datasource uid `prometheus`, dashboard caricate da `/var/lib/grafana/dashboards` (`monitoring/grafana/dashboards/`).
- **Problema già presente (fuori perimetro, solo segnalato):** in `CreateTransferServiceImpl.getAccountsMap` il messaggio è `"Account not found for IBAN: " + iban.value()`. `WebExceptionHandler.handleAccountNotFound` lo mette nel corpo del 404. Il caso si verifica solo in una corsa critica (conto sparito tra il controllo e il lock), ma l'IBAN uscirebbe intero.

## Obiettivo
Esporre con Micrometer i contatori dei bonifici (per tipo ed esito), delle conclusioni, dei rimborsi, delle riconciliazioni e delle notifiche pubblicate, e un gauge delle righe dell'outbox in attesa. Tutto passa da porte out dell'application, con adapter in un modulo dedicato e senza IBAN né importi nei tag e nei log.

## Perimetro
- **Dentro:** porte out `MetricsOutPort` e `PendingNotificationCountOutPort`; porta in e service per aggiornare il gauge; chiamate nei service dei bonifici e dell'outbox; modulo `transfer-micrometer-adapter`; adapter postgres di conteggio; scheduler del gauge nel bootstrap; dashboard `monitoring/grafana/dashboards/bank-business.json` con sezione Fraud (D6 di fraud-07); test; `transfer-service/CLAUDE.md` (da autorizzare).
- **Fuori:** contatore dei fallimenti (404, 503, fondi insufficienti); gauge dei bonifici esterni ancora in corso; istogrammi degli importi; metriche del notification-service; alert Prometheus; correzione del messaggio con l'IBAN; migration.

## Ambiguità / decisioni per l'utente (il piano segue le raccomandazioni R)
1. **D1 (contraddizione con la issue, blocca). `SENT` e `UNKNOWN` non sono stati finali.** Un bonifico esterno passa da `SENT` (o `UNKNOWN`) a `COMPLETED`/`REJECTED` più tardi, in `processOutcomes` o in `reconcileStale`. Un solo contatore "stato finale" con tutti e quattro i valori conterebbe due volte lo stesso bonifico.
   - **R: due contatori distinti.**
     - `bank.transfer.requests{type,status}`: esito restituito al client dal `POST`, uno per richiesta riuscita. I valori sono `COMPLETED`, `REJECTED`, `SENT`, `UNKNOWN`: è il "SENT/UNKNOWN" della issue.
     - `bank.transfer.concluded{type,status}`: un incremento quando il bonifico raggiunge **davvero** `COMPLETED` o `REJECTED`, cioè quando si scrive la notifica nell'outbox.
     - Nessun bonifico compare due volte nello stesso contatore. Il passaggio `SENT → COMPLETED` incrementa `requests{SENT}` e poi `concluded{COMPLETED}`.
   - Alternativa B: un contatore di transizioni (`bank.transfer.transitions{type,status}`, +1 a ogni stato raggiunto). Ogni serie ha senso da sola, ma sommare tra stati conta due volte.
2. **D2 (blocca). Modifica di test esistenti.** Strumentare tre service `@Service` vuol dire aggiungere un parametro al loro unico costruttore. Un secondo costruttore richiederebbe `@Autowired`, vietato in application dal CLAUDE.md.
   - **R (a):** aggiungere `MetricsOutPort` come ultimo parametro e cambiare **solo la riga di costruzione** nel setup dei 4 test elencati (passando un mock Mockito o il fake `RecordingMetricsOutPort`). Nessuna asserzione cambia. Richiede l'autorizzazione esplicita a toccare test esistenti.
   - (b) Togliere `@Service` dai 3 service e crearli con `@Bean` in una `config/TransferServicesConfig`, tenendo i vecchi costruttori con una porta che non fa nulla (come fraud-06 A1). Nessun test toccato, ma cambia il wiring di tutti i bonifici e lascia costruttori che perdono metriche in silenzio.
   - (c) Decorator delle porte in: scartata, perché gli esiti tardivi passano dal metodo privato `applyOutcome` e un decorator non li vede.
3. **D3. Dipendenze nuove (DA APPROVARE).**
   - `io.micrometer:micrometer-core` (compile, versione dal BOM di Boot 4.1.1) nel nuovo `transfer-micrometer-adapter/pom.xml`.
   - La dipendenza interna `transfer-micrometer-adapter` in `transfer-bootstrap/pom.xml`; `<module>` e `dependencyManagement` nel pom padre.
   - In bootstrap nessuna dipendenza esterna in più: `spring-boot-starter-actuator-test` c'è già.
   - Alternativa senza dipendenze nuove: adapter nel bootstrap (Micrometer arriva da actuator). Si scosta dalla struttura (transfer-11 ha spostato la stanza simulata fuori dal bootstrap proprio per questo): sconsigliata.
4. **D4. Il gauge delle righe dell'outbox in attesa.**
   - **R:** una porta out nuova `PendingNotificationCountOutPort.countPending()`, implementata nell'adapter postgres con `SpringDataOutboxRepository.count()` (`SELECT count(*) FROM outbox`, tabella del Transfer stesso).
   - Il conteggio lo chiama un service `RefreshOutboxMetricsServiceImpl` (porta in `RefreshOutboxMetricsInPort`) attivato da uno **scheduler del bootstrap**: proprietà `transfer.metrics.outbox.enabled` (default `true`), `fixed-delay=15s` (uguale allo scrape), `initial-delay=5s`.
   - Il service passa il numero a `MetricsOutPort.outboxPending(long)`. L'adapter lo tiene in un `AtomicLong` letto dal gauge.
   - Così lo scrape di Prometheus non tocca mai il database, e un errore resta nello scheduler (WARN con il solo nome della classe).
   - Costo: 4 `count(*)` al minuto su una tabella che di norma resta quasi vuota (con il publisher spento, il default, cresce di una riga per bonifico concluso). Su Postgres `count(*)` legge tutta la tabella: accettabile con i volumi demo.
   - Alternative:
     - un gauge con funzione chiamata a ogni scrape: stesso costo, ma il database entra nel thread di scrape e un errore dà NaN;
     - aggiornare il gauge a ogni giro del publisher: con il publisher spento (default) il gauge non si aggiornerebbe mai.
   - Non si modifica `PendingNotificationOutPort`: i fake dei test esistenti dovrebbero implementare il metodo nuovo.
5. **D5. Valore del gauge prima della prima lettura e dopo un errore.** R: NaN fino alla prima lettura, perché 0 direbbe falsamente "outbox vuota". Dopo un errore resta l'ultimo valore letto (WARN nel log). Alternativa: NaN dopo ogni errore.
6. **D6. Cosa vuol dire "pubblicata".** R: `send` confermato dal broker, contato riga per riga subito dopo `send`, anche se poi `delete` fallisce. È lo stesso confine del contratto (cancellazione solo dopo la conferma). Conseguenza dichiarata: con un errore tra conferma e cancellazione la riga viene ripubblicata e contata di nuovo (at-least-once). Alternativa: contare dopo `delete`; non conterebbe mai una notifica davvero consegnata se la cancellazione fallisce.
7. **D7. Cosa conta come "rimborso".** R: ogni riaccredito del mittente di un bonifico esterno già addebitato, cioè ogni conclusione `REJECTED` dentro `applyOutcome`, con il tag `cause`:
   - `CLEARING_UNAVAILABLE`: compensazione immediata dopo `ClearingHouseUnavailableException`;
   - `CLEARING_REJECTED`: esito `REJECTED` della stanza, da `processOutcomes` o da `reconcileStale` con risposta presente;
   - `NOT_FOUND_AT_CLEARING`: risposta vuota di `queryStatus` in `reconcileStale`.
   - Il rifiuto antifrode degli esterni **non** è un rimborso (nessun addebito), come tutti i `REJECTED` interni.
8. **D8. Riconciliazioni.** R: `bank.transfer.reconciliations{outcome}`, un incremento per ogni id esaminato da `reconcileStale`:
   - `COMPLETED` / `REJECTED`: esito presente e applicato;
   - `NOT_FOUND`: risposta vuota, rimborso applicato;
   - `SKIPPED`: `applyOutcome` restituisce false (già concluso o bonifico non trovato);
   - `FAILED`: eccezione per quell'id.
   - Gli esiti applicati da `processOutcomes` non sono riconciliazioni: li conta `concluded`.
9. **D9. Bonifici interni `REJECTED`.** Sono sempre rifiuti antifrode, salvati. R: `requests{INTERNAL,REJECTED}` +1 e `concluded{INTERNAL,REJECTED}` +1, nessun rimborso.
10. **D10. Bonifici che falliscono con un'eccezione prima del salvataggio** (404 conto, 503 antifrode, 409/400 fondi insufficienti o valute diverse, 400 IBAN, errore del database con rollback). R: **non** si contano nei contatori di business: non esiste un bonifico salvato, e `http_server_requests` li conta già per codice HTTP. Alternativa per una issue futura: `bank.transfer.failures{type,cause}` con una causa da un elenco chiuso (`ACCOUNT_NOT_FOUND`, `FRAUD_UNAVAILABLE`, `INSUFFICIENT_FUNDS`, `INVALID_REQUEST`, `OTHER`).
11. **D11. Quando si incrementa: dopo il commit.** R: i contatori si chiamano **fuori** da `transactionTemplate.execute(...)`, a commit avvenuto. Un rollback non deve lasciare un conteggio senza bonifico. Per farlo, il metodo privato `applyOutcome` restituisce lo stato concluso (`Optional<TransferStatusEnum>`, vuoto = ignorato) al posto del `boolean`, e riceve la causa del rimborso. Le firme pubbliche non cambiano. Niente `TransactionSynchronization`: sarebbe Spring oltre `@Service`/`@Transactional`.
12. **D12. Valori dei tag.** R: i nomi degli enum in maiuscolo (`INTERNAL`, `COMPLETED`, ...), come nelle API e nel contratto. Le combinazioni possibili si registrano a zero all'avvio: `INTERNAL`×{`COMPLETED`, `REJECTED`}, `EXTERNAL`×{`COMPLETED`, `REJECTED`, `SENT`, `UNKNOWN`}. `EXTERNAL COMPLETED` compare in `requests` solo nella corsa critica di `markUnknownIfSent`. Una combinazione mai vista (es. `PENDING`) viene registrata al primo uso senza eccezioni: la cardinalità resta limitata dagli enum.
13. **D13. Dashboard.** R: un solo file con le sezioni Transfer, Outbox e riconciliazione, e Fraud. Filtri sul solo tag `application`, nessuna variabile `runtime`. Per il gauge si usa `max`, non `sum`: con più istanze ognuna riporta lo stesso conteggio globale.

## Metriche
Tutte hanno anche il tag comune `application="transfer"`.

| Micrometer | Prometheus | Tipo | Tag (elenco chiuso) | Dove si incrementa | Perché lì |
|---|---|---|---|---|---|
| `bank.transfer.requests` | `bank_transfer_requests_total` | counter | `type` ∈ {INTERNAL, EXTERNAL}; `status` ∈ {COMPLETED, REJECTED, SENT, UNKNOWN} | `CreateTransferServiceImpl.executeTransfer` dopo `execute` (INTERNAL); `ExternalTransferSagaServiceImpl`: dopo il rifiuto antifrode, dopo un `submit` riuscito (SENT), dopo la compensazione immediata (REJECTED), dopo `markUnknownIfSent` (stato restituito) | È l'esito che riceve il client, una volta per richiesta, dopo il commit |
| `bank.transfer.concluded` | `bank_transfer_concluded_total` | counter | `type`; `status` ∈ {COMPLETED, REJECTED} | Interni: insieme a `requests`. Esterni: dopo il rifiuto antifrode e dopo ogni `applyOutcome` applicato (compensazione immediata, `processOutcomes`, `reconcileStale`) | Corrisponde uno a uno alla scrittura nell'outbox: ogni bonifico si conclude una volta sola (lo garantisce l'idempotenza di `applyOutcome`) |
| `bank.transfer.refunds` | `bank_transfer_refunds_total` | counter | `cause` ∈ {CLEARING_UNAVAILABLE, CLEARING_REJECTED, NOT_FOUND_AT_CLEARING} | Dopo un `applyOutcome` applicato con esito REJECTED | È l'unico punto con il riaccredito (`account.credit`) |
| `bank.transfer.reconciliations` | `bank_transfer_reconciliations_total` | counter | `outcome` ∈ {COMPLETED, REJECTED, NOT_FOUND, SKIPPED, FAILED} | `reconcileStale`, nel ciclo per id: dopo `applyOutcome` o nel `catch` | Ogni id esaminato dà un solo esito |
| `bank.transfer.outbox.published` | `bank_transfer_outbox_published_total` | counter | nessuno | `PublishPendingNotificationsServiceImpl.publishPending`, subito dopo un `send` riuscito, prima di `delete` | D6 |
| `bank.transfer.outbox.pending` | `bank_transfer_outbox_pending` | gauge | nessuno | Valore impostato da `RefreshOutboxMetricsServiceImpl` ogni 15 secondi | D4 e D5 |

Cardinalità: al massimo 8 + 4 + 3 + 5 + 1 + 1 serie. Nessun IBAN, importo, `transferId` o motivo, per costruzione: la porta riceve solo enum e numeri.

## Architettura
- **Porta out metriche:** `com.bank.transfer.application.port.out.MetricsOutPort`
  - `void transferRequested(TransferTypeEnum type, TransferStatusEnum status)`
  - `void transferConcluded(TransferTypeEnum type, TransferStatusEnum status)`
  - `void transferRefunded(RefundCauseEnum cause)`
  - `void reconciliationExamined(ReconciliationOutcomeEnum outcome)`
  - `void notificationPublished()`
  - `void outboxPending(long rows)`

  Solo enum e numeri. `TransferStatusEnum` è del dominio ed è ammesso nelle porte out (`ExternalTransferRepositoryOutPort` usa `Transfer`). Non si passa `Transfer`, che contiene IBAN e importo. Nessun metodo `default`: ogni implementazione deve gestire tutto.
- **Enum nuovi** in `com.bank.transfer.application.dto`: `RefundCauseEnum`, `ReconciliationOutcomeEnum`.
- **Porta out conteggio:** `com.bank.transfer.application.port.out.PendingNotificationCountOutPort`, con `long countPending()`.
- **Porta in e service del gauge:** `com.bank.transfer.application.port.in.RefreshOutboxMetricsInPort` (`void refreshPendingNotifications()`) e `com.bank.transfer.application.service.RefreshOutboxMetricsServiceImpl` (`@Service`, costruttore `(PendingNotificationCountOutPort, MetricsOutPort)`). Legge il conteggio e lo passa alla porta; le eccezioni le lascia allo scheduler.
- **Chi chiama `MetricsOutPort`:** `CreateTransferServiceImpl`, `ExternalTransferSagaServiceImpl`, `PublishPendingNotificationsServiceImpl`, `RefreshOutboxMetricsServiceImpl`. Il router no.
- **Adapter Micrometer:** nuovo modulo `transfer-micrometer-adapter`, package `com.bank.transfer.micrometer.port.out.micrometer.impl`, classe `MicrometerMetricsOutPortImpl(MeterRegistry)`.
  - Registra subito i contatori di D12 in `EnumMap`; le combinazioni mai viste le registra al primo uso.
  - Il gauge è `Gauge.builder("bank.transfer.outbox.pending", ...)` su un valore che parte da NaN.
  - Nessun log; `NullPointerException` sui `null`.
  - Il nome del modulo è quello della tecnologia (`micrometer`).
- **Adapter postgres:** nuovo `com.bank.transfer.postgres.port.out.postgres.impl.jpa.JpaPendingNotificationCountOutPortImpl` (`@Repository`, `@Transactional(readOnly = true)`, `repository.count()`). Non conosce Micrometer: il confine tra application e adapter è il solo `long`.
- **Bootstrap:**
  - `config/MetricsConfig`: `@Bean MetricsOutPort metricsOutPort(MeterRegistry)`;
  - `config/OutboxMetricsSchedulingConfig` (`@Configuration`, `@EnableScheduling`, `@ConditionalOnProperty(name = "transfer.metrics.outbox.enabled", havingValue = "true")`): crea un `config/OutboxMetricsScheduler` con `@Scheduled(fixedDelayString = "${transfer.metrics.outbox.fixed-delay}", initialDelayString = "${transfer.metrics.outbox.initial-delay}")`. Non lancia mai eccezioni; WARN con il solo `getSimpleName()`.
  - I service `@Service` ricevono la porta per costruttore. Il `MeterRegistry` lo fornisce actuator (Prometheus in esercizio, registry in memoria nei `@SpringBootTest`).
- **Test unitari senza Micrometer:** `transfer-application` non dipende da Micrometer. Nei test si usa un fake `RecordingMetricsOutPort` (test, `com.bank.transfer.application.service`) che registra le chiamate, oppure un mock Mockito nei 4 test esistenti (D2 a).

## Modifiche per modulo
**`transfer-application`** (`pom.xml` invariato)

| File | Tipo | Responsabilità |
|---|---|---|
| `port/out/MetricsOutPort.java` | nuovo | firme sopra |
| `port/out/PendingNotificationCountOutPort.java` | nuovo | `long countPending()` |
| `port/in/RefreshOutboxMetricsInPort.java` | nuovo | `void refreshPendingNotifications()` |
| `dto/RefundCauseEnum.java` | nuovo | `CLEARING_UNAVAILABLE`, `CLEARING_REJECTED`, `NOT_FOUND_AT_CLEARING` |
| `dto/ReconciliationOutcomeEnum.java` | nuovo | `COMPLETED`, `REJECTED`, `NOT_FOUND`, `SKIPPED`, `FAILED` |
| `service/CreateTransferServiceImpl.java` | modificato | parametro `MetricsOutPort` in fondo; dopo `execute`: `transferRequested` e `transferConcluded(INTERNAL, status)` |
| `service/ExternalTransferSagaServiceImpl.java` | modificato | parametro `MetricsOutPort` in fondo; punti della tabella. `applyOutcome(ClearingOutcomeDto, RefundCauseEnum)` restituisce `Optional<TransferStatusEnum>`; le metriche vanno dopo l'`execute`. `processOutcomes` e `reconcileStale` mantengono firma e valore di ritorno (numero di esiti applicati) |
| `service/PublishPendingNotificationsServiceImpl.java` | modificato | parametro `MetricsOutPort` in fondo; `notificationPublished()` dopo ogni `send` riuscito |
| `service/RefreshOutboxMetricsServiceImpl.java` | nuovo | vedi Architettura |
| test `service/RecordingMetricsOutPort.java` | nuovo | fake |
| test `CreateTransferServiceImplTest`, `ExternalTransferSagaServiceImplTest`, `ExternalTransferSagaReconciliationTest`, `PublishPendingNotificationsServiceImplTest` | **modificati solo nella riga del costruttore (D2 a)** | aggiungono `metrics` (mock o fake) |

**`transfer-micrometer-adapter`** (modulo nuovo)

| File | Responsabilità |
|---|---|
| `pom.xml` | parent `transfer-service`. Dipendenze: `transfer-application`, `io.micrometer:micrometer-core` (D3), `junit-jupiter` e `assertj-core` in test (come `transfer-clearing-adapter`) |
| `src/main/java/com/bank/transfer/micrometer/port/out/micrometer/impl/MicrometerMetricsOutPortImpl.java` | implementa `MetricsOutPort` |
| `src/test/java/com/bank/transfer/micrometer/port/out/micrometer/impl/MicrometerMetricsOutPortImplTest.java` | test |

**`transfer-postgres-adapter`**

| File | Tipo | Responsabilità |
|---|---|---|
| `port/out/postgres/impl/jpa/JpaPendingNotificationCountOutPortImpl.java` | nuovo | `countPending()` con `SpringDataOutboxRepository.count()` |
| test `port/out/postgres/impl/jpa/JpaPendingNotificationCountOutPortImplTest.java` | nuovo | `@DataJpaTest` (configurazione `PostgresTestApplication`) |

`SpringDataOutboxRepository`, `JpaPendingNotificationOutPortImpl` e le migration V1-V3 restano invariati.

**`transfer-service/pom.xml`** (modificato): `<module>transfer-micrometer-adapter</module>` prima di `transfer-bootstrap`, e la voce in `dependencyManagement`.

**`transfer-bootstrap`**

| File | Tipo | Responsabilità |
|---|---|---|
| `pom.xml` | modificato | dipendenza `transfer-micrometer-adapter` |
| `config/MetricsConfig.java` | nuovo | bean `MetricsOutPort` |
| `config/OutboxMetricsSchedulingConfig.java` | nuovo | scheduler condizionato |
| `config/OutboxMetricsScheduler.java` | nuovo | giro periodico del gauge |
| `src/main/resources/application.properties` | modificato | `transfer.metrics.outbox.enabled=${TRANSFER_OUTBOX_METRICS_ENABLED:true}`, `transfer.metrics.outbox.fixed-delay=15s`, `transfer.metrics.outbox.initial-delay=5s` |
| test `config/MetricsConfigTest.java`, `config/OutboxMetricsSchedulingConfigTest.java`, `OutboxMetricsDefaultPropertiesTest.java`, `TransferBusinessMetricsIntegrationTest.java` | nuovi | vedi Test |

**`monitoring/grafana/dashboards/bank-business.json`** (nuovo): vedi Dashboard.

**Documentazione (ultima fase, da autorizzare):**
- `transfer-service/CLAUDE.md`: riga `transfer-micrometer-adapter` nella tabella dei moduli; package `micrometer.port.out.micrometer.impl` nella "Struttura dei package"; sezione "Metriche di business" (nomi, tag, D1, D4, D6, D7, rischi).
- `CLAUDE.md` radice: nessuna modifica.

## Test attesi (prima del codice, stesso package)
**`CreateTransferServiceMetricsTest`** (application, nuovo, stesso setup Mockito del test esistente con `RecordingMetricsOutPort`)

| Dato | Quando | Allora |
|---|---|---|
| antifrode ALLOWED, conti esistenti | `executeTransfer` | `requested(INTERNAL, COMPLETED)` e `concluded(INTERNAL, COMPLETED)`, una volta ciascuno |
| antifrode REJECTED | `executeTransfer` | `requested(INTERNAL, REJECTED)`, `concluded(INTERNAL, REJECTED)`, nessun rimborso |
| mittente o destinatario inesistente | `executeTransfer` | `AccountNotFoundException`, **nessuna chiamata** |
| `FraudCheckUnavailableException` | `executeTransfer` | eccezione, nessuna chiamata |
| fondi insufficienti (eccezione dentro la transazione) | `executeTransfer` | eccezione, nessuna chiamata |
| `transferRepository.save` lancia | `executeTransfer` | eccezione, nessuna chiamata (si conta dopo il commit) |

**`ExternalTransferSagaMetricsTest`** (application, nuovo, repository con stato come in `ExternalTransferSagaServiceImplTest`)

| Dato | Quando | Allora |
|---|---|---|
| antifrode REJECTED | `executeExternalTransfer` | `requested(EXTERNAL, REJECTED)`, `concluded(EXTERNAL, REJECTED)`, nessun rimborso |
| `submit` riuscito | `executeExternalTransfer` | solo `requested(EXTERNAL, SENT)` |
| `submit` con `ClearingHouseUnavailableException` | `executeExternalTransfer` | `concluded(EXTERNAL, REJECTED)`, `refunded(CLEARING_UNAVAILABLE)`, `requested(EXTERNAL, REJECTED)` |
| `submit` con `ClearingHouseTimeoutException` | `executeExternalTransfer` | solo `requested(EXTERNAL, UNKNOWN)` |
| timeout ed esito già applicato nel frattempo | `executeExternalTransfer` | `requested(EXTERNAL, <stato restituito>)`, nessun `concluded` in più |
| un SENT, poll con ACCEPTED | `processOutcomes` | `concluded(EXTERNAL, COMPLETED)`, nessun `requested` |
| un UNKNOWN, poll con REJECTED | `processOutcomes` | `concluded(EXTERNAL, REJECTED)`, `refunded(CLEARING_REJECTED)` |
| esito duplicato su un COMPLETED | `processOutcomes` | nessuna chiamata |
| esito per un id sconosciuto | `processOutcomes` | nessuna chiamata |
| `save` lancia dentro `applyOutcome` | `processOutcomes` | nessuna chiamata; valore restituito invariato rispetto a oggi |
| antifrode non disponibile o mittente inesistente | `executeExternalTransfer` | eccezione, nessuna chiamata |

**`ExternalTransferSagaReconciliationMetricsTest`** (application, nuovo)

| Dato | Quando | Allora |
|---|---|---|
| fermo, `queryStatus` ACCEPTED | `reconcileStale` | `reconciliation(COMPLETED)`, `concluded(EXTERNAL, COMPLETED)` |
| fermo, `queryStatus` REJECTED | `reconcileStale` | `reconciliation(REJECTED)`, `concluded(EXTERNAL, REJECTED)`, `refunded(CLEARING_REJECTED)` |
| fermo, `queryStatus` vuoto | `reconcileStale` | `reconciliation(NOT_FOUND)`, `concluded(EXTERNAL, REJECTED)`, `refunded(NOT_FOUND_AT_CLEARING)` |
| id già concluso | `reconcileStale` | `reconciliation(SKIPPED)` e nient'altro |
| `queryStatus` lancia per un id | `reconcileStale` | `reconciliation(FAILED)`; gli altri id proseguono |
| nessun id | `reconcileStale` | nessuna chiamata |
| `maxTransfers` 0 o `createdBefore` null | `reconcileStale` | eccezione come oggi, nessuna chiamata |

**`PublishPendingNotificationsMetricsTest`** (application, nuovo)

| Dato | Quando | Allora |
|---|---|---|
| 3 righe, tutto ok | `publishPending(10)` | 3 `notificationPublished()` |
| `send` fallisce alla 2ª riga | `publishPending` | 1 chiamata |
| `delete` fallisce alla 2ª riga | `publishPending` | 2 chiamate (D6) |
| `findPending` lancia o nessuna riga | `publishPending` | nessuna chiamata |
| `maxRows` 0 | `publishPending` | `IllegalArgumentException`, nessuna chiamata |

**`RefreshOutboxMetricsServiceImplTest`** (application, nuovo): conteggio 7 → `outboxPending(7)`; conteggio 0 → `outboxPending(0)`; la porta lancia → l'eccezione esce e `outboxPending` non viene chiamato; costruttore con argomenti null → `NullPointerException`.

**`MicrometerMetricsOutPortImplTest`** (adapter, `SimpleMeterRegistry`)

| Dato | Quando | Allora |
|---|---|---|
| registry vuoto | costruzione | esistono a 0 le serie di D12, i 3 rimborsi, i 5 esiti della riconciliazione, `outbox.published`; il gauge `outbox.pending` vale NaN |
| — | ogni metodo chiamato una volta | la serie giusta vale 1, le altre 0; il tipo è giusto (Counter o Gauge) |
| — | `outboxPending(5)` poi `outboxPending(2)` | il gauge vale 2 |
| — | `transferRequested(EXTERNAL, PENDING)` | nessuna eccezione, serie nuova a 1 (D12) |
| qualsiasi sequenza | ispezione di `registry.getMeters()` con nome `bank.transfer.*` | chiavi dei tag ⊆ {`type`, `status`, `cause`, `outcome`}; valori `^[A-Z_]+$`; nessun valore simile a un IBAN o a un numero |
| argomento null | qualsiasi metodo | `NullPointerException` |

**`JpaPendingNotificationCountOutPortImplTest`** (postgres, `@DataJpaTest`): tabella vuota → 0; 3 righe salvate → 3; dopo `delete` di una riga → 2.

**Bootstrap**
- `MetricsConfigTest` (`ApplicationContextRunner` con `MetricsConfig` e `SimpleMeterRegistry`): un solo `MetricsOutPort`, di tipo `MicrometerMetricsOutPortImpl`.
- `OutboxMetricsSchedulingConfigTest` (runner, porta in finta):
  - `enabled=true` → un `OutboxMetricsScheduler`; `false` o assente → nessuno;
  - con la porta in che lancia, il giro non lancia e il WARN contiene solo il nome semplice della classe.
- `OutboxMetricsDefaultPropertiesTest`: valori di default delle 3 proprietà da `application.properties`.

**`TransferBusinessMetricsIntegrationTest`** (bootstrap, `@SpringBootTest` su `jdbc:h2:mem:metrics`; antifrode `@Primary` che rifiuta sopra 5000 e altrimenti accetta; `NotificationSenderOutPort` `@Primary` finto che registra; stanza simulata in `ACCEPT`; MockMvc con `print = NONE`; `OutputCaptureExtension`; asserzioni a delta sul `MeterRegistry`; conti propri, non `demo-*`; importi riconoscibili come `1234.57`)

| # | Dato | Quando | Allora |
|---|---|---|---|
| M1 | interno ammesso | POST `/transfers` | `requests{INTERNAL,COMPLETED}` +1, `concluded{INTERNAL,COMPLETED}` +1 |
| M2 | interno sopra soglia | POST | `requests{INTERNAL,REJECTED}` +1, `concluded{INTERNAL,REJECTED}` +1 |
| M3 | interno verso un conto inesistente (404) | POST | nessun contatore `bank.transfer.*` cambia |
| M4 | esterno ammesso | POST | `requests{EXTERNAL,SENT}` +1; `concluded` invariato |
| M5 | M4, poi `processOutcomes()` tramite la porta in | — | `concluded{EXTERNAL,COMPLETED}` +1 |
| M6 | esterno SENT, simulatore azzerato o id sconosciuto alla stanza, `reconcileStale(futuro, 100)` | — | `reconciliations{NOT_FOUND}` +1, `refunds{NOT_FOUND_AT_CLEARING}` +1, `concluded{EXTERNAL,REJECTED}` +1 |
| M7 | righe nell'outbox da M1-M6 | `publishPending(100)` | `outbox.published` cresce del numero di `send` finti riusciti |
| M8 | — | `refreshPendingNotifications()` | il gauge vale `SELECT count(*) FROM outbox` letto con `JdbcTemplate` |
| M9 | dopo M1-M8 | lettura di tutti i meter `bank.transfer.*` | tag `application=transfer`; nessun tag con IBAN, importo o `transferId` |
| M10 | dopo M1-M8 | GET `/actuator/prometheus`, con l'annotazione di Boot 4 che attiva l'export nei test (**da verificare**; `spring-boot-starter-actuator-test` c'è già) | 200; righe `bank_transfer_requests_total{application="transfer",status="COMPLETED",type="INTERNAL"}`, `bank_transfer_outbox_pending{application="transfer"}`; nessuna riga `bank_` contiene un IBAN, un importo usato o un `transferId` |
| M11 | output catturato durante M1-M10 | — | nessun IBAN né importo |

Lo scheduler del gauge resta acceso per default anche in questo contesto: le asserzioni M8 chiamano la porta in a mano e confrontano il valore subito dopo.

## Dashboard `monitoring/grafana/dashboards/bank-business.json`
Lo stile è quello di `bank-technical.json`: `uid` `bank-business`, titolo "Bank - Business", tag `bank`, `refresh` 10s, ultimi 30 minuti, datasource `{"type":"prometheus","uid":"prometheus"}`. **Nessuna** variabile `runtime`: i filtri usano solo `application`. Finestra `[5m]` per smussare i volumi bassi (lo scrape è ogni 15 secondi).

| Sezione | Pannello | Tipo | PromQL |
|---|---|---|---|
| Bonifici | Richieste al minuto per tipo ed esito | timeseries | `sum by (type, status) (rate(bank_transfer_requests_total{application="transfer"}[5m])) * 60` |
| Bonifici | Bonifici conclusi (ultima ora) per stato | stat | `sum by (status) (increase(bank_transfer_concluded_total{application="transfer"}[1h]))` |
| Bonifici | Conclusi al minuto per tipo e stato | timeseries | `sum by (type, status) (rate(bank_transfer_concluded_total{application="transfer"}[5m])) * 60` |
| Bonifici | Quota di rifiuti | timeseries (unit `percentunit`) | `sum(rate(bank_transfer_concluded_total{application="transfer",status="REJECTED"}[5m])) / sum(rate(bank_transfer_concluded_total{application="transfer"}[5m]))` |
| Bonifici | Esterni con esito incerto al minuto | timeseries | `sum(rate(bank_transfer_requests_total{application="transfer",type="EXTERNAL",status="UNKNOWN"}[5m])) * 60` |
| Outbox | Notifiche in attesa | stat (soglie: verde 0, giallo 10, rosso 100) | `max(bank_transfer_outbox_pending{application="transfer"})` |
| Outbox | Notifiche pubblicate al minuto | timeseries | `sum(rate(bank_transfer_outbox_published_total{application="transfer"}[5m])) * 60` |
| Riconciliazione | Esiti della riconciliazione (15 minuti) | timeseries a barre | `sum by (outcome) (increase(bank_transfer_reconciliations_total{application="transfer"}[15m]))` |
| Riconciliazione | Rimborsi per causa (ultima ora) | stat / bargauge | `sum by (cause) (increase(bank_transfer_refunds_total{application="transfer"}[1h]))` |
| Antifrode | Valutazioni al minuto per esito | timeseries | `sum by (status) (rate(bank_fraud_assessments_total{application="fraud"}[5m])) * 60` |
| Antifrode | Quota di rifiuti antifrode | timeseries (`percentunit`) | `sum(rate(bank_fraud_assessments_total{application="fraud",status="REJECTED"}[5m])) / sum(rate(bank_fraud_assessments_total{application="fraud"}[5m]))` |
| Antifrode | Motivi di rifiuto (ultima ora) | bargauge | `sum by (reason) (increase(bank_fraud_reasons_total{application="fraud"}[1h]))` |

Senza traffico le quote di rifiuto valgono NaN (divisione 0/0), e Grafana mostra "No data": è accettato e va scritto nella `description` del pannello.

## Criteri di accettazione
- `.\mvnw.cmd test` da `transfer-service/` verde. Prima si misura il punto di partenza; i test esistenti restano invariati, salvo la riga del costruttore nei 4 file di D2 (se autorizzata).
- `transfer-domain` invariato; `transfer-application` senza import Micrometer (pom invariato); Micrometer solo in `transfer-micrometer-adapter` e `config/MetricsConfig`.
- Nessuna migration nuova. Dipendenze nuove solo quelle approvate in D3.
- Metriche con nomi, tipi, tag ed elenchi chiusi della tabella; incrementi solo dopo il commit; nessun IBAN, importo o `transferId` nei tag e nei log nuovi.
- Firme pubbliche di porte in e out esistenti invariate; `TransferRoutingServiceImpl` invariato.
- Contratto `transfer-notification.md` invariato (l'outbox scrive le stesse righe).
- `/actuator/prometheus` espone le serie `bank_transfer_*`. `bank-business.json` è JSON valido, usa la datasource uid `prometheus` e nessuna variabile `runtime`, e viene caricato dal provisioning esistente.

## Rischi
- **D2:** toccare 4 test esistenti va autorizzato esplicitamente. Con (b) cambia il wiring di tutti i service dei bonifici.
- **Corsa critica già documentata (SENT e "non trovata"):** un rimborso `NOT_FOUND_AT_CLEARING` seguito da un'accettazione della stanza appare nei contatori come un REJECTED. È un limite del flusso, non delle metriche.
- **Conteggio doppio di `outbox.published`** in caso di errore tra conferma e cancellazione (D6, at-least-once).
- **Gauge vecchio dopo un errore del database** (D5); con più istanze va letto con `max`.
- **Scheduler del gauge acceso per default:** in ogni contesto `@SpringBootTest` gira un `count(*)` ogni 15 secondi su H2. Un giro alla chiusura del contesto può scrivere un WARN: se un test esistente controlla l'assenza di WARN nell'output, va spento con la proprietà in quel contesto (da verificare in fase 4).
- **Contatori per istanza, azzerati al riavvio:** solo `rate()` e `increase()`.
- **Annotazione di Boot 4 per l'export nei test** da verificare (M10).
- **`count(*)` legge tutta la tabella su Postgres:** se il publisher resta spento a lungo l'outbox cresce. Con i volumi demo è trascurabile; a regime servirebbe un intervallo più lungo.
- **IBAN già presente nel messaggio di `AccountNotFoundException`** (in `getAccountsMap`): resta com'è, da correggere in un'issue separata.

## Ordine di esecuzione (ogni passo lascia la build verde)
| Fase | Contenuto | Dipende da | Rischio |
|---|---|---|---|
| 0 | fraud-07 completato; `.\mvnw.cmd test` come punto di partenza | — | — |
| 1 | porte, enum, `RecordingMetricsOutPort`; test rossi dei service; parametro nei costruttori più la riga dei 4 test (D2); implementazione | D1, D2 | medio (`applyOutcome` dopo il commit) |
| 2 | `RefreshOutboxMetricsInPort` / `ServiceImpl`, `PendingNotificationCountOutPort` e adapter JPA con i loro test | 1 | basso |
| 3 | modulo `transfer-micrometer-adapter` con test | D3 | basso |
| 4 | bootstrap: `MetricsConfig`, scheduler, proprietà, test di config e integrazione | 2, 3 | medio |
| 5 | `bank-business.json`; verifica manuale con `docker compose` (profilo `apps`) | 4, fraud-07 | basso |
| 6 | `transfer-service/CLAUDE.md` (da autorizzare) | 5 | basso |

## Scostamenti dal CLAUDE.md
Modifica di test esistenti (D2, solo la riga del costruttore) e dipendenze nuove (D3): entrambe da autorizzare. Modulo nuovo conforme a `<svc>-<tecnologia>-adapter`, nessun livello o package inventato. La riga "Prima i test, poi il codice" è rispettata dall'ordine delle fasi.
## Aggiunta approvata dall'utente: nessun IBAN nel messaggio di AccountNotFoundException
Approvazione del piano "così com'è" più questa correzione, richiesta esplicitamente.
- **Problema:** `CreateTransferServiceImpl.getAccountsMap` lancia `new AccountNotFoundException("Account not found for IBAN: " + iban.value())` e `WebExceptionHandler.handleAccountNotFound` mette `e.getMessage()` nel corpo del 404 (`detail`). Capita solo nella corsa critica (conto sparito tra il controllo `requireExistingAccount` e il lock).
- **Correzione:** messaggio fisso senza IBAN, `"Account not found"`, in linea con gli altri messaggi del service (`"Source Account not found"`, `"Destination Account not found"`). Nessun cambio di firma né di tipo di eccezione.
- **Test (prima del codice, nuovo file, `CreateTransferServiceNotFoundMessageTest` in `com.bank.transfer.application.service`):** conto che sparisce tra `findByIban` e `findByIbanForUpdate` (il fake restituisce presente al primo e vuoto al secondo, per mittente e per destinatario) -> `AccountNotFoundException` il cui messaggio non contiene né l'IBAN del mittente né quello del destinatario (`hasMessageNotContaining`) ed è esattamente `"Account not found"`.
- **Nessun test esistente va toccato:** nessun test asserisce il vecchio messaggio (verificato con grep).
- **Fuori perimetro, segnalato:** `AccountReadOnlyInServiceImpl.getAccount` (usato da `GET /accounts/{iban}`) mette nel messaggio l'IBAN che il chiamante ha appena inviato (`"No account found for iban %s"`). Non è stato chiesto e non si tocca in questa fase.
- Fase: dentro la fase 1 di transfer-14, insieme alla strumentazione di `CreateTransferServiceImpl`.

## Aggiornamento dopo la review (approvato dall'utente)
- **Metriche in `processOutcomes` e `reconcileStale`:** `recordConclusion`, `reconciliationExamined(COMPLETED|REJECTED|NOT_FOUND|SKIPPED)` e `applied++` stanno FUORI dal try/catch di `queryStatus`/`applyOutcome`. `FAILED` nasce solo da errori di query o di apply. Se la porta delle metriche lancia dopo un esito gia applicato, l'eccezione si propaga e non si registra `FAILED` per quell'id (i bonifici non ancora applicati nel giro li riprende la riconciliazione). Il corpo del piano (tabella delle metriche e D8) va letto con questa regola.
- **`submitToClearingHouse`:** `requested(EXTERNAL, SENT)` e il ritorno stanno fuori dal try del `submit`: se la metrica lancia, il bonifico resta `SENT` e l'eccezione si propaga (regola scelta dall'orchestratore).
- **`GET /accounts/{iban}` (richiesto dall'utente, ora dentro l'issue):** `AccountReadOnlyInServiceImpl` lancia `AccountNotFoundException("Account not found")` e `WebExceptionHandler.handleAccountNotFound` imposta `instance` fisso `/accounts` (costante `ACCOUNTS_INSTANCE`), quindi l'IBAN non esce dal 404. Vale anche per il 404 di `POST /transfers` con conto mancante (`instance` = `/accounts`). Test nuovi: `AccountReadOnlyInServiceImplTest`, `AccountControllerNotFoundBodyTest`. Sostituisce la riga "fuori perimetro, non si tocca" della sezione precedente.
- **MINOR corretti:** commento sul riferimento debole del gauge; cancellati due test tautologici (`implementsTheRefreshInPort`, `theAdapterIsTheCountOutPort`); aggiunti i test per la metrica che lancia (`ExternalTransferSagaMetricsFailureTest`, `ExternalTransferSagaMetricsOutcomeFailureTest`).
- **Invariato:** `PublishPendingNotificationsServiceImpl` (D6, consegna almeno una volta).

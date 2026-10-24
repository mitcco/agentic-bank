# transfer-service

Conti e bonifici. Package base: `com.bank.transfer`.

## Comandi (dalla cartella `transfer-service/`)
- Test di tutto: `.\mvnw.cmd test`
- Database locale: `docker compose up -d transfer-db` (dalla radice; senza il
  nome del servizio parte anche Kafka). PostgreSQL su `localhost:5433`,
  db/utente `transfer`, password da `TRANSFER_DB_PASSWORD` (default
  `transfer_dev`). Le migration Flyway si applicano all'avvio del Transfer.
- I test di `transfer-postgres-adapter` girano su H2 in memoria (con Flyway):
  non richiedono Docker. In `transfer-bootstrap` `CreateTransferConcurrencyTest`,
  `FraudCheckEndToEndTest`, `ExternalTransferSagaIntegrationTest` e
  `ExternalTransferEndToEndTest`, `ExternalTransferReconciliationIntegrationTest`
  e `DemoDataReconciliationIntegrationTest` usano H2 in memoria; solo
  `TransferApplicationTests` richiede il database Postgres attivo e senza
  fallisce con "Connection to localhost:5433 refused". Con il Postgres attivo quel
  test avvia l'applicazione e **il seed dei dati demo si applica al database di
  sviluppo** (vedi "Dati demo").
- Un solo modulo: `.\mvnw.cmd -pl transfer-clearing-adapter -am test`. Senza `-am`
  Maven usa i jar installati in `~/.m2`, che possono essere vecchi (basta un
  `.\mvnw.cmd install -DskipTests` per allinearli).

## Moduli
| Modulo | Contenuto |
|---|---|
| `transfer-domain` | Account, Iban, Money, Transfer, enum, eccezioni |
| `transfer-application` | Porte, service, command, DTO, mapper (MapStruct). Service dei bonifici: `TransferRoutingServiceImpl` (instradamento per ABI), `CreateTransferServiceImpl` (flusso interno), `ExternalTransferSagaServiceImpl` (Saga esterna) |
| `transfer-rest-adapter` | Controller REST, DTO HTTP, mapper, layer `servicedto` |
| `transfer-postgres-adapter` | JPA, repository Spring Data, entità, seed demo, migrazioni Flyway (`resources/db`) |
| `transfer-fraud-adapter` | Client HTTP verso il Fraud (`HttpFraudCheckOutPortImpl` implementa `FraudCheckOutPort`, `FraudRestClientFactory`, DTO HTTP) |
| `transfer-kafka-adapter` | Producer Kafka del publisher dell'outbox (`KafkaNotificationSenderOutPortImpl` implementa `NotificationSenderOutPort`) |
| `transfer-clearing-adapter` | Stanza di compensazione simulata in memoria (`SimulatedClearingHouseOutPortImpl` implementa `ClearingHouseOutPort`, Java puro) |
| `transfer-micrometer-adapter` | Implementa `MetricsOutPort` con contatori e gauge Micrometer (`MicrometerMetricsOutPortImpl`) |
| `transfer-bootstrap` | `TransferApplication`, `config/FraudConfig`, `config/ClockConfig`, `config/NotificationPublisher*`, `config/TransferRoutingConfig`, `config/Clearing*`, `application.properties`, test di integrazione |

## Publisher dell'outbox
Il publisher delle notifiche è spento di default: si accende con la variabile
`NOTIFICATION_PUBLISHER_ENABLED=true` (broker su `KAFKA_BOOTSTRAP_SERVERS`,
default `localhost:9092`). Con il default `false` le notifiche restano nella
tabella `outbox` e all'avvio compare un WARN.
Nel `docker-compose.yaml` il container `transfer` (profilo `apps`) parte invece con il publisher
e `CLEARING_OUTCOMES_ENABLED` accesi (`true`): i default di `application.properties` restano `false`.

## Bonifici esterni (Saga)
Un solo `POST /transfers`: `TransferRoutingServiceImpl` confronta l'ABI del
destinatario (`Iban.bankCode()`, cifre 6-10) con `transfer.bank-code`
(`TRANSFER_BANK_CODE`, default `05428`; un valore non valido fa fallire
l'avvio).
- **Stesso ABI**: flusso interno invariato (`CreateTransferServiceImpl`), compreso
  il 404 per un conto destinatario inesistente.
- **ABI diverso**: Saga (`ExternalTransferSagaServiceImpl`). Il destinatario non
  si cerca tra i conti. Solo IBAN a 27 caratteri: gli altri danno 400 come oggi.
- Passi: mittente esistente, antifrode, transazione con addebito e stato `SENT`,
  invio alla stanza **fuori** transazione, esito letto da `processOutcomes()`.
- Stati: `SENT`, `UNKNOWN`, `COMPLETED`, `REJECTED`. Risposta 202 per `SENT` e
  `UNKNOWN`, 200 negli altri casi. I bonifici esterni stanno nella tabella
  `external_transfer` (migration V3), senza chiave esterna su `dest_iban`.
- **Compensazione solo quando l'invio e certamente non ricevuto**
  (`ClearingHouseUnavailableException`): riaccredito con un nuovo `credit`,
  `REJECTED`, motivo "Clearing house unavailable".
- **Esito incerto** (timeout o altro errore del `submit`): `UNKNOWN`, nessun
  riaccredito, nessuna notifica, i fondi restano addebitati. Un esito tardivo lo
  chiude: `processOutcomes()` lo applica a `SENT` e `UNKNOWN` (lock del bonifico,
  poi del conto; idempotente).
- **Riconciliazione attiva** (`ReconcileExternalTransfersInPort.reconcileStale`):
  per i bonifici `SENT` o `UNKNOWN` creati da piu di `clearing.reconcile.stale-after`
  (5 minuti, misurati da `created_at`, in UTC) il service chiede alla stanza lo
  stato (`ClearingHouseOutPort.queryStatus`) e applica l'esito con le stesse regole.
  Se la stanza risponde "mai ricevuta" (vuoto) il rimborso e sicuro: `REJECTED`
  "Instruction not found at the clearing house". Giro ogni 30 secondi, massimo
  `clearing.reconcile.batch-size` (50) bonifici.
- L'outbox riceve solo `COMPLETED` e `REJECTED`.
- La Saga non implementa `CreateTransferInPort`: l'unico bean di quel tipo e il
  router. Il flusso interno implementa `CreateInternalTransferInPort`.

### Stanza di compensazione simulata
La stanza e finta e vive nel modulo `transfer-clearing-adapter` (package
`com.bank.transfer.clearing.port.out.clearing.impl`); il bean (`ClearingConfig`),
i WARN, gli scheduler e le proprieta `clearing.*` restano nel `transfer-bootstrap`.
- `CLEARING_SIMULATOR_MODE`: `ACCEPT` (default), `REJECT`, `UNAVAILABLE`,
  `TIMEOUT_THEN_ACCEPT`, `TIMEOUT_THEN_REJECT`. Un valore sconosciuto fa fallire
  l'avvio. L'esito non arriva mai dal `submit`, solo dal poll successivo.
- Il simulatore ricorda gli esiti in memoria (anche dopo il poll) per rispondere a
  `queryStatus`; un riavvio li cancella e ogni bonifico fermo risulta "mai ricevuto".
- `CLEARING_OUTCOMES_ENABLED` (default `false`): con `false` non c'e nessuno
  scheduler e i bonifici esterni restano `SENT` o `UNKNOWN` (WARN all'avvio); con
  `true` girano due scheduler: gli esiti (`processOutcomes()`, ogni 2 secondi) e la
  riconciliazione (ogni 30 secondi, proprieta `clearing.reconcile.*`).

### Limiti e rischi
- **Un conto interno con ABI diverso da `05428` e trattato come esterno**: il
  denaro esce verso la stanza e non arriva al conto. Oggi i dati demo e i test
  usano solo `05428`.
- **`TRANSFER_BANK_CODE` impostata sulla macchina cambia l'instradamento** di tutti
  i contesti del bootstrap: falliscono `RoutingDefaultPropertiesTest`,
  `ExternalTransferSagaIntegrationTest` (wiring) e gli end-to-end dei flussi
  interni. Eliminarla prima di lanciare i test.
- **Race tra il commit di `SENT` e il `submit`**: se la riconciliazione interroga
  prima che l'istruzione sia arrivata alla stanza, la risposta e "non trovata", il
  bonifico viene rimborsato e la stanza potrebbe poi accettare (doppio movimento).
  `clearing.reconcile.stale-after` non va mai impostato vicino a zero (lo scheduler
  rifiuta `0s` e i valori negativi).
- **Una stanza reale deve rispondere "non trovata" in modo autorevole**: nel
  simulatore dopo un riavvio la risposta e vuota per tutto, e i bonifici fermi
  vengono rimborsati.
- **Scheduler spenti** (default): `SENT` e `UNKNOWN` restano fermi e i fondi
  addebitati finche non si accende `CLEARING_OUTCOMES_ENABLED`.
- **Esito applicato e poi fallito** (errore del database durante `applyOutcome`):
  il bonifico resta `SENT` o `UNKNOWN` e lo riprende la riconciliazione.
- Dopo una compensazione immediata la risposta e sempre `REJECTED` "Clearing house
  unavailable", anche nel caso teorico di un esito concorrente gia applicato.
- Niente prenotazione dei fondi ne registro dei movimenti. Scopo didattico.

## Lettura di un bonifico
`GET /transfers/{id}` (`TransferQueryController`, adapter rest) restituisce lo
stato di un bonifico interno o esterno. Porta in `GetTransferInPort`
(`GetTransferServiceImpl`), porta out di sola lettura `TransferQueryOutPort`
(`JpaTransferQueryOutPortImpl`: cerca prima in `transfer`, poi in
`external_transfer`, senza lock).
- **Risposta 200** per qualsiasi stato salvato, anche `SENT` e `UNKNOWN` (il 202
  resta del solo `POST`): `transferId`, `sourceIbanMasked`, `destIbanMasked`,
  `amount` (2 decimali), `currency`, `transferStatus`, `reasons`.
- **IBAN mascherati** da `Iban.masked()`: primi 4 e ultimi 4 caratteri, il resto
  asterischi (27 caratteri). L'IBAN intero non esce mai: ne dalla risposta, ne dai
  log, ne dagli errori, ne dai `toString()` dei DTO.
- **Errori:** 404 "Transfer not found" per un id inesistente (qualunque stringa
  e un id valido: gli id demo non sono UUID); 400 per un id vuoto; **500
  "Stored transfer data is not valid"** se la riga letta ha un importo con piu
  di 2 decimali o un IBAN non valido (`StoredTransferDataException`). I corpi
  degli errori hanno `instance` fisso `/transfers`: l'id richiesto non compare.
- **Motivi:** per gli esterni `reason` e spezzato su `"; "` (come i motivi
  dell'antifrode nel `POST`); per gli interni `REJECTED` la lista e **sempre
  vuota**, perche la tabella `transfer` non salva il motivo.
- **Nessun controllo di accesso ne autenticazione**: chiunque conosca o indovini
  un id legge stato, importo, valuta e IBAN mascherati. I bonifici nuovi hanno
  UUID casuali (non enumerabili), gli id demo (`tr1`, `demo-tr-001`,
  `demo-ext-001`) sono prevedibili. Il mascheramento riduce l'esposizione ma non e
  crittografia ne controllo di accesso; la protezione vera (autenticazione,
  proprieta del conto, TLS) arrivera con il frontend. `GET /accounts/{iban}`
  resta aperto allo stesso modo.
- Altri limiti: nessun rate limiting; la lettura non e bloccante, quindi subito
  dopo un 202 puo restituire `SENT` mentre un esito e in corso di applicazione.

## Elenco dei bonifici di un IBAN
`GET /transfers?iban=...` (`TransferListController`, porta in `ListTransfersInPort`,
`ListTransfersServiceImpl`; porta out di sola lettura `TransferListQueryOutPort`,
`JpaTransferListQueryOutPortImpl`) elenca, paginati, i bonifici interni ed esterni in
cui l'IBAN e mittente o destinatario.
- **Parametri** (tutti facoltativi tranne `iban`; valori case-sensitive):
  `iban` (27 caratteri, normalizzato); `direction`: `ALL` (default), `OUTGOING` (l'IBAN
  e il mittente), `INCOMING` (l'IBAN e il destinatario); `status` (ripetibile, valori di
  `TransferStatusEnum`; `PENDING` e ammesso ma non da mai risultati); `type`: `INTERNAL`
  o `EXTERNAL`; `from` e `to` (istanti ISO-8601, `from` incluso e `to` escluso); `page`
  (default 0) e `size` (default 20, massimo 100); finestra massima `(page + 1) * size <= 1000`.
- **Risposta 200:** `items`, `page`, `size`, `totalElements`, `hasNext`. Ogni elemento:
  `transferId`, `type`, `direction` (relativa all'IBAN chiesto), `counterpartyIbanMasked`,
  `amount`, `currency`, `transferStatus`, `createdAt` (UTC). Niente motivi (si leggono con
  `GET /transfers/{id}`) e niente IBAN chiesto. Ordine: dal piu recente, a parita di data
  per id decrescente.
- **Errori:** 400 con messaggi fissi che non ripetono il valore ricevuto (`instance` fisso
  `/transfers`); un IBAN senza bonifici da 200 con lista vuota; **500 "Stored transfer
  data is not valid"** se una riga della pagina ha un importo con piu di 2 decimali o un
  IBAN non valido: e errata tutta la pagina che la contiene.
- **Come legge le due tabelle:** una query ordinata per `transfer` e una per
  `external_transfer` (le prime `offset + size` righe), fusione in memoria nell'adapter,
  `totalElements` = somma dei due conteggi.
- **Data e fuso:** `created_at` dei bonifici interni lo scrive il default del database nel
  fuso della sessione (la JVM) ed e letto in sola lettura come ora locale; quello degli
  esterni lo scrive l'applicazione in UTC. Ordine e filtri per data sono corretti solo se la
  JVM e la sessione JDBC hanno sempre girato nello stesso fuso; al cambio dell'ora legale
  un'ora e ambigua.
- **Rischi e limiti:**
  - **Nessun controllo di accesso:** chiunque conosca un IBAN (che non e un segreto)
    ricostruisce lo storico del conto (controparti mascherate, importi, stati, date). Limiti
    adottati: `size` massimo 100, finestra massima 1000, controparte sempre mascherata,
    nessun IBAN o importo nei log e negli errori.
  - **L'IBAN sta nella query string:** finisce negli access log di proxy, gateway e Tomcat
    (se attivati) e nella cronologia del browser; per questo non e nel path.
  - **Nessun indice** su `source_iban`, `dest_iban`, `created_at`, e i conteggi girano senza
    `LIMIT`: la finestra di 1000 limita le righe lette, non il costo del conteggio. Un indice
    sarebbe una migration V4 da proporre a parte.
  - **Ordine a parita di data:** ogni tabella ordina per `id` con la collation del database,
    la fusione con `String.compareTo`: su Postgres con una collation non-C i due ordini
    possono non coincidere, e il confine di una pagina puo saltare o ripetere una riga (su
    Postgres `now()` e l'inizio della transazione: tutte le righe interne del seed hanno lo
    stesso `created_at`). I test girano su H2 e non lo coprono.
  - Le due query non sono nella stessa transazione: un bonifico scritto in mezzo puo
    spostare la paginazione di una riga.

## Dati demo
`DemoDataSeeder` (nel `transfer-postgres-adapter`) carica a ogni avvio dati
dimostrativi **deterministici**, solo se mancano:
- 3 conti storici (`ac1..ac3`, IBAN `...0001..0003`) e 50 conti `demo-acc-101..150`
  (IBAN `IT60X0542811101` + 12 cifre, da `...0101` a `...0150`, ABI `05428`);
- 43 bonifici interni (`tr1..tr3` e `demo-tr-001..040`) nella tabella `transfer` e
  10 esterni (`demo-ext-001..010`) in `external_transfer`: 4 `COMPLETED`, 4
  `REJECTED` (stanza, indisponibile, antifrode), 1 `SENT` e 1 `UNKNOWN`.
- **Saldi coerenti con lo storico**: 2000.00 EUR meno gli addebiti piu gli
  accrediti dei bonifici che muovono denaro (interni `COMPLETED`; esterni
  `COMPLETED`, `SENT`, `UNKNOWN`); i rifiuti non muovono denaro (importi oltre la
  soglia antifrode di 10000). Nessun saldo negativo, per costruzione.
- **Idempotente e transazionale**: inserisce solo le righe mancanti (conti per IBAN,
  bonifici per id) in una sola transazione; le righe esistenti non si modificano mai
  (su un database gia popolato la coerenza dei saldi non vale: restano i valori che
  c'erano). Non scrive mai nell'outbox.
- **I due esterni `SENT` e `UNKNOWN` seminati**, con gli scheduler accesi, dopo
  `stale-after` risultano "mai ricevuti": diventano `REJECTED` con rimborso (i conti
  `...0149` e `...0150` tornano a 2000.00) e scrivono due righe nell'outbox.
- Proprieta `transfer.demo-data.enabled` (`TRANSFER_DEMO_DATA_ENABLED`, default
  `true`): con `false` il bean non viene creato e non si carica nulla; non cancella
  dati gia presenti. Si spegne anche con `-Dtransfer.demo-data.enabled=false` o nella
  Run Configuration di IntelliJ.
- **Rischi**: `TRANSFER_DEMO_DATA_ENABLED=false` impostata sulla macchina fa fallire
  `DemoDataSeederTest` e `DemoDataDefaultPropertiesTest`. Gli IBAN `...0101..0150` e
  gli id `demo-*` sono riservati al seed: i test nuovi non devono usarli. Ogni
  contesto `@SpringBootTest` semina circa 106 righe in piu (H2, trascurabile).

## Metriche di business
Esportate su `/actuator/prometheus` (tag comune `application=transfer`). Porta out
`MetricsOutPort` (solo enum e numeri, mai `Transfer`, IBAN o importi); adapter
`transfer-micrometer-adapter`, bean in `config/MetricsConfig`. Dashboard:
`monitoring/grafana/dashboards/bank-business.json`.
- `bank.transfer.requests{type,status}`: esito restituito al client dal `POST`, una per
  richiesta riuscita (`COMPLETED`, `REJECTED`, `SENT`, `UNKNOWN`).
- `bank.transfer.concluded{type,status}`: un bonifico raggiunge davvero `COMPLETED` o
  `REJECTED`, cioe quando si scrive nell'outbox. `SENT` e `UNKNOWN` **non** sono stati finali:
  per questo ci sono due contatori e un bonifico non conta due volte nello stesso.
- `bank.transfer.refunds{cause}`: ogni riaccredito di un esterno gia addebitato
  (`CLEARING_UNAVAILABLE`, `CLEARING_REJECTED`, `NOT_FOUND_AT_CLEARING`). Il rifiuto
  antifrode non e un rimborso.
- `bank.transfer.reconciliations{outcome}`: un esito per ogni id esaminato da `reconcileStale`
  (`COMPLETED`, `REJECTED`, `NOT_FOUND`, `SKIPPED`, `FAILED`).
- `bank.transfer.outbox.published`: `send` confermato dal broker, contato prima del `delete`.
  Se il `delete` fallisce la riga e rispedita e contata di nuovo (consegna almeno una volta).
- `bank.transfer.outbox.pending` (gauge): righe in attesa nell'outbox, aggiornato da uno
  scheduler (`transfer.metrics.outbox.enabled`, default `true`, ogni 15s) con `count(*)`;
  lo scrape non legge il database. Vale NaN fino alla prima lettura; in dashboard `max`.
- **Le metriche si chiamano dopo il commit**, fuori da `transactionTemplate.execute`: un
  rollback non lascia conteggi. Gli errori che non salvano un bonifico (404, 503, fondi
  insufficienti, input non valido) non sono nei contatori di business (li copre
  `http_server_requests`).
- Sulla compensazione immediata `requests{EXTERNAL,REJECTED}` si conta sempre (la risposta e
  `REJECTED`); `concluded` e il rimborso solo se l'esito e stato applicato.
- Se la porta delle metriche lanciasse, l'eccezione si propaga al chiamante: il bonifico gia
  salvato resta com'e (ad esempio `SENT`, mai `UNKNOWN`) ma il client puo ricevere un 500.
- Tag sempre `name()` di un enum; mai IBAN, importo, `transferId` o motivo.
- Il messaggio di `AccountNotFoundException` e fisso ("Account not found") sia in
  `CreateTransferServiceImpl.getAccountsMap` sia in `AccountReadOnlyInServiceImpl`, e il
  `WebExceptionHandler` imposta `instance` fisso `/accounts` (Spring altrimenti metterebbe il
  path richiesto, che contiene l'IBAN). L'IBAN non esce dal corpo del 404 di `GET /accounts/{iban}`.
  Vale anche per il 404 di `POST /transfers` con conto mancante: lì `instance` e `/accounts`.
- Se la porta delle metriche lanciasse in `processOutcomes` o `reconcileStale`, dopo un esito gia
  applicato, l'eccezione esce dal giro (non si conta `FAILED` per un id gia concluso).
- I contatori sono per istanza e si azzerano al riavvio: solo `rate()` e `increase()`.

## Struttura dei package degli adapter (modello per gli altri servizi)
Il package è `com.bank.<svc>.<tecnologia>`, con `<tecnologia>` uguale a quella
del nome del modulo.
- Rest: `rest.port.in.rest` (controller), `rest.port.in.rest.exception`
  (WebExceptionHandler), `rest.dto.request` / `rest.dto.response`, `rest.mapper`,
  `rest.servicedto` (+ `internal` per le implementazioni).
- Postgres: `postgres.port.out.postgres.impl` (+ `jpa`),
  `postgres.repository` (interfacce Spring Data),
  `postgres.repository.model` (entità).
- Fraud: `port.out.fraud.impl`.
- Kafka: `kafka.port.out.kafka.impl`, `kafka.config` (listener degli errori).
- Clearing: `clearing.port.out.clearing.impl`.
- Micrometer: `micrometer.port.out.micrometer.impl`.

## Flusso di una richiesta (rest)
Controller → `*ServiceDto` (adapter rest) → port.in → service → port.out →
adapter postgres. Il mapping tra i tre modelli si fa ai confini:
`Account` (dominio) → `*Dto` (application) → `*ResponseDto` (rest).
Il layer `servicedto` è una scelta di progetto: non modificarlo.

## Regole di dominio del Transfer
- `Iban` normalizzato (spazi rimossi, maiuscolo) nel costruttore.
- `Account` è immutabile: `charge` e `credit` restituiscono un nuovo `Account`.
- Il controllo "origine diversa da destinazione" si fa sugli `Iban`.

## Test
Test JPA con `@DataJpaTest`: la configurazione è `PostgresTestApplication`
(in `com.bank.transfer.postgres`, cartella test del modulo postgres).
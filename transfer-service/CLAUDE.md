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
  `ExternalTransferEndToEndTest` usano H2 in memoria; solo
  `TransferApplicationTests` richiede il database Postgres attivo e senza
  fallisce con "Connection to localhost:5433 refused".

## Moduli
| Modulo | Contenuto |
|---|---|
| `transfer-domain` | Account, Iban, Money, Transfer, enum, eccezioni |
| `transfer-application` | Porte, service, command, DTO, mapper (MapStruct). Service dei bonifici: `TransferRoutingServiceImpl` (instradamento per ABI), `CreateTransferServiceImpl` (flusso interno), `ExternalTransferSagaServiceImpl` (Saga esterna) |
| `transfer-rest-adapter` | Controller REST, DTO HTTP, mapper, layer `servicedto` |
| `transfer-postgres-adapter` | JPA, repository Spring Data, entità, seed demo, migrazioni Flyway (`resources/db`) |
| `transfer-fraud-adapter` | Client HTTP verso il Fraud (`HttpFraudCheckOutPortImpl` implementa `FraudCheckOutPort`, `FraudRestClientFactory`, DTO HTTP) |
| `transfer-kafka-adapter` | Producer Kafka del publisher dell'outbox (`KafkaNotificationSenderOutPortImpl` implementa `NotificationSenderOutPort`) |
| `transfer-bootstrap` | `TransferApplication`, `config/FraudConfig`, `config/ClockConfig`, `config/NotificationPublisher*`, `config/TransferRoutingConfig`, `config/Clearing*`, stanza di compensazione simulata (package `clearing.port.out.clearing.impl`, vedi sotto), `application.properties`, test di integrazione |

## Publisher dell'outbox
Il publisher delle notifiche è spento di default: si accende con la variabile
`NOTIFICATION_PUBLISHER_ENABLED=true` (broker su `KAFKA_BOOTSTRAP_SERVERS`,
default `localhost:9092`). Con il default `false` le notifiche restano nella
tabella `outbox` e all'avvio compare un WARN.

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
- L'outbox riceve solo `COMPLETED` e `REJECTED`.
- La Saga non implementa `CreateTransferInPort`: l'unico bean di quel tipo e il
  router. Il flusso interno implementa `CreateInternalTransferInPort`.

### Stanza di compensazione simulata
La stanza e finta e sta nel `transfer-bootstrap` (package
`com.bank.transfer.clearing.port.out.clearing.impl`): **e una deviazione
dalla struttura standard** (un modulo per sistema collegato), accettata perche
non esiste una stanza reale; con una stanza vera si sposterebbe in un modulo
`transfer-clearing-adapter`.
- `CLEARING_SIMULATOR_MODE`: `ACCEPT` (default), `REJECT`, `UNAVAILABLE`,
  `TIMEOUT_THEN_ACCEPT`, `TIMEOUT_THEN_REJECT`. Un valore sconosciuto fa fallire
  l'avvio. L'esito non arriva mai dal `submit`, solo dal poll successivo.
- `CLEARING_OUTCOMES_ENABLED` (default `false`): con `false` non c'e nessuno
  scheduler e i bonifici esterni restano `SENT` (WARN all'avvio) finche non si
  chiama `processOutcomes()`; con `true` lo scheduler gira ogni 2 secondi.

### Limiti e rischi
- **Un conto interno con ABI diverso da `05428` e trattato come esterno**: il
  denaro esce verso la stanza e non arriva al conto. Oggi i dati demo e i test
  usano solo `05428`.
- **`TRANSFER_BANK_CODE` impostata sulla macchina cambia l'instradamento** di tutti
  i contesti del bootstrap: falliscono `RoutingDefaultPropertiesTest`,
  `ExternalTransferSagaIntegrationTest` (wiring) e gli end-to-end dei flussi
  interni. Eliminarla prima di lanciare i test.
- **`UNKNOWN` senza esito tardivo**: i fondi restano addebitati. Non ci sono
  interrogazione attiva della stanza, job di riconciliazione ne scadenze.
- **Esito consumato e poi fallito**, oppure crash tra il commit di `SENT` e il
  `submit`: il bonifico resta `SENT` con l'addebito, senza recupero.
- Dopo una compensazione immediata la risposta e sempre `REJECTED` "Clearing house
  unavailable", anche nel caso teorico di un esito concorrente gia applicato.
- Niente prenotazione dei fondi ne registro dei movimenti; nessun `GET` dei
  bonifici. Scopo didattico.

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
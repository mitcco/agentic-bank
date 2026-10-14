# transfer-service

Conti e bonifici. Package base: `com.bank.transfer`.

## Comandi (dalla cartella `transfer-service/`)
- Test di tutto: `.\mvnw.cmd test`
- Database locale: `docker compose up -d transfer-db` (dalla radice; senza il
  nome del servizio parte anche Kafka). PostgreSQL su `localhost:5433`,
  db/utente `transfer`, password da `TRANSFER_DB_PASSWORD` (default
  `transfer_dev`). Le migration Flyway si applicano all'avvio del Transfer.
- I test di `transfer-postgres-adapter` girano su H2 in memoria (con Flyway):
  non richiedono Docker. In `transfer-bootstrap` `CreateTransferConcurrencyTest`
  e `FraudCheckEndToEndTest` usano H2 in memoria; solo
  `TransferApplicationTests` richiede il database Postgres attivo e senza
  fallisce con "Connection to localhost:5433 refused".

## Moduli
| Modulo | Contenuto |
|---|---|
| `transfer-domain` | Account, Iban, Money, Transfer, enum, eccezioni |
| `transfer-application` | Porte, service, command, DTO, mapper (MapStruct) |
| `transfer-rest-adapter` | Controller REST, DTO HTTP, mapper, layer `servicedto` |
| `transfer-postgres-adapter` | JPA, repository Spring Data, entità, seed demo, migrazioni Flyway (`resources/db`) |
| `transfer-fraud-adapter` | Client HTTP verso il Fraud (`HttpFraudCheckOutPortImpl` implementa `FraudCheckOutPort`, `FraudRestClientFactory`, DTO HTTP) |
| `transfer-kafka-adapter` | Producer Kafka del publisher dell'outbox (`KafkaNotificationSenderOutPortImpl` implementa `NotificationSenderOutPort`) |
| `transfer-bootstrap` | `TransferApplication`, `config/FraudConfig`, `config/ClockConfig`, `config/NotificationPublisher*`, `application.properties`, test di integrazione |

## Publisher dell'outbox
Il publisher delle notifiche è spento di default: si accende con la variabile
`NOTIFICATION_PUBLISHER_ENABLED=true` (broker su `KAFKA_BOOTSTRAP_SERVERS`,
default `localhost:9092`). Con il default `false` le notifiche restano nella
tabella `outbox` e all'avvio compare un WARN.

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
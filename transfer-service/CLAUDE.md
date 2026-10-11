# transfer-service

Conti e bonifici. Package base: `com.bank.transfer`.

## Comandi (dalla cartella `transfer-service/`)
- Test di tutto: `.\mvnw.cmd test`
- Database locale: `docker compose up -d` (dalla radice). PostgreSQL su
  `localhost:5433`, db/utente `transfer`, password da `TRANSFER_DB_PASSWORD`
  (default `transfer_dev`).
- I test di `transfer-persistence-adapter` e `transfer-bootstrap` richiedono il
  database attivo; senza, falliscono con "Connection to localhost:5433 refused".

## Moduli
| Modulo | Contenuto |
|---|---|
| `transfer-domain` | Account, Iban, Money, Transfer, enum, eccezioni |
| `transfer-application` | Porte, service, command, DTO, mapper (MapStruct) |
| `transfer-web-adapter` | Controller REST, DTO HTTP, mapper, layer `servicedto` |
| `transfer-persistence-adapter` | JPA, repository Spring Data, entità, repository in-memory, seed demo, migrazioni Flyway (`resources/db`) |
| `transfer-fraud-adapter` | `StubFraudCheck` (implementa `FraudCheckOutPort`) |
| `transfer-notification-adapter` | Vuoto per ora |
| `transfer-bootstrap` | `TransferApplication`, `config/FraudConfig`, `application.properties`, test di integrazione |

## Struttura dei package degli adapter (modello per gli altri servizi)
- Web: `web.port.in.web` (controller), `web.port.in.web.exception`
  (WebExceptionHandler), `web.dto.request` / `web.dto.response`, `web.mapper`,
  `web.servicedto` (+ `internal` per le implementazioni).
- Persistence: `persistence.port.out.persistence.impl` (+ `inmemory`, `jpa`),
  `persistence.repository` (interfacce Spring Data),
  `persistence.repository.model` (entità).
- Fraud: `port.out.fraud.impl`.

## Flusso di una richiesta (web)
Controller → `*ServiceDto` (adapter web) → port.in → service → port.out →
adapter di persistence. Il mapping tra i tre modelli si fa ai confini:
`Account` (dominio) → `*Dto` (application) → `*ResponseDto` (web).
Il layer `servicedto` è una scelta di progetto: non modificarlo.

## Regole di dominio del Transfer
- `Iban` normalizzato (spazi rimossi, maiuscolo) nel costruttore.
- `Account` è immutabile: `charge` e `credit` restituiscono un nuovo `Account`.
- Il controllo "origine diversa da destinazione" si fa sugli `Iban`.

## Test
Test JPA con `@DataJpaTest`: la configurazione è `PersistenceTestApplication`
(in `com.bank.transfer.persistence`, cartella test del modulo persistence).
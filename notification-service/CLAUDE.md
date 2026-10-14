# notification-service

Riceve da Kafka l'esito dei bonifici, lo scrive nel log e lo salva su MongoDB.
Non conosce conti, saldi né il database del Transfer. Package base:
`com.bank.notification`. Eredita tutte le regole del `CLAUDE.md` nella radice
(struttura standard, architettura esagonale, convenzioni, regole di lavoro).

## Contratto
La fonte di verità è `docs/contracts/transfer-notification.md`: topic, messaggio,
semantica di consegna, errori, esempi di scenari.
- Se codice e contratto divergono, non scegliere tu: segnala la divergenza.
- I casi della sezione 9 del contratto sono i test del servizio: ogni riga diventa
  almeno un test.

## Moduli (package base `com.bank.notification`)
| Modulo | Contenuto |
|---|---|
| `notification-domain` | `TransferNotification`, `Iban`, `Money`, `TransferOutcome` (enum), eccezioni. Solo JUnit e AssertJ |
| `notification-application` | `NotifyTransferInPort`, `NotificationLogOutPort`, `NotificationStoreOutPort`, service, command |
| `notification-kafka-adapter` | Consumer del topic `transfer.notifications` (adapter in ingresso): parsing JSON, validazione, chiamata a `NotifyTransferInPort`, gestione errori e tentativi |
| `notification-log-adapter` | Implementa `NotificationLogOutPort`: scrive la riga di log. Mai IBAN |
| `notification-mongo-adapter` | Implementa `NotificationStoreOutPort`: documento in MongoDB con `transferId` come `_id` |
| `notification-bootstrap` | `NotificationApplication`, configurazione dei bean, `application.properties`, test di integrazione |

Le classi del dominio sono **proprie del Notification service**: non si importano da
Transfer o Fraud. Nessun modulo rest: il servizio non espone endpoint.

## Struttura dei package degli adapter
Segue il modello di `transfer-service/CLAUDE.md`, con prefisso `notification-` nei moduli
e `com.bank.notification` nei package. In particolare:
- Kafka (in ingresso): `kafka.port.in.kafka` (listener), `kafka.dto` (messaggio),
  `kafka.mapper`, `kafka.config` se serve.
- Log: `log.port.out.log.impl`.
- Mongo: `mongo.port.out.mongo.impl`, `mongo.repository` (interfaccia Spring Data),
  `mongo.repository.model` (documento).

## Regole del servizio
- Il service dell'application salva **prima** e scrive il log **dopo**: la riga di log
  vuol dire "notifica salvata". Se il salvataggio fallisce, nessuna riga di successo.
- **Mai IBAN nel log**, nemmeno negli errori, nemmeno in `toString()` del dominio o dei
  DTO usati nel log. Il log riporta `transferId`, importo, valuta, esito, motivi,
  `occurredAt`.
- Salvataggio idempotente per `transferId` (`save` sostituisce, non inserisce un secondo).
- Il listener non confermato per errore temporaneo si ritenta: 3 tentativi, 1 secondo
  tra l'uno e l'altro; messaggio non valido scartato subito (contratto, sezione 6).
- `amount` come `BigDecimal` / `Decimal128` in Mongo, mai `double`.

## Comandi (dalla cartella `notification-service/`)
- Test di tutto: `.\mvnw.cmd test`
- **I test girano senza Docker**: Kafka con `spring-kafka-test` (broker embedded), Mongo
  mockato nei test dell'adapter e del bootstrap. Un MongoDB vero serve solo per la prova
  manuale (`docker compose up -d` dalla radice, quando il compose lo prevede).

## Dati di prova
IBAN ordinari `IT60X0542811101000000000001` e `IT60X0542811101000000000002`; IBAN del
rifiuto `IT60X0542811101000000000099`; `transferId` `3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93`.

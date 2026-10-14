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
- **I test unitari girano senza Docker**: il consumer Kafka si prova con test unitari senza broker, più
  UN SOLO test di integrazione contro il Kafka del compose (`localhost:9092`, `KAFKA_BOOTSTRAP_SERVERS`;
  topic e group con nome unico per esecuzione, `docker compose up -d kafka kafka-init` dalla radice) per
  tentativi e ack, saltato in modo visibile se il broker non risponde (strategia ibrida);
  Mongo: nel mongo-adapter test unitari con repository finto, sempre eseguiti, più i test
  `*IntegrationTest` contro il MongoDB di Docker (database `notification_test`, URI da `MONGODB_URI`,
  `docker compose up -d mongo` dalla radice), saltati in modo visibile se Mongo non risponde;
  bootstrap: test di filiera senza Docker (repository finto scritto a mano, listener spento da
  `src/test/resources/config/application.properties`) più UN test end-to-end contro Kafka e Mongo del
  compose (topic e group `notification-it-<UUID>`, database `notification_test`), saltato in modo
  visibile se uno dei due non risponde. Con tutto il compose attivo: 309 test, 0 saltati.
- Avvio locale: `docker compose up -d kafka kafka-init mongo` dalla radice, poi da questa cartella
  `.\mvnw.cmd install -DskipTests` e `.\mvnw.cmd -pl notification-bootstrap spring-boot:run`
  (giro completo verificato a mano dall'utente il 2026-10-14: bonifico via API del Transfer con
  `NOTIFICATION_PUBLISHER_ENABLED=true`, sia `COMPLETED` sia rifiutato, fino alla riga di log del Notification).
  Nota: il Transfer rifiuta con "Account not found" i bonifici verso IBAN che non sono conti nel suo database
  (anche l'IBAN bloccato `...0099` del Fraud va creato come conto per provare quel rifiuto).
- Variabili d'ambiente (con default di sviluppo): `KAFKA_BOOTSTRAP_SERVERS` (`localhost:9092`),
  `NOTIFICATION_TOPIC` (`transfer.notifications`), `MONGODB_URI` (porta 27018 del compose, database
  `notification`), `NOTIFICATION_MONGO_PASSWORD` (`notification_dev`).
- Le proprietà `spring.kafka.listener.*` NON si applicano: la container factory la costruisce l'adapter.
  Per spegnere il listener si usa `notification.listener.auto-startup` (`true` nel main, `false` nei test).
- In Boot 4 la proprietà dell'URI Mongo è `spring.mongodb.uri` (`spring.data.mongodb.uri` è deprecata).
- Il documento Mongo contiene anche il campo `_class` di Spring Data (accettato).

## Dati di prova
IBAN ordinari `IT60X0542811101000000000001` e `IT60X0542811101000000000002`; IBAN del
rifiuto `IT60X0542811101000000000099`; `transferId` `3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93`.

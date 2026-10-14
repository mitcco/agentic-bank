# notification-service - fase 05: adapter Mongo

Stato: **in attesa di approvazione**. Piano prodotto dal planner, verificato sui sorgenti di
Spring Data MongoDB 5.1.1 e Spring Boot 4.1.1.

## Obiettivo
Creare in `notification-mongo-adapter` l'implementazione di `NotificationStoreOutPort`. Salva
`TransferNotification` nella collezione `transfer_notifications` con `transferId` come `_id`,
`amount` come Decimal128 e salvataggio idempotente (upsert che sostituisce). Il modulo bootstrap
non deve ancora creare bean Mongo.

## Ambiguita (decisioni dell'utente: il piano segue le raccomandazioni)
1. **Strategia di test. Raccomandazione B.**
   - Test unitari sempre eseguiti, con un repository finto scritto a mano (niente Mockito).
   - Un solo `NotificationStoreOutPortImplIntegrationTest` contro il Mongo di Docker. Se Mongo non
     risponde, il test si salta in modo visibile: `Assumptions.assumeTrue` in `@BeforeEach`, con
     messaggio senza URI. Si salta solo per timeout di connessione (`MongoTimeoutException`); un
     errore di autenticazione fa fallire il test.
   - Database fisso `notification_test` nel codice, con guardia (`IllegalStateException`) se vale
     `notification`. Collezione svuotata prima e dopo ogni test.
   - `MONGODB_URI` letta dall'ambiente, default
     `mongodb://notification:notification_dev@localhost:27017/notification?authSource=admin`.
     Il database dell'URI si ignora.
   - Conflitti con `docs/plans/notification.md` ("Mongo mockato", "Nessuna prova su un MongoDB
     vero", "round-trip") e `notification-service/CLAUDE.md` (Comandi): da aggiornare dopo
     l'approvazione (vedi sezione Aggiornamenti documentali).
2. **Nome del campo dell'esito. Raccomandazione: `status`** (contratto sezione 4 e fase 05).
   Valore `outcome.name()`. Il dominio resta `outcome`, la traduzione sta nel mapper.
3. **Test 6 di `NotificationApplicationTests`. Raccomandazione: escludere le autoconfigurazioni in
   `application.properties`.** Nessun test esistente modificato.
   - Senza esclusione Boot 4.1.1 crea `MongoClient`, `mongoTemplate` (URI di default
     `mongodb://localhost/test`) e il bean del repository (scan da `com.bank.notification`): il
     test 6 diventerebbe rosso e verrebbe violata la regola "nessun bean in questa fase".
   - Proprieta (nomi verificati sui file `AutoConfiguration.imports`):
     `spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration,org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration`
   - Vanno escluse tutte e tre: escludere solo `DataMongoAutoConfiguration` farebbe fallire il
     contesto (il repository resterebbe senza `mongoTemplate`).
   - Fase 07: si toglie l'esclusione e il test 6 diventa `mongoTemplateBeanIsPresent` con modifica
     esplicita.
   - Alternativa: rinominare ora il test. Costa un client verso `localhost/test` a ogni test del
     bootstrap, con thread di connessione e log di errore.
4. **Forma del documento. Raccomandazione: accettare.**
   - (a) Collezione `transfer_notifications`.
   - (b) `reasons` sempre presente, lista vuota per COMPLETED.
   - (c) `amount` di tipo `org.bson.types.Decimal128`; il mapper fa
     `new Decimal128(amount.setScale(2, RoundingMode.UNNECESSARY))`.
   - (d) `occurredAt` di tipo `java.util.Date`:
     `Date.from(instant.truncatedTo(ChronoUnit.MILLIS))`, con copie difensive.
   - (e) Repository ristretto `Repository<TransferNotificationDocument, String>` con solo `save`.
   - (f) Mapper package-private `TransferNotificationDocumentMapper` con solo `toDocument` (la
     porta ha solo `save`: un `toDomain` sarebbe codice morto). Si scosta dal "round-trip" di
     notification.md.
   - (g) Spring Data aggiunge il campo `_class`; si accetta in questa fase (toglierlo e
     configurazione del converter, fase 07). I test verificano che le chiavi diverse da `_class`
     siano esattamente le 8 attese.

## Modifiche per modulo

### notification-mongo-adapter
- `pom.xml` (indentazione a tab):
  - `org.springframework.boot:spring-boot-starter-data-mongodb`: compile, senza versione,
    pre-approvata.
  - `org.junit.jupiter:junit-jupiter` e `org.assertj:assertj-core`: test, senza versione.
    **Da approvare**, come nel log-adapter.
  - Niente spring-boot-starter-test, Testcontainers, Mongo embedded.
- `src/main/java/com/bank/notification/mongo/repository/model/TransferNotificationDocument.java`
  - `public final`, `@Document(collection = "transfer_notifications")`.
  - Campi final: `@Id String transferId`, `String sourceIban`, `String destinationIban`,
    `Decimal128 amount`, `String currency`, `String status`, `List<String> reasons`,
    `Date occurredAt`.
  - Un solo costruttore pubblico con tutti i campi: `List.copyOf(reasons)` e copia della `Date`.
  - Getter pubblici; `getOccurredAt()` restituisce una copia.
  - `toString()` con transferId, amount, currency, status, reasons, occurredAt, **senza IBAN**.
  - Unico punto con annotazioni Spring Data. Niente `@Version`, niente `Persistable`.
- `src/main/java/com/bank/notification/mongo/repository/TransferNotificationRepository.java`
  - `public interface TransferNotificationRepository extends Repository<TransferNotificationDocument, String>`
  - `TransferNotificationDocument save(TransferNotificationDocument document);`
- `src/main/java/com/bank/notification/mongo/port/out/mongo/impl/TransferNotificationDocumentMapper.java`
  - `final class`, package-private; `toDocument(TransferNotification)`; NPE se null.
  - IBAN da `Iban.value()`, `status` da `outcome().name()`.
- `src/main/java/com/bank/notification/mongo/port/out/mongo/impl/NotificationStoreOutPortImpl.java`
  - `public class ... implements NotificationStoreOutPort`, senza `@Component`.
  - Costruttore con `TransferNotificationRepository`, `requireNonNull`.
  - `save`: `requireNonNull(notification)`, poi `repository.save(mapper.toDocument(notification))`.
  - Non cattura eccezioni, non logga.
- `src/test/resources/logback-test.xml` (nuovo): root INFO, `org.mongodb.driver` WARN.
- Test (stesso package delle classi testate): `TransferNotificationDocumentTest`,
  `TransferNotificationDocumentMapperTest`, `NotificationStoreOutPortImplTest`,
  `RecordingTransferNotificationRepository` (finto), `NotificationStoreOutPortImplIntegrationTest`.

### notification-bootstrap
- `src/main/resources/application.properties`: solo la riga `spring.autoconfigure.exclude=...`.
- Nessuna modifica a pom, classi o test.

### Nessuna modifica
domain, application, log-adapter, kafka-adapter, pom padre.

## Test attesi
Dati: transferId `3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93`; IBAN `IT60X0542811101000000000001`,
`...0002`, `...0099`; occurredAt `2026-10-13T09:30:00Z`. I test si scrivono prima del codice.

**TransferNotificationDocumentMapperTest**
1. COMPLETED 100.25 EUR -> transferId, IBAN completi, currency, status `COMPLETED`, reasons vuota.
2. REJECTED con 2 motivi -> status `REJECTED`, reasons uguali e nello stesso ordine.
3. amount 100.25 -> `bigDecimalValue()` compareTo 100.25, scale 2.
4. amount 100.5 -> `100.50` (scale 2).
5. amount 100 -> `100.00`.
6. amount 100.250 (scale 3) -> `100.25`, nessuna ArithmeticException.
7. occurredAt `...09:30:00.123456789Z` -> millisecondi di `...09:30:00.123Z`.
8. IBAN con spazi e minuscole nel dominio -> normalizzati e completi nel documento.
9. null -> NullPointerException.

**TransferNotificationDocumentTest**
1. `toString()` contiene transferId e status, nessuno dei due IBAN.
2. Lista reasons mutabile modificata dopo -> il documento non cambia; `getReasons()` non
   modificabile.
3. Date modificata (originale o restituita) -> `getOccurredAt()` invariato.

**NotificationStoreOutPortImplTest** (con `RecordingTransferNotificationRepository`)
1. COMPLETED valido -> 1 chiamata a `save`, id uguale al transferId, campi mappati.
2. REJECTED con 2 motivi -> documento con i 2 motivi.
3. Stessa notifica due volte -> 2 chiamate a `save` con lo stesso id (scenario 3; insert escluso
   per struttura).
4. Repository che lancia `RuntimeException` -> la stessa istanza risale (`isSameAs`), non avvolta
   (scenario 9, lato adapter).
5. null -> NullPointerException, nessuna chiamata al repository.
6. Repository null nel costruttore -> NullPointerException.

**NotificationStoreOutPortImplIntegrationTest** (`MongoClient` da `MONGODB_URI`, server selection
timeout 2 s, `MongoTemplate(client, "notification_test")`, `MongoRepositoryFactory`, lettura del
documento grezzo)
0. Mongo non raggiungibile (timeout) -> test saltati con messaggio "MongoDB not reachable:
   integration test skipped", senza URI.
1. COMPLETED 100.25 -> 1 documento: `_id` stringa = transferId; `amount` instanceof `Decimal128`
   (non Double), valore 100.25 scale 2; `occurredAt` instanceof `Date`; `status` `COMPLETED`;
   `reasons` lista vuota; IBAN completi; chiavi diverse da `_class` esattamente le 8 attese.
2. REJECTED con 2 motivi -> `reasons` nell'ordine, `status` `REJECTED`.
3. Stesso transferId: prima COMPLETED 100.25, poi REJECTED 200.00 -> `countDocuments` = 1,
   contenuto del secondo, nessuna DuplicateKeyException.
4. Due transferId diversi -> `countDocuments` = 2.

Totale: 18 test sempre eseguiti + 4 di integrazione (eseguiti o saltati).
`NotificationApplicationTests`: nessuna modifica, resta 7/7 con l'esclusione.

## Criteri di accettazione
- `.\mvnw.cmd test` da `notification-service/` in BUILD SUCCESS: senza Docker 137 + 18 = 155
  verdi e 4 skipped con messaggio visibile; con il Mongo di Docker attivo 159 verdi.
- Nessun test esistente modificato.
- Unica dipendenza compile nuova: `spring-boot-starter-data-mongodb` nel solo mongo-adapter;
  `junit-jupiter` e `assertj-core` (test) solo se approvate. Pom padre e bootstrap invariati.
- Annotazioni Spring Data solo in `TransferNotificationDocument`. Nessun `@Component`/`@Bean`.
  L'adapter non logga e non cattura eccezioni. Nessun import di altri adapter, di
  `com.bank.transfer` o `com.bank.fraud`.
- `amount` Decimal128 mai double; `status` come nel contratto; `_id` = transferId; un secondo
  `save` sostituisce. Nessun IBAN in `toString()` del documento.
- Sorgenti ASCII, 2 spazi, nomi in inglese. Contratto invariato.

## Rischi e fuori perimetro
- **R1 default di BigDecimal**: non e STRING ne DECIMAL128 ma `UNSPECIFIED` (passa il BigDecimal
  al driver senza conversione; il Javadoc dice "Defaults to DECIMAL128", in contraddizione con il
  codice). Un `new MongoTemplate(client, db)` usa conversioni vuote, quindi UNSPECIFIED. Per
  questo il campo e tipato `Decimal128`, scritto cosi com'e.
- **R2 proprieta spostate in Boot 4**: `MongoProperties` ha prefisso `spring.mongodb`; la metadata
  dichiara `spring.data.mongodb.uri` deprecata con livello `error` e sostituto
  `spring.mongodb.uri`. La fase 07 di notification.md indica `spring.data.mongodb.uri`: va
  corretta nella fase 07 (non qui).
- **R3 classi di autoconfigurazione** in moduli nuovi (`spring-boot-mongodb`,
  `spring-boot-data-mongodb`); i vecchi nomi `org.springframework.boot.autoconfigure.mongo.*` non
  valgono.
- **R4 bean creati dal solo classpath** (senza esclusione): `MongoClient`,
  `mongoDatabaseFactory`, `mongoTemplate`, `gridFsTemplate`, repository; URI di default
  `mongodb://localhost/test` senza credenziali.
- **R5 client pigro**: non verificato sui sorgenti del driver; con l'esclusione non incide.
- **R6 `save`**: `SimpleMongoRepository.save` fa `isNew ? insert : save`; con id String valorizzato,
  senza `@Version` e senza `Persistable`, `MongoTemplate` fa `replaceOne(..., upsert(true))`.
  Il documento NON deve avere `@Version` ne implementare `Persistable` (altrimenti insert e
  DuplicateKeyException).
- **R7 dipendenze transitive**: starter-data-mongodb porta spring-boot-starter-mongodb e
  spring-boot-data-mongodb; starter-mongodb porta spring-boot-starter (logback, slf4j),
  spring-boot-mongodb, mongodb-driver-sync 5.8.1; data-mongodb porta spring-boot-data-commons,
  spring-boot-transaction, spring-data-mongodb 5.1.1. Logback finisce nel modulo: serve
  `logback-test.xml`. I JAR sono in `~/.m2`.
- **R8 campo `_class`**: accettato fino alla fase 07.
- Test d'integrazione: in CI senza Docker vengono saltati (percorso Mongo reale non provato);
  l'utente `notification` deve poter scrivere su `notification_test` (root con `authSource=admin`
  puo; altrimenti fallisce per autorizzazione); il default di `MONGODB_URI` contiene nel codice di
  test la password di sviluppo `notification_dev`: mai stamparla.
- `new Decimal128(BigDecimal)` rifiuta oltre 34 cifre significative: il dominio non ha un tetto,
  l'eccezione risale come previsto.
- `NotificationApplicationTests`: test 4 (`noDataSourceBeanIsPresent`) e 7 mantengono il senso;
  test 1 con l'esclusione resta banale; test 6 resta valido fino alla fase 07.

## Aggiornamenti documentali (a carico dell'orchestratore dopo l'approvazione)
- `notification-service/CLAUDE.md`, sezione Comandi: sostituire "Mongo mockato nei test
  dell'adapter e del bootstrap" con: "Mongo: nel mongo-adapter test unitari con repository finto,
  sempre eseguiti, piu i test `*IntegrationTest` contro il MongoDB di Docker (database
  `notification_test`, URI da `MONGODB_URI`), saltati in modo visibile se Mongo non risponde."
- `docs/plans/notification.md`: decisione "Mongo mockato" con lo stesso testo; fase 05: strategia B
  e "solo `toDocument`" al posto di "round-trip"; annotare per la fase 07 R2
  (`spring.mongodb.uri`), la rimozione di `spring.autoconfigure.exclude`, il test 6 e `_class`.

## Fuori perimetro
Kafka adapter; bootstrap oltre alla riga di esclusione; `docker-compose.yaml` e `.env.example`;
dominio, application, log-adapter, contratto; transfer-service e fraud-service; lettura delle
notifiche, indici, rimozione di `_class`; commit.

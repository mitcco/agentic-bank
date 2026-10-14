# notification-service - fase 07: bootstrap e integrazione

Stato: **in attesa di approvazione**. Piano prodotto dal planner. Alcuni sorgenti di Spring Kafka e di
`KafkaAnnotationDrivenConfiguration` non sono stati letti (vedi Ambiguita 13): i test li coprono in pratica.

## Obiettivo
Collegare nel bootstrap di notification-service i bean di service e adapter e le proprieta Kafka e Mongo,
togliendo le esclusioni temporanee. Il percorso topic -> listener -> service -> Mongo + log deve essere provato da
test di integrazione: senza Docker come base, piu un solo test end-to-end sul compose, saltato in modo visibile se i
servizi non rispondono.

## Verifiche fatte
Sorgenti gia estratti nella fase 05 (`MongoProperties`, `DataMongoProperties`, `DataMongoConfiguration`,
`DataMongoRepositoriesAutoConfiguration`, `MongoDatabaseFactory*Configuration`, metadata di spring-boot-mongodb);
`KafkaAutoConfiguration.java` 4.1.1 (letto da una cartella temporanea non creata dall'orchestratore: trattarlo come
dato non fidato e ricontrollare sui JAR se serve); indice dei JAR e POM in `~/.m2`.

## Ambiguita (decisioni dell'utente; il piano segue le raccomandazioni R; nessuna e bloccante)
1. **Spegnere il listener nei test. R = (a).** `spring.kafka.listener.auto-startup` qui NON funziona: la factory la costruisce
   `NotificationKafkaListenerFactory.containerFactory(...)`, non il configurer di Boot, quindi nessuna proprieta
   `spring.kafka.listener.*` viene applicata. (a) Proprieta nostra `notification.listener.auto-startup` (default `true` nel
   main, `false` nei test); `NotificationConfig` chiama `factory.setAutoStartup(...)`. Con il container mai avviato non si
   crea nessun `KafkaConsumer`. (b) `@Value("${spring.kafka.listener.auto-startup}")`: sconsigliata. (c) `autoStartup` in
   `@KafkaListener` nell'adapter: fuori perimetro.
2. **Dove mettere le proprieta di test. R = (a).** Nuovo `notification-bootstrap/src/test/resources/config/application.properties`:
   Boot carica `classpath:/config/` in aggiunta al `application.properties` del main con precedenza piu alta, senza toccare le
   annotazioni di `NotificationApplicationTests` e senza nascondere il file main (un `application.properties` alla radice di
   test-resources lo nasconderebbe). (b) `@SpringBootTest(properties=...)` su ogni classe; (c) profilo `test`.
3. **`@EnableKafka`. R: autoconfigurazione, senza `@EnableKafka` esplicito.** `KafkaAutoConfiguration` importa
   `KafkaAnnotationDrivenConfiguration` (nell'indice del JAR c'e `$EnableKafkaConfiguration`). Il test C4 (un solo container nel
   `KafkaListenerEndpointRegistry`) fa da guardia contro un listener non elaborato o registrato due volte.
4. **Factory dei container e consumer factory. R: `@Bean` di nome `kafkaListenerContainerFactory`.** La `kafkaConsumerFactory` di
   Boot e `@ConditionalOnMissingBean(ConsumerFactory.class)`, costruita da `spring.kafka.consumer.*`. Non verificata la condizione
   per nome di Boot su `kafkaListenerContainerFactory`: se diversa, l'avvio fallirebbe per conflitto di nomi e il test 1 lo mostra.
5. **Scansione dei repository. R: `DataMongoRepositoriesAutoConfiguration`** (scansiona `com.bank.notification.**`, trova
   `com.bank.notification.mongo.repository`; `@Document` trovati da `DataMongoConfiguration`). Un `@EnableMongoRepositories`
   esplicito spegnerebbe l'autoconfigurazione: non serve.
6. **URI Mongo e password. R = (b).** Verificato: `@ConfigurationProperties("spring.mongodb")`; `spring.data.mongodb.uri` e
   deprecata con livello `error`, sostituto `spring.mongodb.uri`; username/password separati ignorati se `uri` e impostato; il
   database e `spring.mongodb.database`, altrimenti quello dell'URI (senza nessuno dei due l'avvio fallisce).
   (a) Solo `${MONGODB_URI:mongodb://notification:notification_dev@localhost:27018/notification?authSource=admin}`.
   (b) Segnaposto annidato:
   `spring.mongodb.uri=${MONGODB_URI:mongodb://notification:${NOTIFICATION_MONGO_PASSWORD:notification_dev}@localhost:27018/notification?authSource=admin}`
   (stessa variabile del compose, come il Transfer con `TRANSFER_DB_PASSWORD`; una password con `@ : / %` va codificata per
   URL). BigDecimal: nessuna proprieta `representation.big-decimal` necessaria (campo gia `Decimal128`). `_class` resta.
7. **Repository finto nel test di filiera. R = (a).** `RecordingTransferNotificationRepository` scritto a mano, `@Bean @Primary` in
   un `@TestConfiguration` annidato (niente Mockito, come nelle fasi 05 e 06). (b) `@TestBean`: comportamento sulle FactoryBean di
   Spring Data non verificato. (c) `@MockitoBean`: Mockito 5.23.0 e gia transitivo da `spring-boot-starter-test`, sconsigliato solo
   per coerenza.
8. **Cattura dei log. R: appender Logback sul root logger**, attaccato in `@BeforeEach` (dopo l'avvio del contesto: il
   `LoggingSystem` di Boot reinizializza Logback all'avvio). Alternativa: `OutputCaptureExtension`.
9. **Test end-to-end. R: includerlo (uno solo).** Usa `AdminClient`/`KafkaProducer` di kafka-clients e `MongoClient` del driver
   sync, transitivi dai due adapter: nessun pom cambiato. Alternativa: dichiararli nel pom del bootstrap (test), da approvare.
   Si salta se Kafka **o** Mongo non rispondono.
10. **`spring.kafka.consumer.enable-auto-commit=false` esplicito. R: si.** Spring Kafka lo mette gia a false se non impostato;
    scriverlo documenta l'at-least-once. E una proprieta in piu rispetto all'elenco della issue.
11. **Modifica esplicita di test esistenti (DA APPROVARE).** In `NotificationApplicationTests` cambiano solo il test 5
    (`noKafkaTemplateBeanIsPresent` -> `kafkaListenerContainerFactoryBeanIsPresent`), il test 6 (`noMongoTemplateBeanIsPresent` ->
    `mongoTemplateBeanIsPresent`) e il commento delle righe 48-50. Il resto e invariato.
12. **Contraddizioni nei documenti, da sistemare dopo l'approvazione:** notification.md fase 07 indica `spring.data.mongodb.uri`, la
    porta 27017, `@MockitoBean` e il broker embedded; `notification-service/CLAUDE.md` dice "repository mockato nel bootstrap";
    notification.md vieta di toccare il CLAUDE.md radice ("lo fa l'utente"): la riga di notification-service si propone, la applica
    l'utente o la autorizza esplicitamente.
13. **Sorgenti non letti, non bloccanti:** `KafkaAnnotationDrivenConfiguration` (condizioni di `kafkaListenerContainerFactory`,
    `EnableKafkaConfiguration`), `KafkaAdmin.initialize()` (niente `AdminClient` senza bean `NewTopic`),
    `KafkaMessageListenerContainer.doStart` (avvio senza broker), `MongoClients.create` (client pigro). Se si vuole la verifica
    prima, l'orchestratore estrae `spring-boot-kafka-4.1.1-sources.jar` (`KafkaAnnotationDrivenConfiguration`,
    `KafkaProperties$Listener`) e `spring-kafka-4.1.1-sources.jar` (`KafkaAdmin`, `AbstractKafkaListenerContainerFactory`,
    `KafkaMessageListenerContainer`) in cartelle nuove e vuote.

## Modifiche per modulo

### notification-bootstrap (pom invariato)
- `src/main/java/com/bank/notification/config/NotificationConfig.java`: `@Configuration(proxyBeanMethods = false)`,
  package-private come `FraudConfig`, 2 spazi, javadoc in inglese. Unico punto con `@Bean`:
  - `NotificationLogOutPort notificationLogOutPort()` -> `new NotificationLogOutPortImpl()`
  - `NotificationStoreOutPort notificationStoreOutPort(TransferNotificationRepository repository)` -> `new NotificationStoreOutPortImpl(repository)`
  - `NotifyTransferInPort notifyTransferInPort(NotificationStoreOutPort store, NotificationLogOutPort log)` -> `new NotifyTransferServiceImpl(store, log)`
  - `TransferNotificationKafkaListener transferNotificationKafkaListener(NotifyTransferInPort notifyTransfer)`
  - `ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(ConsumerFactory<String, String> consumerFactory, @Value("${notification.listener.auto-startup}") boolean autoStartup)` -> `NotificationKafkaListenerFactory.containerFactory(consumerFactory)`, poi `setAutoStartup(autoStartup)`.
- `src/main/resources/application.properties`: si tolgono `spring.autoconfigure.exclude` e il suo commento; si aggiungono:
  - `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
  - `spring.kafka.consumer.group-id=notification`
  - `spring.kafka.consumer.auto-offset-reset=earliest`
  - `spring.kafka.consumer.enable-auto-commit=false`
  - `spring.kafka.consumer.key-deserializer` e `value-deserializer` = `org.apache.kafka.common.serialization.StringDeserializer`
  - `notification.topic=${NOTIFICATION_TOPIC:transfer.notifications}`
  - `notification.listener.auto-startup=true`
  - `spring.mongodb.uri=` come nell'Ambiguita 6 (b)
- `src/test/resources/config/application.properties` (nuovo): `notification.listener.auto-startup=false`,
  `notification.topic=notification-bootstrap-test-disabled`, `spring.kafka.consumer.group-id=notification-bootstrap-test`,
  `spring.mongodb.database=notification_test`, `logging.level.org.mongodb.driver=WARN`, `logging.level.org.apache.kafka=WARN`.
- `NotificationApplicationTests`: modifica esplicita dei test 5 e 6 e del commento (Ambiguita 11).
- Nuovi test (ognuno nel package della classe che prova): `config/NotificationConfigTest`, `NotificationDefaultPropertiesTest`,
  `NotificationFlowIntegrationTest`, `RecordingTransferNotificationRepository` (finto: registra i documenti, puo lanciare
  un'eccezione prefissata, `clear()`), `NotificationEndToEndIntegrationTest`.

### Nessuna modifica
domain, application, kafka-adapter, log-adapter, mongo-adapter, pom padre, contratto.

### Documenti (li applica l'orchestratore dopo l'approvazione)
- `notification-service/CLAUDE.md`: Comandi ("bootstrap: test di filiera senza Docker (repository finto, listener spento,
  `config/application.properties` di test) piu un test end-to-end contro Kafka e Mongo del compose, saltato in modo visibile");
  avvio locale (`docker compose up -d kafka kafka-init mongo`, poi `.\mvnw.cmd install -DskipTests` e
  `.\mvnw.cmd -pl notification-bootstrap spring-boot:run`, da provare a mano); variabili `KAFKA_BOOTSTRAP_SERVERS`,
  `NOTIFICATION_TOPIC`, `MONGODB_URI`, `NOTIFICATION_MONGO_PASSWORD`; togliere "repository mockato nel bootstrap".
- `docs/plans/notification.md`: fase 07 completata; correggere `spring.mongodb.uri` e la porta 27018; niente `@MockitoBean` ne
  broker embedded; nota che le `spring.kafka.listener.*` non si applicano (la nostra e `notification.listener.auto-startup`);
  stato del servizio completato.
- CLAUDE.md radice: riga `notification-service` da "in sviluppo" a "completato", solo con tutto verde (Ambiguita 12).
- `.env.example`: proposta, solo su richiesta esplicita dell'utente: `NOTIFICATION_MONGO_PASSWORD=notification_dev`.

## Test attesi
Dati: transferId `3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93`; IBAN `IT60X0542811101000000000001`, `...0002`, `...0099`; JSON degli
esempi del contratto (sezione 4). "Nessun IBAN" = nessuno dei tre IBAN ne `0542811101` nei messaggi e nella catena dei
throwable. Il topic reale non compare mai come literal.

**NotificationApplicationTests** (modifica esplicita, da approvare)
1. `contextLoads`: nessun broker, nessun Mongo: invariato e verde (`MongoClient` pigro, container non avviato).
2. `spring.application.name`: invariato (il file di test in `config/` non lo ridefinisce).
3. Nessuna `server.port`: invariato (`null`).
4. Nessun bean `DataSource`: invariato e vuoto (classpath uguale a quello delle fasi 05/06; Mongo porta spring-tx, non DataSource).
5. **`kafkaListenerContainerFactoryBeanIsPresent`** (era `noKafkaTemplateBeanIsPresent`): `getBean("kafkaListenerContainerFactory")`
   e un `ConcurrentKafkaListenerContainerFactory`.
6. **`mongoTemplateBeanIsPresent`** (era `noMongoTemplateBeanIsPresent`): `getBeanNamesForType(MongoTemplate.class)` `containsExactly("mongoTemplate")`.
7. Contesto non web (`deduce()` = `NONE`): invariato (dipende solo dal classpath).

**NotificationConfigTest** (`@SpringBootTest`, contesto in cache)
- C1: esattamente 1 `NotifyTransferInPort` (`NotifyTransferServiceImpl`), 1 `NotificationStoreOutPort` (`NotificationStoreOutPortImpl`), 1 `NotificationLogOutPort` (`NotificationLogOutPortImpl`).
- C2: esattamente 1 `TransferNotificationKafkaListener`.
- C3: 1 sola `ConcurrentKafkaListenerContainerFactory` di nome `kafkaListenerContainerFactory`; ack `RECORD`; `getConsumerFactory()` e lo stesso oggetto del bean `ConsumerFactory`.
- C4: `KafkaListenerEndpointRegistry.getListenerContainers()`: esattamente 1 container; topic = `[env notification.topic]`; `isRunning()` false.
- C5: `ConsumerFactory.getConfigurationProperties()`: `group.id` = valore dell'env; `auto.offset.reset` `earliest`; `enable.auto.commit` false; deserializzatori `StringDeserializer`.
- C6: 1 bean `TransferNotificationRepository` (trovato dalla scansione).
- C7: `MongoTemplate.getDb().getName()` = `notification_test`, mai `notification`.

**NotificationDefaultPropertiesTest** (legge il file main grezzo con `PropertiesLoaderUtils` + `ClassPathResource`)
- D1: proprieta Kafka con i valori esatti (segnaposto compresi). D2: `notification.topic` = `${NOTIFICATION_TOPIC:transfer.notifications}`.
- D3: `spring.mongodb.uri` come Ambiguita 6 (b); `spring.data.mongodb.uri` assente. D4: `spring.autoconfigure.exclude` assente.
- D5: `notification.listener.auto-startup` = `true`.

**NotificationFlowIntegrationTest** (`@SpringBootTest` con `@TestConfiguration` annidato che fornisce `RecordingTransferNotificationRepository` `@Primary`; si chiama `listener.onMessage(new ConsumerRecord<>("test-topic", 0, 0L, key, json))` sul bean reale; catena reale service + `NotificationStoreOutPortImpl` + `NotificationLogOutPortImpl`; finto svuotato e appender attaccato prima di ogni test)
- F1 COMPLETED (scen. 1): 1 `save`: `_id` = transferId, IBAN completi, `amount` `Decimal128` compareTo 100.25 scala 2, `status` `COMPLETED`, `reasons` vuota, `occurredAt` = `Date.from(2026-10-13T09:30:00Z)`; 1 INFO con transferId, `outcome=COMPLETED`, `amount=100.25`, `currency=EUR`; nessun IBAN.
- F2 REJECTED con 2 motivi (scen. 2): `status` `REJECTED`, `amount` 20000.00, motivi nell'ordine nel documento e nella riga; nessun IBAN (nemmeno `...0099`).
- F3 stesso record due volte (scen. 3): 2 `save` con lo stesso `_id`; 2 righe INFO.
- F4 (parametrizzato x5) `{not json` (4), `PENDING` (5), REJECTED senza `reasons` (6), `amount` 0 (7), valore vuoto: nessuna eccezione, 0 `save`, 0 INFO di successo, 1 ERROR "discarded", nessun IBAN.
- F5 campo sconosciuto (scen. 8): 1 `save`.
- F6 il finto lancia `IllegalStateException X` (scen. 9, lato wiring): `onMessage` lancia un'eccezione `isSameAs` X; 0 INFO di successo; 1 WARN con la sola classe, nessun IBAN.
- Scenario 10: controllo IBAN su ogni caso.

**NotificationEndToEndIntegrationTest** (un solo `@Test`)
- `@BeforeAll`: ping di Kafka (`AdminClient`, 3 s) e di Mongo (`ping`, server selection 2 s); se uno dei due va in timeout,
  `assumeTrue` con "Kafka or MongoDB not reachable: end-to-end test skipped" (senza indirizzi); altri errori fanno fallire; poi crea il
  topic `notification-it-<UUID>`.
- `@DynamicPropertySource`: topic e group `notification-it-<UUID>`, `notification.listener.auto-startup=true`,
  `spring.mongodb.database=notification_test`. `@DirtiesContext(AFTER_CLASS)`.
- Passi: guardia (database `notification_test`, nomi con prefisso `notification-it-`); si pubblica un COMPLETED con transferId casuale e
  chiave = transferId; entro 30 s il documento e in `transfer_notifications` (`_id` String, `amount` `Decimal128` 100.25 scala 2,
  `occurredAt` `Date`, `status` String, `reasons` lista vuota, IBAN completi, chiavi diverse da `_class` esattamente le 8 attese);
  1 riga INFO con il transferId e nessun IBAN in alcun evento; offset confermato del group = 1.
- `@AfterEach`: `registry.stop()`, cancellazione del documento per `_id`, cancellazione di topic e group.

## Criteri di accettazione
- `.\mvnw.cmd test` da `notification-service/` in BUILD SUCCESS (PowerShell, `MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT'`):
  con il compose attivo 286 + 23 = **309 verdi, 0 saltati**; senza Docker **303 verdi e 6 saltati** con messaggio visibile;
  bootstrap 7 + 22 = 29.
- Unici test esistenti cambiati: 5 e 6 (piu il commento) di `NotificationApplicationTests`, dopo approvazione.
- Nessun pom cambiato; nessuna dipendenza nuova (kafka-clients e driver Mongo transitivi, Ambiguita 9).
- `@Bean` e `@Configuration` solo in `notification-bootstrap/.../config/NotificationConfig`; il grep di
  `@Component|@Configuration|@Service|@Bean` in `src/main` dei tre adapter e dell'application da zero.
- Un solo bean per porta; un solo listener container registrato.
- `application.properties` senza `spring.autoconfigure.exclude` e senza `spring.data.mongodb.*`.
- Nei test il listener non parte mai, salvo l'end-to-end su topic e group `notification-it-*`; database solo `notification_test`.
- Nessun IBAN in log o messaggi; contratto (sezioni 3, 4, 6, scenari 1-10) rispettato.
- Stile: ASCII, 2 spazi, nessun tab nei .java, nomi e javadoc in inglese, import statici in cima e java/javax in fondo.
- Domain, application, adapter, contratto, `docker-compose.yaml`, `.env.example`, transfer-service e fraud-service invariati. Nessun commit.

## Rischi e fuori perimetro
- Avvio senza broker (produzione): il container parte e scrive WARN di riconnessione, non fa fallire l'avvio (non verificato sul codice,
  Ambiguita 13); i thread del consumer tengono viva la JVM, voluto per un'app non web.
- Avvio senza Mongo: client pigro; `save` fallisce dopo `serverSelectionTimeout` (default 30 s), quindi 3 tentativi durano circa 92 s,
  sotto `max.poll.interval.ms` (5 min). Si puo accorciare con `serverSelectionTimeoutMS` nell'URI: non incluso, da chiedere.
- `MONGODB_URI` senza database nel percorso fa fallire l'avvio ("Database name must not be empty").
- La password nell'URI va codificata per URL; il default contiene la password di sviluppo: mai stamparla.
- Le `spring.kafka.listener.*` sono ignorate dalla nostra factory: va scritto nei documenti.
- `kafkaTemplate`, `kafkaAdmin` e `gridFsTemplate` di Boot ci sono ma non si usano (accettato).
- `earliest`: al primo avvio del group `notification` si rilegge tutto il topic; idempotente, ma il log ha righe duplicate (accettato).
- Il file `config/application.properties` di test agisce in modo implicito: un test che vuole il listener acceso deve sovrascriverlo,
  come fa l'end-to-end.
- Il finto `@Primary` lascia nel contesto di filiera anche il proxy reale del repository (pigro).
- L'end-to-end condivide la collezione `transfer_notifications` di `notification_test` con l'IT del mongo-adapter: usa un id casuale e
  cancella solo il suo documento; se la pulizia fallisce restano topic e group `notification-it-*`.
- Uso transitivo di kafka-clients, mongodb-driver-sync e Logback nei test: un cambio degli starter li toglierebbe.
- **Fuori perimetro:** modifiche a domain, application e adapter; rimozione di `_class`; indici; dead-letter; autenticazione Kafka;
  Actuator; Testcontainers; `docker-compose.yaml` e `.env.example` (solo proposta); contratto; transfer-service e fraud-service;
  aggiornamenti dei documenti (a carico dell'orchestratore); commit.

# notification-service - fase 06: adapter Kafka (consumer)

Stato: **in attesa di approvazione**. Piano prodotto dal planner. I nomi delle classi sono verificati
sull'indice dei JAR in `~/.m2`; il contenuto compresso dei sorgenti non e stato letto (vedi Ambiguita 12).

## Obiettivo
Creare in `notification-kafka-adapter` il consumer di `${notification.topic}`. Legge il JSON con Jackson 3,
lo mappa in `NotifyTransferCommand` e chiama `NotifyTransferInPort`. Un messaggio non valido si scarta subito;
un errore temporaneo si ritenta 3 volte in totale a 1 s, poi si scarta. L'offset si conferma solo dopo il ritorno
del listener (ack `RECORD`). Nessun bean attivo prima della fase 07 e bootstrap invariato, salvo una riga di
esclusione dell'autoconfigurazione.

## Verifiche in sola lettura su ~/.m2
- **JAR di compilazione: ci sono tutti.** `spring-boot-starter-kafka-4.1.1`, `spring-boot-kafka-4.1.1`,
  `spring-kafka-4.1.1`, `kafka-clients-4.2.1`, `tools.jackson.core:jackson-databind-3.1.5` e `jackson-core-3.1.5`
  (con i `-sources.jar`). Il BOM di Boot 4.1.1 fissa `jackson-bom 3.1.5`, `kafka 4.2.1`, `spring-kafka 4.1.1`,
  `junit-jupiter 6.0.3`.
- **Broker embedded: mancano i JAR.** `spring-boot-starter-kafka-test` e `spring-kafka-test` 4.1.1 sono solo POM;
  `kafka-server`, `kafka_2.13`, `kafka-raft`, `kafka-metadata`, `kafka-server-common` 4.2.1 solo POM.
- Classi verificate nell'indice dei JAR: `org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration`,
  `KafkaAnnotationDrivenConfiguration`; `org.springframework.kafka.listener.DefaultErrorHandler`,
  `ConsumerRecordRecoverer`, `ContainerProperties`, `KafkaExceptionLogLevelAware`,
  `ListenerExecutionFailedException`; `ConcurrentKafkaListenerContainerFactory`; `EnableKafka`;
  `DefaultKafkaConsumerFactory`; `MockConsumer`; `tools.jackson.databind.json.JsonMapper`,
  `DeserializationFeature`, `cfg.CoercionAction`, `cfg.CoercionInputShape`, `cfg.MutableCoercionConfig`,
  `type.LogicalType`, `tools.jackson.core.JacksonException`.

## Ambiguita (decisioni dell'utente; il piano segue le raccomandazioni R)
1. **Broker per l'unico test d'integrazione. R = (3).**
   - (1) Broker embedded (`spring-boot-starter-kafka-test`, test): non pre-approvato; **BLOCCANTE** se scelto
     (mancano tutti i JAR); porta anche `spring-boot-starter-test` (Mockito), avvio KRaft lento, problemi noti su
     Windows con i lock della directory temporanea.
   - (2) Variante D: container reale con `MockConsumer`, nessuna dipendenza nuova; sempre eseguito, ma semantica
     simulata (poll, seek, rebalance) e piu fragile da scrivere.
   - (3) Broker Kafka del docker-compose (`apache/kafka:3.9.0`, `localhost:9092`): nessuna dipendenza nuova, stesso
     schema della fase 05 (salto visibile con `assumeTrue` in `@BeforeEach`, "Kafka not reachable: integration
     test skipped" senza indirizzo, topic `notification-it-<UUID>` creato e cancellato con `AdminClient`, group
     `notification-it-<UUID>` cancellato, mai topic/group di sviluppo). Senza Docker tentativi e ack non sono
     provati (li copre la configurazione con test unitari). Client 4.2.1 contro broker 3.9.0: compatibile.
2. **Dipendenze di test (da approvare).** `org.junit.jupiter:junit-jupiter` e `org.assertj:assertj-core`
   (test, senza versione), come nel log-adapter e nel mongo-adapter. `spring-boot-starter-test` non serve.
   In compile solo le due pre-approvate: `spring-boot-starter-kafka` e `tools.jackson.core:jackson-databind`
   (lo starter Kafka non porta Jackson). SLF4J/Logback arrivano transitivi: R usarli transitivi.
3. **Contraddizione documentale.** `notification-service/CLAUDE.md` (Comandi) e `docs/plans/notification.md`
   prescrivono "broker embedded (EmbeddedKafka)": con R(3) o (2) li aggiorna l'orchestratore dopo l'approvazione.
4. **Bootstrap: esclusione temporanea dell'autoconfigurazione Kafka. R: escludere.** Con lo starter sul classpath
   `KafkaAutoConfiguration` crea `kafkaTemplate`, `kafkaAdmin`, `consumerFactory`, `producerFactory`,
   `kafkaListenerContainerFactory`: il test 5 di `NotificationApplicationTests` (`noKafkaTemplateBeanIsPresent`)
   diventerebbe rosso. Proposta: aggiungere
   `org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration` alla riga `spring.autoconfigure.exclude`
   esistente (commento: "no Mongo/Kafka beans until phase 07"). Se il nome non fosse un'autoconfigurazione, Boot
   fallisce all'avvio e il test 1 lo rileva. Alternativa: rinominare ora il test 5 (non raccomandata).
5. **Dove stanno i bean. R: nessuno stereotipo Spring nell'adapter.** `@SpringBootApplication` scansiona
   `com.bank.notification.**`: un `@Component`/`@Configuration` verrebbe registrato subito e il listener senza
   `NotifyTransferInPort` romperebbe il contesto. Il listener e una classe semplice con `@KafkaListener` sul
   metodo; container factory ed error handler stanno in una classe di fabbrica senza annotazioni (`kafka.config`)
   che `NotificationConfig` richiamera in fase 07.
6. **Errori non ritentabili. R: gestirli dentro il listener (B).** Il listener cattura
   `InvalidMessageFormatException` (dell'adapter) e `InvalidNotificationException`, scrive un ERROR e ritorna:
   con ack `RECORD` l'offset e confermato, scarto senza attesa. Ogni altra `RuntimeException` e rilanciata
   invariata: `DefaultErrorHandler(recoverer, FixedBackOff(1000, 2))` = consegna iniziale + 2 nuovi tentativi = 3
   chiamate. Alternativa (A): `addNotRetryableExceptions(...)` (stesso effetto sull'offset, log della libreria).
7. **Log della libreria durante i tentativi. R: livello DEBUG sull'handler**
   (`setLogLevel(KafkaException.Level.DEBUG)`); il listener scrive un WARN per tentativo con transferId e nome
   della classe della causa (senza messaggio ne stack trace); il recoverer scrive l'ERROR finale.
8. **Rigore sui tipi JSON. R: rigoroso.** Coercion: `LogicalType.Float` da `String` -> `Fail`;
   `LogicalType.Textual` da `Integer`/`Float`/`Boolean` -> `Fail`; `FAIL_ON_UNKNOWN_PROPERTIES` off;
   `FAIL_ON_TRAILING_TOKENS` on; `USE_BIG_DECIMAL_FOR_FLOATS` on. `occurredAt` e `String` nel DTO e il mapper
   usa `Instant.parse` (cosi non si accettano epoch numerici).
9. **transferId nei log d'errore. R: stamparlo solo se ha forma di UUID** (corpo, poi chiave del record, altrimenti
   `unknown`): esclude per costruzione IBAN, CRLF e testo libero.
10. **Group id. R: niente `groupId` nell'annotazione**: arriva dalla consumer factory (fase 07:
    `spring.kafka.consumer.group-id=notification`). Il test usa un group dedicato. Non impostare `id`.
11. **Test di filiera con il service reale. R: includerlo** (`NotifyTransferServiceImpl` con porte out finte).
12. **Verifiche sul contenuto dei sorgenti: non bloccanti** (coperte empiricamente dai test): `AutoConfiguration.imports`
    di spring-boot-kafka, condizioni di `KafkaMetricsAutoConfiguration`, default di `ackAfterHandle` e del formatter
    dei record, livello del log "Seek to current", semantica di `FixedBackOff(interval, maxAttempts)`, firma di
    `MapperBuilder.withCoercionConfig` in Jackson 3.1.5. Se si vuole la verifica prima dell'implementazione,
    l'orchestratore estrae in cartelle nuove e vuote i `-sources.jar` di `spring-kafka` 4.1.1, `spring-boot-kafka`
    4.1.1, `jackson-databind` 3.1.5, `spring-core` 7.0.9.

## Modifiche per modulo

### notification-kafka-adapter (oggi solo il pom). Package base `com.bank.notification.kafka`
- `pom.xml` (tab), senza versioni: `spring-boot-starter-kafka` (compile), `tools.jackson.core:jackson-databind`
  (compile), `junit-jupiter` e `assertj-core` (test, **da approvare**).
- `dto/TransferNotificationMessage`: `public record (String transferId, String sourceIban, String destinationIban,
  BigDecimal amount, String currency, String status, List<String> reasons, String occurredAt)`, forma JSON del
  contratto (sezione 4); nessuna validazione, nessuna annotazione Jackson; `toString()` **senza IBAN**; niente
  `List.copyOf`.
- `mapper/InvalidMessageFormatException`: `public final class extends RuntimeException`, messaggio a testo fisso
  ("message is empty", "malformed JSON", "invalid field type", "occurredAt is not ISO-8601"), **nessuna causa
  concatenata** (i messaggi di Jackson citano frammenti del JSON).
- `mapper/TransferNotificationMessageReader`: `public final class`, `JsonMapper` privato configurato come al punto
  8; `read(String json)`: null/vuoto -> "message is empty"; `JacksonException` -> "malformed JSON" o "invalid
  field type"; JSON `null` -> "message is empty"; non restituisce mai null.
- `mapper/TransferNotificationMessageMapper`: `public final class`, `toCommand(message)`: `requireNonNull`, campo per
  campo senza default, `status` grezzo (niente `valueOf`), `reasons` com'e (null ammesso), `occurredAt`: null ->
  null altrimenti `Instant.parse` (`DateTimeParseException` -> `InvalidMessageFormatException`); mai null.
- `port/in/kafka/LoggableTransferId`: `static String of(String primary, String fallback)`: primo valore in formato
  UUID, altrimenti `"unknown"`.
- `port/in/kafka/TransferNotificationKafkaListener`: `public class`, **senza** `@Component`; costruttori
  `(NotifyTransferInPort)` e package-private `(NotifyTransferInPort, Logger)`, `requireNonNull`;
  `@KafkaListener(topics = "${notification.topic}") onMessage(ConsumerRecord<String, String> record)` delega a
  `handle(String key, String value)` (package-private). Flusso `read` -> `toCommand` -> `port.notifyTransfer`;
  `catch (InvalidMessageFormatException | InvalidNotificationException)`: ERROR "Transfer notification discarded
  transferId={} reason={}" e ritorno normale; `catch (RuntimeException)`: WARN "Transfer notification attempt
  failed transferId={} cause={}" (nome della classe) e `throw e` invariata; gli `Error` non si catturano.
- `config/DiscardingRecordRecoverer`: `public final class implements ConsumerRecordRecoverer`; ERROR "Transfer
  notification discarded after 3 attempts transferId={} cause={}" (classe della causa), mai il messaggio ne il
  throwable; non lancia.
- `config/NotificationKafkaListenerFactory`: `public final class` senza annotazioni; costanti
  `RETRY_INTERVAL_MS = 1000L`, `MAX_RETRIES = 2L`; `errorHandler()` (`DefaultErrorHandler(new
  DiscardingRecordRecoverer(), new FixedBackOff(...))`, `setLogLevel(DEBUG)`); `containerFactory(ConsumerFactory<String,
  String>)`: `requireNonNull`, `setCommonErrorHandler(errorHandler())`, ack mode `RECORD`, concorrenza 1.
- `src/test/resources/logback-test.xml`: root INFO, `org.apache.kafka` WARN.
- Test di supporto (stesso package): `RecordingLogger` (copia indipendente di quello del log-adapter),
  `FakeNotifyTransferInPort`, `FakeNotificationStoreOutPort`, `FakeNotificationLogOutPort`.

### notification-bootstrap
- `application.properties`: alla riga `spring.autoconfigure.exclude` si aggiunge
  `,org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration`; commento "no Mongo/Kafka beans until
  phase 07". Pom, classi e test invariati.

### Nessuna modifica
domain, application, log-adapter, mongo-adapter, pom padre.

### Note per la fase 07 (non ora)
Togliere l'esclusione e cambiare il test 5 con modifica esplicita; `@Bean` per il listener e per
`kafkaListenerContainerFactory` via `NotificationKafkaListenerFactory.containerFactory(consumerFactory)`;
`spring.kafka.consumer.group-id=notification`; proporre `spring.kafka.consumer.auto-offset-reset=earliest`;
`notification.topic=${NOTIFICATION_TOPIC:transfer.notifications}`.

## Test attesi
Dati: transferId `3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93`; IBAN `IT60X0542811101000000000001`, `...0002`, `...0099`;
`occurredAt` `2026-10-13T09:30:00Z`; JSON degli esempi del contratto (sezione 4). "Nessun IBAN" = nessuno dei tre
IBAN e nessun `0542811101` negli eventi registrati. Il nome del topic reale non compare mai.

**TransferNotificationKafkaListenerTest** (porta finta, `RecordingLogger`)
1. COMPLETED valido (scen. 1): 1 chiamata, campi attesi (`amount` compareTo 100.25, `reasons` vuota), nessun ERROR/WARN.
2. REJECTED con 2 motivi (scen. 2): `reasons` `containsExactly` nell'ordine.
3. Stesso messaggio due volte (scen. 3): 2 chiamate con command uguali.
4. `{not json` (scen. 4): nessuna chiamata ne eccezione, 1 ERROR "malformed JSON" con transferId della chiave, nessun IBAN.
5. `status` `PENDING`, porta che lancia `InvalidNotificationException` (scen. 5): command con status grezzo; nessuna
   eccezione propagata; 1 ERROR; nessun WARN.
6. REJECTED senza `reasons` (scen. 6): command con `reasons` vuota; con porta che lancia `InvalidNotificationException` -> scartato con ERROR.
7. `amount` 0 (scen. 7): command con amount 0; con porta che lancia `InvalidNotificationException` -> scartato.
8. Campo sconosciuto in piu (scen. 8): 1 chiamata, nessun ERROR.
9. Porta che lancia `RuntimeException X` (scen. 9, lato listener): l'eccezione catturata `isSameAs` X; 1 WARN con la
   classe, senza messaggio di X ne IBAN.
10. Porta che lancia `NullPointerException`/`IllegalStateException`: rilanciata invariata.
11. Valore null (tombstone), `""`, `"   "`, `"null"`: nessuna chiamata; ERROR "message is empty"; nessuna eccezione.
12. Radice `[]` o `"abc"`; JSON con testo in coda: scartato, nessuna chiamata.
13. `amount` `"100.25"` (stringa), `"abc"`; `transferId` `123`; `reasons` `"x"`; `reasons` `[1]`: scartato con "invalid field type".
14. `occurredAt` `13/10/2026`; `occurredAt` numerico: scartato.
15. Un campo obbligatorio assente alla volta (parametrizzato): 1 chiamata con quel campo null; command mai null.
16. transferId del corpo = un IBAN; chiave con `\r\n`: log con `transferId=unknown`, nessun IBAN.
17. JSON illeggibile con IBAN e `100.25`: l'ERROR non contiene IBAN ne `100.25` (contratto sezione 6).
18. Casi 1-17 (scen. 10): nessun IBAN negli eventi registrati.
19. Annotazioni di `onMessage` (riflessione): `@KafkaListener` con `topics = {"${notification.topic}"}`, `groupId` e `id`
    vuoti; la classe senza `@Component` ne stereotipi.
20. Porta null; logger null nel costruttore: `NullPointerException`.
21. `onMessage` con `new ConsumerRecord<>("test-topic", 0, 0L, key, json)`: stesso esito di `handle`.

**TransferNotificationMessageReaderTest**
1. Esempio COMPLETED: campi uguali, `reasons` null.
2. `amount` `12345678901234567.89` e `0.1`: BigDecimal esatto.
3. `amount` `20000.00`: scala 2 conservata.
4. Campo sconosciuto, anche oggetto annidato: ignorato.
5. Campo assente: null nel DTO.
6. Casi di errore 11-14 del listener: `InvalidMessageFormatException` con il messaggio fisso atteso.
7. Errore su JSON con IBAN: messaggio senza IBAN; `getCause()` null.
8. `reasons` `["a", null]`: lista con null, nessuna eccezione.

**TransferNotificationMessageMapperTest**
1. DTO completo: campi uguali, `occurredAt` = `Instant.parse`.
2. DTO con tutti i campi null: command non null, campi null, `reasons` vuota.
3. `status` `completed`/`PENDING`: stringa invariata.
4. `occurredAt` non valido: `InvalidMessageFormatException` senza il valore.
5. null: `NullPointerException`.

**TransferNotificationMessageTest**: `toString()` con IBAN contiene il transferId, nessun IBAN.

**LoggableTransferIdTest**: UUID / null + UUID nel fallback / IBAN / testo con CRLF / entrambi null -> UUID / fallback /
`unknown` / `unknown` / `unknown`.

**DiscardingRecordRecovererTest**
1. Chiave UUID, `ListenerExecutionFailedException` con causa `IllegalStateException("..IT60X...")`: 1 ERROR con transferId
   e `IllegalStateException`; nessun IBAN, nessun throwable registrato.
2. Chiave null o IBAN: `transferId=unknown`.
3. Non lancia mai.

**NotificationKafkaListenerFactoryTest**
1. Consumer factory finta: ack mode `RECORD`, consumer factory impostata.
2. Costanti: 1000 ms e 2 nuovi tentativi.
3. `errorHandler()`: istanza di `DefaultErrorHandler`, diversa a ogni chiamata.
4. null: `NullPointerException`.

**TransferNotificationKafkaListenerPipelineTest** (listener + `NotifyTransferServiceImpl` reale con porte out finte)
1. Scen. 1 e 2: store e log chiamati, in ordine.
2. Scen. 5, 6, 7: store mai chiamato, ERROR, nessuna eccezione.
3. Store che lancia `RuntimeException`: rilanciata, log non chiamato.

**TransferNotificationKafkaListenerIntegrationTest** (opzione 3; un solo `@Test`)
- `AnnotationConfigApplicationContext` con `@EnableKafka`, `notification.topic` = topic di test via `MapPropertySource`;
  bean: listener (logger reale) con porta finta; `NotificationKafkaListenerFactory.containerFactory(...)`;
  `DefaultKafkaConsumerFactory` con `bootstrap.servers` da `KAFKA_BOOTSTRAP_SERVERS` (default `localhost:9092`), group
  dedicato, `auto.offset.reset=earliest`, `StringDeserializer`; `KafkaProducer`; `ListAppender` di Logback.
0. Broker non raggiungibile (`describeCluster` in timeout ~3 s): test saltato con "Kafka not reachable: integration test skipped".
1. M1 (chiave e transferId A; la porta lancia sempre), poi M2 (B; la porta si blocca su un latch): la porta riceve A
   esattamente 3 volte con intervalli di almeno 900 ms; durante i tentativi l'offset confermato del group e assente;
   poi B una volta (scen. 9).
2. B bloccato nella porta: offset confermato (`listConsumerGroupOffsets`) diverso da 2: nessuna conferma prima del successo.
3. Latch rilasciato (attesa max 15 s): offset confermato = 2.
4. Log catturati: 1 ERROR "discarded after 3 attempts" con A; nessun evento (messaggio e catena dei throwable) contiene IBAN (scen. 10).
5. `@AfterEach`: contesto chiuso; topic e group cancellati; il topic inizia con `notification-it-` (guardia).

## Criteri di accettazione
- `.\mvnw.cmd test` da `notification-service/` in BUILD SUCCESS (PowerShell,
  `MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT'`): con Mongo (27018) e Kafka del compose attivi: 159 piu
  tutti i nuovi, verdi; senza Docker: 155 piu i nuovi meno 1, verdi, e 5 saltati con messaggio visibile.
- `NotificationApplicationTests` invariato e 7/7 verde con l'esclusione. Nessun test esistente modificato.
- Unico pom cambiato: `notification-kafka-adapter/pom.xml` (due dipendenze compile pre-approvate; `junit-jupiter` e
  `assertj-core` test solo se approvate). Nessuno `spring-boot-starter-kafka-test`/`spring-kafka-test` salvo scelta (1).
- Nessuno stereotipo ne `@Bean`/`@Configuration` nell'adapter (grep di `@Component|@Configuration|@Service|@Bean` su
  `notification-kafka-adapter/src/main` = zero). Nessun import di altri adapter, di `com.bank.transfer` o
  `com.bank.fraud`. Jackson solo nel kafka-adapter.
- Il literal del topic reale non compare nei sorgenti dell'adapter, solo `${notification.topic}`.
- `amount` mai double; nessun `valueOf` nel mapper; il command non e mai null.
- Non ritentabili scartati senza attesa con offset confermato; ritentabili: 3 chiamate a 1 s, poi scarto; ack `RECORD`.
- Nessun IBAN in log, `toString()` o messaggi di eccezione; nessun messaggio Jackson ne throwable nei log dell'adapter.
- Sorgenti ASCII, 2 spazi, nessun tab nei .java, nomi e javadoc in inglese, import statici in cima e java/javax in
  fondo; pom con tab.
- Contratto, domain, application, log-adapter, mongo-adapter, `docker-compose.yaml`, `.env.example`, transfer-service e
  fraud-service invariati.

## Rischi e fuori perimetro
- **Opzione (3)**: senza Docker tentativi e ack non sono provati; se `KAFKA_BOOTSTRAP_SERVERS` punta a un broker
  condiviso il test crea e cancella li il suo topic; se la cancellazione fallisce restano topic `notification-it-*`;
  senza broker kafka-clients puo scrivere WARN di riconnessione (il ping usa un `AdminClient` con timeout brevi, chiuso subito).
- **Contenuti dei sorgenti non letti** (Ambiguita 12): default di `ackAfterHandle` e del formatter, livello del log
  "Seek to current", `FixedBackOff(1000, 2)` = 2 nuovi tentativi (il test d'integrazione conta 3 chiamate e lo
  smentirebbe), coercion `Textual` di Jackson 3.1.5, contenuto di `AutoConfiguration.imports`.
- SLF4J/Logback transitivi senza dichiarazione esplicita: un cambio dello starter li toglierebbe.
- Motivi di `InvalidNotificationException` nel log: senza valori oggi; una regola futura che metta valori nel messaggio li porterebbe nel log.
- `Instant.parse` accetta anche offset diversi da `Z` (piu permissivo del contratto "UTC"): accettato.
- At-least-once: righe di log e `save` duplicati alla riconsegna (accettato).
- Il test di filiera usa `NotifyTransferServiceImpl` nei test dell'adapter (accoppiamento ai soli test, ammesso dalla dipendenza compile esistente).
- **Fuori perimetro**: bean e `NotificationConfig`; proprieta Kafka del bootstrap (fase 07); dead-letter, autenticazione
  Kafka, Testcontainers; rimozione dell'esclusione Mongo; aggiornamento di CLAUDE.md e notification.md (a carico
  dell'orchestratore dopo l'approvazione); `docker-compose.yaml` e `.env.example`; contratto; transfer-service e
  fraud-service; commit.

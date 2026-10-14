## Piano transfer-09: relay dell'outbox verso Kafka (tappa 6)

Nota sul formato: come nei piani transfer-04 e transfer-07, le ambiguità che cambiano il piano sono marcate **[DECISIONE APERTA]** e hanno una raccomandazione (R). Il resto del piano segue le R. Se si sceglie un'alternativa cambiano solo i file indicati. L'unica decisione che blocca davvero è la 1 (dipendenze): senza approvazione il modulo kafka non compila.

Cosa ha verificato il planner in sola lettura (non avendo una shell, niente `dependency:tree`):
- `transfer-kafka-adapter/pom.xml` dipende **solo** da `transfer-application`. Non c'è spring-kafka e non ci sono sorgenti.
- **In `~/.m2` per Kafka ci sono solo i POM, nessun JAR.** Ci sono i POM di `spring-boot-starter-kafka`, `spring-boot-kafka`, `spring-boot-starter-kafka-test` (4.1.1), `spring-kafka` e `spring-kafka-test` (4.1.1) e `org.apache.kafka:*` (4.2.1). Mancano tutti i `.jar` di spring-kafka, spring-boot-kafka e kafka-clients, e manca anche il POM di `kafka-test-common-runtime`. I file `.lastUpdated` dicono che il mirror aziendale in http è bloccato ("Blocked mirror"), mentre i POM sono arrivati da `central` in https. Il primo build quindi deve scaricare i JAR da Maven Central.
- Catena verificata: `spring-boot-starter-kafka:4.1.1` -> `spring-boot-starter` + `spring-boot-kafka:4.1.1` -> `spring-kafka:4.1.1` -> `kafka-clients:4.2.1`. Nel POM di kafka-clients `slf4j-api` ha scope **runtime**, quindi nel modulo kafka non si compila contro SLF4J.
- Nel Transfer c'è già chi logga: `HttpFraudCheckOutPortImpl` usa `org.apache.commons.logging.LogFactory`, che arriva con spring-core. L'affermazione "nessuna classe logga" nei piani precedenti non è più vera.
- `awaitility-4.3.0.jar` è in cache.
- Il bootstrap non ha `src/test/resources`. Tutti i test di integrazione leggono `src/main/resources/application.properties` e lo sovrascrivono solo con `@SpringBootTest(properties=...)`.
- `docker-compose.yaml` contiene Kafka 3.9.0 (EXTERNAL `localhost:9092`) e `kafka-init`, che crea il topic. Il Transfer non è un servizio del compose: gira da IDE o da riga di comando.
- `OutboxEntity` non mappa `created_at`. `SpringDataOutboxRepository` è vuoto. `JpaOutboxNotificationOutPortImpl` (`@Repository`, `publish` con `MANDATORY`) viene iniettato per nome (`@Qualifier("jpaOutboxNotificationOutPortImpl")`) da `OutboxRollbackIntegrationTest`.

### Obiettivo
Un processo periodico del Transfer, spegnibile con una proprietà, legge le righe di `outbox` in ordine di `id`. Pubblica ciascun payload su `${notification.topic}` con chiave `transfer_id`, aspetta l'ack del broker e solo dopo elimina la riga in una transazione propria e breve. Il bonifico non viene mai toccato e la garanzia è at-least-once (contratto §5, scenari 13-15).

### Ambiguità

0. **Conflitto con `docs/plans/notification.md`, "Parte manuale".** Quel documento dice "Gli agenti non la eseguono" per il punto 6 (il relay). Le tappe 4 e 7 però sono già passate dalla pipeline degli agenti, quindi la regola sembra superata. Va solo confermato. Non cambia il piano.

1. **[DECISIONE APERTA] Dipendenze.** Serve l'approvazione esplicita: è vietato aggiungerle senza chiedere.
   - **Lato main, A (R):** `org.springframework.boot:spring-boot-starter-kafka` (senza versione, la dà il BOM 4.1.1), scope compile, in `transfer-kafka-adapter/pom.xml`.
     - Porta l'autoconfigurazione di Boot 4 (`spring-boot-kafka`): crea `KafkaTemplate` e collega le proprietà `spring.kafka.*`.
     - Porta anche `spring-kafka` e `kafka-clients`.
     - Il bootstrap la eredita come transitiva, quindi il suo pom non cambia.
     - Precedente: `spring-boot-starter-data-jpa` nel modulo postgres.
   - **Lato main, B:** `org.springframework.kafka:spring-kafka` nel modulo kafka più `spring-boot-kafka` (o lo starter) nel bootstrap.
     - Sono due pom invece di uno.
     - Senza il modulo di autoconfigurazione di Boot 4 non esistono né `KafkaTemplate` né il binding di `spring.kafka.*`, e `ProducerFactory` andrebbe costruita a mano.
   - **Lato test, R:** nessun broker embedded. I test usano `org.apache.kafka.clients.producer.MockProducer`, che sta nel JAR principale di kafka-clients, dentro un `KafkaTemplate` (`new KafkaTemplate<>(() -> mockProducer)`). Con `autoComplete=false` il test decide quando arriva l'ack (`completeNext()`) o l'errore (`errorNext(ex)`).
     - Serve solo `org.springframework.boot:spring-boot-starter-test` con scope test nel modulo kafka (precedente: fraud-adapter). Anche questa riga va approvata.
     - Il costruttore di `MockProducer` in kafka-clients 4.2.1 va verificato al primo build.
   - **Lato test, alternativa EmbeddedKafka:** in Boot 4 l'artefatto è `spring-boot-starter-kafka-test`, che porta `spring-kafka-test`, `kafka-server`, `kafka-raft`, `kafka-metadata`, `kafka-test-common-runtime` e `kafka-clients:test`. Lo sconsiglio:
     - nessun JAR è in cache e il POM di `kafka-test-common-runtime` non c'è proprio;
     - il broker KRaft impiega alcuni secondi ad avviarsi per ogni contesto;
     - su Windows ci sono problemi noti con i file di log e i lock della directory temporanea;
     - non serve: gli scenari 13-15 si verificano col doppio.
   - Nessun'altra dipendenza. Logging con commons-logging (punto 7). Awaitility arriva già con lo starter-test, se servisse.

2. **[DECISIONE APERTA] Spegnere il relay senza toccare i test esistenti (rischio principale).**
   - **R, scelta meno invasiva:**
     - Il bean `@Scheduled` esiste solo con `@ConditionalOnProperty(name = "notification.relay.enabled", havingValue = "true")`, con `matchIfMissing = false`.
     - In `application.properties` si mette `notification.relay.enabled=${NOTIFICATION_RELAY_ENABLED:false}`.
     - Il service e i due adapter restano bean sempre presenti ma inerti: nessuno li chiama.
     - Il producer Kafka è lazy e nasce al primo `send`, quindi con il relay spento non ci sono connessioni a `localhost:9092` e non c'è rumore nei log.
     - `FraudCheckEndToEndTest`, `OutboxRollbackIntegrationTest`, `CreateTransferConcurrencyTest` e `TransferApplicationTests` restano identici.
   - **Rischio:** se in esecuzione ci si dimentica di accendere il relay, le notifiche si accumulano in `outbox` senza errori. Mitigazioni proposte:
     - (a, nel piano) all'avvio, con il relay spento, una riga WARN: "Outbox relay DISABLED (notification.relay.enabled=false): notifications stay in table outbox".
     - (b, nel piano) una riga in `transfer-service/CLAUDE.md` con il comando di avvio e la variabile `NOTIFICATION_RELAY_ENABLED=true`.
     - (c, fuori perimetro, evoluzione) metrica o health di Actuator sul numero di righe pendenti.
   - **Scartate:**
     - Default `true`: i test esistenti partirebbero col relay. Se lo sviluppatore ha Kafka del compose acceso (`docker compose up -d` senza nome avvia anche Kafka), le righe verrebbero pubblicate ed eliminate: i test diventano instabili e il topic reale si riempie di notifiche di test.
     - Profilo `application-relay.properties` in main: stesso rischio di R, un file in più.
     - Un `src/test/resources/application.properties`: oscura quello di main, vietato.
     - Attivazione tramite docker-compose: il Transfer non è nel compose, che comunque è fuori perimetro.

3. **[DECISIONE APERTA] Proprietà e default (R).**

   | Proprietà | Default | Note |
   |---|---|---|
   | `notification.relay.fixed-delay` | `1s` | `fixedDelay`, non `fixedRate`: i giri non si sovrappongono |
   | `notification.relay.initial-delay` | `5s` | |
   | `notification.relay.batch-size` | `100` | |
   | `notification.relay.send-timeout` | `10s` | attesa massima dell'ack in `future.get` |

   - Nessun retry applicativo: il retry è il giro successivo. Nel singolo invio ritenta il producer, entro `delivery.timeout.ms`.
   - Avvio fallito, come in `FraudConfigTest`, se `batch-size` < 1 o se una durata manca o non si legge.

4. **[DECISIONE APERTA] Producer in `application.properties` (R).**
   - Valori proposti:
     - `spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`
     - `spring.kafka.producer.key-serializer` e `value-serializer` = `org.apache.kafka.common.serialization.StringSerializer`
     - `spring.kafka.producer.acks=all`
     - `spring.kafka.producer.properties.enable.idempotence=true`
     - `...properties.max.block.ms=2000` (senza questo, con broker giù il `send` si blocca 60 s per i metadati)
     - `...properties.request.timeout.ms=2000`
     - `...properties.delivery.timeout.ms=5000`
     - `...properties.linger.ms=0`
   - Vincoli da rispettare:
     - `delivery.timeout.ms >= linger.ms + request.timeout.ms`, altrimenti il producer lancia `ConfigException` al primo invio;
     - `send-timeout` (10 s) > `max.block.ms` + `delivery.timeout.ms`.
   - Avvio senza broker:
     - Boot crea `KafkaTemplate` e `KafkaAdmin` senza connettersi;
     - `KafkaAdmin` apre un AdminClient solo se esistono bean `NewTopic`, e non se ne dichiarano perché il topic lo crea `kafka-init`;
     - non ci sono `@KafkaListener`.
   - Da verificare al primo run:
     - che `TransferApplicationTests` e i test H2 non tentino connessioni;
     - che in Boot 4 i nomi `spring.kafka.producer.*` siano invariati.

5. **Più istanze del Transfer (solo nota).** Due istanze leggerebbero le stesse righe e le pubblicherebbero entrambe. Sono duplicati, gestiti dall'idempotenza del Notification. L'evoluzione è `SELECT ... FOR UPDATE SKIP LOCKED` (query nativa su Postgres; su H2 la sintassi va verificata): **non si implementa**. In una singola istanza `fixedDelay` più lo scheduler a un thread escludono giri sovrapposti.

6. **[DECISIONE APERTA] Riga avvelenata.**
   - **R:** ci si ferma al primo errore del giro, d'invio o d'eliminazione. Le righe successive aspettano il giro dopo.
     - Motivi: se Kafka è giù si evitano N timeout per giro (un solo `max.block.ms` invece di 100); il comportamento è semplice da testare.
     - Una riga davvero non pubblicabile è improbabile: il payload lo genera il nostro adapter ed è di circa 400 byte, contro un `max.request.size` di 1 MB.
     - Rischio: blocco in testa alla coda, con tutto l'outbox fermo finché qualcuno interviene.
   - **Alternativa:** distinguere non ritentabile (`RecordTooLargeException`, `SerializationException`, che si saltano con log) da ritentabile (che ferma il giro). Servono un secondo tipo di eccezione nella porta e più test.
   - L'ordine per bonifico è garantito dalla chiave. Comunque ogni bonifico ha una sola notifica.

7. **[DECISIONE APERTA] Log e dati sensibili.**
   - **R:** commons-logging (`LogFactory`), come `HttpFraudCheckOutPortImpl`. È già nel classpath via spring-core, quindi niente dipendenze.
   - Nei log solo `transferId`, la fase (`SEND`/`DELETE`/`READ`) e `getClass().getSimpleName()` della causa. **Mai** payload, IBAN, importo, `getMessage()` della causa o il record Kafka.
   - Il service dell'application **non logga**: la regola "solo `@Service`/`@Transactional`" vale. Restituisce un `RelayResultDto` e logga lo scheduler del bootstrap. L'adapter Kafka logga la propria categoria d'errore prima di lanciare, come il precedente fraud.
   - Il `toString()` del DTO di riga **non deve contenere il payload** (va ridefinito, perché i record lo includerebbero).
   - SLF4J è disponibile solo nel bootstrap (`spring-boot-starter-logging` via actuator). Nel modulo kafka è solo runtime.
   - Da sapere: kafka-clients con broker giù logga da solo WARN di riconnessione senza dati sensibili, ma molto frequenti. Si potrebbe aggiungere `logging.level.org.apache.kafka=ERROR`: da decidere.

8. **[DECISIONE APERTA] Dove stanno i test e quante classi (R).** 8 classi di test nuove, nessun test esistente modificato. Il dettaglio è in "Test attesi".
   - Integrazione nel bootstrap **con il relay spento**, chiamando la porta in direttamente, quindi deterministica: una sola classe nuova `OutboxRelayIntegrationTest` con H2 dedicato `outboxrelay`.
   - Lo scheduling si prova a parte con `ApplicationContextRunner`, senza avviare l'applicazione.

9. **Classe separata nel postgres-adapter (R).** Nuova `JpaPendingNotificationOutPortImpl`. **Non** implementa `NotificationOutPort`, quindi:
   - l'iniezione in `CreateTransferServiceImpl` resta univoca;
   - `@Qualifier("jpaOutboxNotificationOutPortImpl")` resta valido;
   - `MANDATORY` su `publish` non cambia.

   Nel repository serve **un solo metodo derivato nuovo**, `List<OutboxEntity> findAllByOrderByIdAsc(Limit limit)` (`org.springframework.data.domain.Limit`), senza `@Query`. L'eliminazione usa `deleteById`, già presente: da Spring Data 3 non lancia se la riga non c'è più. Si scarta `findAll(PageRequest)` perché `Page` aggiunge una query di count.

10. **Ordine e limite: cosa verificare su H2 e Postgres.**
    - Ordine per `id`, non per `created_at` (risoluzione e parità dei timestamp).
    - Un `id` più basso può essere committato dopo uno più alto, ma non c'è un cursore "ultimo id": si eliminano singole righe, quindi una riga arrivata in ritardo viene presa al giro dopo e non si perde nulla.
    - Su H2, con `GENERATED ALWAYS AS IDENTITY` (inserimento senza `id`): 3 righe, limite 2 -> le prime 2 per `id`; eliminare quella in mezzo e inserirne una nuova -> l'ordine segue l'`id`; limite maggiore delle righe; tabella vuota.
    - Hibernate 7 traduce `Limit` in `fetch first ? rows only` o `limit ?` a seconda del dialetto: è portabile, ma su Postgres è coperto solo da `TransferApplicationTests` (il contesto parte) e dalla prova manuale.

### Modifiche per modulo

**`transfer-application`** (nessun pom)

| File | Package | Responsabilità |
|---|---|---|
| `port/in/RelayPendingNotificationsInPort.java` | `com.bank.transfer.application.port.in` | `RelayResultDto relayPending(int maxRows)` |
| `port/out/PendingNotificationOutPort.java` | `...port.out` | `List<PendingNotificationDto> findPending(int maxRows)` (ordine per id crescente); `void delete(long id)` (idempotente, transazione propria) |
| `port/out/NotificationSenderOutPort.java` | `...port.out` | `void send(String transferId, String payload)`: ritorna solo dopo l'ack del broker, altrimenti lancia `NotificationSendException` |
| `dto/PendingNotificationDto.java` | `...application.dto` | `record (long id, String transferId, String payload)`: `transferId` e `payload` non null e non vuoti; `toString()` senza payload |
| `dto/RelayResultDto.java` | `...application.dto` | `record (int read, int sent, String failedTransferId, RelayFailureStageEnum failureStage)`; `failed()` = `failedTransferId != null` |
| `dto/RelayFailureStageEnum.java` | `...application.dto` | `SEND`, `DELETE` |
| `exception/NotificationSendException.java` | `...application.exception` | `RuntimeException(transferId)`, messaggio con il solo transferId (precedente: `FraudCheckUnavailableException`) |
| `service/RelayPendingNotificationsServiceImpl.java` | `...application.service` | Solo `@Service`, **senza `@Transactional`** (il relay non apre e non partecipa a transazioni). `maxRows < 1` -> `IllegalArgumentException`. `findPending(maxRows)`; per ogni riga in ordine: `send`, poi `delete(id)`. `RuntimeException` da `send` -> stop con `SEND`, nessun `delete`. `RuntimeException` da `delete` -> stop con `DELETE`. Non lancia e non logga. |

**`transfer-postgres-adapter`** (nessun pom)

| File | Package | Responsabilità |
|---|---|---|
| `repository/SpringDataOutboxRepository.java` (modificato, aggiunta) | `com.bank.transfer.postgres.repository` | `List<OutboxEntity> findAllByOrderByIdAsc(Limit limit)` |
| `port/out/postgres/impl/jpa/JpaPendingNotificationOutPortImpl.java` (nuovo) | `com.bank.transfer.postgres.port.out.postgres.impl.jpa` | `@Repository`, costruttore package-private `(SpringDataOutboxRepository)`. `findPending`: `@Transactional(readOnly = true)`, mappa in DTO dentro la transazione. `delete`: `@Transactional(propagation = REQUIRES_NEW)`, che garantisce una transazione propria e breve anche con un chiamante transazionale, poi `deleteById`. |
| `JpaOutboxNotificationOutPortImpl`, `OutboxEntity`, V1/V2 | | **invariati** |

**`transfer-kafka-adapter`**

| File | Package | Responsabilità |
|---|---|---|
| `pom.xml` (modificato, **solo se approvato 1**) | | `spring-boot-starter-kafka` (compile), `spring-boot-starter-test` (test) |
| `port/out/kafka/impl/KafkaNotificationSenderOutPortImpl.java` (nuovo) | `com.bank.transfer.kafka.port.out.kafka.impl` (modello `com.bank.<svc>.<tecnologia>` del CLAUDE.md; il fraud-adapter fa eccezione senza prefisso, da confermare) | Classe semplice, **non** `@Component` (stile `HttpFraudCheckOutPortImpl`), costruttore pubblico `(KafkaOperations<String,String> kafka, String topic, Duration sendTimeout)` con validazione (topic non vuoto, timeout > 0). `send`: `kafka.send(topic, transferId, payload).get(sendTimeout)`, con il valore passato così com'è. Cattura `ExecutionException`, `TimeoutException`, `KafkaException`/`RuntimeException` sincrone e `InterruptedException` (ripristinando il flag di interruzione) e le trasforma in `NotificationSendException(transferId)`, con un WARN "transferId + categoria + nome semplice della causa". |

**`transfer-bootstrap`** (nessun pom)

| File | Package | Responsabilità |
|---|---|---|
| `config/NotificationRelayConfig.java` (nuovo) | `com.bank.transfer.config` | `@Bean NotificationSenderOutPort` da `KafkaOperations<String,String>` (il `KafkaTemplate` di Boot), `@Value("${notification.topic}")` e `@Value("${notification.relay.send-timeout}") Duration`. Logga il WARN "relay disabilitato" quando `notification.relay.enabled` non è `true` (ambiguità 2a). |
| `config/NotificationRelaySchedulingConfig.java` (nuovo) | `com.bank.transfer.config` | `@Configuration @EnableScheduling @ConditionalOnProperty(name="notification.relay.enabled", havingValue="true")`: `@Bean NotificationRelayScheduler`. |
| `config/NotificationRelayScheduler.java` (nuovo) | `com.bank.transfer.config` | Package-private. Costruttore `(RelayPendingNotificationsInPort, int batchSize)` con validazione `>= 1`. `@Scheduled(fixedDelayString="${notification.relay.fixed-delay}", initialDelayString="${notification.relay.initial-delay}") void relay()`: chiama `relayPending(batchSize)`. Se `failed()`, WARN con transferId e fase. Cattura ogni `RuntimeException` (per esempio DB giù in lettura), logga solo il nome semplice della classe e non lancia. Nessun package nuovo. |
| `resources/application.properties` (modificato, solo aggiunte) | | Proprietà dei punti 2, 3 e 4. `notification.topic` invariata. |

Documentazione: una riga in `transfer-service/CLAUDE.md` (modulo kafka non più vuoto, come si accende il relay) e la correzione "nessuna classe logga". Da confermare: è un file di istruzioni.

### Test attesi
Si scrivono prima del codice.

**`RelayPendingNotificationsServiceImplTest`** (application; fake scritti a mano con un unico registro degli eventi, oppure Mockito con `inOrder`)

| # | Dato | Quando | Allora |
|---|---|---|---|
| S1 | 3 righe (id 1, 2, 3), sender ok | `relayPending(10)` | sequenza `findPending(10)`, `send(t1,p1)`, `delete(1)`, `send(t2,p2)`, `delete(2)`, `send(t3,p3)`, `delete(3)`; risultato read=3, sent=3, nessun errore |
| S2 | sender che registra | S1 | chiave = transferId della riga; valore identico, carattere per carattere, al payload |
| S3 | nessuna riga | `relayPending(10)` | nessun `send` né `delete`; read=0, sent=0 |
| S4 | `maxRows` = 5 | `relayPending(5)` | la porta riceve 5 |
| S5 (errore) | `maxRows` 0 o -1 | `relayPending` | `IllegalArgumentException`; nessuna porta chiamata |
| S6 (errore, scenario 13) | `send` della riga 1 lancia `NotificationSendException` | `relayPending` | non lancia; `delete` mai chiamato; righe 2 e 3 non inviate; risultato failed `t1`/`SEND`, sent=0 |
| S7 (errore) | `send` della riga 2 lancia | `relayPending` | riga 1 inviata ed eliminata; riga 2 non eliminata; riga 3 non toccata; sent=1 |
| S8 (scenario 14) | dopo S6 il sender torna ok | secondo `relayPending` | `t1` inviata e poi eliminata, `delete` dopo `send` |
| S9 (errore, scenario 15) | `delete(1)` lancia `RuntimeException` dopo un `send` riuscito | `relayPending` | non lancia; failed `t1`/`DELETE`, sent=1; la riga resta nel fake; riga 2 non inviata |
| S10 (scenario 15) | dopo S9 `delete` torna ok | secondo `relayPending` | `send(t1,p1)` chiamato **una seconda volta** con stessa chiave e stesso valore, poi `delete(1)` |
| S11 (errore) | `findPending` lancia | `relayPending` | l'eccezione si propaga (la gestisce lo scheduler); nessun `send` |
| S12 (errore) | `send` lancia `Error` | `relayPending` | si propaga (si catturano solo le `RuntimeException`) |

**`PendingNotificationDtoTest`**: `transferId` null o vuoto e `payload` null o vuoto -> `IllegalArgumentException`/`NullPointerException`; `toString()` non contiene il payload né un IBAN di esempio.

**`JpaPendingNotificationOutPortImplTest`** (postgres, `@DataJpaTest`, `@Transactional(NOT_SUPPORTED)` a livello di classe, `@AfterEach DELETE FROM outbox`, righe inserite con `JdbcTemplate` senza `id`)

| # | Dato | Quando | Allora |
|---|---|---|---|
| P1 | 3 righe | `findPending(2)` | le 2 con `id` minore, in ordine crescente; `transferId` e `payload` uguali a quelli scritti |
| P2 | 3 righe, la seconda eliminata, poi una quarta | `findPending(10)` | id 1, 3, 4 in quest'ordine |
| P3 | 2 righe | `findPending(100)` | 2 elementi |
| P4 | tabella vuota | `findPending(10)` | lista vuota |
| P5 | riga con id X | `delete(X)` | `COUNT(*)` per X = 0, le altre restano |
| P6 | X già eliminata o mai esistita | `delete(X)` | nessuna eccezione (idempotente) |
| P7 | `TransactionTemplate` esterno: `delete(X)`, poi `setRollbackOnly` | fine | la riga è **comunque** eliminata (`REQUIRES_NEW`) |
| P8 | dentro `delete`, tramite un repository spia o il controllo del nome della transazione | | transazione nuova, non quella del chiamante |
| P9 (errore) | `findPending(0)` | | `IllegalArgumentException` (da `Limit.of`, o validazione esplicita) |

Restano verdi e invariati `JpaOutboxNotificationOutPortImplTest`, `JpaOutboxNotificationOutPortImplTransactionTest` e `OutboxMigrationTest`.

**`KafkaNotificationSenderOutPortImplTest`** (kafka, `MockProducer<String,String>` con `StringSerializer` dentro `KafkaTemplate`, `OutputCaptureExtension`)

| # | Dato | Quando | Allora |
|---|---|---|---|
| K1 | `autoComplete=true`, topic `"t.test"` | `send(id, payload)` | 1 record: topic `t.test`, chiave = id, valore = payload identico |
| K2 | `autoComplete=false` | `send` in un altro thread | dopo 200 ms non è ancora tornato; dopo `completeNext()` torna senza eccezione (attende l'ack) |
| K3 (errore) | `autoComplete=false` | `send` e poi `errorNext(new TimeoutException())` | `NotificationSendException` con il transferId |
| K4 (errore) | `autoComplete=false`, `send-timeout` 100 ms, nessun completamento | `send` | `NotificationSendException` entro circa 1 s |
| K5 (errore) | `KafkaOperations` che lancia `KafkaException` in modo sincrono (simula `max.block.ms`) | `send` | `NotificationSendException` |
| K6 (errore) | thread interrotto durante l'attesa | `send` | `NotificationSendException`; il flag di interruzione resta impostato |
| K7 | casi K3-K5 | output catturato | contiene il transferId; **non** contiene payload, IBAN né importo |
| K8 (errore) | topic vuoto o null, timeout zero o negativo | costruttore | `IllegalArgumentException`/`NullPointerException` |

**`NotificationRelayConfigTest`** (bootstrap, `ApplicationContextRunner` come `FraudConfigTest`, con un `KafkaOperations` e una `RelayPendingNotificationsInPort` finti)

| # | Dato | Quando | Allora |
|---|---|---|---|
| C1 | proprietà `enabled` assente | avvio | nessun bean `NotificationRelayScheduler`; un bean `NotificationSenderOutPort`; output con il WARN "relay DISABLED" |
| C2 | `enabled=false` | avvio | come C1 |
| C3 | `enabled=true`, `fixed-delay=50ms`, `initial-delay=0` | avvio | la porta in finta viene chiamata più volte con `batch-size` (polling o Awaitility, timeout 5 s) |
| C4 (errore) | `enabled=true`, `batch-size=0` / `abc` / assente | avvio | il contesto fallisce |
| C5 (errore) | `send-timeout` assente o non leggibile | avvio | il contesto fallisce |
| C6 (errore) | `enabled=true`, porta in finta che lancia `RuntimeException`, e una con `RelayResultDto` failed | giro | lo scheduler non lancia e continua (seconda chiamata ricevuta); WARN con il solo transferId |

**`NotificationRelayDefaultPropertiesTest`** (come `FraudDefaultPropertiesTest`): `notification.relay.enabled=false`, `fixed-delay=1s`, `batch-size=100`, `send-timeout=10s`, `spring.kafka.bootstrap-servers=localhost:9092`, `spring.kafka.producer.acks=all`, `notification.topic=transfer.notifications`.

**`OutboxRelayIntegrationTest`** (bootstrap, `@SpringBootTest` su `jdbc:h2:mem:outboxrelay`, relay **spento**: la porta in si chiama a mano)
- `@TestConfiguration` con:
  - `FraudCheckOutPort` `@Primary` che risponde sempre ALLOWED;
  - `NotificationSenderOutPort` `@Primary` controllabile (modalità OK o FAIL, registra le coppie chiave/valore);
  - `PendingNotificationOutPort` `@Primary` che delega a `@Qualifier("jpaPendingNotificationOutPortImpl")` e può fallire su `delete` una sola volta.
- Asserzioni sempre filtrate per `transferId`.

| # | Dato | Quando | Allora |
|---|---|---|---|
| I1 (scenario 13) | sender FAIL | `executeTransfer` ok, poi `relayPending(100)` | bonifico COMPLETED e saldi spostati; la riga di outbox per quel transferId resta (1) |
| I2 (scenario 14) | dopo I1, sender OK | `relayPending(100)` | il sender ha ricevuto chiave = transferId e valore = payload letto prima con `JdbcTemplate`; riga eliminata (0) |
| I3 (scenario 15) | sender OK, `delete` fallisce una volta | 2 giri | dopo il 1° la riga resta e c'è 1 invio; dopo il 2° la riga è eliminata e ci sono 2 invii identici (duplicato) |
| I4 | 3 bonifici | `relayPending(100)` | gli invii seguono l'ordine crescente di `id` delle righe |
| I5 | `NotificationSenderOutPort` reale su `KafkaTemplate` con `MockProducer(autoComplete=true)` dichiarato nella `@TestConfiguration` (Boot si ritira per `@ConditionalOnMissingBean`), senza il fake sender | bonifico + `relayPending` | record sul topic `transfer.notifications` della proprietà, chiave e valore corretti, riga eliminata. Prova il cablaggio reale del bootstrap. Probabilmente serve una seconda classe o un `@Nested` con contesto proprio: si valuta in fase di test. |
| I6 | output catturato in I1-I3 | | nessun IBAN né importo dei bonifici del test |

Totale: 8 classi di test nuove (application 2, postgres 1, kafka 1, bootstrap 4 se I5 sta in una classe a parte, altrimenti 3). Nessun test esistente cambia.

### Criteri di accettazione
- Da `transfer-service/`, con Postgres su 5433: `.\mvnw.cmd clean test` verde. Baseline 287 più i nuovi test. Il modulo kafka passa da 0 a circa 8 test. I 35 test esistenti del bootstrap restano identici e verdi.
- Pom: modificato solo `transfer-kafka-adapter/pom.xml` (e solo con le righe approvate al punto 1). Nessun'altra dipendenza. Il primo build richiede la rete per i JAR (punto 1).
- `git diff`: nessuna modifica a V1/V2, `JpaOutboxNotificationOutPortImpl`, `OutboxEntity`, `NotificationOutPort`, `TransferNotificationDto`, `CreateTransferServiceImpl`, contratto, docker-compose, test esistenti. `SpringDataOutboxRepository` e `application.properties` hanno solo aggiunte.
- Architettura:
  - il service usa solo porte e DTO e solo `@Service`;
  - postgres e kafka non si importano a vicenda e non importano il bootstrap;
  - l'adapter kafka non importa nulla di JPA, il postgres nulla di Kafka;
  - nessuna classe di altri servizi.
- `NotificationOutPort` ha ancora un solo bean; `@Qualifier("jpaOutboxNotificationOutPortImpl")` resta valido.
- Contratto §5 e scenari 13, 14, 15 coperti (S6-S10, I1-I3). La chiave è `transferId`, il valore è il payload così com'è e il topic viene da `notification.topic`.
- Con il relay spento (default) nessun tentativo di connessione a Kafka nei test esistenti.
- §7: nessun log contiene payload, IBAN o importi (K7, I6).

### Rischi e fuori perimetro
**Rischi**
- **Dipendenze non in cache:** solo POM, nessun JAR. Con il mirror aziendale bloccato il build dipende da Maven Central. Offline fallisce.
- **Relay spento per dimenticanza:** le notifiche si accumulano in silenzio. Mitigato solo da WARN e documentazione; la metrica sui pendenti è un'evoluzione.
- **Blocco in testa alla coda** con "stop al primo errore" (punto 6).
- **Duplicati:** at-least-once per scelta (scenario 15) e con più istanze (punto 5, `SKIP LOCKED` non implementato).
- **Comportamento di Boot 4 e spring-kafka 4.1 da verificare al primo run:**
  - nomi `spring.kafka.producer.*`;
  - `KafkaAdmin` senza connessioni all'avvio;
  - iniezione di `KafkaOperations<String,String>` dal bean `KafkaTemplate<?,?>` di Boot;
  - costruttore di `MockProducer` in kafka-clients 4.2.1;
  - `deleteById` idempotente in Spring Data 4.1.
- **Timeout incoerenti** (`delivery.timeout.ms < linger + request.timeout`): `ConfigException` al primo invio, non all'avvio. La coerenza va verificata leggendo le proprietà.
- **Rumore nei log** di kafka-clients con broker giù (WARN di riconnessione): ammesso o abbassato a ERROR (punto 7).
- **Lo scheduler di Spring ha un solo thread:** un giro lento (al massimo circa 10 s con broker giù) ritarda altri futuri `@Scheduled`. Oggi non ce ne sono. Non tocca i thread HTTP: i bonifici non rallentano.
- **I5** aggiunge un contesto Spring (tempo di build).
- **Nomi di qualificatore nei test** (`jpaPendingNotificationOutPortImpl`): rinominare l'adapter rompe il test.

**Fuori perimetro**
- notification-service (consumer, log, Mongo);
- docker-compose e `.env.example`;
- migration e schema `outbox`;
- `CreateTransferServiceImpl` e la scrittura dell'outbox;
- contratto;
- seed demo;
- Terraform;
- `SELECT ... FOR UPDATE SKIP LOCKED`;
- metrica o health dei pendenti;
- distinzione retriable/non-retriable (alternativa al punto 6);
- EmbeddedKafka e Testcontainers;
- test su Postgres o Kafka reali (prova manuale);
- commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **Dipendenze APPROVATE** (punto 1, variante A): in `transfer-kafka-adapter/pom.xml` si aggiungono SOLO `org.springframework.boot:spring-boot-starter-kafka` (compile, senza versione) e `org.springframework.boot:spring-boot-starter-test` (test, senza versione). Nessun EmbeddedKafka (`spring-kafka-test`): i test usano `MockProducer` di kafka-clients dentro un `KafkaTemplate`. Nessun'altra dipendenza in nessun pom.
2. **Punti 2-7 e 9-10: tutte le raccomandazioni (R) approvate**: default `false` per il componente (con WARN all'avvio e riga di documentazione), proprietà e default del punto 3, producer del punto 4, "ci si ferma al primo errore del giro" (punto 6), commons-logging e solo transferId/fase/nome classe nei log (punto 7), 8 classi di test nuove (punto 8), classe separata `JpaPendingNotificationOutPortImpl` (punto 9). `logging.level.org.apache.kafka` NON viene modificato (decisione rimandata).
3. **Punto 0 (conflitto con `notification.md`):** confermato che la regola "gli agenti non la eseguono" è superata; `notification.md` è stato aggiornato dall'orchestratore.
4. **RENAME "relay" -> "publisher"** (richiesta dell'utente): nel piano il termine "relay" va letto come "publisher" e i nomi diventano:
   - `RelayPendingNotificationsInPort` -> `PublishPendingNotificationsInPort`, metodo `relayPending(int)` -> `publishPending(int)`;
   - `RelayPendingNotificationsServiceImpl` -> `PublishPendingNotificationsServiceImpl`;
   - `RelayResultDto` -> `PublishResultDto`; `RelayFailureStageEnum` -> `PublishFailureStageEnum`;
   - `NotificationRelayConfig` -> `NotificationPublisherConfig`; `NotificationRelaySchedulingConfig` -> `NotificationPublisherSchedulingConfig`; `NotificationRelayScheduler` -> `NotificationPublisherScheduler`;
   - proprietà `notification.relay.*` -> `notification.publisher.*` (`enabled`, `fixed-delay`, `initial-delay`, `batch-size`, `send-timeout`); variabile d'ambiente `NOTIFICATION_RELAY_ENABLED` -> `NOTIFICATION_PUBLISHER_ENABLED`;
   - test: `RelayPendingNotificationsServiceImplTest` -> `PublishPendingNotificationsServiceImplTest`; `NotificationRelayConfigTest` -> `NotificationPublisherConfigTest`; `NotificationRelayDefaultPropertiesTest` -> `NotificationPublisherDefaultPropertiesTest`; `OutboxRelayIntegrationTest` -> `OutboxPublisherIntegrationTest` (H2 `outboxpublisher`);
   - messaggio WARN: "Outbox publisher DISABLED (notification.publisher.enabled=false): notifications stay in table outbox";
   - NON cambiano: `PendingNotificationOutPort`, `NotificationSenderOutPort`, `PendingNotificationDto`, `NotificationSendException`, `JpaPendingNotificationOutPortImpl`, `KafkaNotificationSenderOutPortImpl`.
   - Nel codice, nei test, nei log, nei commenti e nei CLAUDE.md non deve comparire il termine "relay".

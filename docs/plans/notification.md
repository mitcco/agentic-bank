# Piano notification — notification-service

Direttive per la pipeline planner → tester → implementer → reviewer (`/run-issue notification <NN> <obiettivo>`).
Una fase alla volta, nell'ordine sotto. Ogni fase produce il proprio piano dettagliato in
`docs/plans/notification-<NN>-<slug>.md` e **si ferma per l'approvazione** prima di procedere.

## Obiettivo
Al termine di un bonifico (COMPLETED o REJECTED) il transfer-service pubblica un messaggio Kafka.
Il notification-service lo consuma, scrive una riga di log (senza IBAN) e salva la notifica su MongoDB.

## Fonti di verità (da leggere in ogni fase)
1. `CLAUDE.md` radice e `notification-service/CLAUDE.md`
2. `docs/contracts/transfer-notification.md`: il messaggio, la semantica di consegna e gli scenari
3. `transfer-service/CLAUDE.md`: modello della struttura dei package degli adapter
4. Il piano della fase precedente

## Decisioni già prese (non rimetterle in discussione)
- Nome: `notification-service`, package `com.bank.notification`, moduli `notification-*`.
- Esiti: solo `COMPLETED` e `REJECTED`. Campo `occurredAt` incluso.
- Consegna **at-least-once**: conferma dell'offset solo a salvataggio e log completati; idempotenza con
  `transferId` come `_id` Mongo; errore temporaneo = 3 tentativi a 1 s; messaggio non valido = scarto
  immediato; nessuna dead-letter.
- Il service salva prima, poi scrive il log.
- Nel log **mai gli IBAN**: si stampano `transferId`, importo, valuta, esito, motivi, `occurredAt`.
  Gli IBAN arrivano e vengono salvati in Mongo.
- Test **senza Docker**, strategia **ibrida** per il consumer Kafka (decisione dell'utente): il comportamento (parsing,
  validazione, mappatura, scarto, tentativi) si prova con test unitari senza broker; i tentativi, l'ack dopo il
  ritorno del listener e lo scarto a 3 tentativi si provano con **UN SOLO** test di integrazione con broker
  embedded (EmbeddedKafka). Mongo mockato.
- Nessun modulo rest, nessun endpoint, nessun Actuator.

## Dipendenze pre-approvate (le uniche ammesse; ogni altra va chiesta)
| Dipendenza | Modulo | Scope |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-kafka` (come in `transfer-kafka-adapter`; porta `spring-kafka` e `kafka-clients`) | `notification-kafka-adapter` | compile |
| `tools.jackson.core:jackson-databind` (Jackson 3, senza versione: la dà il BOM; come in `transfer-postgres-adapter`) | `notification-kafka-adapter` | compile |
| `org.springframework.boot:spring-boot-starter-data-mongodb` | `notification-mongo-adapter` | compile |
| `spring-boot-starter-test` (Mockito, AssertJ) | adapter e bootstrap | test |
| Test di integrazione del consumer con broker embedded (EmbeddedKafka): l'artefatto (in Boot 4 probabilmente `org.springframework.boot:spring-boot-starter-kafka-test`) lo verifica il planner della fase 06 (JAR in `~/.m2`, nome esatto) e lo propone all'utente: **da approvare esplicitamente prima di aggiungerlo**. Se non è disponibile, ripiego: container del listener con `MockConsumer` costruito nel test (alternativa D), da chiedere | `notification-kafka-adapter` | test |

Le versioni sono quelle gestite da `spring-boot-starter-parent` (stessa versione di Boot degli altri
servizi). Il planner verifica il nome esatto degli artefatti per la versione di Boot in uso (Boot 4.x) e,
se differisce, lo segnala come ambiguità invece di sceglierne un altro.

## Divieti per tutti gli agenti
- **Non toccare `transfer-service/`, `fraud-service/`, `docker-compose.yaml`, il CLAUDE.md radice.**
  La parte Kafka del Transfer la fa l'utente (sezione "Parte manuale").
- Nessun import di `com.bank.transfer` o `com.bank.fraud`.
- Nessuna modifica al contratto: se serve, si segnala la divergenza.
- Nessun IBAN in log, `toString()`, messaggi di eccezione.
- Non committare. Lavoro su un branch dedicato (non `main`): il planner lo segnala se `git status` dice altro.

## Fasi

### notification-01 — Scheletro Maven
Come `fraud-skeleton-maven.md`: pom padre `notification-service/pom.xml` (stessa versione di Boot e
`java.version=21`, modulo MapStruct solo se il piano lo giustifica), wrapper Maven copiato, `.gitignore`,
`.gitattributes`, e 6 moduli: `notification-domain`, `notification-application`, `notification-kafka-adapter`,
`notification-log-adapter`, `notification-mongo-adapter`, `notification-bootstrap`.
- Dipendenze tra moduli: i tre adapter dipendono solo da `application` (e dal proprio framework);
  `bootstrap` dipende da tutti. Il domain ha solo JUnit e AssertJ.
- `NotificationApplication` e `application.properties` (`spring.application.name=notification`,
  nessuna `server.port`). Nessun starter web.
- Test: solo `NotificationApplicationTests` (contesto carica, nome applicazione). Il contesto non deve
  richiedere un broker né un database all'avvio: se le autoconfigurazioni Kafka/Mongo lo impongono,
  il planner lo segnala come ambiguità.
- Fuori perimetro: qualsiasi classe di business.

### notification-02 — Dominio
`TransferNotification` (record immutabile, validazione nel costruttore), `Iban` e `Money` propri,
`TransferOutcome` (`COMPLETED`, `REJECTED`), eccezioni di dominio.
- Regole dal contratto sezione 4: `transferId` non vuoto; importo > 0 con massimo 2 decimali; valuta di 3
  lettere; `REJECTED` richiede almeno un motivo non vuoto; `COMPLETED` non ha motivi; `occurredAt` presente.
- IBAN normalizzati (spazi rimossi, maiuscolo) nel costruttore. `BigDecimal` con `compareTo`.
- `toString()` di `TransferNotification` e `Iban` **non contiene l'IBAN** (test dedicato).
- Test: un caso per ogni regola e per ogni errore; solo JUnit e AssertJ.

### notification-03 — Application
- `NotifyTransferInPort` con un solo metodo che accetta un `NotifyTransferCommand`.
  Le porte in non espongono oggetti del dominio.
- `NotificationStoreOutPort` (salva una notifica, idempotente per `transferId`) e `NotificationLogOutPort`
  (registra una notifica salvata). Le porte out ricevono oggetti del dominio.
- `NotifyTransferServiceImpl` (SENZA `@Service` né `@Transactional`, decisione dell'utente in `notification-03-application.md`: il bean lo dichiara `NotificationConfig` nel bootstrap, fase 07, come per `CheckTransferServiceImpl` del fraud-service; nessuna dipendenza nuova nell'application): crea il dominio dal command, `store.save`, poi `log.log`.
  Se `save` lancia una `RuntimeException` (l'adapter segnala così un errore del database), il service non
  la cattura né la avvolge: la eccezione risale così com'è, il log non viene chiamato. Serve ai tentativi
  del consumer. Una violazione delle regole del dominio (command non valido) lancia invece l'eccezione di
  dominio della fase 02, prima di qualsiasi chiamata a una porta.
- Test con porte finte: ordine delle chiamate, nessun log dopo errore di salvataggio, command non valido
  → eccezione di dominio senza chiamare nessuna porta.

### notification-04 — Log adapter
`notification-log-adapter`: `NotificationLogOutPortImpl` con SLF4J. Una riga per notifica, per esempio
`Transfer notification transferId=… outcome=… amount=… currency=… occurredAt=… reasons=[…]`.
- Mai IBAN. Reasons presenti solo con REJECTED.
- Test con `OutputCaptureExtension` o appender di test: una riga, contenuto atteso, **nessun IBAN** sia con
  COMPLETED che con REJECTED, motivi con più voci.
- Nessun framework oltre a SLF4J; nessun import Spring.

### notification-05 — Mongo adapter
`notification-mongo-adapter`: documento con `_id` = `transferId`, `sourceIban`, `destinationIban`, `amount`
(`BigDecimal`/Decimal128), `currency`, `status`, `reasons`, `occurredAt`; repository Spring Data;
`NotificationStoreOutPortImpl`; mapper dominio ↔ documento.
- Test senza Docker: mapper (round-trip, `amount` senza perdita di scala, reasons assenti/presenti),
  adapter con repository mockato (chiama `save` una volta con `_id` = `transferId`, un secondo `save` dello
  stesso `transferId` non è un `insert`, eccezione del repository propagata).
- Nessuna prova su un MongoDB vero: è dichiarata come prova manuale nel riepilogo di fase.

### notification-06 — Kafka adapter
`notification-kafka-adapter`: listener sul topic letto dalla proprietà `notification.topic` (segnaposto
`${notification.topic}` nell'annotazione, mai il nome scritto nel codice né nei test), group `notification`,
chiave e valore come stringhe; il valore è parsato in un DTO del messaggio con ignoranza dei campi sconosciuti; mapper
DTO → `NotifyTransferCommand`; chiamata a `NotifyTransferInPort`.
- **Vincoli per il mapper verso `NotifyTransferCommand`** (da `notification-03-application.md`): i campi assenti del JSON arrivano
  `null` nel command, senza default inventati (li rifiuta il service/dominio con `InvalidNotificationException`, non ritentabile);
  `status` si passa come stringa grezza SENZA `TransferOutcome.valueOf` (la conversione esatta e sensibile alle maiuscole è
  nel service; un valore sconosciuto dà già `InvalidNotificationException`, non va duplicata nell'adapter); `reasons` null è
  ammesso (il command lo trasforma in lista vuota); il mapper NON deve mai passare un command `null` (darebbe una
  `NullPointerException`, ritentabile 3 volte per errore).
- Validazione di formato (JSON illeggibile, campi di tipo sbagliato, data non ISO-8601) → scarto immediato senza
  tentativi, log di errore con il solo `transferId` se disponibile (**mai IBAN**).
- Distinzione degli errori: l'eccezione di dominio (messaggio che viola le regole) e gli errori di parsing sono
  **non ritentabili** (scarto immediato); ogni altra `RuntimeException` dal port è **ritentabile**.
- Errore dal port (es. database) → 3 tentativi totali a 1 s, poi scarto con errore nel log. Conferma
  dell'offset solo dopo il successo del port (modalità `RECORD`, conferma dopo ritorno del listener).
- Test (strategia ibrida): scenari 1-8 del contratto (sezione 9) con port finto e senza broker (unit test del
  listener/handler e del mapper); scenario 9 (port che lancia: 3 chiamate, poi il messaggio successivo viene
  elaborato) e conferma dell'offset solo a successo con **UN SOLO** test di integrazione con broker embedded;
  scenario 10 sugli output catturati.
- Il parsing del JSON usa **Jackson** (in Boot 4 è Jackson 3, package `tools.jackson`), nel solo adapter Kafka:
  il dominio e l'application non lo vedono. Il `JsonMapper` è configurato per ignorare i campi sconosciuti e
  per leggere `amount` come `BigDecimal` (mai `double`).

### notification-07 — Bootstrap e integrazione
`NotificationApplication`, `config/NotificationConfig` (bean di service e adapter: **qui va dichiarato `NotifyTransferServiceImpl` con `@Bean`**, perché il service non ha `@Service`; senza il bean il listener della fase 06 non ha una `NotifyTransferInPort`), `application.properties`:
`spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`, `spring.kafka.consumer.group-id=notification`,
`spring.data.mongodb.uri=${MONGODB_URI:mongodb://localhost:27017/notification}`,
`notification.topic=${NOTIFICATION_TOPIC:transfer.notifications}` (stessa variabile del Transfer).
- Test di integrazione nel bootstrap (con lo stesso broker embedded già usato dal test della fase 06 se possibile, oppure
  senza broker con il listener disattivato) e repository Mongo mockato (`@MockitoBean`): un messaggio
  `COMPLETED` e uno `REJECTED` pubblicati sul topic arrivano a `save` con i dati giusti e producono una
  riga di log senza IBAN; messaggio duplicato → due `save` con lo stesso `_id`; messaggio non valido →
  nessuna chiamata a `save`.
- Il contesto parte senza Docker. Un solo bean per ogni porta.
- Chiude la fase: `.\mvnw.cmd test` completo da `notification-service/` in BUILD SUCCESS senza Docker.

## Criteri di accettazione generali
- `.\mvnw.cmd test` da `notification-service/` verde, senza Docker, senza broker o database esterni.
- Struttura identica a transfer/fraud: 6 moduli `notification-*`, hexagonal rispettata, domain senza
  framework, nessun adapter che ne chiama un altro.
- Nessuna dipendenza fuori dall'elenco pre-approvato.
- Nessun IBAN in log, `toString()`, messaggi di errore (verificato dal reviewer a ogni fase).
- Tutti gli scenari della sezione 9 del contratto coperti da almeno un test.
- `transfer-service/`, `fraud-service/`, contratti esistenti e `docker-compose.yaml` invariati.

## Parte manuale: Kafka sul Transfer
I passi 2-8 sono stati eseguiti con la pipeline degli agenti (transfer-02 ... transfer-09); resta all'utente il passo 1 (docker-compose). Il Transfer usa una transactional outbox
(contratto, sezione 5): la notifica non va persa se Kafka non è raggiungibile. Passi, nell'ordine:
1. `docker-compose.yaml`: aggiungere Kafka (immagine `apache/kafka`, modalità KRaft, porta 9092) e, per la
   prova a mano, MongoDB (porta 27017).
2. Migration Flyway `V<n>__outbox.sql` nel Transfer: tabella `outbox` (id, `transferId`, payload JSON, creata
   il). Nessuna colonna di stato: la riga si elimina dopo l'invio. Nessuna modifica alle migration esistenti.
3. Porta in uscita `NotificationOutPort` in `transfer-application` con un metodo che accetta un
   `TransferNotificationDto` (`transferId`, IBAN, importo, valuta, esito come `TransferStatusEnum`, motivi).
   **Senza `occurredAt`**: lo assegna l'adapter dell'outbox.
4. Adapter dell'outbox in `transfer-postgres-adapter`: implementa `NotificationOutPort` scrivendo una riga in `outbox`. Assegna
   `occurredAt` con un `Clock` iniettato (ISO-8601 UTC) e lo mette nel JSON del payload, nella stessa
   transazione del bonifico.
5. `CreateTransferServiceImpl`: chiamata alla porta **dentro** `transactionTemplate.execute`, solo per
   COMPLETED e REJECTED, così bonifico e riga `outbox` sono nella stessa transazione.
6. Publisher (ex "relay", vedi `docs/plans/transfer-09-outbox-relay.md`) in `transfer-kafka-adapter` (ex `transfer-notification-adapter`): legge le righe di `outbox` in ordine di creazione, le pubblica con
   `KafkaTemplate` (topic dalla proprietà `notification.topic=${NOTIFICATION_TOPIC:transfer.notifications}`, chiave `transferId`, JSON), elimina la riga solo dopo la
   conferma del broker; periodico (scheduler), con errori registrati e nuovo tentativo al giro successivo.
7. `spring-kafka` nel pom del modulo; `application.properties` e bean in `transfer-bootstrap`.
8. Test del Transfer (scenari 11-16 del contratto) con un Kafka finto; prova end-to-end a mano con Kafka e
   MongoDB su Docker.

## Rischi
- Boot 4.x: nomi degli artefatti Kafka/Mongo e dei package di test (EmbeddedKafka, `@MockitoBean`) da
  verificare nella fase 01/06/07; scostamenti vanno segnalati, non aggirati.
- Il contesto Spring può tentare di collegarsi a Kafka/Mongo all'avvio nei test: i test lo evitano con
  broker embedded e repository finto; se non basta, ambiguità al planner.
- Mongo non è provato contro un database vero dai test automatici: coperto solo dalla prova manuale.
- At-least-once: righe di log duplicate in caso di riconsegna (accettato).
- Con l'outbox del Transfer la notifica non si perde ma può arrivare più volte (gestito: idempotenza
  sul consumatore).
- Il CLAUDE.md radice dice `notification-service | da fare`: l'aggiornamento della tabella è fuori perimetro
  (lo fa l'utente a fine lavori).

## Fuori perimetro
Dead-letter, autenticazione Kafka, Actuator, endpoint di lettura delle notifiche, invio reale di
email/SMS, Testcontainers, modifiche a transfer-service e fraud-service (la parte Transfer è manuale).

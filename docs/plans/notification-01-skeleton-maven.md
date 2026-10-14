### Obiettivo
Creare lo scheletro Maven multi-modulo di `notification-service` (6 moduli `notification-*`, package `com.bank.notification`, niente web e niente porta HTTP), copiando la struttura di fraud-service/transfer-service, senza codice di business, senza dipendenze verso altri servizi e senza i framework Kafka/Mongo/Jackson.

### Ambiguità
Nessuna blocca il piano. Per ognuna sono indicate le alternative e una raccomandazione. Il piano sotto segue la raccomandazione, che va confermata.

1. **L'applicazione termina subito dopo l'avvio.** Con la sola `spring-boot-starter`, senza web e senza listener, `java -jar` avvia il contesto e il processo esce con codice 0, perché nessun thread non-daemon lo tiene vivo.
   - Il test non resta appeso: `@SpringBootTest` non chiama `main`. Crea il contesto, lo mette in cache e lo chiude allo spegnimento della JVM di surefire.
   - Alternative:
     (a) accettarlo come comportamento atteso della fase 01 e scriverlo nel riepilogo di fase: dalla fase 06 il container del listener Kafka tiene viva l'app;
     (b) aggiungere `spring.main.keep-alive=true`, che però va contro "SOLO spring.application.name";
     (c) aggiungere un thread o un `CommandLineRunner` bloccante, che però è codice inventato.
   - Raccomandazione: (a). Nessun test JUnit sull'uscita del processo. Al massimo una prova a mano facoltativa (`.\mvnw.cmd -pl notification-bootstrap spring-boot:run` termina con "BUILD SUCCESS" dopo il log "Started NotificationApplication").
2. **Moduli vuoti.** Domain, application e i 3 adapter non hanno sorgenti, quindi `maven-jar-plugin` stampa "JAR will be empty". Il warning è innocuo.
   - Alternative: niente segnaposto, oppure `package-info.java`.
   - Raccomandazione: niente segnaposto, come nel fraud (fraud-skeleton-maven.md, Ambiguità 4).
3. **`spring-context`/`spring-tx` in `notification-application` già ora.** Oggi non servono: non c'è nessuna classe. `spring-context` servirà in fase 03 per il `@Service`, ma **non compare nell'elenco delle dipendenze pre-approvate di notification.md**. `spring-tx` non serve affatto: un solo `save` Mongo idempotente e nessun `@Transactional` previsto.
   - Alternative:
     (a) aggiungere ora entrambe, come Transfer e Fraud;
     (b) aggiungere ora solo `spring-context`;
     (c) non aggiungere nulla e chiedere `spring-context` all'utente nella fase 03.
   - Raccomandazione: (c). Così si rispetta "non aggiungere dipendenze senza chiedere", e `spring-tx` resta fuori.
4. **Coerenza con l'application di Transfer/Fraud** (mapstruct, spring-context, spring-tx, spring-boot-starter-test, processor MapStruct).
   - MapStruct: i mapping del servizio sono pochi e piatti (DTO Kafka -> command in fase 06, dominio <-> documento in fase 05). Inoltre `mapstruct` non è nell'elenco pre-approvato.
   - Raccomandazione: niente proprietà `mapstruct.version`, niente voce in `dependencyManagement`, niente `annotationProcessorPaths`; mapper scritti a mano.
   - Cosa resta fuori rispetto al Fraud: mapstruct e il suo processor, `spring-context`, `spring-tx`, `spring-boot-starter-test`. In fase 03 i test usano porte finte e bastano JUnit e AssertJ.
   - L'application oggi dipende da `notification-domain` (compile) più `junit-jupiter` e `assertj-core` (test), come `fraud-memory-adapter`.
5. **CLAUDE.md.**
   - `notification-service/CLAUDE.md` esiste già (è l'unico file presente in `notification-service/`). Si legge e basta.
   - La riga `notification-service | da fare` del CLAUDE.md radice è fuori perimetro: la aggiorna l'utente.
   - Nessun conflitto con la richiesta.
6. **Come scrivere i test di assenza dei bean.** `KafkaTemplate` e `MongoTemplate` non sono sul classpath in questa fase, quindi il test non può importarli: non compilerebbe senza aggiungere dipendenze. `javax.sql.DataSource` invece è nel JDK e si può usare come nel Fraud.
   - Alternative:
     (a) verificare per nome del bean (`context.containsBean("kafkaTemplate")`, `"mongoTemplate"`);
     (b) verificare per nome della classe (`ClassUtils.isPresent("org.springframework.kafka.core.KafkaTemplate", ...)` è falso);
     (c) entrambe.
   - Raccomandazione: (a) per Kafka e Mongo, `getBeanNamesForType(DataSource.class)` per il DataSource.
   - Nota: dalla fase 05/06 questi test cambieranno senso, perché i bean ci saranno. Andranno rivisti in quelle fasi con una modifica esplicita, non in silenzio.
7. **File da copiare da fraud-service.** `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (wrapper 3.3.4, Maven 3.10.0, `only-script`), `.gitignore`, `.gitattributes`.
   - Il planner ha già cercato con grep `(?i)fraud|transfer` in questi file di fraud-service: **nessuna occorrenza**. Le copie identiche non si portano dietro riferimenti.
   - Da ripetere dopo la copia, su tutto `notification-service/` (pom compresi).
   - Si copia solo la sorgente, mai `target/`: in fraud-service ci sono cartelle `target/`, anche di moduli rinominati.
8. **Incongruenza in notification.md (fase 01).** Il piano dice "i **quattro** adapter dipendono solo da application", ma i moduli adapter sono tre (kafka, log, mongo). Si legge come un refuso. Non cambia nulla e il piano generale non si modifica.
9. **Per le fasi future, fuori da questa: l'elenco pre-approvato di notification.md va allineato alle scelte del Transfer.** Elenco attuale, ripetuto qui ma **da NON aggiungere ora**:

   | Dipendenza | Modulo | Scope | Fase |
   |---|---|---|---|
   | `org.springframework.kafka:spring-kafka` | notification-kafka-adapter | compile | 06 |
   | `org.springframework.kafka:spring-kafka-test` | notification-kafka-adapter, notification-bootstrap | test | 06/07 |
   | `org.springframework.boot:spring-boot-starter-data-mongodb` | notification-mongo-adapter | compile | 05 |
   | Jackson (`spring-boot-starter-json` o l'equivalente Boot 4) | notification-kafka-adapter | compile | 06 |
   | `spring-boot-starter-test` | adapter e bootstrap | test | 04-07 |

   Divergenze dal Transfer di oggi:
   - `transfer-kafka-adapter` usa `org.springframework.boot:spring-boot-starter-kafka`, non `spring-kafka`.
   - I test del Transfer usano `MockProducer` di kafka-clients (`KafkaNotificationSenderOutPortImplTest` e i due test di wiring nel bootstrap), non EmbeddedKafka.
   - Per Jackson il Transfer usa `jackson-databind` direttamente (Jackson 3, `tools.jackson`), non uno starter.
   - In Boot 4 lo starter di test Kafka ha probabilmente il nome `spring-boot-starter-kafka-test`, ma va verificato.

   Lato consumer l'equivalente di `MockProducer` è `MockConsumer`, che però non esercita il container del listener (tentativi, ack `RECORD`) richiesto dalla fase 06. È una scelta da fare nella fase 05/06/07, non ora. Il piano generale non si tocca.
10. **`spring-boot-starter` nel bootstrap.** La richiede l'issue, ma non è nell'elenco pre-approvato di notification.md. Nel Fraud arriva per transitività da `spring-boot-starter-webmvc`. Si considera approvata dall'issue stessa. Segnalata comunque.

### Modifiche per modulo
Tutti i file sono nuovi. Nessun file esterno a `notification-service/` viene toccato. Branch corrente: `notification`, non main.

**`notification-service/` (pom padre e strumenti)**
- `notification-service/pom.xml`
  - parent `org.springframework.boot:spring-boot-starter-parent:4.1.1`;
  - `com.bank:notification-service:0.0.1-SNAPSHOT`, `packaging pom`, stessi blocchi vuoti name/description/scm del Fraud;
  - `<properties>`: solo `java.version=21`;
  - `<modules>`, in ordine: `notification-domain`, `notification-application`, `notification-kafka-adapter`, `notification-log-adapter`, `notification-mongo-adapter`, `notification-bootstrap`;
  - `dependencyManagement`: i 5 moduli non-bootstrap a `${project.version}`, niente mapstruct (Ambiguità 4).
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitignore`, `.gitattributes`: copie identiche byte per byte da `fraud-service/`. `.gitattributes` mantiene `eol=lf` per `mvnw`.

**`notification-domain`**
- `notification-domain/pom.xml`: parent `notification-service`; solo `junit-jupiter` e `assertj-core` in scope test. Nessun sorgente; in futuro `com.bank.notification.domain`.

**`notification-application`**
- `notification-application/pom.xml`: `notification-domain` (compile), `junit-jupiter` e `assertj-core` (test). Niente mapstruct, spring-context o spring-tx (Ambiguità 3-4). Nessun sorgente.

**`notification-kafka-adapter`, `notification-log-adapter`, `notification-mongo-adapter`**
- Ciascun `pom.xml` dipende solo da `notification-application`. Nessun framework e nessuna dipendenza di test: arriveranno con le fasi 04, 05 e 06. Nessun sorgente.

**`notification-bootstrap`**
- `notification-bootstrap/pom.xml`
  - dipendenze: i 5 moduli interni, `spring-boot-starter` (compile), `spring-boot-starter-test` (test);
  - niente starter-web e niente Actuator;
  - plugin `spring-boot-maven-plugin`.
- `notification-bootstrap/src/main/java/com/bank/notification/NotificationApplication.java`: `@SpringBootApplication`, `public static void main(String[] args)` -> `SpringApplication.run(NotificationApplication.class, args)`. È l'unica classe di produzione.
- `notification-bootstrap/src/main/resources/application.properties`: solo `spring.application.name=notification`.
- `notification-bootstrap/src/test/java/com/bank/notification/NotificationApplicationTests.java`: test di avvio, descritti sotto.

### Test attesi
Si scrivono prima di `NotificationApplication` e di `application.properties`.

**`com.bank.notification.NotificationApplicationTests`** (`@SpringBootTest`, modulo `notification-bootstrap`)

| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | scheletro completo, senza broker, database né Docker | si avvia il contesto | si carica senza eccezioni (`contextLoads`) e il test termina, senza restare appeso |
| 2 | `application.properties` | si legge `spring.application.name` dall'`Environment` | vale `notification` |
| 3 | `application.properties` | si legge `server.port` | è `null`: nessuna porta configurata |
| 4 (errore) | nessuna dipendenza JDBC/JPA | si cercano i bean di tipo `DataSource` | nessuno, e nessun errore "Failed to configure a DataSource" |
| 5 (errore) | nessuna dipendenza Kafka | `context.containsBean("kafkaTemplate")` | `false` |
| 6 (errore) | nessuna dipendenza Mongo | `context.containsBean("mongoTemplate")` | `false` |
| 7 (errore) | nessuno starter web | si controlla il tipo del contesto | non è un `WebApplicationContext` (nessun server) |

Verifiche di build, a mano e non JUnit:

| # | Dato | Quando | Allora |
|---|---|---|---|
| 8 | `notification-service/` | `.\mvnw.cmd test` | BUILD SUCCESS su tutti e 6 i moduli più il padre; solo i warning "JAR will be empty" |
| 9 (errore) | tutti i `pom.xml` di `notification-service/` | grep `transfer-\|fraud-` | nessuna occorrenza |
| 10 (errore) | tutto `notification-service/` (sorgenti, wrapper, gitignore, gitattributes) | grep `(?i)fraud\|transfer` e `com\.bank\.(transfer\|fraud)` | nessuna occorrenza. Unica eccezione ammessa: la descrizione in `CLAUDE.md` del servizio, che esiste già |
| 11 (errore) | i pom | grep `spring-kafka\|kafka\|mongodb\|jackson\|testcontainers\|mapstruct\|actuator\|starter-web` | nessuna occorrenza |
| 12 | il pom di `notification-domain` | lettura | solo junit-jupiter e assertj-core, scope test |

`dependency:tree` non si usa, per il problema PKIX di questo ambiente: lo sostituiscono le grep dei punti 9-12.

### Criteri di accettazione
- `.\mvnw.cmd test` da `notification-service/`, senza Docker, broker né database: BUILD SUCCESS, e i 7 test di `NotificationApplicationTests` sono verdi.
- Il pom padre elenca esattamente i 6 moduli nell'ordine indicato. Tutti gli `artifactId` iniziano con `notification-`, il padre si chiama `notification-service`, Boot è `4.1.1` e `java.version` è `21`.
- Gli adapter dipendono solo da `notification-application`; il bootstrap dipende da tutti; il domain ha solo JUnit e AssertJ in scope test.
- Nei pom non compaiono kafka, mongo, jackson, testcontainers, mapstruct, actuator o starter web. L'unica dipendenza non presente nel Fraud è `spring-boot-starter`, richiesta dall'issue.
- `application.properties` contiene solo `spring.application.name=notification`.
- L'unica classe di produzione è `com.bank.notification.NotificationApplication`.
- Wrapper, `.gitignore` e `.gitattributes` sono identici a quelli di fraud-service.
- `transfer-service/`, `fraud-service/`, `docs/contracts/`, il CLAUDE.md radice e `notification-service/CLAUDE.md` restano invariati.
- Contratto `transfer-notification.md`: ancora nessuna parte implementata, come previsto. Non c'è nessuna violazione.

### Rischi e fuori perimetro
**Rischi**
- **Processo che esce subito** se avviato a mano (Ambiguità 1). Può sembrare un errore: va scritto nel riepilogo di fase.
- **Test 5-6 per nome del bean.** Restano verdi anche se il bean si chiamasse diversamente. Sono test di non-regressione deboli, da rivedere nelle fasi 05-07, quando i bean ci saranno davvero.
- **Copie da fraud-service.** Rischio di portarsi dietro `target/` o un `fraud-` rimasto nei pom. Lo coprono le grep 9-10.
- **Dipendenze nuove.** `spring-boot-starter` è esplicita, mentre nel Fraud arrivava per transitività. `spring-context` andrà chiesta in fase 03, perché non è nell'elenco pre-approvato.
- **Versione di Boot duplicata** nei tre pom padre: un aggiornamento non si propaga. Un BOM condiviso sarebbe una deviazione strutturale da chiedere prima.
- **Elenco pre-approvato da riallineare** per le fasi 05-07: `spring-boot-starter-kafka`, MockProducer/EmbeddedKafka, Jackson 3, nomi degli starter di test in Boot 4 (Ambiguità 9).

**Fuori perimetro**
- Classi di dominio, porte, service, command, DTO, mapper, listener, documenti Mongo, adapter.
- Dipendenze Kafka, Mongo, Jackson, Testcontainers e spring-kafka-test.
- Proprietà Kafka/Mongo/topic, Docker/compose.
- Modifiche al CLAUDE.md radice (riga della tabella), a `notification-service/CLAUDE.md`, a transfer-service, a fraud-service, ai contratti e a `docs/plans/notification.md`.
- Commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **Ambiguità 1:** approvata la raccomandazione (a): l'applicazione che esce subito dopo l'avvio è il comportamento atteso della fase 01; va scritto nel riepilogo di fase. Nessun `keep-alive`, nessun thread, nessun test JUnit sull'uscita del processo.
2. **Ambiguità 3:** NESSUNA dipendenza in `notification-application` ora (né `spring-context` né `spring-tx`): `spring-context` si chiederà all'utente all'inizio della fase 03.
3. **Ambiguità 4:** niente MapStruct (né proprietà, né `dependencyManagement`, né processor): mapper scritti a mano.
4. **Ambiguità 10:** `spring-boot-starter` nel bootstrap è approvata (richiesta dall'issue).
5. **Ambiguità 6:** i test di assenza dei bean Kafka e Mongo si fanno per nome del bean (`containsBean`), `DataSource` per tipo; vanno rivisti con una modifica esplicita nelle fasi 05-07.
6. **Ambiguità 8:** il "quattro adapter" di `notification.md` è un refuso: gli adapter sono tre. Il piano generale non si modifica.
7. **Ambiguità 9:** l'allineamento dell'elenco delle dipendenze pre-approvate di `notification.md` con le scelte del Transfer (`spring-boot-starter-kafka`, `MockProducer`/`MockConsumer`, `jackson-databind`) NON è parte di questa fase: si decide prima delle fasi 05-07.
8. Ruoli: il tester scrive SOLO il file di test; l'implementer crea tutto il resto (pom, wrapper, classe, properties).

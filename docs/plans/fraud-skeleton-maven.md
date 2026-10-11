### Obiettivo
Creare lo scheletro Maven multi-modulo di `fraud-service` (5 moduli `fraud-*`, package `com.bank.fraud`, porta 8081), copiando la struttura di transfer-service, senza codice di business e senza dipendenze verso il Transfer.

### Ambiguità
Nessuna di queste cambia il piano. Le ho risolte con l'ipotesi indicata, che va confermata.
1. **Proprietà di configurazione antifrode** (soglia `10000`, IBAN bloccato `IT60X0542811101000000000099`): `fraud-service/CLAUDE.md` dice che stanno in `application.properties` di `fraud-bootstrap`, ma i nomi delle chiavi non sono definiti da nessuna parte. Ipotesi: in questa issue non le aggiungo. Arriveranno con l'issue di `fraud-persistence-adapter` / `FraudPolicy`, che fisserà i nomi.
2. **Actuator in bootstrap**: transfer-bootstrap ha `spring-boot-starter-actuator`. Il fraud-service non lo cita. Decisione dell'utente: Actuator NON è incluso nel fraud-bootstrap.
3. **Tabella dei servizi nel `CLAUDE.md` radice** (fraud-service "da fare" -> "in sviluppo"): l'issue non lo chiede. Ipotesi: fuori perimetro.
4. **Moduli vuoti**: git non traccia i package vuoti e transfer non usa `package-info.java`. Ipotesi: niente segnaposto. I moduli senza sorgenti producono jar vuoti, con un warning innocuo di `maven-jar-plugin`.

### Modifiche per modulo
Tutti i file sono nuovi. Nessun file di transfer-service viene toccato.

**`fraud-service/` (pom padre e tooling)**
- `fraud-service/pom.xml`: parent `org.springframework.boot:spring-boot-starter-parent:4.1.1` (stessa versione del Transfer), `groupId com.bank`, `artifactId fraud-service`, `version 0.0.1-SNAPSHOT`, `packaging pom`. Proprietà `java.version=21` e `mapstruct.version=1.6.3`. `<modules>` contiene, in ordine: `fraud-domain`, `fraud-application`, `fraud-web-adapter`, `fraud-persistence-adapter`, `fraud-bootstrap`. `dependencyManagement` elenca i 4 moduli interni a `${project.version}` più `org.mapstruct:mapstruct`. Non deve contenere nessun riferimento a `transfer-*`.
- `fraud-service/mvnw`, `fraud-service/mvnw.cmd`, `fraud-service/.mvn/wrapper/maven-wrapper.properties`: copie identiche di quelli di transfer-service (wrapper 3.3.4, Maven 3.10.0).
- `fraud-service/.gitignore`, `fraud-service/.gitattributes`: copie di quelli di transfer-service.

**`fraud-domain`**
- `fraud-domain/pom.xml`: parent `fraud-service`. Dipendenze solo `junit-jupiter` e `assertj-core` in scope test, come transfer-domain. Nessun sorgente. In futuro qui ci sarà il package `com.bank.fraud.domain`.

**`fraud-application`**
- `fraud-application/pom.xml`: dipende da `fraud-domain`, `mapstruct`, `spring-context`, `spring-tx`. In test `spring-boot-starter-test`. `maven-compiler-plugin` con `mapstruct-processor` negli `annotationProcessorPaths`. Tutto come transfer-application. Nessun sorgente. In futuro qui ci saranno `com.bank.fraud.application.port.in|port.out|service|dto|command`.

**`fraud-web-adapter`**
- `fraud-web-adapter/pom.xml`: dipende da `fraud-application`, `mapstruct`, `spring-boot-starter-webmvc`, `spring-boot-starter-validation`. In test `spring-boot-starter-webmvc-test` e `spring-boot-starter-validation-test`. Processor MapStruct come nel Transfer. Nessun controller.

**`fraud-persistence-adapter`**
- `fraud-persistence-adapter/pom.xml`: dipende solo da `fraud-application`. In test `junit-jupiter` e `assertj-core`. Niente JPA, niente Flyway, niente H2 o PostgreSQL: il servizio non ha persistenza (vedi `fraud-service/CLAUDE.md`). Nessun sorgente.

**`fraud-bootstrap`**
- `fraud-bootstrap/pom.xml`: dipende da `fraud-domain`, `fraud-application`, `fraud-web-adapter`, `fraud-persistence-adapter`. Actuator NON incluso, per decisione dell'utente (vedi Ambiguità 2). In test solo `spring-boot-starter-webmvc-test`. Plugin `spring-boot-maven-plugin`.
- `fraud-bootstrap/src/main/java/com/bank/fraud/FraudApplication.java`: classe `@SpringBootApplication` con `main(String[] args)` che chiama `SpringApplication.run`. È l'unica classe di produzione.
- `fraud-bootstrap/src/main/resources/application.properties`: `spring.application.name=fraud` e `server.port=8081`. Nessun datasource, nessuna proprietà JPA o Flyway.
- `fraud-bootstrap/src/test/java/com/bank/fraud/FraudApplicationTests.java`: test di avvio dell'applicazione (vedi sotto).

### Test attesi
Sono solo test di avvio, nessun test di business. Si scrivono prima di `FraudApplication` e `application.properties`.

**`com.bank.fraud.FraudApplicationTests`** (`@SpringBootTest`, modulo `fraud-bootstrap`)

| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | lo scheletro completo, senza database né Docker | si avvia il contesto Spring | il contesto si carica senza eccezioni (`contextLoads`) |
| 2 | `application.properties` di bootstrap | si legge `server.port` dall'`Environment` | vale `8081` |
| 3 | `application.properties` di bootstrap | si legge `spring.application.name` | vale `fraud` |
| 4 (errore) | nessun datasource configurato e nessuna dipendenza JPA o JDBC sul classpath | si avvia il contesto | nessun bean `DataSource` presente e nessun errore di tipo "Failed to configure a DataSource" |

Verifiche di build, da fare a mano o in CI, non come test JUnit:

| # | Dato | Quando | Allora |
|---|---|---|---|
| 5 | `fraud-service/` | `.\mvnw.cmd test` | BUILD SUCCESS su tutti e 5 i moduli |
| 6 (errore) | `fraud-service/` | `.\mvnw.cmd dependency:tree` | nessun artefatto `transfer-*` e nessuna dipendenza di compile/runtime in `fraud-domain` |

### Criteri di accettazione
- `.\mvnw.cmd test`, lanciato da `fraud-service/` senza Docker né database attivo, finisce in BUILD SUCCESS e i test di `FraudApplicationTests` sono verdi.
- Il pom padre elenca esattamente i 5 moduli. Ogni `artifactId` inizia con `fraud-` e il padre si chiama `fraud-service`.
- La versione di Spring Boot è `4.1.1` e `java.version` è `21`, identiche a `transfer-service/pom.xml`.
- `dependency:tree` non mostra nessun `transfer-*`, e nessun file di `fraud-service` importa `com.bank.transfer`.
- `fraud-domain` ha solo JUnit e AssertJ in scope test.
- `fraud-persistence-adapter` non contiene JPA, Flyway né driver JDBC.
- L'unica classe di produzione è `com.bank.fraud.FraudApplication`. Non ci sono classi di dominio, porte, controller o endpoint.
- Rispetto al modello Transfer non c'è nessuna dipendenza nuova, e Actuator non è incluso, per decisione dell'utente (Ambiguità 2).
- Contratto `docs/contracts/fraud-check.md`: è rispettata la porta 8081 (sezione 3). Il resto del contratto non è ancora implementato, come previsto.
- transfer-service non viene modificato e i suoi test restano invariati.

### Rischi e fuori perimetro
**Rischi**
- **Avvio senza datasource**: se per sbaglio si copiano da transfer `spring-boot-starter-data-jpa`, Flyway o il driver PostgreSQL, il contesto fallisce perché manca il DB. Il test 4 serve a intercettarlo.
- **Collisione di porta**: con il Transfer avviato in locale, 8080 è occupata. Il fraud usa 8081. `@SpringBootTest` in ambiente MOCK non apre porte, quindi il test non dipende da questo.
- **Copie del Transfer**: i pom copiati possono portarsi dietro un `transfer-` rimasto, per esempio nel parent o nel `dependencyManagement`. Va verificato con `dependency:tree` e con una ricerca di "transfer" in `fraud-service/`.
- **Moduli vuoti**: i warning "JAR will be empty" sono attesi. Lo scanning di `@SpringBootApplication` su `com.bank.fraud` non trova bean, e va bene così.
- **Versioni duplicate**: Spring Boot e MapStruct sono duplicati nei due pom padre, e un futuro aggiornamento del Transfer non si propaga. Un BOM condiviso sarebbe una deviazione strutturale da chiedere prima.
- **Dipendenze nuove**: non ne propongo. Le dipendenze dei pom fraud sono un sottoinsieme di quelle del Transfer.

**Fuori perimetro**
- Classi di dominio (`Iban`, `Money`, `TransferToAssess`, `FraudAssessment`, `FraudPolicy`), porte (`CheckTransferInPort`, `BlockedIbanOutPort`), service, DTO, command e mapper.
- Endpoint `POST /fraud-check`, `WebExceptionHandler` e tutto il trattamento degli errori della sezione 7.
- Lista in memoria degli IBAN bloccati e proprietà di soglia e IBAN (vedi Ambiguità 1).
- Test dei casi 1-15 della sezione 11 del contratto.
- L'adapter HTTP lato Transfer che sostituisce `StubFraudCheck`, l'aggiornamento della tabella nel `CLAUDE.md` radice, docker-compose e qualsiasi pom aggregatore nella radice.

**File di riferimento letti**
- `C:\Work\agentic-bank\CLAUDE.md`
- `C:\Work\agentic-bank\fraud-service\CLAUDE.md`
- `C:\Work\agentic-bank\transfer-service\CLAUDE.md`
- `C:\Work\agentic-bank\docs\contracts\fraud-check.md`
- `C:\Work\agentic-bank\transfer-service\pom.xml` e i pom di tutti i moduli `transfer-*`
- `C:\Work\agentic-bank\transfer-service\transfer-bootstrap\src\main\java\com\bank\transfer\TransferApplication.java`
- `C:\Work\agentic-bank\transfer-service\transfer-bootstrap\src\main\resources\application.properties`
- `C:\Work\agentic-bank\transfer-service\transfer-bootstrap\src\test\java\com\bank\transfer\TransferApplicationTests.java`
- `C:\Work\agentic-bank\transfer-service\.mvn\wrapper\maven-wrapper.properties`

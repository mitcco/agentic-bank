## Piano transfer-04: adapter dell'outbox (`NotificationOutPort` -> tabella `outbox`)

### Obiettivo
Implementare `NotificationOutPort` in `transfer-postgres-adapter`. L'adapter scrive una riga in `outbox` con il JSON del contratto `transfer-notification` §4 (`occurredAt` preso da un `Clock` iniettato) e partecipa alla transazione del chiamante, senza aprirne una propria.

### Ambiguità
Alcune decisioni sono aperte e cambiano il piano: sono marcate **[DECISIONE APERTA]**. Il resto del piano usa le raccomandazioni (R). Se l'utente sceglie un'alternativa, cambiano solo i file indicati.

Verifiche fatte prima, in sola lettura. Il planner non ha potuto lanciare `.\mvnw.cmd dependency:tree` perché nel suo set di strumenti non c'è una shell. Al suo posto ha letto i POM in `~/.m2` e il classpath dei report surefire.
- `spring-boot-starter-flyway:4.1.1` -> `spring-boot-flyway:4.1.1` -> `flyway-core:12.4.0` (scope compile) -> `tools.jackson.core:jackson-databind`, senza `<optional>`. La versione gestita dal BOM di Boot 4.1.1 è `jackson-bom.version=3.1.5`.
- `TEST-com.bank.transfer.postgres.OutboxMigrationTest.xml` (8 test, 0 errori) ha nel classpath `tools\jackson\core\jackson-databind\3.1.5`.
- `spring-boot-starter-data-jpa`, Hibernate 7.4.5 e spring-data-jpa 4.1.1 non portano Jackson a compile (spring-data-jpa usa solo Jackson 2 a scope test).
- `transfer-fraud-adapter/pom.xml` dichiara già `tools.jackson.core:jackson-databind` senza versione: è il precedente.
- In `transfer-service` nessuna classe usa un logger.
- Decisione 3 della tappa 03 (`docs/plans/transfer-03-notification-port.md`): **la valuta di 3 lettere e il massimo di 2 decimali li deve garantire questo adapter.** Va quindi coperto qui (punto 7).

1. **[DECISIONE APERTA] Jackson per serializzare.**
   - **A (R):** dipendenza diretta `tools.jackson.core:jackson-databind`, senza versione (la dà il BOM), scope compile, in `transfer-postgres-adapter/pom.xml`. È lo stesso artefatto di `transfer-fraud-adapter`, quindi niente di nuovo nel classpath effettivo, ma serve comunque l'approvazione dell'utente.
   - **B:** usare la transitiva di Flyway senza dichiararla. Rischio: Flyway la usa per la propria configurazione, non è un'API garantita a noi. Un aggiornamento di Flyway o di Boot, o un'esclusione, la toglie e il modulo smette di compilare. Inoltre un `dependency:analyze` la segnala come "used undeclared".
   - **C:** JSON costruito a mano con `StringBuilder`. Niente dipendenza, ma l'escape di `reasons`, che sono testo libero, è a nostro carico: si sconsiglia.
   - Con A o B, il `JsonMapper` lo crea l'adapter (`JsonMapper.builder()...build()`, campo `static final`), senza usare il bean di Spring. Così lo slice `@DataJpaTest`, che non configura Jackson, funziona e la configurazione web non può cambiare il payload.
   - Anche i test leggono il JSON con `JsonMapper.readTree` (stessa dipendenza). Senza Jackson nei test l'unica via è confrontare stringhe (`contains("\"amount\":100.25")`), che è fragile sull'ordine dei campi.

2. **[DECISIONE APERTA] Accesso al database.**
   - **A (R): `JdbcTemplate`**, con un solo `INSERT INTO outbox (transfer_id, payload) VALUES (?, ?)`.
     - Il bean c'è sia in produzione sia in `@DataJpaTest`: `OutboxMigrationTest` lo inietta ed è verde.
     - Sotto `JpaTransactionManager` usa la stessa connessione della transazione JPA, quindi partecipa alla transazione del bonifico.
     - L'errore arriva subito come `DataAccessException`.
     - Non c'è un'entità, quindi `ddl-auto=validate` non controlla `outbox`.
   - **B: entità JPA `OutboxEntity`** (`@Id @GeneratedValue(strategy = IDENTITY) Long id`, `transfer_id` length 36, `payload` String, `created_at` non mappato) con `SpringDataOutboxRepository`. È coerente con `AccountEntity`/`TransferEntity`.
     - Rischio concreto: `CreateTransferConcurrencyTest` e `FraudCheckEndToEndTest` girano su H2 con `ddl-auto=validate` (da `application.properties`). H2 tratta `TEXT` come `CLOB`, mentre una `String` senza `@Lob` è attesa come `VARCHAR`: la validazione può fallire su H2. `@Lob` o `columnDefinition="TEXT"` possono a loro volta fallire su Postgres, dove `text` è riportato come `VARCHAR`. Non è verificabile senza un run.
     - Inoltre `SimpleJpaRepository.save` è `@Transactional(REQUIRED)`: senza la protezione del punto 6 aprirebbe una transazione propria.

3. **[DECISIONE APERTA] Bean `Clock`, stile del bean, profilo `inmemory`.**
   - Adapter `@Repository @Profile("!inmemory")` con costruttore package-private `(JdbcTemplate, Clock)`, come `JpaTransferRepositoryOutPortImpl`. `@Repository` attiva anche la traduzione delle eccezioni.
   - **Il bootstrap va toccato**: oggi non esiste un bean `Clock`. Senza, `CreateTransferConcurrencyTest`, `FraudCheckEndToEndTest` e `TransferApplicationTests` falliscono all'avvio (`NoSuchBeanDefinitionException`).
   - Proposta (R): nuovo `com.bank.transfer.config.ClockConfig` con `@Bean Clock clock()` che restituisce `Clock.systemUTC()`, senza profilo.
   - Alternativa: un `NotificationConfig` che crea l'adapter come `@Bean` (stile `FraudConfig`). Così però si perde `@Repository`, il costruttore va reso pubblico e si cambia stile rispetto agli adapter del modulo.
   - **Profilo `inmemory`:** non ci sarà nessun bean `NotificationOutPort`. In questa tappa nessuno lo inietta, quindi non si rompe nulla. Dalla tappa 5 invece `CreateTransferServiceImpl` non partirebbe con `inmemory`.
     - Opzione 1: `InMemoryNotificationOutPortImpl` (`@Profile("inmemory")`, lista in memoria o no-op) in `transfer-memory-adapter`.
     - Opzione 2: un no-op in config.
     - Da decidere se nella tappa 4 o nella 5 (R: tappa 5, insieme alla chiamata). Dubbio, non verificato: il profilo `inmemory` parte davvero senza Postgres, visto che Flyway e il DataSource restano attivi?

4. **Errori: si propagano, non si catturano (R).** È voluto: il bonifico fallisce e la notifica non si perde.
   - Scrittura fallita: `DataAccessException`, per esempio `DataIntegrityViolationException`.
   - Serializzazione fallita: `tools.jackson.core.JacksonException`. In Jackson 3 è unchecked.
   - Vincoli di formato (punto 7): `IllegalArgumentException`, con un messaggio senza IBAN né importo.
   - Alternativa: avvolgere tutto in un'eccezione dell'adapter (`OutboxWriteException`). Non serve, e il service non può conoscerla.
   - Sul test "payload non serializzabile": con un record di `String` e `BigDecimal` Jackson in pratica non fallisce. Proposta (R): il caso di errore di serializzazione si copre con le violazioni di formato del punto 7. Alternativa: un costruttore package-private che accetta il `JsonMapper` per iniettarne uno che lancia. È un aggancio solo per i test e con due costruttori serve `@Autowired`.

5. **Nomi (R).**
   - Classe `OutboxNotificationOutPortImpl` nel package esistente `com.bank.transfer.postgres.port.out.postgres.impl`, dove c'è già `DemoDataSeeder`. Il prefisso indica il meccanismo, come `Jpa...`, `InMemory...`, `Http...`.
   - Il record del payload è privato e annidato (`OutboxPayload`): non nasce nessun package nuovo.
   - Si scarta un sotto-package `impl.jdbc`, che non è nel modello di `transfer-service/CLAUDE.md` e richiederebbe approvazione.
   - Con l'opzione 2B: `impl.jpa.JpaOutboxNotificationOutPortImpl`, `repository.SpringDataOutboxRepository`, `repository.model.OutboxEntity`.

6. **[DECISIONE APERTA] Transazione.**
   - **R:** `@Transactional(propagation = Propagation.MANDATORY)` sul metodo `publish`. Partecipa a quella di `transactionTemplate.execute`. Senza transazione attiva lancia `IllegalTransactionStateException` prima di scrivere, quindi nessuna riga.
   - Alternative:
     - (a) nessuna annotazione: con `JdbcTemplate` andrebbe in auto-commit, con JPA aprirebbe una transazione propria. Bonifico e notifica non sarebbero più atomici in silenzio, quindi si sconsiglia.
     - (b) controllo esplicito con `TransactionSynchronizationManager.isActualTransactionActive()` -> `IllegalStateException`. Equivalente ma manuale.
   - L'annotazione `@Transactional` sull'adapter è ammessa: la restrizione su Spring vale per i service dell'application.

7. **[DECISIONE APERTA] Formato di `amount`, `occurredAt` e `currency`.**
   - **amount (R):**
     - Si normalizza con `setScale(2, RoundingMode.UNNECESSARY)`: `100.25` -> `100.25`, `100.2` -> `100.20`, `20000` -> `20000.00`, `100.2500` (scala 4 dal DB) -> `100.25`. È coerente con gli esempi del contratto (`100.25`, `20000.00`).
     - Più di 2 decimali significativi (`1.234`) -> `ArithmeticException`, convertita in `IllegalArgumentException`.
     - Si abilita `StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN`, mai `double`.
     - Alternative: arrotondare (`HALF_EVEN`), che però altera l'importo; oppure scala invariata/`stripTrailingZeros`, che produce `100.2` o `2E+4`.
     - **Conseguenza da accettare:** oggi `TransferRequestDto` e `Money` accettano `1.234`. Dalla tappa 5 un bonifico del genere fallirebbe in commit per colpa dell'outbox.
   - **occurredAt (R):** `Instant.now(clock).truncatedTo(ChronoUnit.SECONDS)`, formattato con `DateTimeFormatter.ISO_INSTANT` come `String` nel payload, per esempio `2026-10-13T09:30:00Z`, identico agli esempi. Così non si dipende da come Jackson serializza `Instant`.
     - Alternative: millisecondi, oppure `Instant.toString()` senza troncare (0, 3, 6 o 9 cifre frazionarie variabili, comunque ISO-8601 valido).
   - **currency (R):** si rifiuta con `IllegalArgumentException` se non rispetta `[A-Z]{3}`, come chiede la decisione 3 della tappa 03. Il contratto dice "3 lettere". Va deciso se accettare anche le minuscole: si raccomanda di no.

8. **Logging (R):** l'adapter non logga, come tutto il resto di `transfer-service`. Il test "nessun IBAN nei log" diventa allora: i messaggi delle eccezioni lanciate dall'adapter non contengono IBAN né importo.

### Modifiche per modulo

**`transfer-postgres-adapter`**

| File | Package | Responsabilità |
|---|---|---|
| `pom.xml` (modificato, **solo se approvato 1A**) | | Aggiunge `tools.jackson.core:jackson-databind`, senza versione, scope compile. |
| `src/main/java/.../impl/OutboxNotificationOutPortImpl.java` (nuovo) | `com.bank.transfer.postgres.port.out.postgres.impl` | `@Repository @Profile("!inmemory") public class OutboxNotificationOutPortImpl implements NotificationOutPort`. Costruttore `OutboxNotificationOutPortImpl(JdbcTemplate jdbcTemplate, Clock clock)`. `@Override @Transactional(propagation = MANDATORY) public void publish(TransferNotificationDto n)`. |
| comportamento di `publish` | | `requireNonNull(n)`, poi validazione di `currency` e della scala di `amount` (punto 7), poi `occurredAt` dal `Clock`. Costruisce il payload: `destIban` -> `destinationIban`, `status.name()`, `reasons` `null` se `COMPLETED` (omesso con `@JsonInclude(NON_NULL)`). Lo serializza e fa un solo `INSERT` con `transfer_id = n.transferId()`. Nessun catch, nessun log. |
| nested `private record OutboxPayload(String transferId, String sourceIban, String destinationIban, BigDecimal amount, String currency, String status, List<String> reasons, String occurredAt)` | | Forma del JSON del contratto §4. |
| `private static final JsonMapper JSON` | | Configurato con `WRITE_BIGDECIMAL_AS_PLAIN`. |
| `src/test/java/.../impl/OutboxNotificationOutPortImplTest.java` (nuovo) | stesso package | `@DataJpaTest @Import({OutboxNotificationOutPortImpl.class, FixedClock.class})`. `FixedClock` è un `@TestConfiguration` annidato con `Clock.fixed(Instant.parse("2026-10-13T09:30:00Z"), ZoneOffset.UTC)`. Usa `JdbcTemplate` per leggere e `JsonMapper` per il parse. |
| `src/test/java/.../impl/OutboxNotificationOutPortImplTransactionTest.java` (nuovo) | stesso package | `@DataJpaTest`, `@Transactional(propagation = NOT_SUPPORTED)` a livello di classe, con `TransactionTemplate`. Se il bean manca, si costruisce con `new TransactionTemplate(PlatformTransactionManager)`. `@AfterEach` con `DELETE FROM outbox`. |

Variante 2B, al posto della riga `JdbcTemplate`: `repository/model/OutboxEntity.java`, `repository/SpringDataOutboxRepository.java` e `impl/jpa/JpaOutboxNotificationOutPortImpl.java`. Il comportamento è lo stesso.

**`transfer-bootstrap`** (necessario, punto 3)

| File | Package | Responsabilità |
|---|---|---|
| `src/main/java/com/bank/transfer/config/ClockConfig.java` (nuovo) | `com.bank.transfer.config` | `@Configuration class ClockConfig { @Bean Clock clock() }` -> `Clock.systemUTC()`. |
| `src/test/java/com/bank/transfer/config/ClockConfigTest.java` (nuovo) | `com.bank.transfer.config` | `ApplicationContextRunner` come in `FraudConfigTest`. |

**Nessuna modifica** a `transfer-application` (né `NotificationOutPort` né `TransferNotificationDto`), `transfer-domain`, `transfer-memory-adapter`, `transfer-kafka-adapter`, `transfer-rest-adapter`, `transfer-fraud-adapter`, V1/V2, `application.properties`.

### Test attesi
Si scrivono prima del codice. Costanti dal contratto:
- `TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93"`
- IBAN `...0001` / `...0002`
- motivi `["Amount must not exceed 10000 EUR", "Source IBAN is locked"]`

Righe lette con `SELECT id, transfer_id, payload, created_at FROM outbox ORDER BY id` e l'helper case-insensitive come in `OutboxMigrationTest`. Il JSON si confronta con `readTree`, mai come stringa intera: con Jackson 3 l'ordine delle proprietà non va dato per scontato.

**`OutboxNotificationOutPortImplTest`** (transazionale, rollback per test)

| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | DTO `COMPLETED`, amount `100.25` | `publish` | 1 riga; `transfer_id` = `TRANSFER_ID`; il payload ha esattamente le chiavi `transferId, sourceIban, destinationIban, amount, currency, status, occurredAt`; nessuna chiave `reasons` né `destIban`; `status` = `"COMPLETED"`; `occurredAt` = `"2026-10-13T09:30:00Z"` |
| 2 | DTO `COMPLETED`, amount `100.25` | `publish` | `amount` è un nodo numerico (`isNumber()`, non testuale), `decimalValue` `isEqualByComparingTo("100.25")`; il testo grezzo contiene `"amount":100.25` |
| 3 | DTO `REJECTED`, amount `20000.00`, due motivi | `publish` | `reasons` è un array uguale ai due motivi, nell'ordine; `status` = `"REJECTED"`; il testo contiene `"amount":20000.00` |
| 4 | amount `100.2` / `20000` / `100.2500` | `publish` | il testo contiene rispettivamente `100.20` / `20000.00` / `100.25` (punto 7) |
| 5 | Clock fisso a `09:30:00.123456789Z` (Clock dedicato al test) | `publish` | `occurredAt` = `"2026-10-13T09:30:00Z"`, secondo la scelta del punto 7 |
| 6 | tabella vuota | due `publish`, anche con lo stesso `transferId` | 2 righe, `id` crescenti, entrambe con `transfer_id` corretto |
| 7 | `created_at` | dopo `publish` | non null (default del DB, l'adapter non lo scrive) |
| 8 (errore) | `notification = null` | `publish` | `NullPointerException`, nessuna riga |
| 9 (errore, payload non valido) | amount `1.234` | `publish` | `IllegalArgumentException`; il messaggio non contiene IBAN né `1.234`; nessuna riga |
| 10 (errore, payload non valido) | currency `"EURO"` / `"eur"` / `"E1R"` | `publish` | `IllegalArgumentException`, nessuna riga |
| 11 (errore, scrittura) | `transferId` di 37 caratteri (supera `VARCHAR(36)`) | `publish` | `DataIntegrityViolationException`, che risale non catturata. Nessuna query dopo l'eccezione (portabilità su Postgres) |
| 12 (opz., se scelta l'alternativa del punto 4) | `JsonMapper` che lancia | `publish` | `JacksonException` propagata, nessuna riga |
| 13 | eccezioni dei casi 9-11 | `getMessage()` | non contiene `IT60X...0001` né `IT60X...0002` |

**`OutboxNotificationOutPortImplTransactionTest`** (nessuna transazione del test)

| # | Dato | Quando | Allora |
|---|---|---|---|
| T1 | nessuna transazione attiva | `publish` | `IllegalTransactionStateException` (MANDATORY); `COUNT(*)` = 0 |
| T2 | `transactionTemplate.execute` con `publish` all'interno | commit | `COUNT(*)` = 1 dopo il commit: partecipa e viene committata dal chiamante |
| T3 | `execute` con `publish` e poi `status.setRollbackOnly()` | fine | `COUNT(*)` = 0: il rollback del chiamante annulla la riga |
| T4 | `execute` con `publish` e poi `RuntimeException` (simula il salvataggio del bonifico fallito, scenario 16) | eccezione | eccezione propagata; `COUNT(*)` = 0 |
| T5 | dentro `execute`, `publish` e poi `TransactionSynchronizationManager.isActualTransactionActive()` | | ancora `true` e stessa transazione: nessuna transazione nuova (`REQUIRES_NEW` escluso) |

**`ClockConfigTest`**

| # | Dato | Quando | Allora |
|---|---|---|---|
| C1 | contesto con `ClockConfig` | avvio | un solo bean `Clock`, `getZone()` = `ZoneOffset.UTC` |
| C2 | profilo `inmemory` | avvio | il bean `Clock` esiste comunque e il contesto parte |

I test esistenti restano invariati e verdi: `OutboxMigrationTest`, `JpaTransferRepositoryOutPortImplTest`, `JpaAccountRepositoryOutPortImplTest`, `DemoDataSeederTest`, `CreateTransferConcurrencyTest`, `FraudCheckEndToEndTest`, `FraudConfigTest`.

### Criteri di accettazione
- Si parte da `transfer-service/`. `.\mvnw.cmd test -pl transfer-postgres-adapter -am` e `.\mvnw.cmd test -pl transfer-bootstrap -am` sono verdi, senza Docker, tranne `TransferApplicationTests` che richiede Postgres come oggi.
- Nei pom l'unica modifica possibile è `tools.jackson.core:jackson-databind` in `transfer-postgres-adapter`, e solo se l'utente approva 1A. `.\mvnw.cmd dependency:tree -pl transfer-postgres-adapter` va lanciato dall'implementer e deve mostrare `jackson-databind:3.1.5`, prima via `flyway-core` e poi diretta.
- Il payload rispetta il contratto §4:
  - nomi dei campi esatti (`destinationIban`);
  - `amount` numerico con 2 decimali;
  - `reasons` assente con `COMPLETED`;
  - `occurredAt` ISO-8601 UTC dal `Clock`.
- Una `publish` produce una riga, con `transfer_id` = `transferId`.
- Senza transazione attiva non si scrive niente. Con il rollback del chiamante non resta nessuna riga (scenari 11 e 16).
- `git diff`: nessuna modifica a V1/V2, `NotificationOutPort`, `TransferNotificationDto`, `CreateTransferServiceImpl`, moduli memory, kafka, rest e fraud.
- L'adapter non importa nulla da altri adapter né da altri servizi, e non logga.

### Rischi e fuori perimetro
**Rischi**
- **Dipendenza transitiva** (se l'utente sceglie 1B): Jackson sparisce se Flyway cambia.
- **Proprietà di Jackson 3 da verificare al primo run:** l'ordine delle proprietà, che i test non controllano, e `@JsonInclude` dal package `com.fasterxml.jackson.annotation`, che in Jackson 3 resta quello e arriva con databind.
- **Il bootstrap non parte senza il `Clock`:** `ClockConfig` va aggiunta nella stessa modifica dell'adapter.
- **Profilo `inmemory`:** dalla tappa 5 manca un bean `NotificationOutPort` (punto 3).
- **Variante JPA (2B):** possibile errore di `ddl-auto=validate` su `payload TEXT` in H2 (CLOB) o in Postgres. Si vede solo nei test del bootstrap.
- **Ordine delle scritture con `JdbcTemplate`:** l'INSERT dell'outbox parte subito, mentre il flush JPA di conti e bonifico avviene al commit. Non c'è FK tra `outbox` e `transfer` e il rollback è unico, quindi l'atomicità resta intatta.
- **Più rigidità sul bonifico:** importi con più di 2 decimali o valute non `[A-Z]{3}` faranno fallire il bonifico dalla tappa 5 (punto 7). Va confermato.
- **Messaggi d'errore del database:** possono riportare valori. H2 può includere il valore troppo lungo di `transfer_id`, e un futuro errore sul payload potrebbe riportare IBAN. Chi logga le eccezioni a monte deve tenerne conto (contratto §7).
- **`MANDATORY` funziona solo tramite il proxy Spring:** chiamate dirette su `new OutboxNotificationOutPortImpl(...)` non controllano la transazione. I test passano sempre dal bean.
- **Il relay della tappa 6 sta in `transfer-kafka-adapter` ma deve leggere e cancellare `outbox`, che appartiene a questo modulo.** Senza una porta dedicata rischia di violare "gli adapter non si chiamano tra loro". Va progettato in quella tappa.

**Fuori perimetro**
- Chiamata a `publish` in `CreateTransferServiceImpl` (tappa 5).
- Relay Kafka, `transfer-kafka-adapter`, `spring-kafka`, proprietà Kafka.
- Lettura e cancellazione delle righe di `outbox`.
- Implementazione in-memory di `NotificationOutPort`.
- Modifiche a V1/V2, nuove migration, `TransferNotificationDto`, `NotificationOutPort`.
- Validazione di `amount`/`currency` nel dominio o nel rest.
- Test su Postgres reale.
- Commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **Jackson (punto 1): A.** Dipendenza diretta `tools.jackson.core:jackson-databind` (senza versione, la dà il BOM) nel pom di `transfer-postgres-adapter`. È l'unica modifica ai pom approvata, e solo una riga aggiunta.
2. **Accesso al database (punto 2): JPA (variante 2B), NON `JdbcTemplate`.** Entità `OutboxEntity` (`repository.model`), `SpringDataOutboxRepository` (`repository`) e `impl.jpa.JpaOutboxNotificationOutPortImpl`, come per Account e Transfer. Il mapping deve superare `ddl-auto=validate` sia su H2 (test) sia su Postgres: `id` identity BIGINT, `transfer_id` VARCHAR(36), `payload` colonna TEXT (H2 la tratta come CLOB: il planner/tester valuta `@Column(columnDefinition = "TEXT")` o `@JdbcTypeCode`), `created_at` NON scritto dall'adapter (`insertable = false, updatable = false`, default del database). Il rischio di validazione si controlla con i test del bootstrap (`CreateTransferConcurrencyTest`, `FraudCheckEndToEndTest`, `TransferApplicationTests`), che devono restare verdi.
3. **Clock e stile del bean (punto 3): R.** `ClockConfig` in `transfer-bootstrap` con `Clock.systemUTC()`; adapter `@Repository` (senza `@Profile`, il profilo `inmemory` non esiste più dopo transfer-06).
4. **Transazione (punto 6 del piano): `@Transactional(propagation = MANDATORY)`** sul metodo `publish`. `SimpleJpaRepository.save` è `REQUIRED` e si unisce alla transazione del chiamante: il test T1-T5 lo verifica.
5. **Formato (punto 7): R**, con la differenza che dopo transfer-05 il dominio garantisce già al massimo 2 decimali significativi; l'adapter normalizza con `setScale(2, RoundingMode.UNNECESSARY)` e valida `currency` `[A-Z]{3}`.
6. **Profilo `inmemory`: eliminato da transfer-06.** Decadono: `@Profile("!inmemory")` sull'adapter, l'opzione 1/2 del punto 3 (`InMemoryNotificationOutPortImpl`, no-op), il test C2 (profilo `inmemory`), le citazioni di `transfer-memory-adapter` e "moduli memory", il rischio sul profilo e il fuori perimetro sull'implementazione in-memory.
7. Gli esempi con `JdbcTemplate` nei test (lettura delle righe con `SELECT`) restano validi come modo di LEGGERE la tabella nei test, anche se la scrittura è via JPA.
8. **Il tester non scrive test sul relay né sulla lettura/cancellazione delle righe**: fuori perimetro.

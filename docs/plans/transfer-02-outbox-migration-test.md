## Piano transfer-02: OutboxMigrationTest

### Obiettivo
Verificare con un test `@DataJpaTest` su H2, eseguito tramite Flyway, che lo schema della tabella `outbox` creato dalla V2 già esistente rispetti la sezione 5 del contratto `transfer-notification`. Non si scrive codice di produzione.

### Ambiguità
Nessuna cambia il piano. Le tre verifiche richieste sono risolte, più due note.

1. **(a) Lo schema lo crea Flyway, non Hibernate. Verificato.** `src/test/resources` non esiste in nessun modulo di transfer-service, quindi non c'è un `application.properties` di test. I log surefire di `JpaTransferRepositoryOutPortImplTest` mostrano questa sequenza: `EmbeddedDatabaseFactory` avvia `jdbc:h2:mem:<uuid>`, poi `FlywayExecutor ... (H2 2.4)`, poi `Migrating schema "PUBLIC" to version "1 ..."`. `show-sql` è attivo, eppure non c'è nessun `Hibernate: create table`. Con Flyway nel classpath Boot imposta `ddl-auto=none` sul database embedded. L'ultimo run è precedente alla V2 (dice "Successfully applied 1 migration"). Al prossimo run Flyway applicherà anche `V2__create_outbox.sql`, che è già in `target/classes`.
2. **(b) H2 2.4.240 accetta la V2.** Il log riporta Flyway che avvisa "H2 2.4.240 newer than verified 2.3.232". Questa è solo un'avvertenza, la stessa che compare già con la V1. In H2 2.x `GENERATED ALWAYS AS IDENTITY` è supportato. `DEFAULT now()` è già usato nella V1, che passa su H2. `TEXT` in H2 diventa `CLOB` e si legge comunque con `queryForObject(..., String.class)` o con `rs.getString`. Il nome `OUTBOX` non è tra virgolette: H2 lo porta in maiuscolo, Postgres in minuscolo. Le query scritte con `outbox` senza virgolette funzionano su tutti e due. Su H2 la conferma arriva solo al primo run (vedi Rischi).
3. **(c) Lettura della riga e dell'id generato: si usa una SELECT, non `GeneratedKeyHolder`.** `@DataJpaTest` è transazionale e fa rollback a ogni test, quindi la tabella parte vuota. Dopo l'insert il test esegue `SELECT id, transfer_id, payload, created_at FROM outbox ORDER BY id` con `queryForList` oppure con un `RowMapper` locale. Questa via è portabile tra H2 e Postgres. Si scarta `KeyHolder` con `prepareStatement(sql, new String[]{"id"})` perché il nome della colonna chiave si comporta diversamente nei due database (maiuscole in H2, `RETURNING "id"` in Postgres): sarebbe un punto fragile senza benefici. Il test non inserisce mai `id` a mano, perché `GENERATED ALWAYS` lo rifiuta.
4. **Nota sul package.** Il CLAUDE.md radice dice "test nello stesso package della classe testata", ma qui non c'è una classe da testare (si testa una risorsa SQL). L'issue indica `com.bank.transfer.persistence`, lo stesso package di `PersistenceTestApplication`: si segue l'issue.
5. **Nota sul CLAUDE.md di transfer-service.** Dice che i test di `transfer-persistence-adapter` richiedono PostgreSQL su 5433. I log provano il contrario: `@DataJpaTest` sostituisce il DataSource con H2. Il test non richiede Docker. Correggere il CLAUDE.md è fuori perimetro.

### Modifiche per modulo

**`transfer-persistence-adapter`**

| File | Package | Responsabilità |
|---|---|---|
| `src/test/java/com/bank/transfer/persistence/OutboxMigrationTest.java` (nuovo, scritto dal tester) | `com.bank.transfer.persistence` | `@DataJpaTest` senza `@Import`. Si inietta `@Autowired JdbcTemplate`. Costanti `TRANSFER_ID_1`/`_2` (UUID testuali di 36 caratteri) e `PAYLOAD` (un JSON di esempio dal contratto, sezione 4). Helper privati: `insert(String transferId, String payload)` con `jdbcTemplate.update("INSERT INTO outbox (transfer_id, payload) VALUES (?, ?)", ...)`, e `rows()` con la SELECT ordinata per `id`. |

**Codice di produzione: nessuno.** L'implementer non ha nulla da scrivere. `V2__create_outbox.sql` e `V1__create_account_and_transfer.sql` restano intatti. Nessun pom cambia. Nessun file in `src/main` viene toccato. Nessun altro modulo è coinvolto: application, domain, web, fraud, notification-adapter e bootstrap restano invariati.

### Test attesi
Classe `com.bank.transfer.persistence.OutboxMigrationTest`. Si scrivono prima e devono risultare **verdi al primo run** perché la migration esiste già. Il tester lancia `.\mvnw.cmd test -pl transfer-persistence-adapter -am` da `transfer-service/` e riporta l'esito. Il report deve mostrare la riga di log di Flyway `now at version v2`.

| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | tabella vuota | insert con `TRANSFER_ID_1` e `PAYLOAD` | una sola riga; `transfer_id` = `TRANSFER_ID_1`; `payload` = `PAYLOAD`; `id` non null e maggiore di 0; `created_at` non null |
| 2 | tabella vuota | insert di `TRANSFER_ID_1`, poi di `TRANSFER_ID_2` | `SELECT ... ORDER BY id` restituisce `[TRANSFER_ID_1, TRANSFER_ID_2]`, con `id` della prima riga minore di `id` della seconda. L'ordine si verifica solo su `id`, non su `created_at` (vedi Rischi) |
| 3 (errore) | tabella vuota | `INSERT INTO outbox (payload) VALUES (?)`, senza `transfer_id` | `assertThatThrownBy(...).isInstanceOf(DataIntegrityViolationException.class)` |
| 4 (errore) | tabella vuota | `INSERT INTO outbox (transfer_id) VALUES (?)`, senza `payload` | `DataIntegrityViolationException` |
| 5 | una riga inserita, `id` letto con la SELECT | `DELETE FROM outbox WHERE id = ?` | `update` restituisce 1 e `SELECT COUNT(*) FROM outbox` restituisce 0 |
| 6 | tabella vuota | due insert con lo stesso `TRANSFER_ID_1` (payload uguali o diversi) | nessuna eccezione; `COUNT(*)` = 2; due `id` distinti. Si documenta con un commento che l'assenza di UNIQUE è voluta (at-least-once, contratto sezione 5) |

Un caso facoltativo è a discrezione del tester, non richiesto dall'issue: per i test 3 e 4, verificare anche la variante con valore `null` esplicito (`VALUES (NULL, ?)`), con lo stesso esito.

Collegamento al contratto: i test 1, 5 e 6 coprono la sezione 5 (riga scritta, eliminata dopo la conferma, possibile ripubblicazione) e la base degli scenari 11, 13, 14 e 15. Gli scenari 12 e 16 sono transazionali e restano fuori perimetro.

### Criteri di accettazione
- `OutboxMigrationTest` ha 6 test, tutti verdi al primo run, senza Docker (H2 embedded).
- Gli altri test del modulo restano verdi e invariati: `JpaTransferRepositoryOutPortImplTest`, `JpaAccountRepositoryOutPortImplTest`, `DemoDataSeederTest` e i due test InMemory.
- Il log del run mostra che Flyway applica la V2 su H2. Lo schema non viene da Hibernate.
- `git diff` contiene solo il nuovo file di test. Nessuna modifica a `V1__*.sql` o `V2__*.sql`, ai pom.xml, a `src/main` o a `PersistenceTestApplication`.
- Il test usa solo SQL standard (`INSERT`, `SELECT ... ORDER BY id`, `DELETE`, `COUNT`), valido sia su H2 sia su Postgres. Non contiene `KeyHolder`, sintassi propria di un solo database o nomi tra virgolette.
- Il test non importa nulla da altri servizi né dal dominio.

### Rischi e fuori perimetro

**Rischi**
- **V2 su H2 non ancora eseguita.** Se il primo run fallisce sulla migration, lo stop è immediato: non si modifica la V2. Si torna dall'utente, che decide su una nuova migration.
- **`JdbcTemplate` in `@DataJpaTest` con Boot 4.1.1.** In Boot 3 `JdbcTemplateAutoConfiguration` fa parte della slice. In Boot 4 i moduli sono stati divisi e non ho potuto ispezionare il contenuto di `spring-boot-data-jpa-test-4.1.1.jar`. Se il bean manca, il ripiego è `new JdbcTemplate(dataSource)` a partire dal `DataSource` iniettato. Partecipa comunque alla transazione del test, perché `JpaTransactionManager` espone la connessione. Il ripiego non richiede dipendenze nuove.
- **`created_at` e `now()`.** In H2 e in Postgres `now()` restituisce l'istante di inizio della transazione. Due insert nello stesso test hanno quindi lo stesso `created_at`, ed è per questo che l'ordine si verifica solo su `id`. Non si fanno confronti con l'orologio, per evitare problemi di fuso orario (`TIMESTAMP` senza zona).
- **Postgres dopo un errore.** Un NOT NULL violato manda la transazione in stato aborted. Ogni caso di errore deve stare in un test separato e non fare altre query dopo l'eccezione, così il test resta portabile se un giorno girerà su Postgres. Su H2 il problema non si presenta.
- **Traduzione delle eccezioni.** Il SQLState 23502 deve diventare `DataIntegrityViolationException`. È la traduzione standard sia per H2 sia per Postgres.
- **Nessuna dipendenza nuova.** `spring-boot-starter-data-jpa-test`, H2, Flyway, JUnit e AssertJ sono già nel pom del modulo.

**Fuori perimetro**
- `NotificationOutPort` e qualsiasi porta in `transfer-application`.
- Entità JPA `OutboxEntity`, repository Spring Data e adapter dell'outbox.
- Relay o publisher Kafka, `transfer-notification-adapter` e configurazione Kafka.
- Modifiche a `CreateTransferServiceImpl`, quindi anche la scrittura atomica bonifico più outbox (scenari 11, 12 e 16).
- Indici su `created_at`/`id`, vincoli UNIQUE e nuove migration. Ogni cambio di schema va chiesto e farebbe una `V3`.
- Test su Postgres reale (Testcontainers o Docker).
- Correzione del CLAUDE.md di transfer-service sulla necessità del database per i test di persistence.
- Commit.

**File di riferimento**
- `transfer-service/transfer-persistence-adapter/src/main/resources/db/migration/V2__create_outbox.sql`
- `transfer-service/transfer-persistence-adapter/src/test/java/com/bank/transfer/persistence/PersistenceTestApplication.java`
- `transfer-service/transfer-persistence-adapter/src/test/java/com/bank/transfer/persistence/port/out/persistence/impl/jpa/JpaTransferRepositoryOutPortImplTest.java`
- `docs/contracts/transfer-notification.md` (sezione 5, scenari 11-16)
- Da creare: `transfer-service/transfer-persistence-adapter/src/test/java/com/bank/transfer/persistence/OutboxMigrationTest.java`

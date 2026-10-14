## Piano transfer-07: chiamata a `NotificationOutPort` in `CreateTransferServiceImpl`

### Obiettivo
Ogni bonifico concluso (COMPLETED o REJECTED) deve chiamare `NotificationOutPort.publish(TransferNotificationDto)` dentro `transactionTemplate.execute`, nella stessa transazione dei save. Così bonifico e riga di `outbox` vengono salvati insieme oppure nessuno dei due (contratto `transfer-notification` §5, scenari 11, 12 e 16).

### Ambiguità
Il planner ha controllato ogni punto leggendo il codice. Nessuno cambia la struttura del piano: ognuno cambia solo le righe di test indicate. Ciascuno ha una raccomandazione (R) e va confermato dall'utente.

1. **Ordine delle chiamate nella transazione e origine del DTO.**
   - **R:** `publish` va subito dopo `transferRepositoryOutPort.save(...)`, in tutti e due i rami.
     - COMPLETED: `completeTransfer` (lock, `charge`/`credit`, save dei 2 conti, save del bonifico) restituisce `completed`, poi `publish(dto(completed, reasons))`.
     - REJECTED: `save(rejected)`, poi `publish(dto(rejected, reasons))`.
     - La chiamata sta nella lambda di `executeTransfer`, non dentro `completeTransfer`, che resta dedicata al movimento di denaro. Un helper privato statico `toNotification(Transfer concluded, List<String> reasons)` costruisce il DTO.
   - **Il DTO si costruisce dal `Transfer` concluso locale** (`completed` / `rejected`), **mai dal valore restituito da `save`**:
     - oggi il valore di ritorno viene ignorato;
     - nei test con Mockito `save` restituisce `null`, quindi usarlo romperebbe con NPE tutti i test esistenti;
     - in JPA è una rilettura dell'entità, non serve.
   - Mappatura dei campi: `id()`, `source().value()`, `dest().value()`, `money().amount()`, `money().currency()`, `status()`. Gli IBAN sono già normalizzati da `Iban`.
   - **Differenza se `publish` fallisce.**
     - Se sta dopo i save: i save sono già chiamati ma non ancora scritti su DB (il flush JPA avviene al commit). L'eccezione esce dalla lambda, `TransactionTemplate` fa rollback e la rilancia: non resta nulla.
     - Se stesse prima dei save: nel ramo COMPLETED andrebbe chiamata prima di `charge`, quindi si pubblicherebbe per bonifici che poi falliscono per saldo insufficiente (con rollback, ma con lavoro inutile e un ordine poco chiaro). La sconsiglia.
   - **Nota sull'SQL reale (dall'adapter).** `OutboxEntity` ha `@GeneratedValue(IDENTITY)`, quindi Hibernate esegue l'INSERT in `outbox` subito alla `save`. UPDATE dei conti e INSERT del bonifico partono invece al flush del commit. Su DB la riga di outbox arriva quindi per prima, qualunque sia l'ordine delle chiamate. L'atomicità non cambia, perché il rollback è unico. Va solo tenuto presente leggendo i log SQL.

2. **Modifica all'esistente `CreateTransferServiceImplTest`: richiede l'approvazione dell'utente.**
   - Il SUT si costruisce con `new` esplicito in `@BeforeEach` (righe 67-71, senza `@InjectMocks`): `new CreateTransferServiceImpl(accountRepository, transferRepository, fraudCheck, new TransactionTemplate(transactionManager))`. Con un parametro in più non compila più nessun test della classe.
   - Righe da toccare:
     - **righe 69-70 (unica modifica di una riga esistente):** si aggiunge `notification` agli argomenti, nella posizione scelta al punto 7.
     - **inserimenti senza modificare righe:** un campo `@Mock private NotificationOutPort notification;` dopo la riga 60, l'import di `NotificationOutPort` (zona riga 20-22) e l'import di `TransferNotificationDto` (zona riga 16).
   - Nessun test esistente cambia asserzioni. I `verifyNoInteractions(...)` esistenti (righe 194, 206, 219, 267, 291, 303) restano come sono: il controllo su `notification` va nei test nuovi.
   - Con il `MockitoExtension` stretto non ci sono problemi: `publish` è `void` e non va stubbato.
   - **Non c'è un'alternativa meno invasiva accettabile.**
     - Un secondo costruttore a 4 parametri richiederebbe una porta nulla o no-op nel main. Inoltre, con due costruttori Spring ha bisogno di `@Autowired`, che nel service è vietato (sono ammessi solo `@Service`/`@Transactional`).
     - `@InjectMocks` cambierebbe comunque il setup (righe 65-71) e lo renderebbe implicito.
   - Proposta: solo le righe 69-70 modificate, tutto il resto in aggiunta.

3. **Altri punti che costruiscono `CreateTransferServiceImpl` a mano.** Il grep su `transfer-service/**/*.java` trova **solo** `CreateTransferServiceImplTest:69`.
   - `CreateTransferConcurrencyTest`, `FraudCheckEndToEndTest` e `TransferApplicationTests` ricevono `CreateTransferInPort` da Spring.
   - Nel bootstrap nessuna config crea il service: è un `@Service` trovato dalla scansione dei componenti, quindi Spring inietta il nuovo parametro da solo.
   - `JpaOutboxNotificationOutPortImpl` è già un bean, perché i 21 test del bootstrap partono già con lui e con `ClockConfig`.
   - Nessuna modifica al main del bootstrap.

4. **Motivi nella notifica COMPLETED.** L'issue dice "da `fraudCheckResultDto.reasons()`: per COMPLETED lista vuota".
   - Oggi `HttpFraudCheckOutPortImpl` (riga 71) restituisce `List.of()` per ALLOWED. Il caso "ALLOWED con motivi" però non è escluso dal tipo `FraudCheckResultDto`, e il DTO di notifica lancia `IllegalArgumentException` per un COMPLETED con motivi.
   - (a) passare `fraudCheckResultDto.reasons()`: un ALLOWED con motivi farebbe fallire il bonifico (rollback, 400);
   - (b) **R:** passare `List.of()` per COMPLETED e `fraudCheckResultDto.reasons()` per REJECTED: la notifica COMPLETED è sempre valida e i motivi spuri non bloccano il bonifico.
   - Il piano transfer-03 (Rischi) chiede un test di questo caso: è il test S13, con l'esito che dipende dalla scelta. `TransferResultDto` resta com'è.

5. **Nuovo motivo di fallimento: valuta non `[A-Z]{3}` o vuota.** Nessuno valida la valuta prima: `Money`, `TransferRequestDto` e `CreateTransferCommand` non lo fanno.
   - Ramo REJECTED: un POST con `"currency":"eur"`, che oggi viene salvato REJECTED con 200, diventerà 400 con il messaggio "currency must be three uppercase letters" dell'adapter, dopo che il Fraud è già stato interpellato.
   - Ramo COMPLETED: una valuta diversa da quella dei conti fallisce già prima con `DifferentCurrencyException` (422).
   - È coerente con "la notifica non deve perdersi" (fail closed), ma cambia la risposta dell'API. Va confermato. La validazione a monte è fuori perimetro.

6. **Dove vanno i test di integrazione e quante classi nuove.**
   - **R:** gli scenari 11 e 12 via HTTP vanno come **metodi nuovi in `FraudCheckEndToEndTest`**, solo aggiunte. Lì ci sono già `FakeFraud`, `jdbcTemplate`, `transferIdOf`, `postTransfer(String)` e un contesto H2 in cache. È lo stesso precedente approvato in transfer-05 (decisione 4). Servono:
     - un helper `outboxPayloadsFor(String transferId)`;
     - un helper `outboxRowsMentioning(String iban)`, cioè `select count(*) from outbox where payload like ?` con `%"sourceIban":"<iban>"%`;
     - per il conto inesistente, un overload `postTransfer(String source, String dest, String amount)`.
   - **Lo scenario 16 va in una nuova classe, una sola:** `com.bank.transfer.OutboxRollbackIntegrationTest`.
     - Serve un `NotificationOutPort` `@Primary` che **delega al bean reale e poi lancia**. Il bean reale si inietta con `@Qualifier("jpaOutboxNotificationOutPortImpl")`. Così si dimostra che anche la riga già inserita viene annullata.
     - Messo in `FraudCheckEndToEndTest`, questo bean cambierebbe il contesto di tutti i suoi test esistenti. Alternativa scartata per questo motivo: un wrapper con interruttore dentro `FraudCheckEndToEndTest`.
     - Altra alternativa, senza doppi: valuta `"EURO"` sul ramo REJECTED (punto 5). Copre però solo un errore *prima* della scrittura, non un errore di scrittura.
     - Costo: un contesto Spring in più, con un DB H2 dedicato (`jdbc:h2:mem:outboxrollback`).
   - Il payload si verifica con asserzioni su stringhe e regex, come fa già la classe (`containsPattern("\"amount\":123\\.45[,}]")`), **non con `JsonMapper`**. Jackson nel bootstrap arriva solo come transitiva del web, e così non c'è nessuna nuova dipendenza.

7. **Posizione del nuovo parametro del costruttore.**
   - **R:** `(AccountRepositoryOutPort, TransferRepositoryOutPort, FraudCheckOutPort, NotificationOutPort, TransactionTemplate)`: le porte insieme e l'infrastruttura in fondo.
   - Alternativa: in ultima posizione.
   - In entrambi i casi l'unico chiamante a mano sono le righe 69-70 del test.

8. **`CreateTransferConcurrencyTest`.**
   - Il significato non cambia. I bonifici riusciti scrivono una riga ciascuno. Quelli rifiutati per `InsufficientFundException` (lanciata in `charge`, prima di `publish`) fanno rollback e non scrivono nulla.
   - La riga di outbox non tocca i record dei conti. IDENTITY su H2 non blocca, quindi non c'è un nuovo rischio di deadlock da ordine di lock.
   - **R:** non toccare i 3 metodi esistenti e aggiungere un solo metodo nuovo (vedi Test attesi) che conta le righe. In alternativa si aggiungerebbero asserzioni nei metodi esistenti, ma sarebbe una modifica di test esistenti.

9. **Test con `FraudCheckOutPort` `@Primary` (`CreateTransferConcurrencyTest`).**
   - Il `@Primary` riguarda solo l'antifrode. `NotificationOutPort` ha un solo bean (`JpaOutboxNotificationOutPortImpl`, senza profilo), quindi l'iniezione è univoca e quei test scrivono davvero in `outbox`.
   - Fa eccezione solo la nuova classe del punto 6, dove il `@Primary` di notifica è voluto.
   - Le righe si accumulano nel DB H2 di ogni contesto, perché non c'è relay e nessuna pulizia. Le asserzioni devono quindi filtrare per `transferId` o IBAN, mai fare `count(*)` sull'intera tabella.

10. **Note che non bloccano.**
    - Il service usa già `TransactionTemplate`, una classe Spring diversa da `@Service`/`@Transactional` ammessi dal `CLAUDE.md` radice: è una deviazione che esiste già, fuori perimetro.
    - `TransferNotificationDto` contiene l'enum di dominio `TransferStatusEnum`: è una porta out e ha il precedente di `FraudCheckResultDto`.

**Verifica dell'adapter (per il reviewer in parallelo): nulla contraddice questo piano.**
- `@Transactional(MANDATORY)` è soddisfatto da `transactionTemplate.execute`.
- `reasons` vuoto diventa `null` e il campo è omesso, come chiede il contratto per COMPLETED.
- `setScale(2, UNNECESSARY)` non incontra più importi con 3 decimali dopo transfer-05.

Conseguenze da conoscere:
- (i) INSERT immediato per IDENTITY (punto 1);
- (ii) valuta che fa fallire il bonifico (punto 5);
- (iii) un'eccezione di DB, per esempio `DataIntegrityViolationException`, non ha handler in `WebExceptionHandler`, quindi diventa 500 generico. Il suo messaggio potrebbe includere il payload, cioè gli IBAN, nel log di errore di Spring.

### Modifiche per modulo

**`transfer-application`**

| File | Package | Responsabilità |
|---|---|---|
| `src/main/java/com/bank/transfer/application/service/CreateTransferServiceImpl.java` (modificato) | `com.bank.transfer.application.service` | Nuovo campo `private final NotificationOutPort notificationOutPort`. Costruttore `CreateTransferServiceImpl(AccountRepositoryOutPort, TransferRepositoryOutPort, FraudCheckOutPort, NotificationOutPort, TransactionTemplate)` (posizione: punto 7). Nella lambda di `executeTransfer`: ramo ALLOWED con `completed = completeTransfer(transfer)`, poi `notificationOutPort.publish(toNotification(completed, <motivi: punto 4>))`; ramo REJECTED con `save(rejected)`, poi `publish(toNotification(rejected, fraudCheckResultDto.reasons()))`. Nuovo `private static TransferNotificationDto toNotification(Transfer concluded, List<String> reasons)`. Nessun catch e nessun log. Non cambiano: `requireExistingAccount`, `completeTransfer`, `getAccountsMap`, la validazione prima della transazione e la chiamata al Fraud fuori dalla transazione. Nessun import Spring nuovo. |
| `src/test/java/com/bank/transfer/application/service/CreateTransferServiceImplTest.java` (righe 69-70 modificate, il resto aggiunto) | stesso package | `@Mock NotificationOutPort notification` e i nuovi test S1-S16. |

**`transfer-bootstrap`** (solo test)

| File | Package | Responsabilità |
|---|---|---|
| `src/test/java/com/bank/transfer/FraudCheckEndToEndTest.java` (solo aggiunte) | `com.bank.transfer` | Helper `outboxPayloadsFor(transferId)`, `outboxRowsMentioning(iban)`, `postTransfer(source, dest, amount)`. Metodi nuovi E1-E7. |
| `src/test/java/com/bank/transfer/OutboxRollbackIntegrationTest.java` (nuovo) | `com.bank.transfer` | `@SpringBootTest` su H2 `outboxrollback`. `@TestConfiguration` con un `FraudCheckOutPort` `@Primary` dalla decisione configurabile (campo `volatile` / `AtomicReference`) e un `NotificationOutPort` `@Primary` che delega al bean `jpaOutboxNotificationOutPortImpl`, registra il DTO ricevuto e poi lancia `IllegalStateException("outbox write failed")`. Casi R1-R2. |
| `src/test/java/com/bank/transfer/CreateTransferConcurrencyTest.java` (solo aggiunte, punto 8) | `com.bank.transfer` | Un campo `@Autowired JdbcTemplate` e un metodo nuovo C1. |

**Nessuna modifica** a: `NotificationOutPort`, `TransferNotificationDto`, `JpaOutboxNotificationOutPortImpl`, `OutboxEntity`, migration V1/V2, contratto, `ClockConfig`, `FraudConfig`, `application.properties`, `transfer-kafka-adapter`, `transfer-rest-adapter` (compreso `WebExceptionHandler`), `transfer-fraud-adapter`, domain, pom, Fraud, seed demo.

### Test attesi
I test si scrivono prima del codice.
- Rosso atteso: dopo la modifica delle righe 69-70 la classe non compila finché il costruttore non ha il nuovo parametro.
- Poi S1-S7 e S13 sono rossi finché manca la chiamata a `publish`.
- Le verifiche "mai chiamato" (S8-S12) sono verdi già prima: servono come regressione.

**`CreateTransferServiceImplTest`** (Mockito, `ArgumentCaptor<TransferNotificationDto>`, importi con `isEqualByComparingTo`)

| # | Dato | Quando | Allora |
|---|---|---|---|
| S1 | conti 100/50 EUR, Fraud ALLOWED con `List.of()` | `executeTransfer(30)` | `publish` chiamato 1 volta. DTO: `transferId` = id del `Transfer` salvato e del risultato, `sourceIban` = `SOURCE_IBAN`, `destIban` = `DEST_IBAN`, `amount` uguale per `isEqualByComparingTo` a 30, `currency` "EUR", `status` COMPLETED, `reasons` vuota |
| S2 | Fraud REJECTED con 2 motivi | `executeTransfer` | `publish` chiamato 1 volta: status REJECTED, `reasons` `containsExactly` i 2 motivi nell'ordine, `transferId` = id del bonifico salvato, IBAN e importo corretti |
| S3 | come S1 | `executeTransfer` | `inOrder(fraudCheck, accountRepository, transferRepository, notification)`: `checkFraud` -> `findByIbanForUpdate` x2 -> `save(Account)` x2 -> `save(Transfer)` -> `publish` |
| S4 | come S2 | `executeTransfer` | `inOrder`: `save(Transfer)` -> `publish` |
| S5 | come S1 | `executeTransfer` | `inOrder(transactionManager, notification)`: `getTransaction(any())` -> `publish` -> `commit(any())`, cioè `publish` sta dentro la transazione |
| S6 (errore) | come S1, `publish` lancia `RuntimeException ex` | `executeTransfer` | `isSameAs(ex)`, propagata e non catturata; `rollback(any())` chiamato; `commit` mai chiamato |
| S7 (errore) | come S2, `publish` lancia `ex` | `executeTransfer` | stessa eccezione propagata, `rollback` sì, `commit` no |
| S8 (errore) | Fraud lancia `FraudCheckUnavailableException` | `executeTransfer` | eccezione propagata; `verifyNoInteractions(notification)` |
| S9a/S9b (errore) | conto sorgente / destinazione inesistente | `executeTransfer` | `AccountNotFoundException`; `verifyNoInteractions(notification)` |
| S10 (errore) | importo `1.234` (e `0.001`); IBAN uguali | `executeTransfer` | `IllegalArgumentException`; `verifyNoInteractions(notification)` |
| S11 (errore) | saldo 10, importo 30, ALLOWED | `executeTransfer` | `InsufficientFundException`; `publish` mai chiamato; `rollback` sì |
| S12 (errore) | conto destinazione in USD, ALLOWED | `executeTransfer` | `DifferentCurrencyException`; `publish` mai chiamato |
| S13 (dipende dal punto 4) | Fraud ALLOWED con `List.of("x")` | `executeTransfer` | (b, R): COMPLETED e `publish` con `reasons` vuota. (a): `IllegalArgumentException`, `rollback` sì, `commit` no, `publish` mai chiamato |
| S14 | IBAN sorgente `"it60 x054 2811 1010 0000 0000 001"` | `executeTransfer` | DTO `sourceIban` = `SOURCE_IBAN` normalizzato |
| S15 | importo `30.00`, ALLOWED | `executeTransfer` | DTO `amount` uguale a 30 per `isEqualByComparingTo` (il service non cambia la scala) |
| S16 | come S1 | `executeTransfer` | `transferId` del DTO = id inviato al Fraud = `result.transferId()` |

**`FraudCheckEndToEndTest`** (aggiunte; righe di outbox filtrate per `transferId` o IBAN)

| # | Dato | Quando | Allora |
|---|---|---|---|
| E1 | FakeFraud ALLOWED | POST 123.45 | 200 COMPLETED. Esattamente 1 riga con `transfer_id` = id restituito. Il payload contiene `"transferId":"<id>"`, `"sourceIban":"<source>"`, `"destinationIban":"<dest>"`, `"currency":"EUR"`, `"status":"COMPLETED"` e `"amount":123.45` (regex `[,}]`). Non contiene `"reasons"` né `"destIban"`. `occurredAt` rispetta `\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z` ed è compreso tra gli istanti presi prima e dopo il POST (troncati al secondo) |
| E2 | FakeFraud REJECTED con 2 motivi | POST | 200 REJECTED; 1 riga; payload con `"status":"REJECTED"` e `"reasons":["Source IBAN is locked","Destination IBAN is locked"]` |
| E3 (errore) | scenari di indisponibilità (nuovo `@ParameterizedTest` su `unavailableScenarios()`, oppure solo il 500 per non ripetere il secondo di attesa di t19) | POST | 503; `outboxRowsMentioning(source)` = 0 |
| E4 (errore) | `dest` mai creato | POST | 404; nessuna riga per `source`; `FRAUD.requests()` vuoto |
| E5 (errore) | importo `1.234` | POST | 400; nessuna riga |
| E6 (errore) | importo `2000` con saldo 1000, ALLOWED | POST | 422; nessuna riga; nessun bonifico salvato; saldi invariati |
| E7 (facoltativo, punto 5) | REJECTED con `"currency":"EUR"` minuscola (`"eur"`) | POST | 400; nessun bonifico salvato; nessuna riga. Documenta il nuovo comportamento |

**`OutboxRollbackIntegrationTest`** (scenario 16)

| # | Dato | Quando | Allora |
|---|---|---|---|
| R1 (errore) | conti 1000/0, Fraud ALLOWED, la notifica delega e poi lancia | `createTransfer.executeTransfer(...)` | l'eccezione risale (stesso tipo e messaggio); 0 righe in `transfer` per `source`; 0 righe in `outbox` per il `transferId` registrato dal wrapper (la riga era stata scritta e poi annullata); saldi 1000/0 |
| R2 (errore) | Fraud REJECTED con 1 motivo, stesso wrapper | `executeTransfer` | eccezione propagata; nessun bonifico REJECTED salvato; 0 righe in `outbox` |

**`CreateTransferConcurrencyTest`** (aggiunta)

| # | Dato | Quando | Allora |
|---|---|---|---|
| C1 | conti `P00007`=1000 e `P00008`=0, 20 bonifici paralleli da 100 | `runTogether` | 10 riusciti; righe `outbox` con `"sourceIban":"...P00007"` = 10, cioè una per ogni bonifico riuscito e nessuna per i rifiutati |

I test esistenti restano verdi e con le stesse asserzioni. Cambiano solo le righe 69-70 del setUp di `CreateTransferServiceImplTest` (punto 2).

### Criteri di accettazione
- Da `transfer-service/`, con Postgres su 5433: `.\mvnw.cmd clean test` è verde. Il totale è 255 + nuovi test: application 54 + ~16, bootstrap 21 + ~10 (E3 parametrizzato conta per scenario), gli altri moduli invariati.
- `git diff` nel main tocca solo `CreateTransferServiceImpl.java`. Nei test l'unica riga esistente modificata è il costruttore in `CreateTransferServiceImplTest` (righe 69-70), il resto sono aggiunte. È in più la classe nuova `OutboxRollbackIntegrationTest`.
- Nessun pom modificato e nessuna dipendenza nuova: nei test del bootstrap niente `JsonMapper`.
- Il service importa solo porte, DTO, command, eccezioni dell'application, dominio e i tipi Spring già presenti. Nessun adapter e nessuna classe di altri servizi.
- Contratto §5 e scenari 11, 12, 16: una riga per ogni bonifico COMPLETED o REJECTED, nessuna per i casi 503, 404, 400 e 422, nessuna se la scrittura della notifica fallisce, e in quel caso nessun bonifico salvato.
- Payload conforme a §4: verificati `destinationIban`, `reasons` assente con COMPLETED, `amount` con 2 decimali e `occurredAt` ISO UTC.

### Rischi e fuori perimetro
**Rischi**
- Cambia il comportamento dell'API: valute non `[A-Z]{3}` o vuote sul ramo REJECTED passano da 200 a 400 (punto 5). Un errore del DB sull'outbox diventa 500 generico, e il messaggio, che può contenere IBAN, finisce nel log (punto 10).
- Un ALLOWED con motivi spuri fa fallire il bonifico se si sceglie 4a.
- Un contesto Spring in più (`OutboxRollbackIntegrationTest`) allunga la build. E3 parametrizzato aggiunge circa un secondo per lo scenario t19.
- Le righe di outbox si accumulano negli H2 dei test (nessun relay): le asserzioni devono filtrare per `transferId` o IBAN.
- La regex su `occurredAt` e i limiti di tempo usano il `Clock` reale. Per evitare test instabili il confronto va fatto troncando al secondo.
- L'iniezione per qualificatore `jpaOutboxNotificationOutPortImpl` dipende dal nome di default del bean: se l'adapter viene rinominato, il test di rollback non parte.
- Nessuna nuova dipendenza.

**Fuori perimetro**
- Relay Kafka: lettura e cancellazione delle righe di outbox, `transfer-kafka-adapter`, `spring-kafka`, proprietà Kafka. Scenari 13-15.
- Modifiche a adapter dell'outbox, `OutboxEntity`, `TransferNotificationDto`, `NotificationOutPort`, migration, contratto, Fraud, seed demo, `WebExceptionHandler`.
- Validazione della valuta a monte, cioè nel dominio o nel rest.
- La deviazione `TransactionTemplate` rispetto al `CLAUDE.md` radice.
- Nuove dipendenze, commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **Ambiguità 2:** approvata la modifica alle righe 69-70 di `CreateTransferServiceImplTest` (aggiunta di `notification` al costruttore), più le sole aggiunte descritte nel piano (campo `@Mock`, import, nuovi test). Nessuna asserzione esistente cambia.
2. **Ambiguità 7:** posizione del parametro: `(AccountRepositoryOutPort, TransferRepositoryOutPort, FraudCheckOutPort, NotificationOutPort, TransactionTemplate)`.
3. **Ambiguità 4 (R, variante b):** per COMPLETED il DTO riceve sempre `List.of()`; per REJECTED `fraudCheckResultDto.reasons()`. Il test S13 verifica la variante (b).
4. **Ambiguità 5:** una valuta non `[A-Z]{3}` sul ramo REJECTED passa da 200 a 400; il test E7 (facoltativo) è INCLUSO.
5. **Ambiguità 6 e 8:** gli scenari 11 e 12 come metodi nuovi in `FraudCheckEndToEndTest`; lo scenario 16 nella classe nuova `OutboxRollbackIntegrationTest` (H2 dedicato); un solo metodo C1 nuovo in `CreateTransferConcurrencyTest`.

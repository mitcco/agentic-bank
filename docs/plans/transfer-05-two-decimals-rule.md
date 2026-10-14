## Piano transfer-05: al massimo 2 decimali significativi sull'importo del Transfer

### Obiettivo
Il record `Transfer` deve rifiutare con `IllegalArgumentException` gli importi con più di 2 decimali significativi. Così un bonifico come `1.234` si ferma prima di toccare i conti, il Fraud e il database, e il contratto `transfer-notification` §4 è rispettato a monte.

### Ambiguità
Il planner ha verificato tutti i punti leggendo il codice. Nessuno cambia il piano, quindi il piano continua. Sono comunque da confermare.

1. **Dove va la regola (punto 1).**
   - `Transfer.create` (riga 22) chiama il costruttore canonico, e così fanno anche `complete()` e `reject()` (righe 26-34). Basta quindi metterla nel costruttore compatto: aggiungerla anche in `create` sarebbe una copia inutile.
   - Posizione proposta: subito dopo `money.amount().signum() <= 0` e prima di `source.equals(dest)`. Così `0` e `0.000` danno ancora "has to be greater than 0".
   - Conseguenza: se l'importo ha troppi decimali e anche gli IBAN coincidono, il messaggio restituito è quello dei decimali.
   - Il messaggio dell'issue è dato come "esempio". Il piano usa esattamente `"Transfer amount can have at most 2 decimals"`. Va confermato.

2. **Dati esistenti e di seed (punto 1).**
   - `DemoDataSeeder` usa `100.00`, `50.25`, `5000.00`: tutti validi. Lo stesso vale per i seed di `InMemoryTransferRepositoryOutPortImpl`, che si verificano solo leggendo, senza modifiche.
   - V1 dichiara `amount NUMERIC(19,4)`, `TransferEntity` ha `scale = 4` e V2 non inserisce bonifici. Un valore riletto come `10.5000` torna `10.5` con `stripTrailingZeros` e passa.
   - `JpaTransferRepositoryOutPortImpl.toDomain` ricostruisce `Transfer` solo in `save` (che restituisce l'entità appena salvata) e in `findById`. `findById` non è nella porta `TransferRepositoryOutPort`, che espone solo `save`, quindi nessun flusso di produzione rilegge i bonifici.
   - Rischio residuo: in un Postgres locale ci possono essere righe salvate prima di questa modifica con `1.2340`, perché l'API le accettava. Oggi nessun codice di produzione le rilegge. Va deciso se pulirle o ignorarle.

3. **Risposta della REST e ordine nel service (punto 2).**
   - `WebExceptionHandler.handleIllegalArgument` restituisce `ProblemDetail` 400 con `detail = e.getMessage()`, quindi `"Transfer amount can have at most 2 decimals"`. Il messaggio non contiene l'importo, quindi la risposta non lo rivela.
   - In `CreateTransferServiceImpl.executeTransfer` l'ordine è: `Iban`, `Money`, `Transfer.create` (riga 46), poi `requireExistingAccount` x2, poi `fraudCheckOutPort.checkFraud`, poi la transazione. L'errore quindi arriva prima dei repository, del Fraud e della transazione, come oggi per l'IBAN uguale. Il service non va modificato.
   - Nessun limite al trasporto: `TransferRequestDto.amount` è un `BigDecimal` e il mapper MapStruct lo copia così com'è, quindi `1.234` arriva al dominio. Anche `1.2340` arriva con scala 4 e viene rifiutato perché ha 3 decimali significativi.

4. **Test esistenti con più di 2 decimali significativi (punto 3).** Cercato in tutti i moduli di `transfer-service`.
   - **Nessun test esistente costruisce un `Transfer` con più di 2 decimali significativi**, quindi nessuno va modificato. Importi trovati:
     - domain `TransferTest`: 10 e 0;
     - application `CreateTransferServiceImplTest`: 30;
     - postgres `JpaTransferRepositoryOutPortImplTest`: 10.50;
     - postgres `DemoDataSeederTest`: 100.00, 50.25, 5000.00;
     - memory `InMemoryTransferRepositoryOutPortImplTest`: 10, 100.00, 50.25, 5000.00;
     - bootstrap `CreateTransferConcurrencyTest`: 100 e 10;
     - bootstrap `FraudCheckEndToEndTest`: `AMOUNT = "123.45"`.
   - Unico `1.234` presente: `TransferNotificationDtoTest` #14 (`currencyLengthAndAmountDecimalsAreNotCheckedByTheDto`). Usa solo il DTO e non crea un `Transfer`, quindi la nuova regola non lo tocca e resta verde.
   - Nel fraud-adapter (`HttpFraudCheckOutPortImplTest` con `10.10`, `30`, `1E+3`; `FraudCheckHttpRequestDtoTest` con `1E+3`) i `BigDecimal` vanno direttamente all'adapter, senza `Transfer`. Non sono toccati.
   - Il `FraudCheckEndToEndTest` di fraud-service è di un altro servizio ed è fuori perimetro.

5. **Dove mettere i test (punto 4).**
   - **Proposta:** il test HTTP va come nuovo metodo in `transfer-bootstrap` `FraudCheckEndToEndTest`. È il più economico perché ha già il `FakeFraud` che registra le richieste, `transferRowsOf`, `balanceOf` e un contesto H2 già in cache. Non ha senso una nuova classe con un nuovo contesto.
   - Serve un helper aggiuntivo `postTransfer(String amount)`, in overload. Il `postTransfer()` esistente e i test esistenti non cambiano. Si aggiungono cose a una classe di test esistente senza modificare i test che ci sono: va comunicato all'utente.
   - **Alternativa scartata:** `TransferControllerTest` nel rest-adapter. Lì il `TransferServiceDto` è una lambda, quindi non si può mostrare che il Fraud non viene chiamato. Per farlo bisognerebbe collegare il vero `CreateTransferServiceImpl` dentro un test dell'adapter, mescolando i livelli.
   - Per la parte HTTP basta il test esistente `returns400WhenTheRequestIsInvalid`. Si può aggiungere un caso facoltativo con il nuovo messaggio.

### Modifiche per modulo

**`transfer-domain`**

| File | Package | Responsabilità |
|---|---|---|
| `src/main/java/com/bank/transfer/domain/Transfer.java` (modificato) | `com.bank.transfer.domain` | Nel costruttore compatto, dopo il controllo `signum() <= 0`: se `money.amount().stripTrailingZeros().scale() > 2` lancia `IllegalArgumentException("Transfer amount can have at most 2 decimals")`. Si può estrarre un `private static boolean hasAtMostTwoDecimals(BigDecimal)`. `create`, `complete` e `reject` non cambiano. Java puro, nessun import nuovo oltre a `java.math.BigDecimal` se serve. |
| `src/test/java/com/bank/transfer/domain/TransferTest.java` (solo aggiunte) | `com.bank.transfer.domain` | Nuovi casi limite, vedi "Test attesi". `@ParameterizedTest` è già disponibile con `junit-jupiter`. |

**`transfer-application`**

| File | Package | Responsabilità |
|---|---|---|
| `src/test/java/com/bank/transfer/application/service/CreateTransferServiceImplTest.java` (solo aggiunte) | `com.bank.transfer.application.service` | Nuovo test sui troppi decimali, rifiutati prima di qualsiasi interazione. `CreateTransferServiceImpl` **non cambia**. |

**`transfer-bootstrap`**

| File | Package | Responsabilità |
|---|---|---|
| `src/test/java/com/bank/transfer/FraudCheckEndToEndTest.java` (solo aggiunte) | `com.bank.transfer` | Nuovo metodo `t24_...` e helper `postTransfer(String amount)`. |

**Nessuna modifica** a `transfer-rest-adapter` (nel main), `transfer-postgres-adapter`, `transfer-memory-adapter`, `transfer-fraud-adapter`, `transfer-kafka-adapter`, `Money`, `Account`, migration, pom.

### Test attesi
Si scrivono prima del codice. I nuovi test di rifiuto devono essere rossi prima della modifica a `Transfer`.

**`TransferTest`** (`com.bank.transfer.domain`)

| # | Dato | Quando | Allora |
|---|---|---|---|
| D1 | importo `1.234` / `0.001` / `100.255` (parametrizzato) | `new Transfer(..., PENDING)` | `IllegalArgumentException` con messaggio esatto `"Transfer amount can have at most 2 decimals"` |
| D2 | importo `1.234` | `Transfer.create(SOURCE, DEST, money)` | stessa eccezione: `create` passa dal costruttore |
| D3 | importo `1.234` | messaggio dell'eccezione | non contiene `"1.234"` |
| D4 | importo `100` / `100.2` / `100.25` / `10.5000` / `100.00` / `2E+4` / `1E+2` (parametrizzato, `new BigDecimal(String)`) | `new Transfer(..., PENDING)` | nessuna eccezione; `money()` `isEqualTo` `Money` di input |
| D5 | importo `0.000` (scala 3, zero) | costruttore | `IllegalArgumentException` con `"Transfer amount has to be greater than 0"`: conta prima la regola del positivo |
| D6 | importo `1.234` e IBAN uguali | costruttore | `IllegalArgumentException` (vale l'ordine scelto all'ambiguità 1, il messaggio è quello dei decimali) |
| D7 | `Transfer.create` con `100.25`, PENDING | `complete()` | stato `COMPLETED`, stesso `id` e stesso `money` |
| D8 | `Transfer.create` con `10.5000`, PENDING | `reject()` | stato `REJECTED`, stesso `id` e stesso `money` |
| D9 | Transfer `COMPLETED` valido | `complete()` / `reject()` | `IllegalStateException`: comportamento invariato |

**`CreateTransferServiceImplTest`** (`com.bank.transfer.application.service`)

| # | Dato | Quando | Allora |
|---|---|---|---|
| S1 | conti esistenti e Fraud `ALLOWED` (stub `lenient`), comando con `1.234` | `executeTransfer` | `IllegalArgumentException` con il messaggio dei decimali; `verifyNoInteractions(accountRepository, fraudCheck, transferRepository, transactionManager)` |
| S2 (facoltativo) | come S1 con `0.001` | `executeTransfer` | stessa eccezione, nessuna interazione |
| S3 (facoltativo) | conti esistenti e `ALLOWED`, comando con `30.00` | `executeTransfer` | `COMPLETED`: regressione, il valore con zeri in coda passa |

**`FraudCheckEndToEndTest`** (`com.bank.transfer`, H2 e FakeFraud)

| # | Dato | Quando | Allora |
|---|---|---|---|
| E1 | conti `source`=1000 e `dest`=0, FakeFraud che risponde `ALLOWED` | POST `/transfers` con `"amount":1.234` | HTTP 400, `application/problem+json`, `$.status` = 400, `$.detail` = `"Transfer amount can have at most 2 decimals"`; `FRAUD.requests()` vuoto; `transferRowsOf(source)` = 0; saldi 1000 e 0 invariati; il corpo non contiene `1.234`, `source` né `dest` |
| E2 (facoltativo) | stessa situazione | POST con `"amount":10.5000` | 200 `COMPLETED`; il Fraud riceve una richiesta: lo stesso valore con zeri in coda passa anche da HTTP |

Tutti i test esistenti restano **invariati** e verdi (vedi ambiguità 4).

### Criteri di accettazione
- Da `transfer-service/`: `.\mvnw.cmd test` è verde senza Docker, tranne `TransferApplicationTests`, che come oggi richiede Postgres. In alternativa: `-pl transfer-domain,transfer-application,transfer-bootstrap -am`.
- D1, D2, S1 ed E1 falliscono prima della modifica a `Transfer.java` e passano dopo.
- `git diff` sul main tocca solo `transfer-domain/.../Transfer.java`. Nei test ci sono solo aggiunte: nessuna riga esistente modificata in `TransferTest`, `CreateTransferServiceImplTest` e `FraudCheckEndToEndTest`.
- Nessuna modifica a pom, `Money`, `Account`, V1/V2, `CreateTransferServiceImpl`, `WebExceptionHandler`, moduli memory, fraud e kafka.
- `Transfer` resta Java puro, senza import di framework.
- Il messaggio d'errore non contiene importi né IBAN.
- Coerenza con il contratto §4: ogni `Transfer` costruibile ha un importo maggiore di zero e al massimo 2 decimali significativi.

### Rischi e fuori perimetro

**Rischi**
- **Dati già nel Postgres locale** con più di 2 decimali, salvati prima di questa modifica: oggi nessuno li rilegge in produzione. Un futuro use case di lettura dei bonifici, per esempio un `findById` nella porta, lancerebbe `IllegalArgumentException` su quelle righe.
- **Cambia il comportamento della API:** `1.234`, che prima veniva accettato, ora riceve 400. I client esterni vanno avvisati.
- **Ordine dei controlli nel costruttore:** decide quale messaggio vede l'utente quando più regole sono violate insieme (ambiguità 1).
- **Scale negative e notazione esponenziale:** `2E+4` e `1E+2` sono accettati per scelta. Il formato di uscita resta compito dell'adapter dell'outbox (piano transfer-04, punto 7: `setScale(2, UNNECESSARY)`), che con questa regola non incontrerà più `ArithmeticException` per importi arrivati dal dominio.
- **Classe di test condivisa:** E1 aggiunge un metodo a una classe di test esistente. Non cambia i test che ci sono, ma va comunicato all'utente come da regole.
- **Nessuna nuova dipendenza.**

**Fuori perimetro**
- `Money` e la sua scala, `Account`, i saldi.
- Migration e scala delle colonne.
- Il contratto del Fraud e `transfer-fraud-adapter`.
- L'adapter dell'outbox, `NotificationOutPort` e la sua chiamata nel service.
- Il profilo `inmemory` e `transfer-memory-adapter`.
- Validazione Bean Validation sul `TransferRequestDto`.
- Gestione di `amount` null nella richiesta (oggi NPE).
- Pulizia dei dati nel database locale.
- Nuove dipendenze, commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. Piano approvato così com'è.
2. I test facoltativi S2, S3 ed E2 sono inclusi.
3. Dati già presenti nel Postgres locale con più di 2 decimali: ignorati (dati di sviluppo).
4. Si aggiungono metodi a `CreateTransferServiceImplTest` e `FraudCheckEndToEndTest` senza modificare i test esistenti (accettato).

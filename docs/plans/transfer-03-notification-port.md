## Piano transfer-03: NotificationOutPort e TransferNotificationDto

### Obiettivo
Aggiungere a `transfer-application` il record `TransferNotificationDto`, validato nel costruttore, e la porta in uscita `NotificationOutPort`. Il record si scrive dopo i suoi test. Non si tocca nessun altro modulo.

### Ambiguità
Nessuna di queste cambia il piano di base. Le regole 1-9 dell'issue sono un elenco chiuso e il piano implementa solo quelle. I punti 2, 3 e 4 sono decisioni dell'utente: se le accetta, si aggiungono righe ai test e controlli al costruttore, ma la struttura del piano resta la stessa. Il planner non ha scelto un comportamento.

1. **Tipo di eccezione.** La scelta viene dal codice esistente:
   - `Transfer` e `Money` usano `Objects.requireNonNull`, quindi un campo null lancia `NullPointerException`. Anche `FraudCheckResultDto` usa `requireNonNull(decision, "decision")`.
   - Le violazioni di una regola lanciano `IllegalArgumentException`: importo minore o uguale a zero in `Transfer`, importo negativo in `Money`, IBAN non valido in `Iban`.
   - I test verificano solo il tipo, con `assertThatThrownBy(...).isInstanceOf(...)` (`TransferTest`, `MoneyTest`, `FraudCheckResultDtoTest`), mai il messaggio.
   - Proposta: campi null -> `NullPointerException` con il nome del campo come messaggio; status non ammesso, combinazione status/motivi non coerente, importo minore o uguale a zero -> `IllegalArgumentException`. Nessuna eccezione custom: in `application/exception` ci sono solo eccezioni di use case.
   - `IllegalStateException` si scarta: `Transfer` la usa per una transizione di stato, non per validare un costruttore.
   - L'utente deve confermare.
2. **`transferId`, `sourceIban`, `destIban` o `currency` vuoti o blank.** Il contratto (sezione 4) vuole `transferId` "non vuota" e gli IBAN "già normalizzati". L'issue non lo chiede. Nel progetto, `Iban` rifiuta i blank, mentre `Money` e `CreateTransferCommand` non controllano la valuta. Va deciso se il DTO deve rifiutare i blank (con `IllegalArgumentException`) o fidarsi del chiamante. Il chiamante è `CreateTransferServiceImpl`, che passa `Transfer.id()` (un UUID) e `Iban.value()` (già normalizzato). Oggi il piano non li controlla.
3. **Valuta di 3 lettere e importo con al massimo 2 decimali.** Sono vincoli del contratto (sezione 4), assenti dall'issue e dal dominio del Transfer (`Money` non li impone). Se li mettiamo nel DTO, una valuta non valida fa fallire la pubblicazione dentro la transazione del bonifico, quindi fallisce il bonifico stesso. Se non li mettiamo, li deve garantire l'adapter dell'outbox o il dominio. Va deciso dove stanno. Oggi il piano non li controlla.
4. **Motivi blank o null dentro la lista.** Il contratto vuole "una o più voci non vuote". `HttpFraudCheckOutPortImpl.toResult` filtra già i motivi null e blank e rifiuta un `REJECTED` senza motivi, quindi la tappa 5 riceverà dati puliti. Va deciso se il DTO li deve rifiutare comunque. Un effetto c'è già senza fare nulla: `List.copyOf` lancia `NullPointerException` se la lista contiene un null. Un motivo `"  "` invece oggi passa.
5. **Nota, non blocca: `destIban` contro `destinationIban`.** Il campo del DTO si chiama `destIban`, come in `CreateTransferCommand` e `FraudCheckOutPort`. Il nome JSON `destinationIban` del contratto lo produce l'adapter dell'outbox (tappa 4). Va ricordato in quella tappa.
6. **Nota, non blocca: enum del dominio nel DTO.** Il CLAUDE.md radice vieta oggetti del dominio solo nelle porte *in*. Questa è una porta *out*, e il caso ha già un precedente: `FraudCheckResultDto` contiene `FraudStatusEnum`. Non c'è conflitto.

### Modifiche per modulo

**`transfer-application`**, l'unico modulo coinvolto.

| File | Package | Responsabilità |
|---|---|---|
| `src/main/java/com/bank/transfer/application/dto/TransferNotificationDto.java` (nuovo) | `com.bank.transfer.application.dto` | `public record TransferNotificationDto(String transferId, String sourceIban, String destIban, BigDecimal amount, String currency, TransferStatusEnum status, List<String> reasons)`. Il costruttore compatto, nell'ordine: (a) `Objects.requireNonNull` sui 6 campi obbligatori, con il nome del campo come messaggio; (b) `amount.signum() <= 0` -> `IllegalArgumentException`; (c) `reasons = reasons == null ? List.of() : List.copyOf(reasons)`; (d) `PENDING` -> `IllegalArgumentException`; (e) `REJECTED` con `reasons` vuota -> `IllegalArgumentException`; (f) `COMPLETED` con `reasons` non vuota -> `IllegalArgumentException`. Non ha `occurredAt`. I messaggi delle eccezioni non riportano IBAN né importo. Importa dal dominio solo `TransferStatusEnum`. |
| `src/main/java/com/bank/transfer/application/port/out/NotificationOutPort.java` (nuovo) | `com.bank.transfer.application.port.out` | `public interface NotificationOutPort { void publish(TransferNotificationDto notification); }`. Il Javadoc dice solo che la notifica va registrata per la consegna al termine di un bonifico concluso, senza citare outbox né Kafka. |
| `src/test/java/com/bank/transfer/application/dto/TransferNotificationDtoTest.java` (nuovo, scritto prima del codice) | `com.bank.transfer.application.dto` | Test del record (vedi sotto). |

Nient'altro: nessun file esistente cambia, nessun pom.xml cambia, gli altri moduli non vengono toccati.

### Test attesi
Classe `TransferNotificationDtoTest`, con JUnit 5 e AssertJ come in `FraudCheckResultDtoTest`. Costanti dagli esempi del contratto (sezione 4):
- `TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93"`
- `SOURCE_IBAN = "IT60X0542811101000000000001"`, `DEST_IBAN = "IT60X0542811101000000000002"`
- `AMOUNT = new BigDecimal("100.25")`, `CURRENCY = "EUR"`

Un helper privato `notification(TransferStatusEnum status, List<String> reasons)` costruisce un DTO valido. Gli importi si confrontano con `isEqualByComparingTo`. Prima della scrittura del record, i test non compilano (rosso atteso).

| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | `COMPLETED`, `reasons = List.of()` | si costruisce il DTO | creato; i 5 campi scalari sono uguali a quelli passati; `amount` è uguale per `isEqualByComparingTo`; `status` = `COMPLETED`; `reasons` è vuota |
| 2 | `REJECTED`, `reasons = List.of("Source IBAN is locked")` | si costruisce il DTO | creato; `reasons` `containsExactly("Source IBAN is locked")` |
| 3 | `COMPLETED`, `reasons = null` | si costruisce il DTO | `reasons` `isNotNull().isEmpty()` |
| 4a (errore) | `REJECTED`, `reasons = null` | si costruisce il DTO | `IllegalArgumentException` |
| 4b (errore) | `REJECTED`, `reasons = List.of()` | si costruisce il DTO | `IllegalArgumentException` |
| 5 (errore) | `COMPLETED`, `reasons = List.of("x")` | si costruisce il DTO | `IllegalArgumentException` |
| 6 (errore) | `PENDING`, `reasons = List.of()` | si costruisce il DTO | `IllegalArgumentException` |
| 7a-7f (errore) | tutti i campi validi tranne uno null, uno per volta: `transferId`, `sourceIban`, `destIban`, `amount`, `currency`, `status` | si costruisce il DTO | `NullPointerException`; un `assertThatThrownBy` per campo, nello stile di `TransferTest.rejectsNullFields`, oppure un test per campo |
| 8a (errore) | `amount = BigDecimal.ZERO` | si costruisce il DTO | `IllegalArgumentException` |
| 8b (errore) | `amount = new BigDecimal("0.00")` | si costruisce il DTO | `IllegalArgumentException`: lo zero con scala deve essere rifiutato |
| 8c (errore) | `amount = new BigDecimal("-1")` | si costruisce il DTO | `IllegalArgumentException` |
| 9a | lista `ArrayList` con `"a"`, `REJECTED` | dopo la creazione si aggiunge `"b"` alla lista originale | `dto.reasons()` `containsExactly("a")` |
| 9b | DTO `REJECTED` con un motivo | `dto.reasons().add("x")` | `UnsupportedOperationException` |

### Criteri di accettazione
- `TransferNotificationDtoTest` è scritto prima del record. Tutti i suoi test passano con `.\mvnw.cmd test -pl transfer-application -am`, lanciato da `transfer-service/`.
- I test esistenti del modulo restano verdi e invariati: `FraudCheckResultDtoTest`, `TransferResultDtoTest`, `AccountToAccountDtoMapperTest`, `CreateTransferServiceImplTest`.
- `git diff` contiene solo i 3 file nuovi. Nessun pom.xml modificato e nessuna dipendenza nuova: JUnit, AssertJ e il dominio ci sono già.
- Il record non importa Spring, Jackson o altri framework. Dal dominio importa solo `TransferStatusEnum`.
- `NotificationOutPort` ha un solo metodo, `void publish(TransferNotificationDto notification)`. Né il nome né il Javadoc citano outbox o Kafka.
- Il DTO non ha `occurredAt` e copre i campi della sezione 4 del contratto tranne quello.
- Le regole del record sono coerenti con la sezione 4 del contratto (`REJECTED` <=> almeno un motivo, `COMPLETED` senza motivi, importo maggiore di zero, solo `COMPLETED`/`REJECTED`) e con lo scenario 5 della sezione 9 (`PENDING` non valido).

### Rischi e fuori perimetro

**Rischi**
- **Il DTO è più severo dei dati che riceverà.** Se nella tappa 5 il service costruisce il DTO con `COMPLETED` e motivi non vuoti, l'eccezione fa fallire la transazione del bonifico. Oggi non succede, perché `HttpFraudCheckOutPortImpl` restituisce `List.of()` per `ALLOWED`. Un test della tappa 5 deve comunque coprire il caso.
- **I vincoli aperti (punti 2-4 delle ambiguità) restano scoperti.** Un `transferId` blank, una valuta di 4 lettere o un motivo blank passerebbero il DTO e arriverebbero al consumatore, che però scarta solo i casi della sezione 6. Bisogna decidere in quale tappa garantirli.
- **Null dentro `reasons`.** `List.copyOf` lancia `NullPointerException` se un elemento è null. È un comportamento implicito, senza un test dedicato a meno che l'utente non lo chieda.
- **Messaggi delle eccezioni.** Se finissero nei log con IBAN o importo violerebbero lo spirito della sezione 7 del contratto. Il piano li vuole senza dati sensibili.
- **Nessuna dipendenza nuova.**

**Fuori perimetro**
- Test dell'interfaccia `NotificationOutPort`: la useranno i test del service nella tappa 5.
- Modifiche a `CreateTransferServiceImpl` e a `CreateTransferServiceImplTest`.
- Adapter dell'outbox, entità JPA, assegnazione di `occurredAt` con `Clock`, serializzazione JSON, nome `destinationIban`.
- Relay Kafka, `transfer-notification-adapter`, configurazione in `transfer-bootstrap`.
- Nuove migration, pom.xml, moduli diversi da `transfer-application`.
- Commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **Eccezioni:** proposta confermata. Campo null -> `NullPointerException` con il nome del campo; regola violata -> `IllegalArgumentException`. Nessuna eccezione custom.
2. **Stringhe blank:** `transferId`, `sourceIban`, `destIban` e `currency` blank (vuoti o di soli spazi) sono rifiutati con `IllegalArgumentException`, dopo il controllo dei null.
3. **Valuta di 3 lettere e massimo 2 decimali:** NON controllati nel DTO. Li garantisce l'adapter dell'outbox (tappa 4); in caso contrario il consumer scarta il messaggio.
4. **Motivi blank nella lista:** un motivo blank in `reasons` è rifiutato con `IllegalArgumentException`. Un motivo null nella lista lancia `NullPointerException` (comportamento di `List.copyOf`) e ha un test dedicato.

### Test aggiunti dalle decisioni (si sommano ai casi 1-9)
| # | Dato | Quando | Allora |
|---|---|---|---|
| 10a-10d (errore) | `transferId`, `sourceIban`, `destIban`, `currency` uno per volta `""` | si costruisce il DTO | `IllegalArgumentException` |
| 10e-10h (errore) | gli stessi quattro campi, uno per volta `"   "` | si costruisce il DTO | `IllegalArgumentException` |
| 11 (errore) | `REJECTED`, `reasons = List.of("Source IBAN is locked", "  ")` | si costruisce il DTO | `IllegalArgumentException` |
| 12 (errore) | `REJECTED`, `reasons = List.of("")` | si costruisce il DTO | `IllegalArgumentException` |
| 13 (errore) | `REJECTED`, lista `Arrays.asList("a", null)` | si costruisce il DTO | `NullPointerException` |
| 14 | `currency = "ABCD"` e `amount = new BigDecimal("1.234")` con `COMPLETED` | si costruisce il DTO | creato senza eccezioni (documenta che questi vincoli non stanno nel DTO) |

I messaggi delle eccezioni non contengono IBAN né importo, neanche per il blank di `sourceIban`/`destIban`.

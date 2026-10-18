### Obiettivo
Creare in `notification-domain` (package `com.bank.notification.domain`) i tipi propri del Notification: `TransferNotification`, `Iban`, `Money`, `TransferOutcome` e un'unica eccezione di dominio non ritentabile. Devono applicare le regole della sezione 4 del contratto `transfer-notification.md`, senza framework e senza mai esporre IBAN in `toString()` o nei messaggi d'errore.

### Ambiguità
Nessuna ambiguità blocca il piano. Per ognuna ci sono le alternative e una raccomandazione. Il piano sotto segue le raccomandazioni, che vanno confermate dall'utente. Se un punto viene deciso diversamente, cambiano solo le righe di test indicate.

1. **IBAN lungo 27 caratteri: il contratto non lo dice.** La sezione 4 chiede solo "già normalizzato (maiuscolo, senza spazi)". Non c'è né la lunghezza 27 né l'espressione regolare. L'issue scrive "lungo 27 caratteri come nel contratto", ma è una divergenza: 27 e `[A-Z]{2}[0-9]{2}[A-Z0-9]+` vengono dagli `Iban` di Transfer e Fraud e dai dati di prova.
   - (a) Seguire Transfer e Fraud: 27 caratteri più il pattern. Il produttore non emette altro.
   - (b) Lunghezza generica 15-34 (IBAN di tutti i paesi).
   - (c) Solo il pattern, senza vincolo di lunghezza.
   - Raccomandazione: (a), coerente con il produttore. Il contratto andrebbe esplicitato con una modifica a parte, fuori perimetro. Con (b) o (c) cambiano i test Iban #5 e #6.
2. **Mittente uguale a destinatario.** La sezione 4 del contratto di notifica non lo vieta: il vincolo esiste solo nel fraud-check e nel `Transfer`.
   - (a) Accettarlo: il Notification registra quello che è successo, e la regola è già nel produttore.
   - (b) Rifiutarlo con `InvalidNotificationException`: un messaggio "impossibile" verrebbe scartato.
   - Raccomandazione: (a): si segue il contratto e non si aggiungono regole. Test TN #22. Con (b) il test si inverte e va segnalata una divergenza dal contratto.
3. **Gerarchia delle eccezioni.** Il Transfer usa NPE per i campi null e `IllegalArgumentException` per le regole violate. Il Fraud usa una sola `InvalidFraudRequestException` per tutto.
   - Raccomandazione: un'unica `com.bank.notification.domain.exception.InvalidNotificationException extends RuntimeException`, con un solo costruttore `(String message)`, lanciata anche per i campi **null**, con il nome del campo nel messaggio.
   - Motivo: la sezione 6 del contratto classifica "campo obbligatorio mancante o nullo" come messaggio non valido, da scartare subito. La fase 06 tratta come ritentabile "ogni altra RuntimeException": un NPE verrebbe ritentato 3 volte per errore.
   - Non estende `IllegalArgumentException`: il consumer potrebbe prendere per "non ritentabile" una IAE generica nata altrove, per esempio nella mappatura Mongo.
   - Niente sottoclassi (per esempio per la valuta): servirebbero solo se la fase 06 dovesse distinguere i casi, e oggi non deve.
   - Package `domain.exception` come in Transfer e Fraud. L'issue dice `com.bank.notification.domain`: si legge come package base.
4. **Dove stanno le regole dell'importo (domanda 2 dell'issue).**
   - (a) `Money` "dell'importo notificato": non null, > 0, `stripTrailingZeros().scale() <= 2`, currency `[A-Z]{3}`.
   - (b) `Money` generica (non null, currency) e regole > 0 e scala in `TransferNotification`, come fa il Transfer.
   - Raccomandazione: (a): nel servizio non ci sono operazioni aritmetiche e uno zero non serve mai, quindi un solo punto di validazione con test concentrati in `MoneyTest`.
   - Nota: il Fraud usa `amount.scale()`. Qui si usa la scala **significativa**, come chiede l'issue.
5. **Forma di `Iban` e come `TransferNotification` espone gli IBAN (domanda 3).** Raccomandazione: `record Iban(String value)` con il valore normalizzato, e componenti `Iban sourceIban`, `Iban destinationIban`. Il tipo garantisce la validazione, e le fasi 05/06 leggono `sourceIban().value()`. L'alternativa (String nel record) perderebbe la garanzia.
6. **Accessori pubblici (domanda 4).** Bastano gli accessori dei record: `transferId()`, `sourceIban().value()`, `destinationIban().value()`, `amount().amount()`, `amount().currency()`, `outcome()`, `reasons()`, `occurredAt()`. Nessun metodo di conversione, nessuna factory: le fasi 03/05/06 usano il costruttore canonico.
7. **Nomi dei componenti.** Raccomandazione: `TransferNotification(String transferId, Iban sourceIban, Iban destinationIban, Money amount, TransferOutcome outcome, List<String> reasons, Instant occurredAt)`.
   - `sourceIban`, `destinationIban` e `amount` come nel contratto. Il contratto chiama `status` l'esito, ma il CLAUDE.md impone il tipo `TransferOutcome`, da cui `outcome`.
   - Alternativa: `money`/`status`. Cambia solo la nomenclatura, non i test.
8. **`occurredAt` (domanda 5).** `Instant`, solo non null, nessun controllo sul futuro: il clock del Transfer fa fede. Si segue l'issue.
9. **Valuta (domanda 6).** `[A-Z]{3}`, senza lista ISO e senza limitarla a `EUR`. Si segue il contratto ("3 lettere") come indicato dall'issue.
10. **Motivi (domanda 8).**
    - Tipo `List<String>`. Un tipo dedicato non aggiunge regole: i testi sono quelli del Fraud, liberi.
    - Conservati come arrivano, **senza trim**: il dominio non riscrive i testi del produttore.
    - `reasons` null: si raccomanda di rifiutarlo con `InvalidNotificationException`, come `FraudAssessment`. Conseguenza da annotare per le fasi 03/06: un `reasons` assente in un `COMPLETED` va mappato a `List.of()` dal mapper.
    - Alternativa: null trasformato in lista vuota nel dominio. Cambia il test TN #15.
11. **`isRejected()` / metodi per il log (domanda 9).** Raccomandazione: non aggiungerli: `outcome()` e `reasons()` bastano. Se la fase 04 li vorrà, si aggiungeranno lì.
12. **Formato di `transferId`.** Il contratto dice "UUID, non vuota". Raccomandazione: validare solo la non vuotezza, come il Fraud (`"abc"` accettato), senza trim. Validare l'UUID sarebbe una regola in più che il contratto non elenca tra gli scarti della sezione 6.
13. **`toString()`.**
    - `Iban`: `Iban[****]`.
    - `TransferNotification`: transferId, outcome, importo, valuta, reasons, occurredAt, senza IBAN. Sono i campi ammessi dalla sezione 7. L'alternativa è il solo transferId, come nel Fraud.
    - `Money`: `toString` del record, perché l'importo è ammesso nel log. I **messaggi di eccezione** invece non contengono né IBAN né importo.
14. **Stile dei test.** "Stile tab come il Fraud": i test del Fraud usano nomi di metodo in italiano e indentazione a 4 spazi (il Transfer a 2). Raccomandazione: stile del Fraud.

### Modifiche per modulo
**`notification-domain`**: solo file nuovi. Il pom resta invariato.

`src/main/java/com/bank/notification/domain/`
- `TransferOutcome.java`: enum con `COMPLETED` e `REJECTED`.
- `Iban.java`: `record Iban(String value)`.
  - null o blank: eccezione.
  - Normalizzazione: rimuove `\s+` e applica `toUpperCase(Locale.ROOT)`.
  - Controlli: lunghezza 27 e `Pattern` precompilato `[A-Z]{2}[0-9]{2}[A-Z0-9]+`.
  - `toString()` mascherato. I messaggi non contengono il valore.
- `Money.java`: `record Money(BigDecimal amount, String currency)`.
  - Controlli: amount non null, `signum() > 0`, `stripTrailingZeros().scale() <= 2`; currency non null e `[A-Z]{3}`.
  - `equals` con `compareTo`. `hashCode` su `stripTrailingZeros()` e currency, come il modello del Transfer, riscritto senza importarlo.
- `TransferNotification.java`: record con i 7 componenti dell'Ambiguità 7.
  - Ordine dei controlli: `transferId` non null/blank, `sourceIban`, `destinationIban`, `amount`, `outcome`, `reasons` non null, nessun elemento null o blank, `REJECTED` con lista non vuota, `COMPLETED` con lista vuota, `occurredAt`.
  - Gli elementi vanno controllati **prima** di `List.copyOf`, che lancerebbe un NPE. Poi `reasons = List.copyOf(reasons)` come copia difensiva immutabile.
  - Nessun override di equals/hashCode: sono coerenti grazie a `Money.equals`. Si ridefinisce solo `toString()`, senza IBAN.

`src/main/java/com/bank/notification/domain/exception/`
- `InvalidNotificationException.java`: `extends RuntimeException`, costruttore `(String message)`. Copre ogni violazione, null compresi.

`src/test/java/...`: un test per classe, nello stesso package della classe testata (vedi sotto).

Nessun altro modulo viene toccato.

### Test attesi
Si scrivono prima del codice. Usano JUnit 5 e AssertJ. Dati di prova: `IT60X0542811101000000000001`/`...0002`, ID `3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93`. "Eccezione" vuol dire `InvalidNotificationException`.

**`com.bank.notification.domain.TransferOutcomeTest`**
| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | l'enum | `values()` | esattamente `[COMPLETED, REJECTED]` (sezione 8 del contratto) |

**`com.bank.notification.domain.IbanTest`**
| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | `IT60X0542811101000000000001` | costruzione | `value()` uguale |
| 2 | `" it60 x054 2811 1010 0000 0000 001 "` | costruzione | `value()` = `IT60X0542811101000000000001` |
| 3 | IBAN con spazi e minuscole e lo stesso IBAN normalizzato | equals/hashCode | uguali |
| 4 | null / `""` / `"   "` | costruzione | eccezione |
| 5 | 26 caratteri / 28 caratteri | costruzione | eccezione |
| 6 | 27 caratteri: `1T60...` (cifra in testa), `ITX0...` (lettera al posto delle cifre di controllo), carattere `-` o `*` | costruzione | eccezione |
| 7 | un IBAN valido | `toString()` | non contiene l'IBAN né la parte `0542811101` |
| 8 | un IBAN malformato (lunghezza e formato) | costruzione | il messaggio non contiene l'input |

**`com.bank.notification.domain.MoneyTest`**
| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | `100`, `100.2`, `100.25`, `100.2500`, `2E+4`, `1E+2` con `EUR` | costruzione | valido, `amount()` `isEqualByComparingTo` all'input |
| 2 | `0`, `0.00`, `-1` | costruzione | eccezione |
| 3 | `0.001`, `1.234`, `100.255` | costruzione | eccezione |
| 4 | amount null / currency null | costruzione | eccezione con il nome del campo nel messaggio |
| 5 | currency `EUR` | costruzione | valido |
| 6 | currency `eur`, `EURO`, `E1R`, `""`, `EU` | costruzione | eccezione |
| 7 | `100.2500 EUR` e `100.25 EUR` | equals / hashCode | uguali, stesso hash |
| 8 | `2E+4 EUR` e `20000.00 EUR` | equals / hashCode | uguali, stesso hash |
| 9 | `100 EUR` e `100 USD`; `100 EUR` e `101 EUR` | equals | diversi |
| 10 | importi rifiutati `1.234`, `-1` | costruzione | il messaggio non contiene `1.234` né `-1` |

**`com.bank.notification.domain.TransferNotificationTest`**
| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | `COMPLETED` valido, `reasons` = `List.of()` | costruzione | tutti gli accessori restituiscono i valori attesi (importo con `isEqualByComparingTo`) |
| 2 | `REJECTED` con 2 motivi (esempio della sezione 4) | costruzione | `reasons()` contiene esattamente i due motivi, nell'ordine |
| 3 | `REJECTED` con un motivo `"  Source IBAN is locked "` | costruzione | motivo conservato invariato, senza trim |
| 4 | `transferId` null / `""` / `"   "` | costruzione | eccezione |
| 5 | `transferId` `"abc"` | costruzione | valido (Ambiguità 12) |
| 6 | `sourceIban` null | costruzione | eccezione, messaggio con "source" |
| 7 | `destinationIban` null | costruzione | eccezione |
| 8 | `amount` null | costruzione | eccezione |
| 9 | `outcome` null | costruzione | eccezione |
| 10 | `occurredAt` null | costruzione | eccezione |
| 11 | `occurredAt` nel futuro | costruzione | valido |
| 12 | `REJECTED` con `List.of()` | costruzione | eccezione (scenario 6 del contratto) |
| 13 | `REJECTED` con `["motivo", ""]` / `["   "]` | costruzione | eccezione |
| 14 | `REJECTED` con una lista che contiene null (`Arrays.asList("a", null)`) | costruzione | eccezione (**non** NPE) |
| 15 | `reasons` null, sia con `COMPLETED` sia con `REJECTED` | costruzione | eccezione (Ambiguità 10) |
| 16 | `COMPLETED` con `["motivo"]` | costruzione | eccezione |
| 17 | lista mutabile passata al costruttore | la lista originale viene modificata dopo la costruzione | `reasons()` invariato |
| 18 | notifica costruita | `reasons().add(...)` | `UnsupportedOperationException` |
| 19 | due notifiche identiche tranne l'importo `100.25` contro `100.2500` | equals / hashCode | uguali, stesso hash |
| 20 | `COMPLETED` e `REJECTED` con IBAN valorizzati | `toString()` | contiene `transferId`; non contiene nessuno dei due IBAN né `0542811101` |
| 21 | ogni caso di errore costruito con IBAN e importo `1234.56` (per esempio REJECTED senza motivi, COMPLETED con motivi, transferId vuoto) | costruzione | il messaggio non contiene gli IBAN né `1234.56` |
| 22 | `sourceIban` uguale a `destinationIban` | costruzione | valido (Ambiguità 2, da confermare) |

**`com.bank.notification.domain.exception.InvalidNotificationExceptionTest`**
| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | il tipo | si controlla la gerarchia | è una `RuntimeException` e **non** una `IllegalArgumentException` |
| 2 | il messaggio `"x"` | costruzione | `getMessage()` = `"x"` |

### Criteri di accettazione
- `.\mvnw.cmd test` da `notification-service/` dà BUILD SUCCESS: i 7 test di baseline più tutti i test nuovi, verdi, senza Docker.
- Il `pom.xml` di `notification-domain` e gli altri pom restano invariati: nessuna nuova dipendenza.
- Nessun import di `org.springframework`, `jakarta`, `tools.jackson`/`com.fasterxml`, `com.bank.transfer`, `com.bank.fraud` nel modulo (verifica con grep).
- Ogni `BigDecimal` si confronta con `compareTo` / `isEqualByComparingTo`, mai con `equals`.
- Nessun IBAN in `toString()` o nei messaggi di eccezione, e nessun importo nei messaggi di eccezione (test Iban #7-8, Money #10, TN #20-21).
- Regole della sezione 4 del contratto coperte. Scenari della sezione 9 coperti per la parte di dominio: 6 (TN #12) e 7 (Money #2). Gli altri appartengono alle fasi 03-07.
- Ogni test sta nello stesso package della classe testata.
- Restano invariati `transfer-service/`, `fraud-service/`, `docs/contracts/` e i CLAUDE.md.

### Rischi e fuori perimetro
**Rischi**
- **Contratto più vago dell'issue** sulla forma dell'IBAN (Ambiguità 1). Se un giorno il Transfer accetta IBAN non italiani, le notifiche verrebbero scartate in silenzio come non valide.
- **`reasons` null rifiutato.** Se le fasi 03/06 dimenticano di mappare l'assenza a `List.of()`, tutti i `COMPLETED` verranno scartati. Va scritto nel riepilogo di fase come vincolo per i mapper.
- **`List.copyOf` lancia NPE** sugli elementi null: se il controllo non viene prima, il consumer ritenta un messaggio non valido (TN #14 lo copre).
- **`hashCode` di `Money`** non coerente con `equals` se l'implementer usa `amount.hashCode()` del record (TN #19 e Money #7-8 lo coprono).
- **`toString()` del record generato** se si dimentica l'override: l'IBAN finirebbe nei log (TN #20 e Iban #7).
- Nessuna dipendenza nuova proposta.

**Fuori perimetro**
- Porte, service, command, DTO, mapper e adapter (kafka, log, mongo), bootstrap, conversioni JSON/Mongo.
- Factory `completed()`/`rejected()` e metodi `isRejected()`.
- Modifiche al contratto (anche per esplicitare i 27 caratteri), a transfer-service, a fraud-service, ai CLAUDE.md, ai pom.
- Commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **Ambiguità 1, 2, 3, 4, 6, 7, 8:** tutte le raccomandazioni (R) approvate: IBAN di 27 caratteri più pattern; mittente uguale a destinatario ACCETTATO; un'unica `InvalidNotificationException extends RuntimeException` (anche per i campi null, con il nome del campo nel messaggio); `Money` con > 0, scala significativa massima 2 e valuta `[A-Z]{3}`; `record Iban(String value)` e `TransferNotification` con componenti `Iban`; motivi `List<String>` senza trim, `reasons` null rifiutato; `transferId` solo non vuoto; `toString()` di `TransferNotification` senza IBAN, messaggi di eccezione senza IBAN né importo.
2. **Ambiguità 14 (stile), decisione dell'utente: "uniforma tutti alla linea più pulita".** Interpretazione registrata: per TUTTO il `notification-service` (non per transfer-service né fraud-service, la cui riformattazione è un'altra issue) si usa l'unica convenzione del Transfer: rientro di 2 spazi, nomi di test in inglese, import statici in cima, poi gli altri import in ordine alfabetico con `java`/`javax` in fondo, una riga vuota tra i blocchi. I file Java già creati nella fase 01 (`NotificationApplication.java`, `NotificationApplicationTests.java`) vanno riportati a questa convenzione senza cambiare le asserzioni. I pom restano con i tab (convenzione degli XML di Spring/Maven di tutto il repository).

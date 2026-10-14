### Obiettivo
Implementare in `notification-log-adapter` la classe `NotificationLogOutPortImpl`, che per ogni notifica salvata scrive una sola riga di log INFO con transferId, esito, importo, valuta, occurredAt e motivi, senza mai IBAN.

### Ambiguità
Nota preliminare: le ambiguità 1 e 2 cambiano i pom. Secondo le regole del ruolo del planner bloccano l'implementazione finché l'utente non decide. Il resto del piano descrive l'opzione raccomandata per ciascuna e serve a dare all'utente un quadro completo. Il branch corrente è `notification`, corretto.

1. **API di logging e dipendenza di compilazione (bloccante).** Verificati pom e classpath: `notification-log-adapter` dipende solo da `notification-application`, che dipende solo da `notification-domain`. JUnit e AssertJ sono in scope test e non passano ai moduli che dipendono da questi. Quindi oggi non sono raggiungibili né commons-logging, né slf4j, né spring-core. Qualunque API esterna richiede una dipendenza nuova.
   - (a) **commons-logging**: `commons-logging:commons-logging`, versione 1.3.6 dal BOM, il JAR c'è in `~/.m2`. Spring 7 lo usa direttamente. È coerente con il Transfer (`HttpFraudCheckOutPortImpl`, `KafkaNotificationSenderOutPortImpl`), ma serve comunque una dipendenza compile da aggiungere, e il piano generale indica SLF4J.
   - (b) **SLF4J**: `org.slf4j:slf4j-api`, scope compile, senza versione. Il BOM di Boot 4.1.1 fissa `slf4j.version=2.0.18`; `slf4j-api-2.0.18.jar`, `slf4j-parent` e `slf4j-bom` 2.0.18 sono in `~/.m2`, quindi si risolve offline. Non porta altre dipendenze. Nel bootstrap la versione è la stessa portata da `spring-boot-starter-logging`, quindi non ci sono conflitti. È anche quello che scrive `docs/plans/notification.md`, fase 04 ("con SLF4J"), però `slf4j-api` non compare nella tabella delle dipendenze pre-approvate.
   - (c) **java.util.logging**: nessuna dipendenza. Nel bootstrap `spring-boot-starter-logging` include `jul-to-slf4j` e il LoggingSystem di Logback installa il bridge, quindi l'output arriva a Logback. Svantaggi: costo del bridge, livelli da configurare in due posti, API poco usata nel progetto.
   - **Raccomandazione: (b)**. È lo standard con Boot e Logback, è già nel piano generale, è un solo artefatto senza dipendenze transitive e non porta nulla di Spring. L'utente deve approvare esplicitamente `org.slf4j:slf4j-api` (compile) nel pom di `notification-log-adapter`.
2. **Dipendenze di test (bloccante).** Oggi il log-adapter non ha né JUnit né AssertJ.
   - (i) `org.junit.jupiter:junit-jupiter` e `org.assertj:assertj-core`, scope test, come nel dominio e nell'application. Sono leggeri, non portano Spring né Logback, ma non sono nella tabella pre-approvata per gli adapter.
   - (ii) `spring-boot-starter-test`, scope test, che è pre-approvato. Porta JUnit, AssertJ, Mockito, `spring-boot-starter`, quindi Logback, e `OutputCaptureExtension`. Però mette Spring nei test di un modulo che la fase 04 vuole senza Spring.
   - (iii) Nessun test nel modulo e test solo nel bootstrap. Lo sconsiglia: il bootstrap non dichiara bean prima della fase 07.
   - **Raccomandazione: (i)**, con un logger finto scritto nel test (punto 7), senza `OutputCaptureExtension` e senza appender Logback.
3. **Formato e livello.** Livello INFO, nome del logger uguale al nome della classe. Formato proposto: un solo spazio tra le coppie, ordine fisso.
   - `Transfer notification transferId=<id> outcome=<COMPLETED|REJECTED> amount=<importo> currency=<XXX> occurredAt=<ISO-8601> reasons=["<m1>", "<m2>"]`
   - COMPLETED: la proposta raccomandata è **omettere `reasons`**, in linea con la fase 04 ("Reasons presenti solo con REJECTED") e con il contratto ("assente con COMPLETED"). L'alternativa è `reasons=[]` sempre presente, utile a chi analizza la riga con un parser.
   - Esempio COMPLETED: `Transfer notification transferId=3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93 outcome=COMPLETED amount=100.25 currency=EUR occurredAt=2026-10-13T09:30:00Z`
   - Esempio REJECTED: `Transfer notification transferId=3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93 outcome=REJECTED amount=20000.00 currency=EUR occurredAt=2026-10-13T09:31:00Z reasons=["Amount must not exceed 10000 EUR", "Source IBAN is locked"]`
4. **Rappresentazione dei campi.**
   - Importo: raccomandato `amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()`. Il dominio garantisce al massimo 2 decimali significativi, quindi non c'è mai arrotondamento. Risultati: `2E+4` dà `20000.00`, `100.5` dà `100.50`, `1.000` dà `1.00`. Alternativa: `toPlainString()` semplice, che conserva la scala inviata dal produttore (`2E+4` dà `20000`, `20000.00` resta `20000.00`), ma così la stessa somma può comparire in forme diverse.
   - occurredAt: `DateTimeFormatter.ISO_INSTANT`, cioè UTC con `Z` e frazioni di secondo solo se presenti.
   - Motivi, raccomandazione: tra virgolette doppie, separati da `", "`, nell'ordine della lista. Alternativa: lo stile di `List.toString()` (`[a, b]`), più semplice ma ambiguo se un motivo contiene una virgola.
5. **Iniezione di righe (CRLF) e caratteri speciali.** `reasons` e anche `transferId` sono testo libero del produttore: il dominio controlla che `transferId` non sia vuoto, ma non che sia un UUID. Raccomandato un escape reversibile su questi due campi: `\` diventa `\\`, `"` diventa `\"`, `\r` `\n` `\t` diventano `\r` `\n` `\t` letterali, gli altri caratteri ISO-control e U+2028/U+2029 diventano `\uXXXX`. Alternativa: sostituirli con uno spazio, più leggibile ma con perdita di informazione. outcome, currency, importo e occurredAt sono sicuri per costruzione e non hanno bisogno di escape.
6. **IBAN dentro i motivi.** Il contratto li esclude per costruzione, ma il testo è libero.
   - Raccomandata una difesa in profondità: applicare a `reasons` e `transferId`, prima dell'escape, la stessa regex di `IbanRedactingProducerListener`, `[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}`, con sostituzione `[IBAN]`. La regex va copiata, non importata: è vietato importare classi del Transfer.
   - I falsi positivi sono improbabili: un UUID in minuscolo o con trattini non corrisponde.
   - Limiti accettati: non vengono riconosciuti IBAN in minuscolo o scritti con spazi.
   - Alternativa: nessuna mascheratura, accettando il rischio come fa il resto del sistema.
7. **Costruttore e logger.** Raccomandato:
   - un costruttore `public NotificationLogOutPortImpl()` che usa `LoggerFactory.getLogger(NotificationLogOutPortImpl.class)`, per la fase 07;
   - un costruttore package-private `NotificationLogOutPortImpl(org.slf4j.Logger logger)` con `Objects.requireNonNull(logger, "logger")`, usato dal test con un `RecordingLogger` che estende `org.slf4j.helpers.LegacyAbstractLogger` (presente in slf4j-api 2.x). Il finto deve implementare solo 5 `isXxxEnabled()`, `getFullyQualifiedCallerName()` e `handleNormalizedLoggingCall(...)`.
   - Alternativa: solo un campo statico, testabile però solo con Logback, quindi con l'opzione (ii) del punto 2. Se si sceglie (a) commons-logging, il finto implementa `org.apache.commons.logging.Log` (18 metodi).
8. **Chiamata null.** `log(null)`: raccomandata `NullPointerException` tramite `Objects.requireNonNull(notification, "notification")`. Il service non passa mai null.
9. **Lunghezza dei motivi.** Non ci sono limiti alla lunghezza di un motivo, quindi una riga può diventare enorme. Raccomandato di non troncare in questa fase; segnalato come rischio.
10. **Modulo o bean.** Nessuno in questa fase: la configurazione dei bean è nella fase 07, come già deciso.

### Modifiche per modulo
**notification-log-adapter** (oggi contiene solo il pom)
- `pom.xml`: aggiungere `org.slf4j:slf4j-api` (compile, senza versione) e `org.junit.jupiter:junit-jupiter` e `org.assertj:assertj-core` (test, senza versione). **Solo dopo l'approvazione delle ambiguità 1 e 2.**
- `src/main/java/com/bank/notification/log/port/out/log/impl/NotificationLogOutPortImpl.java`, package `com.bank.notification.log.port.out.log.impl`:
  - `public class NotificationLogOutPortImpl implements NotificationLogOutPort`
  - `public NotificationLogOutPortImpl()` e `NotificationLogOutPortImpl(Logger logger)` (package-private)
  - `@Override public void log(TransferNotification notification)`: controllo di null; se `!logger.isInfoEnabled()` ritorna subito; altrimenti una sola `logger.info(line(notification))`. Nessun try/catch: un'eccezione del logger risale.
  - `static String line(TransferNotification n)` (package-private): costruisce la riga **solo** dagli accessori `transferId()`, `outcome()`, `amount().amount()`, `amount().currency()`, `occurredAt()`, `reasons()`. Non usa mai `toString()` della notifica, né `sourceIban()` o `destinationIban()`.
  - Helper privati: `formatAmount(BigDecimal)`, `sanitize(String)` (redazione IBAN e poi escape), `formatReasons(List<String>)`.
  - Javadoc in inglese che dichiara: mai IBAN, una riga per notifica, nessuna eccezione catturata.
- `src/test/java/com/bank/notification/log/port/out/log/impl/RecordingLogger.java`: estende `LegacyAbstractLogger`; registra livello, messaggio, argomenti e throwable; ha i flag `infoEnabled` e `toThrow`.
- `src/test/java/com/bank/notification/log/port/out/log/impl/NotificationLogOutPortImplTest.java`.

Nessun'altra modifica: dominio, application, kafka, mongo, bootstrap, contratto e pom padre restano invariati. Le dipendenze nuove sono gestite dal BOM di Boot, quindi il pom padre non va toccato.

### Test attesi
Classe `NotificationLogOutPortImplTest`. Dati di prova:
- transferId `3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93`;
- COMPLETED con IBAN `IT60X0542811101000000000001` e `...0002`;
- REJECTED con `...0099` e `...0002`, e una variante con `...0001` e `...0002`.

| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | COMPLETED 100.25 EUR, 2026-10-13T09:30:00Z | `log` | un solo evento, livello INFO, messaggio **uguale** alla riga COMPLETED di esempio; non contiene `reasons` (scenario 1) |
| 2 | REJECTED con i due motivi del contratto, 20000.00 | `log` | un solo evento INFO, messaggio uguale alla riga REJECTED di esempio, motivi nell'ordine (scenario 2) |
| 3 | REJECTED con 3 motivi | `log` | i motivi compaiono nell'ordine della lista |
| 4 | casi 1-3 | `log` | il messaggio non contiene `IT60X0542811101000000000001`, `...0002`, `...0099`, né `0542811101`, né `IT60X` (scenario 10) |
| 5 | importi `2E+4`, `100.5`, `20000.00`, `1.000` (test parametrizzato) | `log` | `amount=20000.00`, `100.50`, `20000.00`, `1.00`; mai notazione scientifica |
| 6 | occurredAt con millisecondi | `log` | `occurredAt=2026-10-13T09:30:00.123Z` |
| 7 | motivo `"a\r\nTransfer notification transferId=fake"` | `log` | un solo evento; il messaggio non contiene `\r` né `\n` reali, contiene la sequenza escapata |
| 8 | motivo con `"`, `\` e un carattere di controllo (`\u0007`) | `log` | escapati come da ambiguità 5 |
| 9 | transferId con `\n` | `log` | escapato, una sola riga |
| 10 | motivo `"Source IT60X0542811101000000000099 locked"` | `log` | contiene `[IBAN]`, non contiene `0542811101` (se si approva l'ambiguità 6) |
| 11 | qualunque notifica | `log` | il messaggio non contiene `TransferNotification[` (non si usa `toString()`) |
| 12 | due notifiche diverse | due `log` | due eventi, uno per notifica |
| 13 | logger con INFO disabilitato | `log` | nessun evento, nessuna eccezione |
| 14 | logger che lancia `RuntimeException X` | `log` | l'eccezione catturata `isSameAs` X |
| 15 | notifica null | `log` | `NullPointerException` |
| 16 | logger null | costruttore | `NullPointerException` |
| 17 | costruttore senza argomenti (SLF4J senza provider, quindi NOP) | `log` di una notifica valida | nessuna eccezione |

### Criteri di accettazione
- `.\mvnw.cmd test` da `notification-service/` in BUILD SUCCESS: 115 test di baseline più quelli nuovi del log-adapter, nessun test esistente modificato.
- Nel pom del log-adapter compaiono solo le dipendenze approvate dall'utente; nessun altro pom cambia.
- Un grep su `notification-log-adapter/src/main` per `org.springframework`, `java.util.logging`, `org.apache.commons.logging`, `System.out`, `System.err`, `sourceIban`, `destinationIban`, `com.bank.transfer`, `com.bank.fraud`, `toString()` dà zero risultati (salvo l'API scelta).
- Package `com.bank.notification.log.port.out.log.impl`, nome `...OutPortImpl`, stile del servizio: 2 spazi, import statici in cima, java/javax in fondo, javadoc in inglese.
- Contratto, sezione 7 e scenari 1, 2 e 10 coperti dai test 1-4.

### Rischi e fuori perimetro
- **Dipendenze nuove**: `slf4j-api` (compile), `junit-jupiter` e `assertj-core` (test), da approvare. Senza approvazione l'implementazione non può partire.
- Nei test del modulo, senza provider SLF4J compare su stderr l'avviso "No SLF4J providers were found" (test 17). È innocuo.
- Nel bootstrap la riga passa dal pattern di default di Logback: se qualcuno cambia il formato dei log, la parte chiave=valore resta stabile ma il prefisso cambia.
- La mascheratura degli IBAN non riconosce forme in minuscolo o con spazi. Il formato della riga, una volta adottato, diventa di fatto un'interfaccia per chi analizza i log.
- Con la consegna at-least-once la stessa riga può comparire due volte: è accettato.
- Fuori perimetro:
  - Kafka, Mongo, `NotificationConfig` e bean (fase 07);
  - dominio, application, contratto, transfer-service, fraud-service;
  - log strutturato in JSON, troncamento dei motivi;
  - commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **Ambiguità 1: SLF4J approvato.** Si aggiunge al pom di `notification-log-adapter` SOLO `org.slf4j:slf4j-api` (compile, senza versione: la dà il BOM di Boot 4.1.1, 2.0.18).
2. **Ambiguità 2: opzione (i) approvata.** Si aggiungono al pom SOLO `org.junit.jupiter:junit-jupiter` e `org.assertj:assertj-core` (test, senza versione), con un logger finto scritto nel test (`RecordingLogger` che estende `LegacyAbstractLogger`). NIENTE `spring-boot-starter-test`.
3. **Ambiguità 3-10: tutte le raccomandazioni (R) approvate**: riga con `transferId`, `outcome`, `amount` (sempre con 2 decimali), `currency`, `occurredAt` (ISO_INSTANT), `reasons` solo per REJECTED tra virgolette separati da `", "`; escape reversibile di `reasons` e `transferId`; mascheratura `[IBAN]` con la regex copiata; costruttore pubblico senza argomenti e package-private con il logger; `log(null)` -> NPE; nessun troncamento; nessun modulo né bean in questa fase.
4. Ruoli: il tester aggiunge al pom del log-adapter le tre dipendenze approvate (altrimenti i test non compilano) e scrive i test; l'implementer scrive la classe di produzione e non tocca né pom né test.

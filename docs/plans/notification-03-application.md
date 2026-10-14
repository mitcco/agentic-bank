### Obiettivo
Creare in `notification-application` le porte (`NotifyTransferInPort`, `NotificationStoreOutPort`, `NotificationLogOutPort`), il `NotifyTransferCommand` e un `NotifyTransferServiceImpl` senza annotazioni Spring. Il service costruisce `TransferNotification` dal command, poi chiama `save` e dopo `log`. Le eccezioni delle porte risalgono senza essere toccate; un command non valido lancia `InvalidNotificationException` prima di chiamare qualsiasi porta. Nessuna dipendenza nuova e bootstrap invariato.

### Ambiguità
Nessuna ambiguità richiede ancora una decisione dell'utente: le decisioni 1-8 coprono tutto. Restano tre scelte di dettaglio che non cambiano i test. Sono indicate qui perché siano visibili:

1. **Scostamento da `docs/plans/notification.md` (decisione 1, alternativa a).** La fase 03 scrive "`NotifyTransferServiceImpl` (`@Service`)", ma il service non avrà `@Service`. Nella tabella "Dipendenze pre-approvate" la riga `org.springframework:spring-context` (per `@Service`) non si usa più. Dopo l'approvazione l'orchestratore deve aggiornare sia la riga della fase 03 sia quella riga della tabella. Il planner non scrive file. La fase 07 deve dichiarare il bean in `NotificationConfig`, come si fa per `CheckTransferServiceImpl` del fraud-service.
2. **Campi IBAN, amount e currency null (scelta del piano, segue il modello di `CheckTransferServiceImpl`).** Il service chiama direttamente `new Iban(command.sourceIban())` e `new Money(command.amount(), command.currency())`.
   - Un `sourceIban` null produce "IBAN must not be null or blank" da `Iban`, non "source IBAN must not be null" dal costruttore di `TransferNotification`. Il messaggio quindi non distingue mittente e destinatario.
   - Il tipo resta `InvalidNotificationException` e il messaggio non contiene il valore, quindi i test non cambiano.
   - Solo `outcome` null arriva al costruttore del dominio, come chiede la decisione 3.
3. **Un caso di test in più** in `NotifyTransferServiceImplValidationTest`: REJECTED con un motivo null (`Arrays.asList("a", null)`) dà `InvalidNotificationException` e non `NullPointerException`. Discende dalla decisione 2 (il command non controlla gli elementi). Se non lo si vuole, si toglie senza altri effetti.

### Modifiche per modulo

**`notification-application`** (solo file nuovi; il `pom.xml` non cambia: `notification-domain` in compile, `junit-jupiter` e `assertj-core` in test)

`src/main/java/com/bank/notification/application/`
- `port/in/NotifyTransferInPort.java` (`com.bank.notification.application.port.in`)
  - Interfaccia con `void notifyTransfer(NotifyTransferCommand command);`.
  - Non espone tipi del dominio.
  - Javadoc in inglese: lancia `InvalidNotificationException` se il command viola le regole del contratto; le eccezioni delle porte out risalgono invariate.
- `port/out/NotificationStoreOutPort.java` (`...application.port.out`)
  - Interfaccia con `void save(TransferNotification notification);`.
  - Javadoc in inglese: idempotente per `transferId`; un secondo salvataggio con lo stesso `transferId` sostituisce il documento, non lo duplica.
- `port/out/NotificationLogOutPort.java` (`...application.port.out`)
  - Interfaccia con `void log(TransferNotification notification);`.
  - Javadoc in inglese: registra una notifica già salvata.
- `command/NotifyTransferCommand.java` (`...application.command`)
  - `public record NotifyTransferCommand(String transferId, String sourceIban, String destinationIban, BigDecimal amount, String currency, String status, List<String> reasons, Instant occurredAt)`.
  - Il costruttore compatto fa solo `if (reasons == null) reasons = List.of();`: niente `List.copyOf` e nessun altro controllo.
  - Javadoc per i mapper:
    - `reasons` null o assente diventa una lista vuota;
    - qui non si valida nessun campo, la validazione la fanno il service e il dominio;
    - `status` è la stringa del contratto (`COMPLETED`/`REJECTED`, maiuscolo).
- `service/NotifyTransferServiceImpl.java` (`...application.service`)
  - `public class NotifyTransferServiceImpl implements NotifyTransferInPort`, **senza** `@Service` né `@Transactional`.
  - Costruttore `public NotifyTransferServiceImpl(NotificationStoreOutPort store, NotificationLogOutPort log)` con `Objects.requireNonNull` su entrambe le porte (messaggi "store" e "log").
  - `notifyTransfer`, nell'ordine:
    1. `Objects.requireNonNull(command, "command")`;
    2. costruisce `new TransferNotification(command.transferId(), new Iban(...), new Iban(...), new Money(...), toOutcome(command.status()), command.reasons(), command.occurredAt())`;
    3. chiama `store.save(notification)`;
    4. chiama `log.log(notification)` con la stessa istanza.
  - Nessun try/catch, nessun wrapping, nessun log.
  - `private static TransferOutcome toOutcome(String status)`:
    - null restituisce null, e il dominio lancia "outcome must not be null";
    - `"COMPLETED".equals(status)` restituisce `COMPLETED`, `"REJECTED".equals(status)` restituisce `REJECTED`;
    - qualunque altro valore lancia `new InvalidNotificationException("status is not supported")`;
    - niente `TransferOutcome.valueOf` e il valore ricevuto non entra nel messaggio.
  - Javadoc della classe: il bean lo dichiara il bootstrap (fase 07).

`src/test/java/com/bank/notification/application/`
- `service/FakeNotificationStoreOutPort.java`
  - Classe package-private che riceve il registro condiviso `List<String>`.
  - Aggiunge `"save"` al registro e conserva le notifiche ricevute in `saved()`.
  - `throwing(RuntimeException)` fa lanciare l'eccezione dopo aver registrato la chiamata.
- `service/FakeNotificationLogOutPort.java`: uguale al precedente, con `"log"` e `logged()`.
- `service/NotifyTransferServiceImplTest.java`, `service/NotifyTransferServiceImplValidationTest.java`, `command/NotifyTransferCommandTest.java` (vedi Test attesi).

Stile: 2 spazi, nomi dei test in inglese, import statici in cima, poi gli altri in ordine alfabetico con `java`/`javax` in fondo e una riga vuota tra i blocchi.

**Altri moduli**: nessuna modifica. `notification-domain`, gli adapter, `notification-bootstrap` e tutti i pom restano invariati.

### Test attesi
Si scrivono prima del codice, con JUnit 5 (anche `@ParameterizedTest`, già incluso nell'aggregatore `junit-jupiter`) e AssertJ.
- Dati di prova: IBAN `IT60X0542811101000000000001` e `...0002`, ID `3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93`, importo `100.25 EUR`, `occurredAt` `2026-10-13T09:30:00Z`.
- Motivi: `"Amount must not exceed 10000 EUR"`, `"Source IBAN is locked"`.
- "Eccezione" vuol dire `InvalidNotificationException`.

**`com.bank.notification.application.service.NotifyTransferServiceImplTest`**
| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | command COMPLETED valido con `reasons` null | `notifyTransfer` | registro = `[save, log]`; `saved().get(0)` `isSameAs` `logged().get(0)`; campi attesi (importo con `isEqualByComparingTo`, IBAN `value()`, outcome COMPLETED, `occurredAt`); `reasons()` vuoto (scenario 1) |
| 2 | REJECTED con due motivi | `notifyTransfer` | notifica salvata con `reasons()` `containsExactly` i due motivi nell'ordine; registro `[save, log]` (scenario 2) |
| 3 | store che lancia `RuntimeException X` | `notifyTransfer` | l'eccezione catturata `isSameAs` X; registro = `[save]`; log mai chiamato (scenario 9, parte application) |
| 4 | log che lancia `RuntimeException Y` | `notifyTransfer` | l'eccezione catturata `isSameAs` Y; `saved()` ha 1 elemento; registro `[save, log]` |
| 5 | lo stesso command (stesso `transferId`) inviato due volte | due `notifyTransfer` | save chiamato 2 volte con lo stesso `transferId`; registro `[save, log, save, log]` (scenario 3, parte application) |
| 6 | store null; log null | costruttore | `NullPointerException` in entrambi i casi |
| 7 | service valido | `notifyTransfer(null)` | `NullPointerException`; registro vuoto |

**`com.bank.notification.application.service.NotifyTransferServiceImplValidationTest`** (in ogni riga: eccezione, registro vuoto e nessuna chiamata alle porte)
| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | un solo campo null alla volta: `transferId`, `sourceIban`, `destinationIban`, `amount`, `currency`, `status`, `occurredAt` (parametrizzato) | `notifyTransfer` | eccezione; nessuna porta chiamata |
| 2 | `status` null | `notifyTransfer` | messaggio = "outcome must not be null" (arriva dal dominio) |
| 3 | `status` `PENDING`, `completed`, `""`, `" COMPLETED"` | `notifyTransfer` | eccezione con messaggio "status is not supported" che non contiene il valore ricevuto (scenario 5) |
| 4 | REJECTED con `reasons` null; REJECTED con `List.of()` | `notifyTransfer` | eccezione (scenario 6) |
| 5 | COMPLETED con un motivo | `notifyTransfer` | eccezione |
| 6 | `amount` `0` | `notifyTransfer` | eccezione (scenario 7) |
| 7 | `amount` `1.234` | `notifyTransfer` | eccezione |
| 8 | `currency` `eur` | `notifyTransfer` | eccezione |
| 9 | `sourceIban` non valido (26 caratteri); `destinationIban` con formato non valido | `notifyTransfer` | eccezione |
| 10 | REJECTED con `Arrays.asList("a", null)` (Ambiguità 3) | `notifyTransfer` | eccezione, non NPE |
| 11 | tutti i casi di errore 1-10 | messaggio dell'eccezione | non contiene `IT60X0542811101000000000001`, `...0002`, `0542811101` né l'importo di prova (`100.25`, `1.234`) |

**`com.bank.notification.application.command.NotifyTransferCommandTest`**
| # | Dato | Quando | Allora |
|---|---|---|---|
| 1 | `reasons` null | costruzione | `reasons()` `isEqualTo(List.of())` ed è vuota |
| 2 | `reasons` = `Arrays.asList("a", null)` | costruzione | nessuna eccezione; `reasons()` contiene `"a"` e null |
| 3 | tutti i campi null tranne `reasons` | costruzione | nessuna eccezione; accessori null |
| 4 | campi valorizzati | costruzione | gli accessori restituiscono i valori passati (`amount` con `isEqualByComparingTo`) |

L'assenza di log nel service non si prova con un test: è un criterio verificato con grep.

### Criteri di accettazione
- `.\mvnw.cmd test` da `notification-service/` dà BUILD SUCCESS senza Docker: i 77 test di baseline (dominio 70, bootstrap 7, inclusi i 7 di `NotificationApplicationTests`) più i nuovi, tutti verdi.
- Tutti i `pom.xml` sono invariati, in particolare `notification-application/pom.xml`: nessun `spring-context`, nessuna dipendenza nuova.
- Il grep su `notification-service/notification-application/src` per `org.springframework`, `jakarta`, `tools.jackson`, `com.fasterxml`, `com.bank.transfer`, `com.bank.fraud`, `org.slf4j`, `java.util.logging`, `org.apache.commons.logging`, `System.out`, `System.err` dà zero risultati.
- Nessun `@Service` né `@Transactional`.
- Il service non usa `TransferOutcome.valueOf` e non ha `catch`.
- Le porte in non espongono tipi del dominio; le porte out ricevono `TransferNotification`.
- I test stanno nello stesso package della classe testata.
- Nessun IBAN né importo nei messaggi di eccezione (riga 11 della validazione).
- Scenari del contratto coperti per la parte application: 1, 2, 3, 5, 6, 7 e 9 (propagazione).
- Restano invariati `transfer-service/`, `fraud-service/`, `docs/contracts/`, i CLAUDE.md, `notification-bootstrap` e `notification-domain`.

### Rischi e fuori perimetro
**Rischi**
- **Il bean non esiste ancora in Spring.** Fino alla fase 07 nessun contesto istanzia il service, quindi i test d'integrazione del bootstrap non coprono questa fase: la copertura è solo unitaria. Se la fase 07 dimentica il `@Bean` in `NotificationConfig`, il listener della fase 06 non avrà una `NotifyTransferInPort`.
- **Vincoli per i mapper della fase 06:**
  - i campi assenti devono arrivare null nel command, senza default inventati: li rifiuta il service o il dominio;
  - `reasons` assente può arrivare null, perché il command lo converte in `List.of()`;
  - `status` va passato come stringa, senza `valueOf` nel mapper: la conversione esatta e sensibile alle maiuscole la fa solo il service.
- **`status` sconosciuto genera una `InvalidNotificationException` creata dall'application** (classe del dominio usata dal service). La fase 06 la classifica come non ritentabile insieme a quelle del dominio. Il messaggio "status is not supported" non contiene il valore, quindi il log d'errore non dice quale status è arrivato. È voluto.
- **`notifyTransfer(null)` lancia `NullPointerException`.** Per la regola della fase 06 ("ogni altra RuntimeException è ritentabile") verrebbe ritentata 3 volte. È accettato perché è un errore di programmazione, e il mapper non deve mai passare null. La fase 06 deve garantirlo.
- **Messaggio IBAN generico** per mittente o destinatario null (Ambiguità 2).
- **IBAN normalizzati dal dominio.** Un IBAN con spazi o minuscole viene accettato anche se il contratto lo vuole già normalizzato. È coerente con la fase 02.
- **Duplicati nel log.** Un messaggio ripetuto produce due `save` e due righe di log: è il comportamento at-least-once accettato.
- Nessuna dipendenza nuova proposta.

**Fuori perimetro**
- `@Service`, `spring-context`, `@Transactional`.
- DTO di ritorno (il service è `void`), mapper.
- Adapter log, Mongo e Kafka; `NotificationConfig` e bootstrap.
- Aggiornamento di `docs/plans/notification.md` (lo fa l'orchestratore dopo l'approvazione).
- Modifiche al dominio, al contratto, agli altri servizi, ai CLAUDE.md.
- Commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **Alternativa (a):** niente `@Service` né `spring-context` né altre dipendenze nell'application; il bean lo dichiara `NotificationConfig` nella fase 07. Il bootstrap non si tocca.
2. Tipi e normalizzazione del command, conversione dell'esito (String, confronto esatto, nessun `valueOf`, messaggio senza valore), service `void`, nomi delle porte, `Objects.requireNonNull` sulle porte e su `notifyTransfer(null)` (NPE), test con fake scritti a mano: tutte le raccomandazioni approvate.
3. Ruoli: il tester scrive SOLO i test e i fake (compresa la cartella `src/test`); l'implementer crea porte, command e service in `src/main`.

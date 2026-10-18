# transfer-10 - Saga minima e didattica per i bonifici esterni (v3: instradamento per ABI con service e porte in)

Stato: **in attesa di approvazione** (v3). Piano prodotto dal planner sul branch `apply-saga-pattern` (da main, commit `0f25dad`).
Sostituisce la v1 di questo piano (endpoint dedicato
`POST /transfers/external`, scartato dall'utente: "non mi piace che sia il client a scegliere se l'IBAN e esterno").
Solo piano: nessuna fase avviata. Scopo: **didattico**.

## v3.1: UNKNOWN e slf4j (decisione dell'utente dopo il piano della Fase 2)
Queste voci **prevalgono** sulle sezioni successive di questo file; il dettaglio e in `transfer-10-02-saga-stanza-simulata.md` (rev. 2).
- **UNKNOWN nel perimetro.** Si compensa solo su `ClearingHouseUnavailableException` (invio certamente non ricevuto). `ClearingHouseTimeoutException` o qualunque altro errore di `submit`: stato UNKNOWN, fondi addebitati, nessuna compensazione, nessuna notifica; un esito tardivo applicato da `processOutcomes()` chiude il bonifico.
- **Dominio:** `UNKNOWN`; `markUnknown()` da SENT; `settle()` e `rejectAfterSent()` da SENT **o** UNKNOWN (emendamento E0 alla Fase 1).
- **Log:** slf4j al posto di `System.Logger` (`slf4j-api` nell'application, Logback nel bootstrap). **Unica modifica di pom:** `slf4j-api` senza versione in `transfer-application/pom.xml` (da approvare; sostituisce "nessuna modifica ai pom").
- **Simulatore:** modi `ACCEPT`, `REJECT`, `UNAVAILABLE`, `TIMEOUT_THEN_ACCEPT`, `TIMEOUT_THEN_REJECT`.
- **REST (Fase 3):** 202 per SENT **e** UNKNOWN, 200 negli altri casi; end-to-end con `TIMEOUT_THEN_*`.
- **Fuori perimetro:** UNKNOWN non e piu fuori (restano RETURNED e RESERVED); fuori restano riconciliazione attiva (interrogare la stanza), job di riconciliazione, scadenze automatiche.
- **Valore didattico:** si aggiunge "incertezza: si compensa solo quando si e certi che l'effetto non e avvenuto; con esito incerto UNKNOWN tiene i fondi e un esito tardivo chiude la Saga"; "cosa NON mostra": riconciliazione attiva e scadenze automatiche (non piu "stato sconosciuto").
- **Rischi:** tolti "invio fallito = rimborso" e "bridge JUL"; aggiunti "UNKNOWN senza esito tardivo: fondi addebitati finche non arriva un esito" e "ordine dei catch Timeout/Unavailable".

## Cosa cambia nella v3 rispetto alla v2 (proposta dell'utente)
Il router non e piu un bean costruito a mano nel bootstrap: e un normale `@Service` che usa **due porte in**.
- `TransferRoutingServiceImpl` (`@Service`) implementa `CreateTransferInPort` (l'unico bean di quel tipo: REST e test non cambiano).
- Nuova porta in `CreateInternalTransferInPort` (stessa firma di `CreateTransferInPort`), implementata da `CreateTransferServiceImpl`: cambia solo la riga `implements` (e l'import);
  `@Service`, costruttore a 5 argomenti e logica restano; `CreateTransferServiceImplTest` lo usa col tipo concreto e non cambia.
- `ExternalTransferSagaServiceImpl` implementa `CreateExternalTransferInPort` (e `ProcessClearingOutcomesInPort`).
- Il router riceve `(BankCode, CreateInternalTransferInPort, CreateExternalTransferInPort)`.
- Spariscono `TransferRoutingConfig` con i `new` e il rischio dei `@Qualifier` / `@Primary`: i service sono tutti per scansione e Spring inietta le porte out come oggi.
  Resta solo un bean `BankCode` da `transfer.bank-code` (config minima nel bootstrap).
- La zona grigia "service che usa porte in" e risolta: e lo schema scelto dall'utente.

## Cosa cambia rispetto alla v1
- Non c'e piu `POST /transfers/external` e non nascono `ExternalTransferController`, `ExternalTransferServiceDto` ne il 400
  "Destination is an account of this bank".
- Resta un solo `POST /transfers`. Il nuovo `TransferRoutingServiceImpl` sceglie il flusso confrontando l'ABI del destinatario con `transfer.bank-code`.
- `CreateTransferServiceImpl` passa a implementare `CreateInternalTransferInPort`; l'unico bean `CreateTransferInPort` e il router.
- `TransferController.executeTransfer` restituisce `ResponseEntity<TransferResponseDto>`: 202 se lo stato e `SENT`, altrimenti 200.
- Nel dominio arrivano `BankCode` e `Iban.bankCode()`.
- Le fasi cambiano ordine: router, cambio di `implements` e 202 arrivano insieme nell'ultima fase.
- Il resto e invariato: tabella `external_transfer` con V3, stato `SENT`, rimborso con `credit` sotto lock, simulatore nel bootstrap,
  `processOutcomes()` a mano piu scheduler spento, `System.Logger`. Nessun GET, nessun nuovo modulo, nessuna modifica ai pom.

## Fatti riverificati dal planner sul branch
- **Chi usa il bean concreto `CreateTransferServiceImpl`:** nessun test. Compare solo in `CreateTransferServiceImplTest`, che lo costruisce con `new` e 5 argomenti.
- **Chi inietta `CreateTransferInPort` per tipo:** `CreateTransferConcurrencyTest`, `OutboxPublisherIntegrationTest`,
  `OutboxPublisherKafkaWiringIntegrationTest`, `OutboxRollbackIntegrationTest`, piu `TransferServiceDtoImpl`: serve esattamente un bean di quel tipo.
- **I test del bootstrap usano bean `@Primary`** per sostituire `FraudCheckOutPort`, `NotificationOutPort` (in `OutboxRollbackIntegrationTest`, che
  avvolge `@Qualifier("jpaOutboxNotificationOutPortImpl")`) e `NotificationSenderOutPort`. Con la v3 i service restano bean per scansione e ricevono le porte
  out come oggi: nessun cablaggio manuale che possa scavalcarli.
- **`CreateTransferServiceImpl` nei test:** solo come tipo concreto in `CreateTransferServiceImplTest` (`:71`, `:75`); nessun test lo usa come `CreateTransferInPort`.
- **Tutti i test esistenti usano IBAN con ABI `05428`:** restano nel flusso interno, compresi t29 e l'IBAN bloccato `...0099`.
- **`TransferControllerTest` e compatibile:** costruisce `new TransferController(service)` con un argomento (il costruttore non cambia), controlla solo
  `status().isOk()` per COMPLETED e REJECTED, `jsonPath` e i codici di errore; nessun test chiama direttamente il metodo del controller.
- **`IbanTest` resta invariato** (solo test nuovi). La regex di `Iban` garantisce gia che gli indici 5..9 siano alfanumerici: `bankCode()` non puo fallire su un `Iban` valido.
- **`TransactionTemplate` non e dichiarato nel bootstrap:** viene dall'autoconfigurazione di Boot, ed e lo stesso che usa oggi il service `@Service`; il bean del router lo riceve per tipo.
- Su questo branch `notification-service` non c'e: il precedente `NotifyTransferServiceImpl` vale come idea ma non si puo verificare qui.
- `TransferNotificationDto` oggi rifiuta solo `PENDING`: con `SENT` nell'enum serve una guardia.
- Gia confermati dalla v1: FK `transfer.dest_iban REFERENCES account(iban)` in V1; costruttore a 5 argomenti di `CreateTransferServiceImpl`;
  `TransferServiceDto`, `CreateTransferInPort`, `FraudCheckOutPort` usate come lambda nei test; `TransferRepositoryOutPort` ha solo `save`;
  `WebExceptionHandler` mappa 400, 404, 422 e 503.

## Obiettivo
Aggiungere al transfer-service una Saga orchestrata minima per i bonifici verso un ABI diverso dal nostro: addebito, invio a una stanza di
compensazione simulata, poi conferma oppure rimborso. Resta l'unico `POST /transfers` e il flusso interno resta identico, con i 392 test verdi senza modifiche.

## Ambiguita (il piano segue le raccomandazioni R; nessuna blocca il piano)
1. **(a) Criterio interno/esterno: deciso dall'utente.** Si confronta `Iban.bankCode()` del destinatario con il `BankCode` configurato. Stesso ABI: flusso
   interno invariato, compreso il 404 per un destinatario inesistente. ABI diverso: Saga, e il destinatario non si cerca tra i conti. Limiti: solo IBAN a
   27 caratteri; un conto con ABI diverso da `05428` eventualmente presente nel database non riceverebbe mai bonifici interni (oggi `DemoDataSeeder` e i test usano solo `05428`).
2. **(a) Proprieta. R:** `transfer.bank-code=${TRANSFER_BANK_CODE:05428}`. Il bean `BankCode` sta in `config/TransferRoutingConfig` (solo quel bean). Un valore non valido fa fallire l'avvio (voluto).
3. **(a) Cablaggio: deciso dall'utente.** Router `@Service` che implementa `CreateTransferInPort` e usa le porte in `CreateInternalTransferInPort` e `CreateExternalTransferInPort`.
   `CreateTransferServiceImpl` cambia solo `implements` (e import): `@Service`, costruttore e test restano. Conseguenza: una nuova interfaccia con la stessa firma di `CreateTransferInPort`
   (il ruolo e diverso: strategia interna contro punto d'ingresso).
4. **(a) Ordine di validazione nel router. R:** il router costruisce prima `new Iban(source)` e poi `new Iban(dest)`, nello stesso ordine del service interno: con due IBAN non
   validi l'eccezione e il messaggio restano quelli di oggi. Un command null da NPE.
5. **(g) 202. R:** e `TransferController` a decidere: `"SENT".equals(body.transferStatus())` da 202, altrimenti 200. `TransferServiceDto`, `CreateTransferInPort` e `FraudCheckOutPort`
   restano invariate. Compromesso: una piccola mappatura stato->codice HTTP nell'adapter, che e il posto giusto per il protocollo.
6. **(b) FK su `dest_iban`. R:** tabella separata `external_transfer` in `V3__create_external_transfer.sql`. Il nome del vincolo generato non e portabile tra H2 e Postgres e la ricostruzione della tabella e stata scartata.
7. **(c) Stato e motivo. R:** `SENT` nell'enum; colonna `reason VARCHAR(500)` nullable; il motivo passa dalla porta come `String`. Per il rifiuto dell'antifrode, i motivi uniti con `"; "`.
8. **(d) Rimborso e idempotenza. R:** in una transazione, `findByIdForUpdate` sul bonifico, applica solo se lo stato e `SENT`, poi lock del conto, `credit`, `REJECTED` con motivo, outbox. Ordine dei lock: bonifico, poi conto.
9. **(e) Simulatore nel bootstrap. R:** nessun nuovo modulo (deviazione dalla regola "un modulo per tecnologia", documentata; si sposta in `transfer-clearing-adapter` con una stanza reale).
10. **(f) Esiti. R:** `processOutcomes()` a mano, piu uno scheduler spento (`CLEARING_OUTCOMES_ENABLED=false`).
11. **(h) Transazioni. R:** `TransactionTemplate`; l'invio alla stanza fuori da ogni transazione.
12. **(i) Log. R:** `System.Logger`, solo `transferId`, stato e nome semplice dell'eccezione.
13. **GET. R:** non farlo.

## Modifiche per modulo

**transfer-domain** (`com.bank.transfer.domain`)
- `BankCode` (nuovo record): esattamente 5 caratteri `[A-Z0-9]`, altrimenti `IllegalArgumentException`.
- `Iban`: aggiungere `BankCode bankCode()` = `new BankCode(value.substring(5, 10))`. Niente altro cambia.
- `TransferStatusEnum`: aggiungere `SENT`.
- `Transfer`: tre transizioni nuove, ognuna restituisce una nuova istanza (`complete()` e `reject()` invariati): `markSent()` PENDING->SENT; `settle()` SENT->COMPLETED; `rejectAfterSent()` SENT->REJECTED.

**transfer-application** (`com.bank.transfer.application`)
- `port/in/CreateExternalTransferInPort`: `TransferResultDto executeExternalTransfer(CreateTransferCommand)`; `port/in/ProcessClearingOutcomesInPort`: `int processOutcomes()`;
  `port/in/CreateInternalTransferInPort`: `TransferResultDto executeTransfer(CreateTransferCommand)` (stessa firma di `CreateTransferInPort`).
- `port/out/ExternalTransferRepositoryOutPort`: `void save(Transfer, String reason)` e `Optional<Transfer> findByIdForUpdate(String)`;
  `port/out/ClearingHouseOutPort`: `void submit(ClearingInstructionDto)` e `List<ClearingOutcomeDto> pollOutcomes()`.
- `dto/ClearingInstructionDto`, `dto/ClearingOutcomeDto` (un `REJECTED` vuole un motivo non vuoto, un `ACCEPTED` non ne vuole), `dto/ClearingDecisionEnum {ACCEPTED, REJECTED}`;
  `exception/ClearingHouseUnavailableException(transferId)` (messaggio con il solo id).
- `dto/TransferNotificationDto`: la guardia diventa "solo COMPLETED o REJECTED".
- `service/ExternalTransferSagaServiceImpl` (`@Service`): implementa `CreateExternalTransferInPort` e `ProcessClearingOutcomesInPort`. Non controlla piu che il destinatario sia interno.
  Costruttore: `(AccountRepositoryOutPort, ExternalTransferRepositoryOutPort, FraudCheckOutPort, NotificationOutPort, ClearingHouseOutPort, TransactionTemplate)`. Passi: validazione,
  mittente esistente, antifrode, transazione di addebito con `SENT`, `submit` fuori transazione; se l'invio fallisce, compensazione "Clearing house unavailable"; poi `applyOutcome`, idempotente.
- `service/TransferRoutingServiceImpl implements CreateTransferInPort` (`@Service`). Costruttore `(BankCode ourBankCode, CreateInternalTransferInPort internal, CreateExternalTransferInPort external)`,
  null rifiutati. `executeTransfer`: valida i due `Iban`, poi se il `bankCode()` del destinatario e il nostro chiama `internal`, altrimenti `external`.
- `service/CreateTransferServiceImpl`: **solo `implements CreateInternalTransferInPort`** al posto di `CreateTransferInPort` (e l'import). `@Service`, costruttore e logica invariati.

**transfer-postgres-adapter** (`com.bank.transfer.postgres`)
- `V3__create_external_transfer.sql`: `id VARCHAR(36) PK`, `source_iban VARCHAR(27) NOT NULL REFERENCES account(iban)`, `dest_iban VARCHAR(27) NOT NULL` (senza FK), `amount NUMERIC(19,4)`,
  `currency VARCHAR(3)`, `status VARCHAR(20)`, `reason VARCHAR(500)` nullable, `created_at TIMESTAMP DEFAULT now()`.
- `repository.model.ExternalTransferEntity`; `repository.SpringDataExternalTransferRepository` (`findByIdForUpdate` con `@Lock(PESSIMISTIC_WRITE)`);
  `port.out.postgres.impl.jpa.JpaExternalTransferRepositoryOutPortImpl` (`@Repository`).

**transfer-rest-adapter** (`com.bank.transfer.rest`)
- `port.in.rest.TransferController`: `ResponseEntity<TransferResponseDto> executeTransfer(...)`: 202 se `SENT`, altrimenti 200. Costruttore invariato.
- `TransferServiceDto`, `TransferServiceDtoImpl`, i mapper e `WebExceptionHandler`: invariati.

**transfer-bootstrap** (`com.bank.transfer`)
- `config/TransferRoutingConfig`: solo il bean `BankCode` da `transfer.bank-code`. Router e service interno sono `@Service` e non si cablano a mano.
- Simulatore `clearing.port.out.clearing.impl.SimulatedClearingHouseOutPortImpl` e `SimulatedClearingModeEnum` (`ACCEPT`, `REJECT`, `UNAVAILABLE`); `config/ClearingConfig`, `config/ClearingOutcomesScheduler`, `config/ClearingOutcomesSchedulingConfig`.
- `application.properties`: `transfer.bank-code=${TRANSFER_BANK_CODE:05428}`, `clearing.simulator.mode=${CLEARING_SIMULATOR_MODE:ACCEPT}`, `clearing.outcomes.enabled=${CLEARING_OUTCOMES_ENABLED:false}`,
  `clearing.outcomes.fixed-delay=2s`, `clearing.outcomes.initial-delay=5s`.
- `transfer-service/CLAUDE.md`: criterio ABI, 202, simulatore nel bootstrap, proprieta (modifica da autorizzare).

## Test attesi (prima del codice, stesso package della classe)

**BankCodeTest / IbanTest** (solo test nuovi): `IT60X0542811101000000123456` -> `bankCode()` `05428`; IBAN minuscolo con spazi -> ABI normalizzato; ABI `03069` -> `03069`, diverso da `05428`;
`new BankCode` con null, vuoto, 4 o 6 caratteri, simboli, minuscole -> `IllegalArgumentException`.

**TransferTest** (solo nuovi): PENDING `markSent()` -> SENT (nuova istanza); SENT `settle()`/`rejectAfterSent()` -> COMPLETED/REJECTED; PENDING `settle()`/`rejectAfterSent()` -> `IllegalStateException`;
SENT `markSent()`/`complete()`/`reject()` -> `IllegalStateException`; COMPLETED o REJECTED `settle()`/`rejectAfterSent()` -> `IllegalStateException`.

**TransferNotificationDtoTest** (un test nuovo): status SENT -> `IllegalArgumentException`.

**ClearingOutcomeDtoTest**: ACCEPTED senza motivo -> ok; REJECTED con motivo nullo o vuoto -> `IllegalArgumentException`; ACCEPTED con motivo -> `IllegalArgumentException`; transferId o decision null -> NPE.

**TransferRoutingServiceImplTest** (le due porte in come lambda o mock; `internal` e `external` sono `CreateInternalTransferInPort` e `CreateExternalTransferInPort`): ABI `05428` -> solo `internal`, risultato restituito cosi com'e; ABI `03069` -> solo `external`; ABI `05428` con `internal` che lancia
`AccountNotFoundException("Destination Account not found")` -> stessa eccezione propagata, `external` mai chiamato; IBAN bloccato `...0099` (ABI `05428`) -> `internal`; IBAN destinatario non valido ->
`IllegalArgumentException`, nessuna porta chiamata; sorgente e destinatario entrambi non validi -> eccezione dell'IBAN sorgente (stesso ordine di oggi); eccezione del flusso esterno (es.
`FraudCheckUnavailableException`) -> propagata invariata; argomenti null nel costruttore -> NPE.

**ExternalTransferSagaServiceImplTest** (Mockito, `TransactionTemplate` su un transaction manager finto): mittente inesistente -> `AccountNotFoundException("Source Account not found")`, antifrode, stanza e repository
mai chiamati; destinatario esterno -> `findByIban(dest)` **mai** chiamato; stesso IBAN o importo con 3 decimali -> `IllegalArgumentException`, nessuna interazione; antifrode non disponibile -> eccezione propagata,
nessun salvataggio, nessuna transazione, nessun `submit`; antifrode REJECTED -> REJECTED salvato con motivi, outbox REJECTED, conto non toccato, nessun `submit`; fondi insufficienti ->
`InsufficientFundException`, rollback, nessun `submit`; caso felice -> in ordine lock, addebito, SENT salvato, commit, **poi** `submit`, risultato SENT, nessun outbox; `submit` che lancia dopo l'addebito ->
riaccredito, REJECTED "Clearing house unavailable", outbox REJECTED, risultato REJECTED; SENT + ACCEPTED -> COMPLETED, un outbox COMPLETED, conto non toccato, restituisce 1; SENT + REJECTED -> in ordine lock del
bonifico, lock del conto, accredito, REJECTED con motivo, outbox; bonifico gia COMPLETED o REJECTED -> nessun effetto, restituisce 0; stesso REJECTED due volte -> un solo accredito, un solo outbox; transferId
sconosciuto -> ignorato, restituisce 0; un esito fallisce -> rollback di quell'esito, il successivo viene applicato.

**JpaExternalTransferRepositoryOutPortImplTest e ExternalTransferMigrationTest** (`@DataJpaTest`, H2): destinatario non tra i conti -> save SENT, salvato e riletto; riga SENT + save REJECTED con motivo -> una riga,
stato e `reason` aggiornati; mittente non tra i conti -> `DataIntegrityViolationException`; status null -> `DataIntegrityViolationException`; id assente -> `findByIdForUpdate` vuoto; null -> NPE.

**TransferControllerTest** (solo nuovi; gli esistenti restano invariati e verdi): servicedto lambda che restituisce SENT -> POST `/transfers` **202** con id, `SENT` e `reasons` vuoto; COMPLETED -> 200 (gia coperto da
`returnsTransferResult`); REJECTED dopo la compensazione, con motivo -> 200 con il motivo.

**SimulatedClearingHouseOutPortImplTest** (bootstrap): nessun invio -> poll vuoto; ACCEPT -> submit poi poll: un ACCEPTED con lo stesso id, secondo poll vuoto; REJECT -> REJECTED con motivo; UNAVAILABLE ->
`ClearingHouseUnavailableException`, poll vuoto; cambio di modo -> vale il nuovo; istruzione null -> NPE.

**TransferRoutingConfigTest** (`ApplicationContextRunner`, porte finte): il bean `BankCode` vale `05428` con la proprieta assente e il valore configurato altrimenti; `transfer.bank-code=ABC` -> il contesto fallisce.
Un test (anche nel bootstrap `@SpringBootTest` H2) verifica `getBeansOfType(CreateTransferInPort.class)` di dimensione 1 e di tipo `TransferRoutingServiceImpl`, e un solo bean per `CreateInternalTransferInPort` e `CreateExternalTransferInPort`.

**ClearingOutcomesSchedulingConfigTest**: proprieta assente o false -> nessuno scheduler; true -> scheduler presente; la porta lancia -> un giro dello scheduler non lancia.

**ExternalTransferEndToEndTest** (H2 `externale2e`, antifrode `@Primary` sempre ALLOWED, scheduler spento, modo del simulatore reimpostato in `@BeforeEach`; destinatario esterno `IT60X0306909606100000012345`): saldo 1000 con
ACCEPT, POST `/transfers` 123.45 verso ABI `03069` -> **202** SENT, saldo 876.55, riga SENT in `external_transfer`, nessuna riga in `transfer`, nessun outbox; poi `processOutcomes()` -> COMPLETED, un outbox
COMPLETED, saldo 876.55; saldo 1000 con REJECT, POST poi `processOutcomes()` -> REJECTED con motivo, **saldo uguale a 1000** (`isEqualByComparingTo`), un outbox REJECTED; secondo `processOutcomes()` -> 0, saldo
invariato; UNAVAILABLE -> POST 200 REJECTED "Clearing house unavailable", saldo 1000; destinatario ABI `05428` inesistente -> **404** "Destination Account not found", nessuna riga, antifrode mai chiamata (come t29, che
resta invariato); tutti i casi: log catturati senza IBAN ne importo.

## Criteri di accettazione
- `.\mvnw.cmd test` verde alla fine di ogni fase: i 392 test esistenti senza modifiche piu i nuovi. Solo `TransferApplicationTests` richiede Postgres.
- Esiste un solo bean per ciascuna porta: `CreateTransferInPort` (il router), `CreateInternalTransferInPort` (`CreateTransferServiceImpl`), `CreateExternalTransferInPort` (la saga). Costruttore e test di
  `CreateTransferServiceImpl` sono invariati; l'unica modifica alla classe e la riga `implements` (con l'import).
- Con ABI `05428`, `POST /transfers` ha lo stesso comportamento di oggi: codici (200, 400, 404, 422, 503), corpi e messaggi. Il 202 compare solo per `SENT`.
- `TransferServiceDto`, `CreateTransferInPort` e `FraudCheckOutPort` sono invariate.
- Nessuna migration esistente modificata. V3 si applica su H2 e su Postgres e `validate` passa.
- Nessun nuovo modulo e nessuna modifica ai pom. Architettura esagonale rispettata (salvo la zona grigia del router, segnalata).
- L'outbox riceve solo COMPLETED e REJECTED. La compensazione e un nuovo accredito. Un esito duplicato non ha effetti.
- Nessun IBAN ne importo nei log.

## Rischi e fuori perimetro
**Rischi**
- **Cambio di `implements` senza il router nella stessa fase:** i 6 contesti `@SpringBootTest` falliscono all'avvio ("No qualifying bean of type CreateTransferInPort"). Le due cose vanno nella stessa fase e nello stesso passo.
- **Due bean `CreateTransferInPort`:** se `CreateTransferServiceImpl` continua a implementare anche `CreateTransferInPort`: tutti i test che iniettano per tipo falliscono con "expected single matching bean".
- **Dipendenza circolare:** il router dipende dalle due porte in, che non dipendono da lui: nessun ciclo.
- **`ExternalTransferSagaServiceImpl` e un bean `@Service`** che richiede le porte `ExternalTransferRepositoryOutPort` e `ClearingHouseOutPort`: va introdotto nella stessa fase delle loro implementazioni.
- **Proprieta `transfer.bank-code` non valida:** l'avvio fallisce (voluto). Un `ApplicationContextRunner` che carica `TransferRoutingConfig` senza la proprieta deve usare il default.
- **Criterio per ABI:** un conto interno con un ABI diverso da `05428` verrebbe trattato come esterno; un IBAN estero (lunghezza diversa da 27) viene rifiutato con 400 come oggi.
- **Rischi invariati dalla v1:** invio fallito = rimborso (sicuro solo con il simulatore); esito consumato e poi fallito: bonifico fermo in SENT; crash tra SENT e `submit`; scheduler spento nella demo; bridge JUL di
  `System.Logger`; simulatore nel bootstrap; due tabelle di bonifici; modo del simulatore mutabile nei test.

**Fuori perimetro:** notification-service, fraud-service, `docker-compose.yaml`, contratti; prenotazione dei fondi, ledger; stati RETURNED, RESERVED, UNKNOWN; recupero, retry e timeout; GET; endpoint dedicato; test di
concorrenza; IBAN non italiani; commit.

## Valore didattico
**Cosa mostra:**
- **Instradamento:** la stessa richiesta segue due flussi diversi in base a una regola di dominio (`BankCode`). Il client non sa nulla della Saga e vede solo 200 o 202.
- **Passi locali:** addebito con commit, effetto esterno fuori transazione, esito in una nuova transazione. Non esiste una transazione distribuita.
- **Stato persistito prima dell'effetto:** `SENT` e salvato prima del `submit`.
- **Compensazione:** un accredito nuovo, non un annullamento, con `REJECTED` e il motivo. C'e anche la compensazione immediata quando l'invio fallisce.
- **Idempotenza:** "applica solo se SENT" sotto lock della riga.
- **Asincronia:** risposta 202, poi esito in polling, notifiche attraverso l'outbox esistente.

**Cosa NON mostra:** prenotazione dei fondi, registro dei movimenti, recupero dopo crash e timeout, stato sconosciuto e riconciliazione, inbox degli esiti, storni dopo il regolamento.

## Fasi
Ordine scelto perche il comportamento visibile cambi solo all'ultima fase. Ogni fase lascia la build verde.

| Fase | Contenuto | Dipende da | Rischio |
|---|---|---|---|
| 1. Dominio, contratti, persistenza | `BankCode`, `Iban.bankCode()`, `SENT`, 3 transizioni; guardia in `TransferNotificationDto`; porte, DTO ed eccezione (nessun service); V3, entita, repository, implementazione JPA e test | - | medio (V3, `validate`) |
| 2. Saga e stanza simulata | **Primo passo (deciso dall'utente dopo il review della Fase 1): ridefinire `toString()` di `ClearingInstructionDto` senza IBAN ne importo, come `PendingNotificationDto`, con un test.** Poi `ExternalTransferSagaServiceImpl` (`@Service`, non ancora raggiungibile da REST) con i test Mockito; simulatore, `ClearingConfig`, scheduler, proprieta del clearing e test. Il contesto si avvia perche ogni porta ha la sua implementazione | 1 | alto (logica della saga, lock, transazioni) |
| 3. Instradamento e REST | **in un solo passo**: `CreateInternalTransferInPort`, `implements` di `CreateTransferServiceImpl` cambiato, `TransferRoutingServiceImpl` (`@Service`), `TransferRoutingConfig` (solo `BankCode`), `transfer.bank-code`; poi `TransferController` con 202; `TransferRoutingServiceImplTest`, `TransferRoutingConfigTest`, nuovi test del controller, `ExternalTransferEndToEndTest`; `transfer-service/CLAUDE.md` (da autorizzare). Da verificare subito dopo il passo: i 6 contesti `@SpringBootTest` | 1, 2 | basso-medio (un solo bean per porta) |

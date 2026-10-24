# fraud-service

Valuta se un bonifico è ammissibile dal punto di vista antifrode. Non conosce
conti, saldi né il database del Transfer. Package base: `com.bank.fraud`.
Eredita tutte le regole del `CLAUDE.md` nella radice (struttura standard,
architettura esagonale, convenzioni di nome, regole di lavoro).

## Contratto
La fonte di verità è `docs/contracts/fraud-check.md`: endpoint, formato di
richiesta e risposta, regole di valutazione, errori, esempi di scenari.
- Se codice e contratto divergono, non scegliere tu: segnala la divergenza.
  Si corregge il codice oppure si apre una modifica esplicita al contratto.
- I casi 1-16 della sezione 11 del contratto sono i test del Fraud service:
  ogni riga diventa almeno un test.
- I testi dei motivi (`reasons`) sono quelli del contratto, alla lettera.

## Moduli (package base `com.bank.fraud`)
| Modulo | Contenuto |
|---|---|
| `fraud-domain` | `Iban`, `Money`, `TransferToAssess`, `FraudAssessment`, `FraudPolicy` (la regola di business, funzione pura), enum, eccezioni. Solo JUnit e AssertJ come dipendenze |
| `fraud-application` | `CheckTransferInPort`, `BlockedIbanOutPort`, service, DTO e command |
| `fraud-rest-adapter` | `POST /fraud-check`, DTO HTTP, mapper, gestione degli errori secondo la sezione 7 del contratto |
| `fraud-memory-adapter` | Implementa `BlockedIbanOutPort` con una lista in memoria letta dalla configurazione. Nessun database |
| `fraud-micrometer-adapter` | Implementa `MetricsOutPort` con contatori Micrometer (`MicrometerMetricsOutPortImpl`). Package `com.bank.fraud.micrometer.port.out.micrometer.impl` |
| `fraud-bootstrap` | `FraudApplication`, configurazione dei bean, `application.properties`, test di integrazione |

Le classi del dominio sono **proprie del Fraud service**: `Iban` e `Money`
si riscrivono qui, non si importano dal Transfer.

## Struttura dei package degli adapter
Segue il modello descritto in `transfer-service/CLAUDE.md` (adapter rest e
adapter in uscita), con il prefisso `fraud-` nei moduli e `com.bank.fraud`
nei package.

## Comandi (dalla cartella `fraud-service/`)
- Test di tutto: `.\mvnw.cmd test`
- Il servizio gira sulla porta **8081** (8080 è del Transfer).
- Non richiede il database: i test si lanciano senza Docker.

## Dati di prova
- Soglia: 10.000 EUR. Un importo uguale alla soglia è ammesso.
- IBAN bloccato di prova: `IT60X0542811101000000000099`.
- Soglia e IBAN bloccati sono parametri di configurazione in
  `fraud-bootstrap` (`application.properties`), con questi valori di default.
  Il dominio non li legge da nessuna parte: li riceve dal chiamante.

## Particolarità del servizio
- **Nessuna persistenza**: niente JPA, niente Flyway. Se servirà una tabella
  degli IBAN bloccati, sarà una nuova issue con un secondo adapter sulla
  stessa porta.
- **Si valutano tutte le regole** e i motivi si sommano, nell'ordine:
  soglia, mittente, destinatario.
- Il `transferId` serve solo per tracciabilità: non influisce mai sulla
  decisione e viene restituito identico nella risposta.
- **Nei log non finiscono mai IBAN né importi**: solo `transferId`, esito e
  motivi. Vale anche per i messaggi di errore.
- Un rifiuto antifrode (`REJECTED`) viaggia sempre con codice `200`, mai con
  un codice di errore.
- Valuta supportata: solo `EUR`. Le altre danno `400 UNSUPPORTED_CURRENCY`.

## Metriche
Esporta su `/actuator/prometheus` (tag comune `application=fraud`). Porta out
`MetricsOutPort.assessmentCompleted(FraudStatusEnum, List<FraudReasonEnum>)`, chiamata
da `CheckTransferServiceImpl` subito dopo `policy.assess(...)`.
- `bank.fraud.assessments{status}` (`bank_fraud_assessments_total`): una per valutazione,
  `status` = `ALLOWED` o `REJECTED` (nomi del dominio, non `APPROVED`).
- `bank.fraud.reasons{reason}` (`bank_fraud_reasons_total`): una per ogni motivo,
  `reason` = `AMOUNT_OVER_THRESHOLD`, `SOURCE_IBAN_LOCKED`, `DESTINATION_IBAN_LOCKED`.
  Un rifiuto con due motivi incrementa due serie e una sola valutazione.
- Le 5 serie sono registrate a zero all'avvio. I tag sono sempre `name()` di un enum,
  **mai** il testo del motivo (contiene la soglia), un IBAN, un importo o il `transferId`.
- Le richieste non valide (400) e gli errori della porta degli IBAN bloccati **non si contano**:
  non c'e stata una valutazione (le copre `http_server_requests`).
- `CheckTransferServiceImpl` ha un solo costruttore `(BlockedIbanOutPort, BigDecimal, MetricsOutPort)`:
  nessuna implementazione che non registra niente. Senza `MeterRegistry` l'avvio fallisce, senza
  ripiego silenzioso.
- Se la porta delle metriche lanciasse, la valutazione gia fatta diventerebbe un 500.
- I contatori sono per istanza e si azzerano al riavvio: in dashboard solo `rate()` e `increase()`.

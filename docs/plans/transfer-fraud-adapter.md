# transfer: adapter in uscita verso il fraud-service

> BOZZA da rivedere prima di lanciare la pipeline. Le sezioni "Domande aperte" e
> "Da verificare" contengono i punti che il codice esistente non permette di
> decidere da soli.

## Obiettivo
Sostituire `StubFraudCheck` con un adapter HTTP in `transfer-fraud-adapter` che
implementa `FraudCheckOutPort` chiamando `POST /fraud-check` del fraud-service
(porta 8081), secondo il contratto `docs/contracts/fraud-check.md`.

## Decisioni già prese (dal contratto)
- Chiamata sincrona, prima di qualsiasi scrittura sui conti (sezione 8).
- Timeout: 1 secondo per la connessione, 3 secondi per la risposta.
- **Fail closed**: ogni esito diverso da una risposta `200` valida significa
  "servizio non disponibile": codice diverso da `200` (compresi 400, 415, 500, 503),
  timeout, connessione rifiutata, corpo non leggibile, `status` con valore non
  previsto, `REJECTED` senza motivi. Il Transfer non addebita, non accredita e non
  salva niente; il chiamante del Transfer riceve un errore, **non** un rifiuto
  antifrode.
- Nessun nuovo tentativo automatico nella prima versione.
- I motivi (`reasons`) non vengono interpretati: si registrano o si mostrano.
- Si ignorano i campi sconosciuti nelle risposte (sezione 10).
- Nessuna autenticazione in questa versione.
- IBAN e importi **non vanno mai nei log** dell'adapter né nei messaggi delle
  eccezioni. Si può registrare `transferId`, esito e motivi.
- Il Transfer non importa classi del Fraud: l'adapter ha i propri DTO di richiesta e
  risposta, con i nomi dei campi del contratto.
- Struttura e package: come gli altri adapter (`transfer-service/CLAUDE.md`); oggi il
  modulo ha solo `port.out.fraud.impl.StubFraudCheck`.
- L'URL base del fraud-service è una proprietà di configurazione, mai scritta nel codice.

## Domande aperte (le risposte cambiano il perimetro)
1. **Manca il `transferId` nella porta.** Il contratto lo richiede (stringa non vuota,
   restituita identica), ma `FraudCheckOutPort.checkFraud(sourceIban, destIban, amount,
   currency)` non lo riceve. Va aggiunto un parametro? Questo tocca
   `transfer-application` (porta, `CreateTransferServiceImpl`, i loro test) e lo stub.
   Occorre verificare se l'id del bonifico esiste già al momento della chiamata
   (`CreateTransferServiceImpl` crea il `Transfer` prima di chiamare l'antifrode).
2. **Motivi: lista o stringa.** Il contratto restituisce `reasons` (lista di stringhe);
   `FraudCheckResultDto` ha un solo `String reason`. Si uniscono i motivi in un'unica
   stringa (con quale separatore?) o si cambia il DTO in una lista? Cambiare il DTO
   tocca `TransferResultDto`, il web adapter del Transfer e la loro API pubblica.
3. **Fail closed: quale errore.** Oggi `CreateTransferServiceImpl` non gestisce il
   fallimento dell'antifrode e il Transfer non ha un'eccezione dedicata. Serve una
   nuova eccezione (dove? application) e la sua mappatura nel web adapter del Transfer
   (quale codice HTTP: 503? 502?). Questa parte è nel perimetro di questa issue o
   di una successiva?
4. **Client HTTP e dipendenze.** Il pom di `transfer-fraud-adapter` ha solo
   `transfer-application` (+ JUnit e AssertJ in test). Per chiamare HTTP serve un
   client (per esempio `RestClient` di Spring, che richiede `spring-web`, oppure il
   `HttpClient` del JDK senza dipendenze nuove). Quale? Aggiungere dipendenze ai pom
   richiede il tuo ok esplicito (CLAUDE.md radice).
5. **Test con un "Fraud service finto".** Il contratto (sezione 11, casi 16-21) li
   chiede. Con quale strumento: `MockRestServiceServer` (richiede il client Spring),
   un server HTTP locale del JDK nei test, o WireMock (dipendenza nuova)?
6. **Stub: rimuoverlo o tenerlo.** Si elimina `StubFraudCheck` e le proprietà
   `fraud.stub.*`, oppure resta selezionabile (profilo o proprietà) per i test e per
   lavorare senza il Fraud acceso? Con il Fraud reale, le regole e gli IBAN bloccati
   di prova cambiano (nel Fraud: soglia 10000 e IBAN bloccato
   `IT60X0542811101000000000099`; nello stub: altri IBAN).
7. **Valuta.** Il Fraud supporta solo `EUR`; per altre valute risponde
   `400 UNSUPPORTED_CURRENCY`, che per il Transfer significa "servizio non
   disponibile". Il Transfer può già ricevere bonifici in valute diverse? Se sì, il
   comportamento (bonifico fermo) è accettato?
8. **Test di integrazione del Transfer.** I test esistenti di `transfer-bootstrap`
   dipendono dallo stub. Quali devono cambiare e quali si adattano? Non si modificano
   per farli passare: vanno elencati nel piano.

## Da verificare nel codice esistente (segnala come ambiguità se non torna)
- La forma reale di `FraudCheckOutPort`, `FraudCheckResultDto`, `TransferResultDto` e
  l'uso in `CreateTransferServiceImpl` (che cosa succede oggi con `reason` vuoto).
- Come `FraudConfig` costruisce il bean e dove vanno la proprietà dell'URL e i timeout.
- Se i test di `transfer-bootstrap` richiedono Docker (PostgreSQL) e quali usano lo stub.
- Che cosa mappa oggi il web adapter del Transfer per gli errori e come si inserirebbe
  un errore "antifrode non disponibile".

## Riferimenti
Contratto `docs/contracts/fraud-check.md`: sezioni 3, 4, 5, 7, 8, 9, 10 e 11 (casi
16-21 lato Transfer). `transfer-service/CLAUDE.md`. Piani `docs/plans/fraud-04-web-adapter.md`
e `docs/plans/fraud-06-bootstrap-config.md` per il lato Fraud.

## Test richiesti
- Con un Fraud service finto: `ALLOWED`, `REJECTED` con più motivi, e il corpo
  inviato (campi, `amount` come numero JSON con la scala corretta, `transferId`).
- Ogni esito che il contratto classifica come "non disponibile": `500`, `400`,
  `415`, timeout di connessione e di lettura, connessione rifiutata, corpo non
  JSON, `status` sconosciuto, `REJECTED` senza motivi. Nessuno diventa `ALLOWED`.
- Campi sconosciuti nella risposta ignorati.
- Un test verifica che nessun messaggio di eccezione né output contenga IBAN o importi.
- Test del bootstrap del Transfer aggiornati per il nuovo adapter (senza Docker per
  la parte HTTP, se possibile).

## Fuori perimetro
Modifiche al fraud-service e al contratto, nuovi tentativi automatici, circuit breaker,
autenticazione, audit/salvataggio delle valutazioni, `/v2/fraud-check`.

## Decisioni dell'utente sulle domande aperte (prevalgono sul resto)
1. **Sì:** si aggiunge il `transferId` alla porta `FraudCheckOutPort` (e si adattano `CreateTransferServiceImpl`, i loro test, lo stub).
2. **Lista:** il risultato porta i motivi come lista (non più una singola stringa). Si adattano `FraudCheckResultDto` e tutto ciò che lo usa (anche `TransferResultDto` / API pubblica del Transfer, da pianificare con attenzione).
3. **In perimetro:** eccezione dedicata per "antifrode non disponibile" (fail closed) e sua mappatura nel web adapter del Transfer. Il codice HTTP non è stato deciso: il planner lo propone.
4. **Dipendenze: autorizzato aggiungerle** ai pom dove servono (il planner indica quali e dove: client HTTP e dipendenze di test), con il minimo necessario.
5. **Fraud finto nei test:** primo strumento proposto, `MockRestServiceServer` (coerente con un client Spring).
6. **Stub selezionabile dal profilo `inmemory`:** l'adapter HTTP è il default; `StubFraudCheck` resta attivo solo con il profilo `inmemory` (come le classi in memoria del Transfer).
7. **Solo EUR:** il comportamento "valuta diversa = bonifico fermo" è accettato.
8. **Test di integrazione:** si procede adattandoli (elenco nel piano; non si modificano solo per farli passare).

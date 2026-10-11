# fraud: adapter web del fraud-service

## Obiettivo
Implementare `fraud-web-adapter`: `POST /fraud-check`, DTO HTTP, mapper e
traduzione degli errori secondo la sezione 7 del contratto.

## Decisioni già prese
- Struttura e livelli dei package: replica `transfer-web-adapter`, compreso il
  layer `servicedto` (vedi transfer-service/CLAUDE.md). Prima di pianificare,
  leggi il codice di quel modulo.
- Endpoint `POST /fraud-check`, `application/json`, risposta `200` per entrambi
  gli esiti (`ALLOWED` e `REJECTED`).
- Il controller chiama solo `CheckTransferInPort`, attraverso il livello
  `servicedto`. Non importa classi di dominio.
- DTO di richiesta e risposta propri dell'adapter, con i nomi dei campi del
  contratto. In risposta `reasons` è omesso quando l'esito è `ALLOWED`.
- Bean Validation SOLO per presenza dei campi obbligatori e tipi. Le regole di
  formato (IBAN, importo, IBAN uguali) restano nel dominio e non vengono
  duplicate nell'adapter.
- Gestione degli errori in un solo `@RestControllerAdvice`, con i codici della
  sezione 7: `MALFORMED_REQUEST`, `INVALID_REQUEST` (con `details`),
  `UNSUPPORTED_CURRENCY`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR`.
- I messaggi di errore sono TESTI FISSI: mai `exception.getMessage()`, perché
  potrebbe contenere un IBAN o un importo. Niente stack trace nel corpo.
- Nessun log di IBAN o importi, neanche in caso di errore.
- Un esito `REJECTED` non passa mai dal gestore degli errori.
- La porta 8081 si configura in `fraud-bootstrap`, non qui.

## Da verificare nel codice esistente (segnala come ambiguità se non torna)
Quali eccezioni lancia il dominio per un valore non valido. Il gestore non deve
catturare `IllegalArgumentException` in generale: deve mappare solo le eccezioni
di validazione del Fraud service. Se il dominio usa `IllegalArgumentException`,
dillo nel piano prima di scegliere come procedere.

## Riferimenti
Contratto: sezioni 3, 4, 5, 7 e gli scenari di errore della sezione 11.

## Test richiesti
- Il test del controller usa `MockMvc` in modalità standalone con un fake di
  `CheckTransferInPort` (niente contesto Spring completo).
- Ogni errore della sezione 7 ha un test: JSON non valido, campo mancante, IBAN
  non valido, importo zero, importo con troppi decimali, IBAN uguali, valuta non
  supportata, `Content-Type` sbagliato, errore interno.
- Un test per `ALLOWED` (senza `reasons`) e uno per `REJECTED` con più motivi.
- Un test verifica che nessun corpo di errore contenga l'IBAN o l'importo inviati.

## Fuori perimetro
Adapter in uscita, bootstrap, configurazione dei bean, porta, test di integrazione.
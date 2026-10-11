# Contratto: controllo antifrode

## 1. Scopo
Questo contratto definisce come il transfer-service chiede al fraud-service se un bonifico è consentito. Il transfer invia
i dati essenziali del bonifico (IBAN del mittente, IBAN del destinatario, importo e valuta), il fraud-service risponde con "ALLOWED"
o "REJECTED", nel secondo caso fornisce anche una, o più, motivazioni.

## 2. Parti coinvolte
- Fornitore: fraud-service. Implementa il contratto ed espone l'endpoint. Valuta se un bonifico è ammissibile dal punto di vista antifrode,
senza verificare saldi, esistenza dei conti o qualsiasi altro controllo in carico al transfer.
- Consumatore: transfer-service. Chiama il fraud-service durante la creazione di un bonifico, tramite l'adapter in uscita che implementa
FraudCheckOutPort

Il contratto è l'unico punto di contatto tra i due servizi: nessuno dei due importa classi dell'altro, nè legge i suoi dati. Se in futuro
altri servizi avessero bisogno del controllo antifrode, useranno lo stesso contratto senza modifiche al fraud-service.

## 3. Endpoint
- **Metodo e percorso**: `POST /fraud-check`
- **Porta in sviluppo**: 8081
- **Formato**: richiesta e risposta in `application/json`

I dati del bonifico viaggiano nel corpo della richiesta e non nell'URL, perché
contengono IBAN e importi che non devono comparire nei log degli intermediari.

## 4. Richiesta
Tutti i campi sono obbligatori

| Campo | Tipo JSON | Formato e vincoli |
|---|---|---|
| `transferId` | stringa | identificativo del bonifico nel Transfer: stringa non vuota, di cui il Fraud non valida il formato (il Transfer usa un UUID). Serve solo per tracciabilità e non influisce sulla decisione |
| `sourceIban` | stringa | IBAN del conto di origine: 27 caratteri, formato `[A-Z]{2}[0-9]{2}[A-Z0-9]+`. Spazi e minuscole vengono normalizzati (spazi rimossi, maiuscolo) |
| `destinationIban` | stringa | IBAN del conto di destinazione, stesso formato |
| `amount` | numero | maggiore di zero, separatore decimale `.`, massimo 2 decimali |
| `currency` | stringa | codice ISO 4217 di 3 lettere. Il confronto è esatto: è supportata solo `EUR` (vedi sezione 6) |

esempio:
{
"transferId": "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93",
"sourceIban": "IT60X0542811101000000000001",
"destinationIban": "IT60X0542811101000000000002",
"amount": 100.25,
"currency": "EUR"
}

## 5. Risposta di successo
Codice HTTP `200` per entrambi gli esiti: un rifiuto antifrode è una risposta
valida, non un errore. Tutti i campi sono obbligatori tranne reasons

| Campo | Tipo JSON | Formato e vincoli |
|---|---|---|
| `transferId` | stringa | identificativo ricevuto nella richiesta, restituito identico. Serve solo per tracciabilità e non influisce sulla decisione |
| `status` | stringa | risultato della validazione antifrode: `ALLOWED` o `REJECTED`, in maiuscolo |
| `reasons` | array di stringhe | presente solo con status REJECTED, elenca una o più motivazioni che hanno portato al blocco della transazione |
esempio:

nel caso di allowed
{
"transferId": "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93",
"status": "ALLOWED"
}

nel caso di rejected
{
"transferId": "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93",
"status": "REJECTED",
"reasons" : ["Source IBAN is locked", "Destination IBAN is locked"]
}

## 6. Regole di valutazione
Il servizio decide in base a più parametri:
- sopra i 10000 EUR la transazione non è valida (un importo uguale alla soglia è ammesso), come reasons "Amount must not exceed 10000 EUR". Il testo è fisso e non riporta la soglia configurata;
- IBAN bloccati, sia per il mittente che per il destinatario "X IBAN is locked", dove X è "Source" o "Destination" a seconda dei casi;
- se scattano più validazioni, vengono restituite tutte, nell'ordine: importo, mittente, destinatario;
- la valuta supportata è solo `EUR`: una valuta diversa non viene valutata e dà l'errore `400 UNSUPPORTED_CURRENCY` (sezione 7).

## 7. Errori
Ogni risposta diversa da `200` è un errore del contratto, non un esito della
valutazione: il bonifico non è né `ALLOWED` né `REJECTED`.

| Codice HTTP | Quando si verifica | `code` nel corpo |
|---|---|---|
| `400` | Corpo non leggibile come JSON | `MALFORMED_REQUEST` |
| `400` | Campo obbligatorio mancante o nullo | `INVALID_REQUEST` |
| `400` | IBAN non valido (vuoto o formato errato) | `INVALID_REQUEST` |
| `400` | `amount` minore o uguale a zero, oppure con più di 2 decimali | `INVALID_REQUEST` |
| `400` | `currency` diversa da `EUR` (valuta non supportata, vedi sezione 6) | `UNSUPPORTED_CURRENCY` |
| `415` | `Content-Type` diverso da `application/json` | `UNSUPPORTED_MEDIA_TYPE` |
| `500` | Errore interno del Fraud service | `INTERNAL_ERROR` |
| `503` | Servizio temporaneamente non disponibile | `SERVICE_UNAVAILABLE` |

Corpo di ogni errore (JSON):

| Campo | Tipo JSON | Descrizione |
|---|---|---|
| `code` | stringa | codice stabile, tra quelli della tabella |
| `message` | stringa | descrizione per le persone (log, assistenza); il chiamante non deve interpretarla |
| `details` | array di stringhe | facoltativo; per `INVALID_REQUEST` elenca i campi errati, ad esempio `"amount: must be greater than 0"` |

Esempio, richiesta con importo non valido:
{
"code": "INVALID_REQUEST",
"message": "Request validation failed",
"details": ["amount: must be greater than 0"]
}

Esempio, valuta non supportata:
{
"code": "UNSUPPORTED_CURRENCY",
"message": "Only EUR is supported"
}

Vincoli:
- I messaggi e i dettagli **non contengono mai gli IBAN né l'importo** ricevuti, per non
  farli comparire nei log degli intermediari.
- Il corpo di un errore non contiene stack trace né dettagli interni.
- Un rifiuto antifrode (`REJECTED`) non usa mai questi codici: viaggia sempre in `200`.

## 8. Comportamento del chiamante
Il transfer chiama il fraud-service in modo sincrono, prima di qualsiasi scrittura
sui conti.

- **Timeout**: 1 secondo per stabilire la connessione, 3 secondi per ricevere la
  risposta.
- **Qualunque esito diverso da una risposta `200` valida** significa "servizio non
  disponibile": risposta con codice diverso da `200` (qualsiasi errore della
  sezione 7), timeout, connessione rifiutata, corpo non leggibile, `status` con un
  valore non previsto, oppure `REJECTED` senza motivi.
- **Servizio non disponibile = bonifico fermo** (*fail closed*): il Transfer non
  addebita, non accredita e non salva niente. Il chiamante del Transfer riceve un
  errore, non un rifiuto antifrode.
- **Nessun nuovo tentativo automatico** nella prima versione. Poiché il contratto è
  idempotente (sezione 9), un tentativo ripetuto è sicuro e potrà essere
  introdotto in seguito.
- **I motivi (`reasons`) non vengono interpretati**: il Transfer li registra o li
  mostra, non li usa per decidere.
- **Il chiamante ignora i campi sconosciuti** presenti nelle risposte (vedi sezione 10).

## 9. Garanzie e vincoli
- **Idempotenza**: la stessa richiesta (stesso `transferId` e stessi dati) produce
  sempre la stessa risposta, a parità di regole e di lista degli IBAN bloccati. Il
  servizio non ha effetti collaterali visibili al chiamante.
- **Nessuno stato condiviso**: il servizio non chiama altri servizi e non legge né
  scrive dati del Transfer.
- **Salvataggio**: in questa versione il servizio non conserva le valutazioni.
  Una versione futura potrà registrarle per audit, senza modificare il contratto.
- **Eco dell'identificativo**: il `transferId` ricevuto viene restituito identico
  nella risposta di successo.
- **Dati nei log**: IBAN e importi **non vanno mai nei log** del Fraud service né
  del suo adapter. Si può registrare il `transferId`, l'esito e i motivi.
- **Nessuna autenticazione** in questa versione: il servizio è raggiungibile solo
  nella rete di sviluppo.

## 10. Compatibilità e versione
**Versione attuale**: 1, percorso `/fraud-check`.

Modifiche **compatibili**, che si possono fare senza avvisare i consumatori:
- aggiungere campi facoltativi alla risposta (i consumatori ignorano i campi
  sconosciuti)
- aggiungere nuove valute supportate
- aggiungere nuove regole di valutazione con un nuovo motivo
- aggiungere nuovi codici di errore (il Transfer tratta allo stesso modo ogni
  risposta diversa da `200`)

Modifiche **incompatibili**, che richiedono una nuova versione del contratto:
- rimuovere o rinominare un campo, o cambiarne il tipo
- aggiungere un campo **obbligatorio** alla richiesta
- aggiungere un nuovo valore di `status` (i consumatori lo leggerebbero come
  servizio non disponibile)
- cambiare il significato di una regola (per esempio la soglia)

Una versione incompatibile viene pubblicata con un nuovo percorso
(`/v2/fraud-check`), mantenendo per un periodo concordato anche la precedente.

Nota: `amount` è un numero JSON. Un futuro consumatore che legge i numeri come
virgola mobile (per esempio in JavaScript) deve gestirli come decimali esatti,
oppure si passa a una nuova versione con l'importo come stringa.

## 11. Esempi di scenari
Dati di prova: IBAN bloccato `IT60X0542811101000000000099`; IBAN ordinari
`IT60X0542811101000000000001` e `IT60X0542811101000000000002`. In tutti i casi il
`transferId` è valorizzato con un UUID valido tipo '3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93'.

**Dal lato del Fraud service**

| # | Scenario | Richiesta (campi rilevanti) | Risposta |
|---|---|---|---|
| 1 | Caso felice | amount `1000.25`, EUR, IBAN ordinari | `200`, `ALLOWED` |
| 2 | Importo uguale alla soglia | amount `10000.00` | `200`, `ALLOWED` |
| 3 | Importo oltre la soglia | amount `10000.01` | `200`, `REJECTED`, `["Amount must not exceed 10000 EUR"]` |
| 4 | Mittente bloccato | `sourceIban` bloccato | `200`, `REJECTED`, `["Source IBAN is locked"]` |
| 5 | Destinatario bloccato | `destinationIban` bloccato | `200`, `REJECTED`, `["Destination IBAN is locked"]` |
| 6 | Entrambi bloccati | entrambi bloccati | `200`, `REJECTED`, mittente poi destinatario |
| 7 | Soglia e mittente bloccato | amount `20000.00`, mittente bloccato | `200`, `REJECTED`, importo poi mittente |
| 8 | Importo zero | amount `0` | `400`, `INVALID_REQUEST` |
| 9 | Troppi decimali | amount `10.001` | `400`, `INVALID_REQUEST` |
| 10 | Campo mancante | senza `currency` | `400`, `INVALID_REQUEST` |
| 11 | IBAN vuoto | `sourceIban` vuoto | `400`, `INVALID_REQUEST` |
| 12 | Valuta non supportata | currency `USD` | `400`, `UNSUPPORTED_CURRENCY` |
| 13 | Corpo non JSON | testo qualunque | `400`, `MALFORMED_REQUEST` |
| 14 | Tipo di contenuto errato | `Content-Type: text/plain` | `415`, `UNSUPPORTED_MEDIA_TYPE` |
| 15 | Idempotenza | stessa richiesta del caso 3, due volte | stessa risposta entrambe le volte |

**Dal lato del Transfer** (con un Fraud service finto)

| # | Il Fraud service... | Il Transfer... |
|---|---|---|
| 16 | risponde `ALLOWED` | prosegue, salva i conti e il bonifico `COMPLETED` |
| 17 | risponde `REJECTED` con motivi | salva il bonifico `REJECTED`, non tocca i conti, restituisce i motivi |
| 18 | risponde `500` | blocca il bonifico, nessun salvataggio, errore al chiamante |
| 19 | non risponde entro il timeout | come il caso 18 |
| 20 | risponde `200` con `status` sconosciuto | come il caso 18 |
| 21 | risponde `REJECTED` senza motivi | come il caso 18 |
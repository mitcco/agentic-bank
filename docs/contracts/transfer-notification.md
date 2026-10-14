# Contratto: notifica di fine bonifico

## 1. Scopo
Questo contratto definisce il messaggio Kafka con cui il transfer-service comunica al
notification-service l'esito di un bonifico concluso. Il notification-service lo
registra nel log e lo salva in un database.

## 2. Parti coinvolte
- Produttore: transfer-service. Pubblica un messaggio al termine di ogni bonifico
  salvato come `COMPLETED` o `REJECTED`, dopo il commit della transazione.
- Consumatore: notification-service.
- Nessuno dei due importa classi dell'altro né legge i suoi dati: il messaggio è
  l'unico punto di contatto.

## 3. Canale
| Voce | Valore |
|---|---|
| Topic | `transfer.notifications` (valore predefinito, configurabile con la variabile `NOTIFICATION_TOPIC`; deve essere lo stesso per Transfer e Notification) |
| Chiave del messaggio | `transferId` (stringa) |
| Formato del valore | JSON, UTF-8 |
| Consumer group | `notification` |
| Broker in sviluppo | `localhost:9092` |

## 4. Messaggio
Tutti i campi sono obbligatori tranne `reasons`.

| Campo | Tipo JSON | Formato e vincoli |
|---|---|---|
| `transferId` | stringa | identificativo del bonifico nel Transfer (UUID), non vuota |
| `sourceIban` | stringa | IBAN del mittente, già normalizzato (maiuscolo, senza spazi) |
| `destinationIban` | stringa | IBAN del destinatario, stesso formato |
| `amount` | numero | maggiore di zero, separatore decimale `.`, massimo 2 decimali |
| `currency` | stringa | codice ISO 4217 di 3 lettere (oggi solo `EUR`) |
| `status` | stringa | `COMPLETED` o `REJECTED`, in maiuscolo |
| `reasons` | array di stringhe | presente solo con `REJECTED`, una o più voci non vuote; assente con `COMPLETED` |
| `occurredAt` | stringa | istante della conclusione del bonifico, ISO-8601 UTC (es. `2026-10-13T09:30:00Z`) |

Esempio `COMPLETED`:
{
"transferId": "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93",
"sourceIban": "IT60X0542811101000000000001",
"destinationIban": "IT60X0542811101000000000002",
"amount": 100.25,
"currency": "EUR",
"status": "COMPLETED",
"occurredAt": "2026-10-13T09:30:00Z"
}

Esempio `REJECTED`:
{
"transferId": "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93",
"sourceIban": "IT60X0542811101000000000099",
"destinationIban": "IT60X0542811101000000000002",
"amount": 20000.00,
"currency": "EUR",
"status": "REJECTED",
"reasons": ["Amount must not exceed 10000 EUR", "Source IBAN is locked"],
"occurredAt": "2026-10-13T09:31:00Z"
}

## 5. Quando il Transfer pubblica
- Un messaggio per ogni bonifico concluso, `COMPLETED` o `REJECTED`.
- Nessun messaggio se il bonifico non viene concluso: antifrode non disponibile (il
  Transfer non salva niente), conto inesistente, richiesta non valida.
- **La notifica non può andare persa se Kafka non è raggiungibile.** Il Transfer usa una
  *transactional outbox*: nella stessa transazione del database che salva il bonifico
  scrive anche la notifica in una tabella `outbox` del proprio database. Bonifico e
  notifica sono quindi salvati insieme o non lo è nessuno dei due.
- Un processo del Transfer legge le notifiche non ancora inviate e le pubblica sul topic.
  La riga viene **eliminata** dalla tabella solo dopo la conferma del broker: la tabella
  contiene soltanto le notifiche ancora da inviare. Se Kafka non è raggiungibile la riga
  resta e viene ritentata finché la pubblicazione riesce, senza far fallire né rallentare
  il bonifico.
- Il produttore garantisce quindi la consegna **at-least-once**: dopo un errore tra
  conferma del broker ed eliminazione della riga la stessa notifica può essere
  pubblicata più volte. Il consumatore è idempotente (sezione 6).
- Le notifiche dello stesso bonifico sono pubblicate con la stessa chiave `transferId`.
  L'ordine tra bonifici diversi non è garantito.
- La tabella `outbox` appartiene al Transfer: il Notification non la legge.

## 6. Comportamento del consumatore
- **Consegna at-least-once**: l'offset viene confermato solo dopo che il messaggio è
  stato salvato e scritto nel log. Lo stesso messaggio può arrivare più di una volta.
- **Idempotenza**: il salvataggio usa `transferId` come identificativo del documento,
  quindi un duplicato sostituisce lo stesso documento e non ne crea un secondo. Nel log la
  stessa riga può comparire due volte. I duplicati possono nascere sia dalla riconsegna di
  Kafka sia dalla ripubblicazione dell'outbox del Transfer.
- **Errore temporaneo** (database non raggiungibile): nuovi tentativi a intervalli fissi di
  1 secondo, massimo 3 tentativi in totale. Dopo l'ultimo il messaggio viene scartato e
  l'errore registrato nel log con il solo `transferId`. Nessuna dead-letter nella prima
  versione.
- **Messaggio non valido** (JSON illeggibile, campo obbligatorio mancante o nullo,
  `status` sconosciuto, `REJECTED` senza motivi, `amount` non positivo): scartato subito,
  senza tentativi, con un errore nel log senza IBAN né importo. Il consumatore ignora i
  campi sconosciuti.
- **Ordine**: garantito per chiave (stesso `transferId`), non tra bonifici diversi.

## 7. Dati nei log
- Nel log del Notification **non finiscono mai gli IBAN**, né nei messaggi di errore.
- Si registrano `transferId`, importo, valuta, esito, motivi e `occurredAt`.
- Gli IBAN arrivano e vengono salvati nel database.

## 8. Compatibilità e versione
Versione attuale: 1, topic predefinito `transfer.notifications`.

Compatibili: aggiungere campi facoltativi, aggiungere motivi.
Incompatibili (nuovo topic `transfer.notifications.v2`): rimuovere o rinominare un campo,
cambiarne il tipo, aggiungere un campo obbligatorio, aggiungere un valore di `status`
(il consumatore lo scarterebbe come non valido).

## 9. Esempi di scenari
| # | Scenario | Esito atteso nel Notification |
|---|---|---|
| 1 | `COMPLETED` valido | documento salvato, riga di log senza IBAN |
| 2 | `REJECTED` con due motivi | documento con entrambi i motivi, log con i motivi |
| 3 | stesso messaggio due volte | un solo documento |
| 4 | JSON non valido | scartato, errore nel log, nessun documento |
| 5 | `status` = `PENDING` | scartato, errore nel log |
| 6 | `REJECTED` senza `reasons` | scartato, errore nel log |
| 7 | `amount` = 0 | scartato, errore nel log |
| 8 | campo sconosciuto in più | ignorato, messaggio elaborato |
| 9 | database non raggiungibile | 3 tentativi, poi scarto con errore nel log |
| 10 | log: nessuna riga contiene IBAN | verificato su tutti i casi precedenti |

**Dal lato del Transfer** (verificati nel Transfer, con un Kafka finto)

| # | Scenario | Esito atteso |
|---|---|---|
| 11 | bonifico `COMPLETED` o `REJECTED` salvato | nella stessa transazione c'è una riga in `outbox` |
| 12 | bonifico non concluso (antifrode non disponibile, conto inesistente) | nessuna riga `outbox` |
| 13 | Kafka non raggiungibile | il bonifico va a buon fine, la riga resta in `outbox` |
| 14 | Kafka di nuovo raggiungibile | la riga è pubblicata e poi eliminata |
| 15 | errore dopo la conferma del broker, prima dell'eliminazione | la riga è ripubblicata: duplicato, gestito dal Notification (scenario 3) |
| 16 | salvataggio del bonifico fallito | nessuna riga `outbox` (stessa transazione) |

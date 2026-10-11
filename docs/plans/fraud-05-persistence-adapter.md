# fraud-05: adapter in uscita del fraud-service

## Obiettivo
Implementare `fraud-persistence-adapter`: l'implementazione di `BlockedIbanOutPort`
con una lista di IBAN bloccati tenuta in memoria.

## Decisioni già prese
- Struttura dei package: replica `transfer-persistence-adapter`, nella parte
  che riguarda le implementazioni in memoria (leggi quel modulo prima di
  pianificare). Nessuna parte JPA, nessun repository Spring Data, nessuna
  entità, nessuna migration Flyway: il Fraud service non ha un database.
- Una sola classe, `InMemoryBlockedIbanRepository` (o il nome che il Transfer
  usa per analoghe implementazioni in memoria), che implementa
  `BlockedIbanOutPort`.
- Il costruttore riceve una `Collection<String>` di IBAN e costruisce internamente
  gli `Iban` del dominio: la normalizzazione e la validazione passano solo da lì.
  Un IBAN non valido nella lista fa fallire la costruzione con l'eccezione del
  dominio, in modo che un errore di configurazione emerga all'avvio.
- L'insieme interno è una copia difensiva immutabile: modificare la collezione
  passata dopo la costruzione non cambia il comportamento.
- La porta restituisce, tra gli IBAN candidati, solo quelli bloccati. Il risultato
  è un insieme immutabile.
- Nessuna annotazione Spring e nessuna dipendenza da Spring nel modulo: la classe
  viene registrata come bean in `fraud-bootstrap` (fase successiva). Se il
  Transfer usa annotazioni negli adapter in memoria, segnalalo come ambiguità
  invece di copiarle.
- Nessun log di IBAN: neanche quando la lista è vuota o quando un IBAN non è valido.

## Da verificare nel codice esistente (segnala come ambiguità se non torna)
La firma esatta di `BlockedIbanOutPort` definita nella fase 3 (tipo del parametro e
del risultato), e il nome che il Transfer dà alle implementazioni in memoria.
Rispetta la firma esistente: non modificare la porta.

## Dipendenze del modulo
Solo `fraud-application` (e quindi il dominio, in modo transitivo). JUnit e AssertJ
in `test`. Nient'altro.

## Riferimenti
Contratto: sezioni 6 (lista degli IBAN bloccati) e 9 (dati nei log).
CLAUDE.md del fraud-service, sezione "Particolarità del servizio".

## Test richiesti
- tra i candidati ce n'è uno bloccato: viene restituito solo quello
- nessun candidato bloccato: insieme vuoto
- candidati vuoti: insieme vuoto
- lista di configurazione vuota: nessun IBAN bloccato, nessun errore
- IBAN in lista scritto con spazi e minuscole, candidato normalizzato: viene trovato
- IBAN non valido nella lista: la costruzione fallisce
- modificare la collezione passata al costruttore dopo la costruzione non
  cambia il risultato
- l'insieme restituito non è modificabile

## Fuori perimetro
Bootstrap, configurazione dei bean e delle proprietà, tabella o file esterni per
la lista, test di integrazione, adapter web.
# fraud: application del fraud-service

## Obiettivo
Implementare `fraud-application`: porte, service, DTO, controllo della valuta.

## Decisioni già prese
- Porta di input `CheckTransferInPort`: riceve un `FraudCheckRequestDto` (tipi
  semplici) e restituisce un `FraudCheckResponseDto`.
- Porta di output `BlockedIbanOutPort`: dato un insieme di IBAN, restituisce
  quali sono bloccati. In questa fase esiste solo l'interfaccia (e un fake nei test).
- Il service converte il DTO in `TransferToAssess` (qui scatta la validazione dei
  costruttori del dominio), chiede alla porta quali IBAN sono bloccati, chiama
  `FraudPolicy` e converte il risultato nel DTO di risposta.
- Il `transferId` NON influisce sulla decisione: viene restituito identico.
- La soglia è un parametro del service, passato dal costruttore.
- Il controllo "solo EUR" sta nel service, con un'eccezione dedicata
  `UnsupportedCurrencyException`. Il formato di IBAN e importo è già nel dominio.
- Le eccezioni di validazione del dominio NON vengono tradotte qui: lo fa
  l'adapter web nella fase successiva.
- Il service usa `@Service`, come nel transfer-service.
- Nessun log di IBAN o importi.

## Riferimenti
Contratto: sezioni 4, 5, 6 e 11 (casi 1-7 e 15). CLAUDE.md del fraud-service.

## Fuori perimetro
Adapter web, adapter in uscita reale, configurazione dei bean, test di integrazione.
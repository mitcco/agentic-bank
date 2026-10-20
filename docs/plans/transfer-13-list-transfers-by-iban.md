# transfer-13: Elenco dei bonifici di un IBAN, con filtri (piano)

Stato: **in attesa di approvazione**. Si lavora su `main`; il commit lo fa l'utente. Il piano usa solo classi e interfacce nuove: **nessuno dei 707 test cambia**, nessuna migration, nessuna dipendenza. Il punto delicato e la data: le due tabelle salvano `created_at` in fusi diversi, quindi i bonifici interni vanno letti come ora locale della JVM e convertiti in UTC (limite da dichiarare).

**Prima di iniziare va chiusa transfer-12** (committata): questa issue la usa (`Iban.masked()`, `TransferQueryController`, l'handler 404, la ricerca nelle due tabelle). Ordine raccomandato: commit di transfer-12 a cura dell'utente, poi transfer-13 con baseline 707.

## Fatti verificati nel codice reale (working tree con transfer-12)
- `TransferQueryOutPort` ha un solo metodo `findById` ed e usata come lambda in `GetTransferServiceImplTest`: non va toccata. `JpaTransferQueryOutPortImpl` cerca prima in `transfer`, poi in `external_transfer`, senza lock. `StoredTransferDto` (IBAN interi, `toString` senza dati sensibili) e `TransferDetailsDto` (IBAN mascherati). `GetTransferServiceImpl` maschera con `Iban.masked()`, porta l'importo a `setScale(2)` e spezza i motivi su `"; "`.
- REST di transfer-12: `TransferQueryController` ha un costruttore con il solo `TransferQueryServiceDto`, usato da `TransferQueryControllerTest`; anche `TransferQueryServiceDto` e usata come lambda. Non vanno toccati. `WebExceptionHandler`: `IllegalArgumentException` -> 400 `ProblemDetail`; `TransferNotFoundException` -> 404 con `instance` fisso `/transfers` (Spring altrimenti mette nell'`instance` il path richiesto). **Conseguenza: un IBAN nel path finirebbe nell'`instance` di ogni errore; nella query string no** (l'`instance` contiene solo il path).
- `Iban.masked()`: primi 4 caratteri, 19 asterischi, ultimi 4.
- Date: `transfer` (V1) ha `created_at TIMESTAMP NOT NULL DEFAULT now()`; **`TransferEntity` non la mappa**, quindi il valore lo scrive il database nel fuso della sessione JDBC (H2: fuso della JVM; driver Postgres: sessione impostata sul fuso della JVM; su questa macchina CEST, +2h rispetto a UTC). `external_transfer.created_at` e mappata (`updatable = false`) e scritta dall'applicazione in UTC.
- In `transfer` mittente e destinatario sono conti nostri (FK su entrambi). In `external_transfer` il mittente e un conto nostro e il destinatario un IBAN esterno.
- `SpringDataTransferRepository` ed `SpringDataExternalTransferRepository` estendono `JpaRepository`; `JpaSpecificationExecutor` fa parte di Spring Data JPA gia presente: nessuna dipendenza.
- Dati demo (`DemoDataCatalog`): il conto `demo-acc-141` (IBAN `IT60X0542811101000000000141`) e mittente di `demo-ext-001` (esterno, COMPLETED, 59.73) e destinatario di `demo-tr-034` (interno, COMPLETED, 32.46, dal conto 134); `ac1` e mittente di `tr1` (COMPLETED) e destinatario di `tr3` (REJECTED).

## Obiettivo
Esporre l'elenco paginato dei bonifici (interni ed esterni) in cui un IBAN e mittente o destinatario, con filtri facoltativi, controparte mascherata e senza log sensibili.

## Ambiguita (il piano segue le raccomandazioni R)
1. **Forma dell'endpoint. R: `GET /transfers?iban=...`.** L'IBAN resta fuori dal path, quindi fuori dall'`instance` degli errori. Non si scontra con `POST /transfers` ne con `GET /transfers/{id}`. **Controller nuovo** (`TransferListController`); `TransferQueryController` e `AccountController` restano intatti. Alternativa: `GET /accounts/{iban}/transfers` (piu espressivo, ma l'IBAN finisce nel path e quindi negli errori, e servirebbe un `instance` fisso anche per i 400).
2. **Filtri (il minimo utile).** `direction`: `ALL` (default), `OUTGOING` (l'IBAN e il mittente), `INCOMING` (l'IBAN e il destinatario); si usano OUTGOING/INCOMING e non SENT/RECEIVED perche SENT e gia uno **stato** (`?direction=SENT&status=SENT` confonderebbe). `status`: ripetibile (`?status=COMPLETED&status=REJECTED`), valori di `TransferStatusEnum`, assente = tutti; PENDING ammesso ma non da mai risultati (non e mai salvato), documentato. `type`: `INTERNAL` o `EXTERNAL`, assente = entrambi (sceglie quali tabelle leggere). `from` e `to`: istanti ISO-8601 in UTC (es. `2026-10-19T00:00:00Z`); `from` incluso, `to` escluso. `page` (default 0) e `size` (default 20, massimo 100); finestra massima `(page + 1) * size <= 1000`. Ordinamento fisso: dal piu recente, a parita di data per id decrescente. Nessun filtro sull'importo (fuori perimetro).
3. **Validazione. R:** il controller riceve **tutti i parametri come `String`** facoltativi (`required = false`) e il **service li valida**, lanciando `IllegalArgumentException` con messaggi fissi che non ripetono il valore ricevuto; l'handler esistente restituisce 400 `ProblemDetail`. Cosi non intervengono gli errori di conversione di Spring (`MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`), che oggi l'handler non gestisce e darebbero un 400 con un corpo diverso. Casi che danno 400: `iban` mancante o non valido, `direction`, `status` o `type` sconosciuti, date non ISO, `from` non precedente a `to`, `page` minore di 0, `size` fuori da 1..100, finestra oltre 1000.
4. **Paginazione su due tabelle. R: due query ordinate e fusione in memoria, nell'adapter.** Per ogni tabella richiesta si leggono le prime `offset + size` righe ordinate (`offset = page * size`) piu il conteggio; si fondono per data decrescente e id; si restituisce la fetta da `offset` a `offset + size`. Totale **esatto** = somma dei due conteggi; `hasNext = offset + size < totale`. Il costo cresce con la pagina e la finestra massima di 1000 lo limita. Alternativa: una `UNION ALL` nativa (portabile su H2 e Postgres): l'ordinamento nel SQL mescolerebbe date in fusi diversi (punto 5), scartata.
5. **Data e fuso. R:** mappare `created_at` in `TransferEntity` **in sola lettura** (`insertable = false, updatable = false`, `LocalDateTime`), cosi il default del database resta l'unico che la scrive. Nell'adapter le date interne si interpretano nel **fuso della JVM** (`ZoneId.systemDefault()`), quelle esterne in **UTC**; entrambe diventano `Instant`. I filtri `from` e `to` si convertono allo stesso modo per ciascuna tabella. **Limite dichiarato:** vale se l'applicazione (e la sessione JDBC) ha sempre girato nello stesso fuso di oggi; al cambio dell'ora legale un'ora e ambigua. Una V4 non correggerebbe le righe esistenti e mescolerebbe fusi nella stessa tabella: niente V4. Alternative: niente data e niente filtro per data (ma l'ordinamento "piu recenti prima" si perde); ordinare per tabella (concatenazione, non un vero ordine).
6. **Risposta. R:** `{ items: [...], page, size, totalElements, hasNext }`. Ogni elemento: `transferId`, `type` (`INTERNAL`/`EXTERNAL`), `direction` (`OUTGOING`/`INCOMING`, relativa all'IBAN chiesto), `counterpartyIbanMasked`, `amount` (scala 2), `currency`, `transferStatus`, `createdAt` (ISO-8601 UTC). Niente motivi (si leggono con `GET /transfers/{id}`) e niente IBAN chiesto (il chiamante lo conosce).
7. **IBAN senza bonifici o che non e un conto nostro. R: 200 con lista vuota.** Coerente con "elenco filtrato per IBAN"; vale anche per un IBAN esterno, che mostra i bonifici esterni ricevuti. L'esistenza di un conto e gia rivelata da `GET /accounts/{iban}` (404), quindi nascondere qui non e una protezione. Alternativa: 404 se l'IBAN non e un conto (rivela l'esistenza e nasconde i destinatari esterni).
8. **Privacy. Rischio dichiarato:** senza autenticazione, chiunque conosca o indovini un IBAN (non e un segreto) ricostruisce lo storico di quel conto: controparti mascherate, importi, stati e date. E piu sensibile del GET per id. Limiti adottati: `size` massimo 100, finestra massima 1000, controparte sempre mascherata, nessun log con IBAN o importi, nessun IBAN negli errori (query string, messaggi fissi). Evoluzione: autenticazione e proprieta con il frontend.

## Modifiche per file
**transfer-application** (`com.bank.transfer.application`)
| File | Tipo | Responsabilita |
|---|---|---|
| `command/ListTransfersCommand.java` | nuovo | parametri grezzi: `String iban, String direction, List<String> statuses, String type, String from, String to, String page, String size` (tutti facoltativi; la lista null diventa vuota) |
| `dto/TransferDirectionEnum.java` | nuovo | `ALL`, `OUTGOING`, `INCOMING` |
| `dto/TransferTypeEnum.java` | nuovo | `INTERNAL`, `EXTERNAL` |
| `dto/TransferListCriteria.java` | nuovo | criteri validati verso la porta out: `Iban iban, TransferDirectionEnum direction, Set<TransferStatusEnum> statuses, Set<TransferTypeEnum> types, Instant from, Instant to, int offset, int size` (`toString` senza IBAN) |
| `dto/StoredTransferRowDto.java` | nuovo | `transferId, TransferTypeEnum type, String sourceIban, String destIban, BigDecimal amount, String currency, TransferStatusEnum status, Instant createdAt` (`toString` con il solo id) |
| `dto/StoredTransferListDto.java` | nuovo | `List<StoredTransferRowDto> rows, long total` |
| `dto/TransferListItemDto.java` | nuovo | `transferId, String type, String direction, String counterpartyIbanMasked, BigDecimal amount, String currency, String status, Instant createdAt` (`toString` con il solo id) |
| `dto/TransferPageDto.java` | nuovo | `List<TransferListItemDto> items, int page, int size, long totalElements, boolean hasNext` |
| `port/in/ListTransfersInPort.java` | nuovo | `TransferPageDto listTransfers(ListTransfersCommand command)` |
| `port/out/TransferListQueryOutPort.java` | nuovo | `StoredTransferListDto findByIban(TransferListCriteria criteria)`: righe ordinate dalla piu recente (poi per id decrescente), da `offset` a `offset + size`, e totale esatto |
| `service/ListTransfersServiceImpl.java` | nuovo | `@Service`, implementa `ListTransfersInPort`, costruttore `(TransferListQueryOutPort)`. Validazione e default dei punti 2 e 3 con messaggi fissi ("iban is required"; gli errori di `Iban`; "direction must be ALL, OUTGOING or INCOMING"; "status is not valid"; "type must be INTERNAL or EXTERNAL"; "from and to must be ISO-8601 instants"; "from must be before to"; "page must be zero or more"; "size must be between 1 and 100"; "page window too large"); criteri: direzione ALL di default, nessuno stato = tutti, nessun tipo = entrambi; per ogni riga `direction` = OUTGOING se `sourceIban` e uguale all'IBAN chiesto, altrimenti INCOMING; controparte = l'altro IBAN mascherato con `new Iban(...).masked()`; importo `setScale(2)`; `status.name()`; `hasNext = offset + size < total`. Nessun log |

**transfer-postgres-adapter** (`com.bank.transfer.postgres`)
| File | Tipo | Responsabilita |
|---|---|---|
| `repository/model/TransferEntity.java` | modificato (solo un campo in sola lettura piu getter) | `@Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt`. Costruttore invariato |
| `repository/SpringDataTransferRepository.java` | modificato (estende anche `JpaSpecificationExecutor<TransferEntity>`) | nessun metodo nuovo |
| `repository/SpringDataExternalTransferRepository.java` | modificato (estende anche `JpaSpecificationExecutor<ExternalTransferEntity>`) | metodi esistenti invariati |
| `port/out/postgres/impl/jpa/JpaTransferListQueryOutPortImpl.java` | nuovo | `@Repository`. Per ogni tabella richiesta da `types`, una `Specification`: direzione (OUTGOING `sourceIban = iban`, INCOMING `destIban = iban`, ALL una delle due), `status in statuses` se presenti, `createdAt >= from` e `< to` convertiti nel fuso della tabella. `findAll(spec, PageRequest.of(0, offset + size, Sort.by(DESC createdAt, DESC id)))` da righe e totale; le date diventano `Instant` (interni con `systemDefault`, esterni UTC); si fondono, si ordinano e si taglia la fetta; `total` = somma dei due conteggi |

**transfer-rest-adapter** (`com.bank.transfer.rest`)
| File | Tipo | Responsabilita |
|---|---|---|
| `dto/response/TransferListItemResponseDto.java` | nuovo | campi dell'elemento, `createdAt` come `Instant` |
| `dto/response/TransferListResponseDto.java` | nuovo | `items, page, size, totalElements, hasNext` |
| `mapper/TransferPageDtoToTransferListResponseDtoMapper.java` | nuovo | MapStruct, come gli altri mapper |
| `servicedto/TransferListServiceDto.java` | nuovo | `TransferListResponseDto listTransfers(String iban, String direction, List<String> statuses, String type, String from, String to, String page, String size)` |
| `servicedto/internal/TransferListServiceDtoImpl.java` | nuovo | `@Service`: costruisce `ListTransfersCommand`, chiama la porta in, mappa la risposta |
| `port/in/rest/TransferListController.java` | nuovo | `@GetMapping("/transfers")` con `@RequestParam(required = false)` per tutti i parametri (`status` come `List<String>`); sempre 200 se valido |

`WebExceptionHandler`, `TransferQueryController`, `TransferController` e tutte le porte esistenti restano **invariati**.

**transfer-service/CLAUDE.md** (ultima fase, **da autorizzare**): nuova sottosezione "Elenco dei bonifici di un IBAN": endpoint, parametri, default e limiti, forma della risposta, fusione delle due tabelle, fuso di `created_at` (limite), privacy (nessun controllo di accesso), assenza di un indice dedicato.

## Test attesi (prima del codice, stesso package, solo classi nuove)
**`ListTransfersServiceImplTest`** (application, porta out come lambda): `defaultsAreAllDirectionsAllStatusesBothTypesFirstPageOf20` (solo `iban`: ALL, stati vuoti, entrambi i tipi, `offset` 0, `size` 20); `ibanIsNormalized`; `outgoingAndIncomingAreRelativeToTheIban` (controparte mascherata corretta); `amountHasTwoDecimalsAndStatusIsItsName` (10.5000 -> 10.50, `"COMPLETED"`); `filtersArePassedToThePort`; `pageAndSizeBecomeOffsetAndSize` (`page=2`, `size=10` -> `offset` 20; `hasNext` true con totale 31, false con 30); `emptyResultIsAnEmptyPage`; `invalidInputsAreRefusedWithFixedMessages` (parametrizzato su tutti i casi di 400, messaggio fisso che non contiene il valore ricevuto, porta mai chiamata).
**Test dei DTO** (application): `TransferListCriteriaTest`, `StoredTransferRowDtoTest`, `TransferListItemDtoTest`, `TransferPageDtoTest`: campi obbligatori (NPE col nome del campo) e `toString` senza IBAN ne importi.
**`JpaTransferListQueryOutPortImplTest`** (postgres, `@DataJpaTest`, `@Import` delle implementazioni JPA; dati propri, non `demo-*`; date impostate con `UPDATE ... SET created_at = ?` via JDBC: interni in ora locale della JVM, esterni in UTC): `allDirectionsBothTablesNewestFirst` (conto X: mittente di un interno e di un esterno, destinatario di un interno: 3 righe dalla piu recente, totale 3); `outgoingAndIncoming` (2 righe; 1 riga); `statusAndTypeFilters`; `dateRangeFromInclusiveToExclusive`;
`internalLocalTimeAndExternalUtcAreMergedOnTheSameClock` (interno con il default del database, esterno salvato un secondo dopo: l'esterno viene prima); `paginationAcrossTheTwoTables` (5 righe alternate, `offset` 2, `size` 2: la 3a e la 4a in ordine globale, totale 5); `tieOnDateIsOrderedByIdDescending`; `unknownIbanIsEmpty`; `createdAtIsReadOnlyOnTheInternalEntity`; `nullCriteriaIsRefused`.
**`TransferListControllerTest`** (rest, MockMvc standalone con `WebExceptionHandler`, servicedto come lambda che registra i parametri): `passesAllParametersAsTheyAre` (`status` come lista di 2); `returns200WithItemsAndPageMetadata`; `returns200WithAnEmptyList`; `badRequestBodyDoesNotEchoTheIban` (400 problem+json, `instance` `/transfers`, corpo senza l'IBAN); `getByIdIsStillServedByTheOtherController` (i due controller insieme senza conflitti di mapping).
**`TransferListServiceDtoImplTest`** (rest): costruisce il command dai parametri, mappa la pagina, propaga le eccezioni.
**`TransferListEndToEndTest`** (bootstrap, `@SpringBootTest` su `jdbc:h2:mem:transferlist`, MockMvc con `MockMvcPrint.NONE`, `OutputCaptureExtension`, antifrode `@Primary` sempre ALLOWED; dati seminati): `seededAccount141ListsAnExternalOutgoingAndAnInternalIncoming` (2 elementi, senza assumere l'ordine perche creati nello stesso istante del seed: `demo-ext-001` EXTERNAL OUTGOING COMPLETED 59.73, controparte `IT60` + 19 `*` + `0001`; `demo-tr-034` INTERNAL INCOMING COMPLETED 32.46, controparte `IT60` + 19 `*` + `0134`); `directionFilterOnTheSeededAccount`; `statusFilterOnAc1` (`status=REJECTED` -> solo `tr3`, INCOMING); `newTransferAppearsFirst` (conto proprio, bonifico interno nuovo dopo il seed); `invalidParametersAnswer400WithoutTheIban` (`direction=SENT`; `size=500`; senza `iban`: corpo e log senza l'IBAN); `unknownIbanAnswers200Empty`; `logsNeverContainIbansNorAmounts`.

## Criteri di accettazione
- `.\mvnw.cmd test` da `transfer-service/` verde: **i 707 test esistenti invariati, nessuna eccezione**, piu i nuovi.
- Nessuna dipendenza, migration (V1-V3 intatte) o nuovo modulo.
- Classi esistenti modificate solo in: un campo in sola lettura piu getter in `TransferEntity` (costruttore invariato) e `extends JpaSpecificationExecutor` nei due repository Spring Data. `TransferQueryOutPort`, `TransferQueryController`, `TransferQueryServiceDto`, `WebExceptionHandler`, `TransferController` e tutte le porte esistenti invariati.
- `GET /transfers?iban=...` restituisce l'elenco paginato dalle due tabelle con filtri, default e limiti come da specifica; totale esatto; controparte mascherata; 400 con messaggi fissi senza IBAN; 200 vuoto per IBAN senza bonifici. Nessun IBAN ne importo nei log ne nei `toString` dei nuovi DTO.

## Rischi
- **Privacy:** lo storico di un conto e leggibile da chiunque conosca l'IBAN (punto 8); i limiti riducono l'esposizione ma non la eliminano.
- **Fuso di `created_at` degli interni:** ordine e filtri per data sono corretti solo se la JVM (e la sessione JDBC) ha sempre girato nel fuso attuale; un'ora e ambigua al cambio dell'ora legale.
- **Costo delle pagine profonde:** con la fusione in memoria si leggono fino a 1000 righe per tabella; il limite della finestra e obbligatorio.
- **Nessun indice** su `source_iban`, `dest_iban` e `created_at`: con i volumi demo non serve; un indice sarebbe una V4 da proporre a parte.
- **Coerenza tra le due query:** non sono nella stessa transazione; un bonifico scritto in mezzo puo spostare la paginazione di una riga (accettabile per una lettura).
- **PENDING accettato come filtro** ma sempre vuoto (documentato). **Dipendenza da transfer-12 non committato.**

## Fuori perimetro
Autenticazione e proprieta dei conti; filtri sull'importo; ordinamenti scelti dal client; paginazione a cursore; motivi nell'elenco; indici o migration (V4); esportazione; frontend; commit.

## Fasi e passi interni (ogni passo lascia la build verde)
| Fase | Contenuto | Dipende da | Rischio |
|---|---|---|---|
| 1. Application e persistenza | **P1:** command, enum e DTO con i loro test. **P2:** `TransferListQueryOutPort`; `TransferEntity.createdAt` in sola lettura; `JpaSpecificationExecutor` sui repository; `JpaTransferListQueryOutPortImpl` con il test JPA. **P3:** `ListTransfersInPort` e `ListTransfersServiceImpl` con il test | transfer-12 committato | medio (fusione, fusi, paginazione) |
| 2. REST ed end-to-end | **P4:** DTO di risposta, mapper, servicedto e `TransferListController` con i test. **P5:** `TransferListEndToEndTest` | 1 | basso |
| 3. Documentazione | `transfer-service/CLAUDE.md` (da autorizzare) | 2 | basso |

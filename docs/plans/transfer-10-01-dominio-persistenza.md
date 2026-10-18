# transfer-10-01: Dominio, contratti, persistenza (piano di dettaglio della Fase 1)

Stato: **in attesa di approvazione**. Fase 1 del piano v3 approvato `transfer-10-saga-minima.md`, branch `apply-saga-pattern`.

## Fatti verificati dal planner
- `Transfer` e un record a 5 componenti con `requirePending()`, che lancia `IllegalStateException("Transfer is already " + status)`. L'enum oggi vale `COMPLETED, REJECTED, PENDING`.
- Nessun punto rompe con `SENT`: nel codice e nei test non c'e alcuno `switch` sullo stato, ne `TransferStatusEnum.values()` / `valueOf` ne `@EnumSource`. `TransferEntity` usa `@Enumerated(STRING)` con `length = 20`.
  Il payload dell'outbox usa `status().name()` solo dopo la validazione del DTO. `TransferResultDto` porta lo stato come `String`.
- Unica guardia da cambiare: `TransferNotificationDto` rifiuta solo `PENDING`; senza modifiche una notifica `SENT` senza motivi passerebbe.
- `TransferTest` controlla solo il tipo dell'eccezione sulle transizioni non valide. `IbanTest` ha 6 test e nessuno tocca `bankCode`.
- Test dell'adapter postgres: `@DataJpaTest` + `@Import(Jpa...Impl.class)`, `EntityManager` per flush/clear, lock verificato con `entityManager.getLockMode(...) == PESSIMISTIC_WRITE`
  (`JpaAccountRepositoryOutPortImplTest`); i test di migration usano `JdbcTemplate` e `DataIntegrityViolationException` (`OutboxMigrationTest`); configurazione `PostgresTestApplication`; H2 embedded, Flyway applica le migration.
- Nei `@DataJpaTest` del modulo `ddl-auto=validate` NON e attivo (con Flyway il default di Boot e `none`); `validate` vale solo in `transfer-bootstrap/application.properties`.
- V1: nomi di tabella in maiuscolo, `REFERENCES account (iban)`, `DEFAULT now()` (portabile H2/Postgres). V2 crea `OUTBOX`.
- Stile di `JpaAccountRepositoryOutPortImpl`: `@Repository`, costruttore package-private, `Objects.requireNonNull`, nessun `@Transactional`.
- `FraudCheckUnavailableException`: porta il `transferId`, messaggio con il solo id, nessuna causa, non e una `IllegalArgumentException`.

## Obiettivo
Introdurre i mattoni della Saga senza cambiare alcun comportamento: nel dominio `BankCode`, `Iban.bankCode()`, lo stato `SENT` e tre transizioni; nell'application i contratti in uscita;
nell'adapter postgres la tabella `external_transfer` con la sua implementazione JPA. Build verde con i 392 test invariati.

## Ambiguita (il piano segue le raccomandazioni R; nessuna blocca la fase)
1. **Porte in in Fase 1. R: nessuna.** `CreateExternalTransferInPort` e `ProcessClearingOutcomesInPort` passano alla Fase 2 insieme a `ExternalTransferSagaServiceImpl`. `CreateInternalTransferInPort` e il router restano in Fase 3.
2. **Contratti della stanza (`ClearingHouseOutPort`, 3 DTO, eccezione) in Fase 1. R: si.** Sono il contratto su cui la Fase 2 scrive saga e simulatore; `ClearingOutcomeDto` ha regole proprie da testare. Nessun bean li richiede.
3. **`ExternalTransferRepositoryOutPort.save`: `void`.** (Alternativa: `Transfer`, nessuno userebbe il valore.)
4. **`findByIdForUpdate` senza `@Transactional(MANDATORY)`**, come `JpaAccountRepositoryOutPortImpl.findByIbanForUpdate`: chiama sempre la saga dentro `TransactionTemplate`.
5. **`BankCode`: nessuna normalizzazione**, regola stretta `[A-Z0-9]{5}`; `Iban.bankCode()` passa un valore gia normalizzato.
6. **Messaggio delle transizioni non valide:** lo stesso di oggi, `"Transfer is already " + status`, con helper privato `requireStatus(expected)`; `requirePending()` lo riusa.
7. **`ClearingOutcomeDto`:** `ACCEPTED` vuole `reason == null` (anche vuota o blank e rifiutata); `REJECTED` vuole un motivo non vuoto.
8. **`ClearingInstructionDto`:** solo `Objects.requireNonNull` con il nome del campo (stile `TransferNotificationDto`).
9. **Coerenza stato/motivo e lunghezza del motivo nell'adapter:** l'adapter salva quello che riceve, non tronca e non controlla; e responsabilita della saga (Fase 2). Oltre 500 caratteri il database rifiuta.
10. **`validate` dentro il modulo:** `@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")` solo in `ExternalTransferMigrationTest` (proprieta di test, non una dipendenza).

## Modifiche per modulo

**transfer-domain** (`com.bank.transfer.domain`)
| File | Tipo | Responsabilita |
|---|---|---|
| `BankCode.java` | nuovo | `record BankCode(String value)`: non null e `[A-Z0-9]{5}`, altrimenti `IllegalArgumentException` con messaggio fisso (senza il valore) |
| `Iban.java` | modificato | aggiunge `public BankCode bankCode()` = `new BankCode(value.substring(5, 10))`. Costruttore e regole invariati |
| `TransferStatusEnum.java` | modificato | aggiunge `SENT` |
| `Transfer.java` | modificato | `markSent()` PENDING->SENT; `settle()` SENT->COMPLETED; `rejectAfterSent()` SENT->REJECTED; nuova istanza con stesso id e importo. Helper privato `requireStatus(TransferStatusEnum)`. `create`, `complete()`, `reject()` e le validazioni invariati |

**transfer-application** (`com.bank.transfer.application`)
| File | Tipo | Responsabilita |
|---|---|---|
| `port/out/ExternalTransferRepositoryOutPort.java` | nuovo | `void save(Transfer transfer, String reason)` (reason nullable); `Optional<Transfer> findByIdForUpdate(String id)` (blocca la riga fino a fine transazione, javadoc come `AccountRepositoryOutPort`) |
| `port/out/ClearingHouseOutPort.java` | nuovo | `void submit(ClearingInstructionDto)` (puo lanciare `ClearingHouseUnavailableException`); `List<ClearingOutcomeDto> pollOutcomes()` (restituisce gli esiti pronti e li consuma) |
| `dto/ClearingInstructionDto.java` | nuovo | `record(String transferId, String sourceIban, String destIban, BigDecimal amount, String currency)`, `requireNonNull` per campo |
| `dto/ClearingOutcomeDto.java` | nuovo | `record(String transferId, ClearingDecisionEnum decision, String reason)`: transferId e decision non null; transferId non vuoto; REJECTED vuole reason non vuota; ACCEPTED vuole reason null |
| `dto/ClearingDecisionEnum.java` | nuovo | `ACCEPTED`, `REJECTED` |
| `exception/ClearingHouseUnavailableException.java` | nuovo | `RuntimeException` (non `IllegalArgumentException`), campo `transferId` e accessor `transferId()`; messaggio "Clearing house unavailable for transfer " + id; nessuna causa |
| `dto/TransferNotificationDto.java` | modificato | `status == PENDING` diventa `status != COMPLETED && status != REJECTED`, messaggio "status must be COMPLETED or REJECTED" |

Nessun service, nessuna porta in, nessun cambio a `CreateTransferServiceImpl`.

**transfer-postgres-adapter** (`com.bank.transfer.postgres`)
| File | Tipo | Responsabilita |
|---|---|---|
| `resources/db/migration/V3__create_external_transfer.sql` | nuovo | `CREATE TABLE EXTERNAL_TRANSFER` nello stile di V1. V1 e V2 intatte |
| `repository/model/ExternalTransferEntity.java` | nuovo | `@Entity @Table(name = "external_transfer")`; campi e `@Column` come `TransferEntity` piu `reason` (`length = 500`, nullable); `created_at` non mappato; costruttore protetto vuoto, costruttore a 7 argomenti, getter |
| `repository/SpringDataExternalTransferRepository.java` | nuovo | `JpaRepository<ExternalTransferEntity, String>`; `@Lock(PESSIMISTIC_WRITE) @Query("select t from ExternalTransferEntity t where t.id = :id") Optional<ExternalTransferEntity> findByIdForUpdate(@Param("id") String id)` |
| `port/out/postgres/impl/jpa/JpaExternalTransferRepositoryOutPortImpl.java` | nuovo | `@Repository`, costruttore package-private; `requireNonNull` su transfer e id (reason nullable); mappatura sullo stile di `JpaTransferRepositoryOutPortImpl` |

Colonne di V3: `id VARCHAR(36) PRIMARY KEY`; `source_iban VARCHAR(27) NOT NULL REFERENCES account (iban)`; `dest_iban VARCHAR(27) NOT NULL` (senza FK); `amount NUMERIC(19, 4) NOT NULL`;
`currency VARCHAR(3) NOT NULL`; `status VARCHAR(20) NOT NULL`; `reason VARCHAR(500)` nullable; `created_at TIMESTAMP NOT NULL DEFAULT now()`.

**transfer-rest-adapter, transfer-bootstrap, pom, CLAUDE.md:** nessuna modifica. Il bootstrap prende entita, repository e implementazione per scansione; `validate` gira su V3 nei 6 contesti H2.

## Test attesi (prima del codice, stesso package della classe)

**`BankCodeTest`** (nuovo, `com.bank.transfer.domain`): `acceptsFiveDigits` (`05428`); `acceptsUppercaseLettersAndDigits` (`AB12C`); `equalValuesAreEqual`; `rejectsNull`; `rejectsEmptyAndBlank`;
`rejectsFourOrSixCharacters` (`0542`, `054281`); `rejectsLowercase` (`ab12c`); `rejectsSymbolsAndSpaces` (`05-28`, `05 28`): tutti `IllegalArgumentException`.

**`IbanTest`** (solo nuovi): `bankCodeIsTheAbiAtPositionsSixToTen` (`IT60X0542811101000000123456` -> `05428`); `bankCodeIsReadAfterNormalization` (`" it60 x054 2811 1010 0000 0123 456 "` -> `05428`);
`bankCodeOfAnotherBank` (`IT60X0306909606100000012345` -> `03069`, diverso da `05428`); `blockedDemoIbanBelongsToOurBank` (`IT60X0542811101000000000099` -> `05428`).

**`TransferTest`** (solo nuovi): `markSentMovesAPendingTransferToSentKeepingIdAndMoney` (l'originale resta PENDING); `settleMovesASentTransferToCompleted`; `rejectAfterSentMovesASentTransferToRejected`;
`pendingTransferCannotBeSettledOrRejectedAfterSent`; `sentTransferCannotBeSentAgainNorCompletedNorRejectedAsPending`; `completedTransferCannotBeSentSettledOrRejectedAfterSent`;
`rejectedTransferCannotBeSentSettledOrRejectedAfterSent` (tutti `IllegalStateException`); `invalidTransitionMessageNamesTheCurrentStatus` (SENT `markSent()` -> "Transfer is already SENT").

**`TransferNotificationDtoTest`** (un nuovo): `sentStatusIsRefused` (SENT, reasons vuoto -> `IllegalArgumentException`).

**`ClearingOutcomeDtoTest`** (nuovo, `...application.dto`): `acceptedWithoutReasonIsValid`; `rejectedWithReasonIsValid`; `rejectedWithNullReasonIsRefused`; `rejectedWithBlankReasonIsRefused` (`""`, `"  "`);
`acceptedWithReasonIsRefused` (`"x"`, `""`); `nullTransferIdIsRefused` (NPE "transferId"); `blankTransferIdIsRefused`; `nullDecisionIsRefused` (NPE "decision").

**`ClearingInstructionDtoTest`** (nuovo): `keepsAllTheFields` (importo con `isEqualByComparingTo`); `nullFieldsAreRefused` (NPE col nome del campo).

**`ClearingHouseUnavailableExceptionTest`** (nuovo, `...application.exception`): `messageHasTheTransferIdButNoCause`; `isARuntimeExceptionButNotAnIllegalArgument`.

**`ExternalTransferMigrationTest`** (nuovo, `com.bank.transfer.postgres`, `@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")`, `JdbcTemplate`; conto mittente inserito con SQL; colonne lette in modo insensibile al maiuscolo come in `OutboxMigrationTest`):
`schemaMatchesTheEntity`; `destinationThatIsNotAnAccountIsAccepted` (`IT60X0306909606100000012345`); `unknownSourceIsRejected`; `missingStatusIsRejected` (entrambi `DataIntegrityViolationException`);
`reasonIsOptionalAndCreatedAtHasADefault`; `transferTableStillRequiresAnExistingDestination` (V1 intatta).

**`JpaExternalTransferRepositoryOutPortImplTest`** (nuovo, `...postgres.port.out.postgres.impl.jpa`, `@DataJpaTest`, `@Import({JpaExternalTransferRepositoryOutPortImpl.class, JpaAccountRepositoryOutPortImpl.class})`; in `@BeforeEach` solo il conto mittente):
`findByIdForUpdateReturnsEmptyWhenTransferDoesNotExist`; `savesATransferToAnIbanThatIsNotAnAccount` (ABI `03069`); `findByIdForUpdateLocksTheRow` (`PESSIMISTIC_WRITE`);
`saveReplacesTheRowWithSameIdAndStoresTheReason`; `nullReasonLeavesTheColumnEmpty`; `doesNotWriteIntoTheTransferTable`; `rejectsNulls` (NPE).

## Criteri di accettazione
- `.\mvnw.cmd test` da `transfer-service/` verde: 392 test esistenti senza modifiche piu i nuovi. Solo `TransferApplicationTests` richiede Postgres; eseguirlo almeno una volta con `transfer-db` attivo per provare V3 e `validate` su Postgres vero.
- I 6 contesti `@SpringBootTest` del bootstrap si avviano con `validate` su V3 (H2).
- Nessuna migration esistente modificata; V3 e l'unica nuova.
- Nessuna modifica ai pom, nessun nuovo modulo, nessun file in rest-adapter o bootstrap.
- Il dominio non ha import di framework nuovi; l'application aggiunge solo interfacce, record ed eccezione, senza service e senza porte in.
- `CreateTransferServiceImpl`, `TransferRepositoryOutPort`, `TransferEntity`, `JpaTransferRepositoryOutPortImpl` e `DemoDataSeeder` invariati.
- `TransferNotificationDto` rifiuta `SENT`; COMPLETED e REJECTED come oggi.

## Rischi e fuori perimetro
**Rischi**
- Entita e V3 non allineate: ogni discrepanza fa fallire i 6 contesti del bootstrap; mitigato da `ExternalTransferMigrationTest` con `validate`.
- V3 su Postgres vero provata solo con `TransferApplicationTests` (Docker) o a mano; `now()`, `NUMERIC`, `REFERENCES` senza nome di vincolo sono gia in V1.
- `findByIdForUpdate` fuori transazione lancia `TransactionRequiredException`: in Fase 1 nessuno lo chiama; in Fase 2 la saga lo usa dentro `TransactionTemplate`.
- Codice senza chiamanti fino alla Fase 2 (porta stanza, DTO, eccezione, implementazione JPA): nessun bean li richiede.
- Il nuovo `@Repository` entra in tutti i contesti del bootstrap senza dipendenze oltre allo Spring Data repository.
- `SENT` e un valore nuovo: nessun `switch`/`values()` lo intercetta oggi; codice futuro che enumera gli stati dovra tenerne conto.

**Fuori perimetro:** porte in, `ExternalTransferSagaServiceImpl`, simulatore, config e scheduler del clearing (Fase 2); `CreateInternalTransferInPort`, router, cambio di `implements`, `transfer.bank-code`, 202, end-to-end, `transfer-service/CLAUDE.md` (Fase 3);
notification-service, fraud-service, `docker-compose.yaml`, contratti, commit.

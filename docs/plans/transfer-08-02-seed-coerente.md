# transfer-08-02: Seed coerente (piano di dettaglio della Fase 2)

Stato: **in attesa di approvazione**. Fase 2 del piano `transfer-08-seed-demo.md` (rev. 2, approvato); la Fase 1 (`transfer-08-01-riconciliazione.md`) e completata (611 test verdi, reviewer APPROVED). Si lavora su `main`; il commit lo fa l'utente.

La Fase 2 cambia un solo test esistente, `DemoDataSeederTest` (metodo `loadsDemoAccountsAndTransfers` e `@Import`, cambio autorizzato dall'utente). Nessun test della Fase 1 ne della Saga risente dei dati seminati: le asserzioni sono per id oppure contano esiti della coda del simulatore, che il seed non tocca.

## Fatti verificati nel codice
- **`DemoDataSeeder` oggi:** costruttore `(AccountRepositoryOutPort, TransferRepositoryOutPort)`. Salta tutto se `...0001` esiste. Crea `ac1..ac3` (1000.00, 250.50, 0.00) e `tr1..tr3` (100.00 COMPLETED, 50.25 COMPLETED, 5000.00 REJECTED). Non e transazionale e non ha condizioni di attivazione.
- **`DemoDataSeederTest` oggi:** `@DataJpaTest` con `@Import(JpaAccount..., JpaTransfer..., DemoDataSeeder)`; `loadsDemoAccountsAndTransfers` verifica i 6 valori sopra; `doesNotOverwriteExistingDataWhenRunAgain` salva `ac1` a 1.00, rilancia e verifica 1.00. I saldi 1000.00, 250.50, 0.00 legati agli IBAN `...0001..0003` compaiono solo qui (gli altri usi sono unit test con mock o payload di stringhe).
- **Persistenza dei bonifici esterni:** `JpaExternalTransferRepositoryOutPortImpl.save` fa un merge di `new ExternalTransferEntity(7 argomenti)`; il costruttore imposta `createdAt = LocalDateTime.now(ZoneOffset.UTC)` (`updatable = false`); `findInProgressIdsCreatedBefore` usa la query derivata con `Limit` sugli stati SENT e UNKNOWN.
- `SpringDataTransferRepository` e `SpringDataExternalTransferRepository` estendono `JpaRepository`: `existsById` disponibile.
- **Saga dopo la Fase 1:** `reconcileStale` interroga `queryStatus` e, se l'istruzione non c'e, applica REJECTED "Instruction not found at the clearing house" (rimborso e outbox REJECTED); `processOutcomes` applica solo esiti della coda del simulatore, alimentata solo da `submit`.
- **Proprieta:** `clearing.reconcile.stale-after=5m`; lo scheduler si accende con `clearing.outcomes.enabled` (default `false`).
- **Test del bootstrap che interrogano esiti o riconciliazione:** `ExternalTransferSagaIntegrationTest` e `ExternalTransferEndToEndTest` verificano valori esatti restituiti da `processOutcomes()` (1 oppure 0): contano solo esiti della coda, **il seed non mette nulla in coda, quindi restano validi**.
  `ExternalTransferReconciliationIntegrationTest` controlla il valore di `reconcileStale` solo con `isGreaterThanOrEqualTo(1)`, il resto e per id; `MAX` = 100 e molto piu dei bonifici in corso di un contesto; `freshTransfersAreLeftAlone` usa una soglia di un'ora fa, che esclude i seminati (creati all'avvio del contesto). **Resta valido.** In quel contesto il primo `reconcileStale(now + 1s)` rimborsa anche i 2 SENT e UNKNOWN seminati: effetto sui soli conti demo 149 e 150, che nessuna asserzione legge. Gli IBAN dei test hanno una lettera piu 5 cifre: nessuna collisione con il seed.
- **Il seeder gira a ogni avvio:** e un `ApplicationRunner`, quindi gira in ogni contesto `@SpringBootTest` e nei `@DataJpaTest` che lo importano; i `@DataJpaTest` che non lo importano non lo vedono.

## Effetto dichiarato sulla riconciliazione
- I bonifici esterni seminati in SENT (`demo-ext-009`) e UNKNOWN (`demo-ext-010`) hanno `created_at` uguale all'istante del seed (UTC). Con lo scheduler acceso (`CLEARING_OUTCOMES_ENABLED=true`) vengono riconciliati al primo giro dopo `stale-after` (5 minuti).
- Il simulatore non li ha mai ricevuti: risposta "non trovato", REJECTED "Instruction not found at the clearing house", rimborso (i conti 149 e 150 tornano a 2000.00) e **una riga outbox REJECTED ciascuno**. Con lo scheduler spento restano SENT e UNKNOWN.
- Il seed in se non scrive mai nell'outbox; le 2 righe nascono dalla riconciliazione. Con il publisher Kafka acceso diventano notifiche verso il Notification.

## Ambiguita residue (il piano segue le raccomandazioni R)
1. **Database gia popolato. R:** il seeder inserisce solo le righe mancanti (conti per IBAN, bonifici per id). **La coerenza dei saldi vale solo su un database vuoto:** se un conto demo esiste gia, i bonifici demo mancanti non ne correggono il saldo. Dichiarato nel javadoc e in CLAUDE.md.
2. **Transazione. R:** `@Transactional` su `run` (l'adapter usa gia `@Transactional`; Boot crea proxy CGLIB). Errore a meta: rollback di tutto, il riavvio completa il lavoro.
3. **Log. R:** un INFO slf4j "Demo data: {} accounts, {} transfers, {} external transfers inserted", senza IBAN ne importi.
4. **Valori storici. R:** ricalcolati con la regola unica (approvato). Le righe gia presenti sul Postgres dell'utente non si toccano.

## Regole del catalogo (deterministiche, senza margini di scelta)
Valuta sempre `EUR`, importi con scala 2 (`BigDecimal.valueOf(cents, 2)`). `fmt12(n)` = `String.format("%012d", n)`.

**Conti (53)**
- Storici: `ac1` -> `IT60X0542811101000000000001`, `ac2` -> `...0002`, `ac3` -> `...0003`.
- Nuovi, n = 101..150: id `demo-acc-` + n; IBAN `IT60X0542811101` + `fmt12(n)` (27 caratteri, ABI `05428`).
- Saldo di ogni conto: `INITIAL_BALANCE` 2000.00 - addebiti + accrediti, secondo la tabella dei movimenti.

**Bonifici interni (43, tabella `transfer`)**
- Storici: `tr1`: ac1 -> ac2, 100.00, COMPLETED; `tr2`: ac2 -> ac3, 50.25, COMPLETED; `tr3`: ac3 -> ac1, **15000.00**, REJECTED.
- Nuovi, k = 1..40: id `demo-tr-` + `%03d`(k); sorgente = conto `100 + k` (da 101 a 140); destinatario = conto `101 + ((k + 6) mod 50)`; stato REJECTED se `k mod 6 == 0` (k = 6, 12, 18, 24, 30, 36: sono 6), altrimenti COMPLETED (34);
  importo COMPLETED: `cents = 1000 + (k * 7919) mod 89000` (da 10.00 a 899.99); importo REJECTED: `10000.00 + 500.00 * (k / 6)` (10500.00, 11000.00, 11500.00, 12000.00, 12500.00, 13000.00).

**Bonifici esterni (10, tabella `external_transfer`), k = 1..10**
- id `demo-ext-` + `%03d`(k); sorgente = conto `140 + k` (da 141 a 150, tutte diverse).
- Destinatario: `IT60X` + `ABI[(k - 1) mod 4]` + `11101` + `fmt12(k)`, con `ABI = [03069, 02008, 05034, 01030]`.
- Importo: `cents = 1000 + (k * 4973) mod 89000`, **tranne** k = 8, che vale 12000.00.
- Stato e motivo: k = 1..4 COMPLETED, motivo null; k = 5, 6 REJECTED "Rejected by the clearing house"; k = 7 REJECTED "Clearing house unavailable"; k = 8 REJECTED "Amount must not exceed 10000 EUR"; k = 9 SENT, motivo null; k = 10 UNKNOWN, motivo null.

**Tabella dei movimenti (regola del saldo)**
| Bonifico | Movimento |
|---|---|
| interno COMPLETED | -importo alla sorgente, +importo al destinatario |
| interno REJECTED | nessuno |
| esterno COMPLETED, SENT, UNKNOWN | -importo alla sorgente |
| esterno REJECTED (k = 5, 6, 7: compensato; k = 8: antifrode) | nessuno (netto zero) |

**Nessun saldo negativo, per costruzione.** Ogni conto nuovo e sorgente di **al piu un** bonifico che muove denaro (i conti 101-140 di un solo interno, i 141-150 di un solo esterno), con importo <= 899.99 < 2000.00. Vale in qualunque ordine e anche senza contare gli accrediti.

**Valori di controllo** (da usare nei test)
| Conto | Calcolo | Saldo seminato |
|---|---|---|
| ac1 | 2000 - 100.00 | **1900.00** |
| ac2 | 2000 + 100.00 - 50.25 | **2049.75** |
| ac3 | 2000 + 50.25 | **2050.25** |
| 101 | - `demo-tr-001` (89.19); nessun accredito | **1910.81** |
| 108 | - `demo-tr-008` (643.52) + `demo-tr-001` (89.19) | **1445.67** |
| 141 | - `demo-ext-001` (59.73) + `demo-tr-034` (32.46) | **1972.73** |
| 143 | - `demo-ext-003` (159.19); accredito da `demo-tr-036` REJECTED: nessuno | **1840.81** |
| 147 | `demo-ext-007` compensato (0) + `demo-tr-040` (507.60) | **2507.60** |
| 148 | `demo-ext-008` antifrode (0); nessun accredito | **2000.00** |
| 149 | - `demo-ext-009` SENT (457.57) | **1542.43** (2000.00 dopo la riconciliazione) |
| 150 | - `demo-ext-010` UNKNOWN (507.30) | **1492.70** (2000.00 dopo la riconciliazione) |

## Modifiche per file
| File | Tipo | Responsabilita |
|---|---|---|
| postgres `port/out/postgres/impl/DemoDataCatalog.java` | nuovo | Java puro, package-private, metodi statici: `accounts()` (53, saldi **calcolati** applicando la tabella dei movimenti ai bonifici), `internalTransfers()` (43), `externalTransfers()` (10, `record ExternalSeed(Transfer transfer, String reason)`). Costanti `INITIAL_BALANCE` e le stringhe dei motivi. Oggetti costruiti con il dominio, quindi validati |
| postgres `port/out/postgres/impl/DemoDataSeeder.java` | modificato | `@ConditionalOnProperty(name = "transfer.demo-data.enabled", havingValue = "true", matchIfMissing = true)`. Costruttore `(AccountRepositoryOutPort, TransferRepositoryOutPort, ExternalTransferRepositoryOutPort, SpringDataTransferRepository, SpringDataExternalTransferRepository)`. `@Transactional run`: conti con `findByIban` vuoto, poi interni con `!existsById`, poi esterni con `!existsById` (`save(transfer, reason)`); log dei conteggi; mai `NotificationOutPort`. **Sparisce il controllo globale su `ACC1`.** Javadoc: dati deterministici, coerenti solo su database vuoto, righe esistenti mai modificate |
| bootstrap `resources/application.properties` | modificato | blocco `#DEMO DATA` con `transfer.demo-data.enabled=${TRANSFER_DEMO_DATA_ENABLED:true}` (fine riga come nel resto del file) |

Nessun'altra classe di produzione cambia: porte, saga, simulatore e scheduler restano come sono.

## Test attesi (prima del codice, stesso package)

**`DemoDataCatalogTest`** (nuovo, `com.bank.transfer.postgres.port.out.postgres.impl`, JUnit e AssertJ): `has53AccountsWithUniqueIdsAndIbans`; `allAccountsBelongToOurBank` (`05428`); `newIbansFollowTheScheme`; `has43InternalTransfersWithTheAgreedMix` (36 COMPLETED e 7 REJECTED; i REJECTED nuovi sono k = 6, 12, ..., 36; sorgente diversa dal destinatario; entrambi conti del catalogo);
`internalAmountsFollowTheFormula` (k = 1, 8, 34, 40: 89.19, 643.52, 32.46, 507.60; k = 6, 36: 10500.00, 13000.00); `has10ExternalTransfersWithTheAgreedStatusesAndReasons`; `externalDestinationsBelongToOtherBanks` (rotazione `03069`, `02008`, `05034`, `01030`); `externalSourcesAreDistinctAccounts141To150`; `transferIdsAreUnique` (53 id);
`legacyDataIsCoherent` (1900.00, 2049.75, 2050.25; `tr3` 15000.00 REJECTED); `balancesAreReplayedIndependentlyFromTheHistory` (ricostruzione nel test: mappa IBAN -> 2000.00 e tabella dei movimenti **scritta nel test e non presa dal catalogo**; `isEqualByComparingTo`); `controlValuesMatch` (conti 101, 108, 141, 143, 147, 148, 149, 150);
`everyAccountIsTheSourceOfAtMostOneMovingTransfer` (importi <= 899.99); `fraudRejectionsAreAboveTheThreshold` (> 10000.00); `isDeterministic`.

**`DemoDataSeederTest`** (esistente, **modifica autorizzata dall'utente**): annotazione di classe: `@Import({JpaAccountRepositoryOutPortImpl.class, JpaTransferRepositoryOutPortImpl.class, JpaExternalTransferRepositoryOutPortImpl.class, DemoDataSeeder.class})` e `JdbcTemplate` iniettato.
`loadsDemoAccountsAndTransfers` **cambiato**: `ac1` 1900.00, `ac2` 2049.75, `ac3` 2050.25; `tr1` e `tr2` invariati; `tr3` 15000.00 REJECTED. `doesNotOverwriteExistingDataWhenRunAgain` **invariato**.
Metodi nuovi: `loadsTheWholeCatalog` (53 conti, 43 righe in `transfer`, 10 in `external_transfer`); `storedBalancesMatchTheCatalog`; `externalRowsKeepStatusAndReason` (JDBC su `demo-ext-005`, `-008`, `-009`, `-010`); `runningTwiceChangesNothing`;
`fillsOnlyTheMissingRowsOfAPartiallySeededDatabase` (delete JDBC di `demo-tr-001` e `demo-ext-001`, conto 101 salvato a 1.00, poi `run(null)`: i due bonifici tornano, il conto 101 resta 1.00); `writesNoOutboxRows` (0).

**`DemoDataSeedingDisabledTest`** (nuovo, stesso package, `@DataJpaTest(properties = "transfer.demo-data.enabled=false")`, stessi `@Import`): `noSeederAndNoDataWhenDisabled` (nessun bean `DemoDataSeeder`; `account`, `transfer` ed `external_transfer` vuote).

**`DemoDataDefaultPropertiesTest`** (nuovo, `com.bank.transfer`, stile `FraudDefaultPropertiesTest`): `demoDataIsEnabledByDefault`.

**`DemoDataReconciliationIntegrationTest`** (nuovo, `com.bank.transfer`, `@SpringBootTest` su `jdbc:h2:mem:demoreconcile`, scheduler spenti; si iniettano `ReconcileExternalTransfersInPort`, `AccountRepositoryOutPort`, `JdbcTemplate`; **un solo metodo sequenziale**, perche tutto dipende dallo stesso seed): `seededSentAndUnknownAreLeftAloneUntilStaleThenRefunded`:
(1) controllo iniziale: 53 conti con i saldi del catalogo (149 a 1542.43, 150 a 1492.70); `demo-ext-009` SENT, `demo-ext-010` UNKNOWN; 0 righe outbox per gli id demo; (2) `reconcileStale(now - 1h, 100)`: invariati (`created_at` e l'istante del seed); (3) `reconcileStale(now + 1s, 100)`: restituisce >= 2; `demo-ext-009` e `-010` REJECTED "Instruction not found at the clearing house"; conti 149 e 150 a 2000.00; 1 riga outbox REJECTED ciascuno; tutti gli altri esterni demo e tutti gli altri saldi invariati; (4) nuovo `reconcileStale(now + 1s, 100)`: nessun cambiamento per gli id demo (idempotente).

## Criteri di accettazione
- `.\mvnw.cmd test` da `transfer-service/` verde: **i 611 test esistenti invariati tranne `DemoDataSeederTest`**: cambiano l'`@Import` (piu `JdbcTemplate`) e i valori attesi di `loadsDemoAccountsAndTransfers`; `doesNotOverwriteExistingDataWhenRunAgain` resta invariato; si aggiungono i metodi nuovi. Nessun altro test cambia; in particolare `ExternalTransferSagaIntegrationTest`, `ExternalTransferEndToEndTest` ed `ExternalTransferReconciliationIntegrationTest` sono invariati e verdi.
- Nessun nuovo modulo, dipendenza o migration. Dominio, application, simulatore e scheduler invariati.
- Su database vuoto: 53 conti, 43 bonifici interni e 10 esterni; saldi uguali alla ricostruzione indipendente; 0 righe outbox dal seed. Su database popolato: solo righe mancanti, nessuna riga modificata.
- `transfer.demo-data.enabled=false` (o `TRANSFER_DEMO_DATA_ENABLED=false`) impedisce la creazione del bean e non cancella nulla.
- **Verifica manuale (in Fase 3):** sul Postgres dell'utente compaiono i dati demo; `...0001` resta come oggi; con lo scheduler acceso, dopo circa 5 minuti `demo-ext-009` e `-010` diventano REJECTED e i conti 149 e 150 tornano a 2000.00.

## Rischi
- `TRANSFER_DEMO_DATA_ENABLED=false` sulla macchina spegne il seed anche nei test: `DemoDataSeederTest` fallirebbe (da documentare in CLAUDE.md, come `TRANSFER_BANK_CODE`).
- Notifiche per dati demo: la riconciliazione dei 2 bonifici demo scrive righe outbox REJECTED; con il publisher acceso arrivano al Notification. Voluto (mostra la Saga), da dichiarare.
- Database popolato: la coerenza vale solo per i conti creati in un'unica esecuzione su database vuoto (dichiarato).
- Contesti del bootstrap: circa 106 righe in piu per contesto. In `ExternalTransferReconciliationIntegrationTest` il primo `reconcileStale` futuro rimborsa anche i 2 bonifici demo: neutro per le asserzioni (per id, `>= 1`).
- Nuovi test con IBAN numerici `...0101`-`...0150` o id `demo-*` collidono con il seed (da documentare).
- `@Transactional` sul runner: se il proxy non si applicasse, un errore lascerebbe un seed parziale; il riavvio completerebbe comunque grazie al controllo riga per riga.

## Fuori perimetro
Ricalcolo dei saldi di righe gia presenti; cancellazione o pulizia dei dati demo; titolari e anagrafiche; esiti nel simulatore per i bonifici seminati; nuove migration; modifiche a saga, simulatore e scheduler; `transfer-service/CLAUDE.md` e verifica manuale (Fase 3); commit (a cura dell'utente).

## Ordine dei passi interni (ogni passo lascia la build verde)
1. **S1:** `DemoDataCatalogTest`, poi `DemoDataCatalog`. Nessuno lo usa ancora; build verde.
2. **S2, atomico:** modifiche a `DemoDataSeederTest` e metodi nuovi; `DemoDataSeedingDisabledTest` e `DemoDataDefaultPropertiesTest`; poi `DemoDataSeeder` unificato e la proprieta in `application.properties`; infine `.\mvnw.cmd test` completo, perche i contesti del bootstrap ora seminano 53 conti e 53 bonifici.
3. **S3:** `DemoDataReconciliationIntegrationTest`.

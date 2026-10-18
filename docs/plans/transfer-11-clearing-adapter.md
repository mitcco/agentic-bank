# transfer-11: Stanza di compensazione in un adapter proprio (piano)

Stato: **in attesa di approvazione**. Si lavora su `main`; il commit lo fa l'utente. Richiesta dell'utente: "non mi piace, crea un adapter anche se e in memoria". Raccomandato un modulo `transfer-clearing-adapter` che mantiene il package attuale (nessun import cambia). Le modifiche di pom sono tre e vanno approvate.

## Fatti verificati nel codice
- Nel `transfer-bootstrap` ci sono solo due classi di produzione nel package `com.bank.transfer.clearing.port.out.clearing.impl`: `SimulatedClearingHouseOutPortImpl` e `SimulatedClearingModeEnum`. Il simulatore e Java puro (nessuna annotazione Spring, nessun logger); importa solo `java.util` e classi di `transfer-application` (`ClearingHouseOutPort`, `ClearingInstructionDto`, `ClearingOutcomeDto`, `ClearingDecisionEnum`, `ClearingHouseUnavailableException`, `ClearingHouseTimeoutException`); non usa il dominio. Membri usati da fuori, tutti `public`: costruttore, `setMode`, `mode()`, `REJECT_REASON`, metodi della porta.
- Punti d'uso (Grep): main `config/ClearingConfig` (lo crea con `new`, passando il modo da `clearing.simulator.mode`); test che restano nel bootstrap: `config/ClearingConfigTest`, `ExternalTransferSagaIntegrationTest`, `ExternalTransferEndToEndTest`, `ExternalTransferReconciliationIntegrationTest`; test nello stesso package del simulatore: `SimulatedClearingHouseOutPortImplTest` e `SimulatedClearingHouseQueryStatusTest`; testo: `transfer-service/CLAUDE.md`. Non lo usano: `ClearingDefaultPropertiesTest`, gli scheduler e i loro test, i `DemoData*`.
- Nessun test sfrutta l'accesso a livello di package: i due test del package usano solo membri pubblici, JUnit 5 e AssertJ.
- Pom: il padre `transfer-service/pom.xml` elenca 7 moduli e ha un `dependencyManagement` per ogni modulo interno piu mapstruct; `transfer-kafka-adapter` dipende da `transfer-application`, dallo starter Kafka e da `spring-boot-starter-test` (test); `transfer-bootstrap` dipende dai 6 moduli interni piu gli starter actuator e test.
- Modello di adapter in memoria: `fraud-service/fraud-memory-adapter/pom.xml` contiene solo `fraud-application` piu `junit-jupiter` e `assertj-core` in scope test (versioni dal BOM di Boot).
- Regole: CLAUDE.md radice: `<svc>-<tecnologia>-adapter`, tecnologia = nome della tecnologia (`memory`, `postgres`...) **o del sistema collegato** (`fraud`); package `com.bank.<svc>.<tecnologia>`; niente dipendenze ai pom senza chiedere. `transfer-service/CLAUDE.md` dice gia che con una stanza vera si sposterebbe in un modulo `transfer-clearing-adapter`.

## Ambiguita (il piano segue le raccomandazioni R)
1. **Nome del modulo. R: `transfer-clearing-adapter`** (sistema collegato, come `fraud`; il package `com.bank.transfer.clearing...` segue gia lo schema). Una stanza reale futura starebbe nello stesso modulo. Alternativa: `transfer-memory-adapter` (per tecnologia): richiede il package `com.bank.transfer.memory...`, quindi cambiano gli import di `ClearingConfig` e di 4 test, e il nome non dice quale sistema simula.
2. **Package. R: invariato**, `com.bank.transfer.clearing.port.out.clearing.impl`: nessun import cambia.
3. **Spostare i due test del simulatore. R: si**, nel nuovo modulo, stesso package e stesso contenuto. Tecnicamente potrebbero restare nel bootstrap, ma il modulo resterebbe senza test. **Eccezione da approvare:** spostamento di 2 test esistenti (cambia solo il modulo).
4. **Modifiche di pom: tre, da approvare** (motivo: nuovo modulo richiesto dall'utente; nessuna libreria esterna nuova).
5. **Dipendenze di test del nuovo modulo. R: `junit-jupiter` e `assertj-core`** (scope test, versioni dal BOM), come `fraud-memory-adapter`. Alternativa: `spring-boot-starter-test` (piu pesante e non serve).
6. **Documentazione. R:** aggiornare solo `transfer-service/CLAUDE.md` (da autorizzare). Il CLAUDE.md radice non cambia. Piani e disegni non si toccano.

## Interazione con il lavoro non committato e ordine
- Nel working tree: la Fase 1 della riconciliazione (611 test verdi, non committata) e cinque test del seed (`DemoDataCatalogTest`, `DemoDataSeederTest`, `DemoDataSeedingDisabledTest` nel postgres-adapter; `DemoDataDefaultPropertiesTest` e `DemoDataReconciliationIntegrationTest` nel bootstrap), forse scritti a meta dal tester interrotto.
- Rischio: se quei test non compilano (es. `DemoDataCatalog` non esiste ancora), `.\mvnw.cmd test` fallisce per tutto il servizio e transfer-11 non si puo verificare.
- Sovrapposizioni con il seed: solo `transfer-service/CLAUDE.md` (Fase 3 del seed). Il seed tocca postgres-adapter, `application.properties` e i test `DemoData*`; transfer-11 tocca i pom, il package clearing e i due test del simulatore.
- **R:** (1) completare la Fase 2 del seed (test piu implementazione, build verde) e, se si vuole, la sua Fase 3; (2) commit delle Fasi 1 e 2 a cura dell'utente; (3) poi transfer-11 su un working tree pulito, con baseline = test dopo il seed. **Alternativa** se il seed resta bloccato: `git stash push -- <file DemoData*>` (operazione dell'utente), transfer-11 con baseline 611, poi ripristino (piu rischiosa).

## Modifiche per file
**Nuovo modulo `transfer-service/transfer-clearing-adapter/`**
| File | Tipo | Responsabilita |
|---|---|---|
| `pom.xml` | nuovo | parent `com.bank:transfer-service:0.0.1-SNAPSHOT`; `artifactId` `transfer-clearing-adapter`; dipendenze: `com.bank:transfer-application` (compile, versione dal `dependencyManagement`), `org.junit.jupiter:junit-jupiter` (test), `org.assertj:assertj-core` (test). Nient'altro |
| `src/main/java/com/bank/transfer/clearing/port/out/clearing/impl/SimulatedClearingHouseOutPortImpl.java` | spostato (`git mv`) | contenuto invariato |
| `src/main/java/com/bank/transfer/clearing/port/out/clearing/impl/SimulatedClearingModeEnum.java` | spostato (`git mv`) | contenuto invariato |
| `src/test/java/com/bank/transfer/clearing/port/out/clearing/impl/SimulatedClearingHouseOutPortImplTest.java` | spostato (`git mv`, **eccezione da approvare**) | contenuto invariato |
| `src/test/java/com/bank/transfer/clearing/port/out/clearing/impl/SimulatedClearingHouseQueryStatusTest.java` | spostato (`git mv`, **eccezione da approvare**) | contenuto invariato |

**Pom modificati (da approvare)**
| File | Modifica |
|---|---|
| `transfer-service/pom.xml` | `<module>transfer-clearing-adapter</module>` dopo `transfer-fraud-adapter` (prima del bootstrap); in `dependencyManagement` la voce `com.bank:transfer-clearing-adapter:${project.version}` |
| `transfer-bootstrap/pom.xml` | dipendenza `com.bank:transfer-clearing-adapter`, senza versione, accanto agli altri adapter |

**Bootstrap, cosa resta e cosa no:** restano **invariati** `config/ClearingConfig` (crea il simulatore con `new` e scrive i due WARN), `ClearingOutcomesScheduler*`, `ClearingReconciliationScheduler*`, `application.properties` (`clearing.*`) e tutti i test del bootstrap. Spariscono dal bootstrap la cartella main e la cartella test del package `clearing`. Lo scan dei componenti non trova il simulatore (nessuna annotazione): come oggi lo crea solo `ClearingConfig`.

**`transfer-service/CLAUDE.md`** (fase 2, **da autorizzare**): tabella dei moduli: nuova riga `transfer-clearing-adapter` (stanza simulata in memoria, `SimulatedClearingHouseOutPortImpl` implementa `ClearingHouseOutPort`, Java puro) e dalla riga del bootstrap si toglie "stanza di compensazione simulata (package ...)"; "Struttura dei package degli adapter": aggiungere "Clearing: `clearing.port.out.clearing.impl`"; sezione "Stanza di compensazione simulata": togliere il paragrafo sulla deviazione e scrivere che vive nel modulo `transfer-clearing-adapter`, mentre bean, WARN, scheduler e proprieta restano nel bootstrap.

## Test
- **Nessun test nuovo:** il comportamento non cambia; il cablaggio lo provano gia `ClearingConfigTest` e i tre test di integrazione.
- **Spostati (eccezione da approvare):** `SimulatedClearingHouseOutPortImplTest` e `SimulatedClearingHouseQueryStatusTest`.
- **Invariati e da rieseguire:** `ClearingConfigTest`, `ExternalTransferSagaIntegrationTest` (`wiringHasOneBeanPerPortAndNoScheduler`), `ExternalTransferEndToEndTest`, `ExternalTransferReconciliationIntegrationTest`, `ClearingDefaultPropertiesTest`, i test degli scheduler.
- **Conteggio:** il totale dei test del transfer-service resta uguale alla baseline (i test tolti dal bootstrap compaiono identici in `transfer-clearing-adapter`).

## Criteri di accettazione
- `.\mvnw.cmd test` da `transfer-service/` verde con **lo stesso numero di test della baseline** (611, oppure 611 piu i test del seed se completato prima).
- `.\mvnw.cmd -pl transfer-clearing-adapter test` esegue i due test spostati, verdi.
- Nessuna riga di logica cambiata; classi e test spostati identici byte per byte (rename al 100% con `git diff -M`).
- Il nuovo modulo dipende solo da `transfer-application` (piu JUnit e AssertJ in test): nessuna dipendenza dal bootstrap, nessuna libreria esterna nuova. Nessuna classe `clearing.port.out.clearing.impl` resta nel bootstrap.
- `transfer-service/CLAUDE.md` aggiornato solo dopo l'autorizzazione.

## Rischi
- Ciclo di dipendenze: il modulo non deve dipendere dal bootstrap ne da altri adapter (il pom proposto dipende solo da `transfer-application`).
- Ordine del reactor: Maven ordina per dipendenze; la voce `<module>` va comunque prima del bootstrap.
- Classi duplicate durante lo spostamento: se per un momento esistono sia nel bootstrap sia nel modulo, lo stesso nome qualificato compare due volte sul classpath; per questo copia, dipendenza e rimozione stanno in **un unico passo**.
- Build offline: `junit-jupiter` e `assertj-core` sono gia nel repository locale.
- IDE: dopo la modifica dei pom serve reimportare il progetto Maven.
- Working tree sporco: vedi la sezione sull'interazione con il seed.

## Fuori perimetro
Cambi di comportamento della stanza; una stanza reale; spostamento di `ClearingConfig` o degli scheduler nel modulo; rinomina del package; altri moduli o servizi; CLAUDE.md radice; piani e disegni; commit (a cura dell'utente).

## Fasi e passi interni (ogni passo lascia la build verde)
| Fase | Contenuto | Dipende da | Rischio |
|---|---|---|---|
| 1. Modulo e spostamento | **P1, atomico:** nuovo `transfer-clearing-adapter/pom.xml`, `<module>` e `dependencyManagement` nel padre, dipendenza nel bootstrap, `git mv` delle 2 classi main; poi `.\mvnw.cmd test`. **P2:** `git mv` dei 2 test; poi `.\mvnw.cmd test` e confronto del conteggio con la baseline | seed completato e committato (raccomandato) | basso |
| 2. Documentazione | `transfer-service/CLAUDE.md` (da autorizzare) | 1 | basso |

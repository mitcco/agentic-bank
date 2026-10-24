# fraud-07: Metriche di business della valutazione antifrode (piano)

Stato: **approvato dall'utente ed eseguito** (decisioni D1-D8 con le raccomandazioni R; D2 nella forma (b), vedi "Aggiornamento dopo la review"). Si lavora sul branch `feature/business-metrics`; il commit lo fa l'utente. Notification-service non si tocca. Da eseguire **prima** di transfer-14: è più piccolo e collauda lo schema (porta out, modulo `*-micrometer-adapter`, wiring, test di integrazione) che transfer-14 replica.

## Fatti verificati nel codice reale
- `CheckTransferServiceImpl` (`com.bank.fraud.application.service`) **non** ha `@Service` (decisione A1 di fraud-06). Il bean lo crea `FraudConfig.checkTransferInPort(BlockedIbanOutPort, @Value threshold)` con `new CheckTransferServiceImpl(port, threshold)`. Costruttore `(BlockedIbanOutPort, BigDecimal)`.
- Il costruttore è chiamato con `new` in `CheckTransferServiceImplTest` (6 punti), `CheckTransferServiceImplValidationTest` (1) e `FraudCheckContractScenariosTest` (rest adapter, 1).
- Il flusso di `checkTransfer`:
  - costruisce `TransferToAssess`, e un `InvalidFraudRequestException` diventa `InvalidFraudCheckRequestException`;
  - chiama `blockedIbanOutPort.findBlocked`;
  - calcola `FraudAssessment assessment = policy.assess(...)`;
  - restituisce `FraudCheckResponseDto` con `status().name()` e i motivi tradotti da `reason.message(threshold)`.
- Dominio:
  - `FraudStatusEnum` vale `ALLOWED` o `REJECTED` (il contratto `fraud-check.md` dice lo stesso);
  - `FraudReasonEnum` vale `AMOUNT_OVER_THRESHOLD`, `SOURCE_IBAN_LOCKED`, `DESTINATION_IBAN_LOCKED`;
  - `FraudAssessment(transferId, status, reasons)`: con `ALLOWED` non ha motivi, con `REJECTED` ne ha almeno uno.
- **Il testo del motivo contiene la soglia** ("Amount must not exceed 10000 EUR"): non va mai usato come tag.
- `FraudConfigTest` usa `new ApplicationContextRunner().withUserConfiguration(FraudConfig.class)`, senza `MeterRegistry` né autoconfigurazioni.
- `fraud-bootstrap/pom.xml` ha già `spring-boot-starter-actuator` e `micrometer-registry-prometheus` (non committati). Come dipendenza di test ha solo `spring-boot-starter-webmvc-test`, non `spring-boot-starter-actuator-test` (che il Transfer invece ha).
- `application.properties` espone `health,prometheus` e ha `management.metrics.tags.application=${spring.application.name}` (cioè `fraud`).
- Moduli attuali: domain, application, rest-adapter, memory-adapter, bootstrap. Il Dockerfile fa `COPY . .` e `-pl fraud-bootstrap -am`, quindi un modulo nuovo non richiede modifiche al Dockerfile.

## Obiettivo
Contare le valutazioni antifrode per esito e per motivo con Micrometer, tramite una porta out dell'application e un adapter in un modulo dedicato, senza IBAN, importi o `transferId` nei tag e nei log.

## Perimetro
- **Dentro:** porta `MetricsOutPort` e chiamata dal service; modulo `fraud-micrometer-adapter`; bean nel bootstrap; test unitari, dell'adapter e di integrazione; aggiornamento di `fraud-service/CLAUDE.md` (da autorizzare).
- **Fuori:** metriche delle richieste non valide (le copre già `http_server_requests`), latenze, notification-service, contratto. La dashboard la crea transfer-14 (vedi D6).

## Ambiguità / decisioni per l'utente (il piano segue le raccomandazioni R)
1. **D1 (contraddizione con la issue). La issue dice `APPROVED/REJECTED`, il codice e il contratto dicono `ALLOWED/REJECTED`.** R: tag `status` con i valori del dominio, `ALLOWED` e `REJECTED`. Rinominare in `APPROVED` creerebbe un secondo vocabolario per lo stesso concetto.
2. **D2. Come passare la porta al service. Decisione finale: (b).**
   - **(b) R, adottata:** un solo costruttore `(BlockedIbanOutPort, BigDecimal, MetricsOutPort)` e aggiornamento delle 8 righe `new CheckTransferServiceImpl(...)` nei 3 test esistenti (`CheckTransferServiceImplTest` 6, `CheckTransferServiceImplValidationTest` 1, `FraudCheckContractScenariosTest` 1), solo il costruttore e nessuna asserzione. Richiede l'autorizzazione a toccare test esistenti, che l'utente ha dato. In produzione non resta nessuna implementazione che non fa nulla.
   - (a) Scartata: un costruttore a 2 argomenti che delega a una porta `NoOpMetricsOutPort` per non toccare i test. Era stata implementata e poi rimossa su richiesta dell'utente: lasciava in `main` una classe che esisteva solo per i test e un wiring sbagliato avrebbe perso le metriche in silenzio.
3. **D3 (blocca). `FraudConfigTest` non avvia più il contesto.** `checkTransferInPort` riceverà un `MetricsOutPort`, e il bean `MetricsOutPort` richiede un `MeterRegistry`, che il runner non ha. Tutti i casi C1-C7 ed E6 fallirebbero.
   - **(a) R:** nel runner di `FraudConfigTest` aggiungere solo `.withBean(MeterRegistry.class, SimpleMeterRegistry::new)`. Riga di setup; asserzioni invariate. Richiede l'autorizzazione a toccare un test esistente.
   - (b) `ObjectProvider<MetricsOutPort>` con fallback che non fa nulla in `FraudConfig`. Scartata: se manca il bean le metriche spariscono in silenzio.
4. **D4. Come contare i motivi.** R: due contatori.
   - `bank.fraud.assessments{status}`: uno per valutazione.
   - `bank.fraud.reasons{reason}`: uno per ogni motivo. Un rifiuto con due motivi incrementa due serie.
   - Alternativa: un contatore con `status` e `reason` insieme. Scartata: con più motivi la stessa valutazione verrebbe contata due volte.
5. **D5. Richieste non valide (400) ed errori della porta degli IBAN bloccati.** R: non si contano: nessuna valutazione è avvenuta, e `http_server_requests_seconds_count{status="400"}` le copre già. Alternativa: un esito `INVALID`, che però non appartiene al dominio.
6. **D6. Proprietario della dashboard `bank-business.json`.** R: la crea transfer-14, con una sezione dedicata al Fraud. Le query del Fraud sono elencate qui per riferimento; i pannelli restano vuoti finché fraud-07 non è in produzione. Alternativa: fraud-07 crea il file con la sola sezione Fraud e transfer-14 lo estende.
7. **D7. Dipendenze nuove (DA APPROVARE).**
   - `io.micrometer:micrometer-core` (scope compile) nel nuovo `fraud-micrometer-adapter/pom.xml`. Versione dal BOM di Spring Boot 4.1.1; in fraud-bootstrap è già presente in modo transitivo tramite actuator.
   - Dipendenza interna `fraud-micrometer-adapter` in `fraud-bootstrap/pom.xml`, più `<module>` e `dependencyManagement` nel pom padre.
   - Se l'export Prometheus nei test richiede l'annotazione di Boot 4 dentro `spring-boot-starter-actuator-test`, va aggiunta quella dipendenza (scope test) in fraud-bootstrap. **R:** non aggiungerla e verificare con il `MeterRegistry` del contesto (vedi test I4).
   - Alternativa a zero dipendenze: adapter dentro `fraud-bootstrap`. Si scosta dalla struttura (il bootstrap contiene solo configurazione) e va chiesta: sconsigliata.
8. **D8. Contatori registrati a zero all'avvio.** R: sì, per tutte le combinazioni dell'elenco chiuso (2 esiti e 3 motivi). Così `rate()` e `increase()` hanno la serie anche prima del primo evento.

## Metriche
Tutte hanno anche il tag comune `application="fraud"`, messo dalla configurazione.

| Micrometer | Prometheus | Tipo | Tag (elenco chiuso) | Dove si incrementa | Perché lì |
|---|---|---|---|---|---|
| `bank.fraud.assessments` | `bank_fraud_assessments_total` | counter | `status` ∈ {`ALLOWED`, `REJECTED`} | `CheckTransferServiceImpl.checkTransfer`, subito dopo `policy.assess(...)` e prima di costruire la risposta | È l'unico punto in cui esiste una valutazione completa. Le richieste non valide e gli errori della porta non arrivano lì |
| `bank.fraud.reasons` | `bank_fraud_reasons_total` | counter | `reason` ∈ {`AMOUNT_OVER_THRESHOLD`, `SOURCE_IBAN_LOCKED`, `DESTINATION_IBAN_LOCKED`} | Stessa chiamata, una volta per ogni motivo di `assessment.reasons()` | Il tag è il nome dell'enum, mai `message()`, che contiene la soglia |

Cardinalità: 5 serie in tutto. Nessun `transferId`, IBAN o importo, per costruzione: la porta riceve solo enum.

## Architettura
- **Porta out:** `com.bank.fraud.application.port.out.MetricsOutPort`:
  `void assessmentCompleted(FraudStatusEnum status, List<FraudReasonEnum> reasons)`
  Solo enum del dominio: le porte out possono usarli (`BlockedIbanOutPort` usa `Iban`). Non si passa `FraudAssessment`, che contiene il `transferId`.
- **Chi la chiama:** solo `CheckTransferServiceImpl`.
- **Nessuna implementazione che non fa nulla (D2 b):** il service richiede sempre una `MetricsOutPort`; nei test si passa il fake `RecordingMetricsOutPort` (application) o una lambda (rest adapter).
- **Adapter:** nuovo modulo `fraud-micrometer-adapter`, package `com.bank.fraud.micrometer.port.out.micrometer.impl`, classe `MicrometerMetricsOutPortImpl` con costruttore `(MeterRegistry)`.
  - Nel costruttore registra i 5 contatori (`Counter.builder(...).description(...).tag(...).register(registry)`) in due `EnumMap`.
  - Rifiuta i `null` con `NullPointerException`. Non scrive log.
  - Il nome del modulo è quello della tecnologia (`micrometer`), non una categoria come `metrics`, come chiede il CLAUDE.md radice.
- **Bootstrap:** in `FraudConfig` un nuovo `@Bean MetricsOutPort metricsOutPort(MeterRegistry registry)` e `checkTransferInPort(BlockedIbanOutPort, BigDecimal, MetricsOutPort)`. Il `MeterRegistry` lo fornisce l'autoconfigurazione di actuator (Prometheus in esercizio, registry in memoria nei test `@SpringBootTest`).
- **Test unitari senza Micrometer:** in `fraud-application` un fake `RecordingMetricsOutPort` (test, stesso package del service, come `FakeBlockedIbanOutPort`) che registra le chiamate. `fraud-application` non dipende da Micrometer.

## Modifiche per modulo
**`fraud-application`** (`pom.xml` invariato)

| File | Tipo | Responsabilità |
|---|---|---|
| `src/main/java/com/bank/fraud/application/port/out/MetricsOutPort.java` | nuovo | firma sopra |
| `src/main/java/com/bank/fraud/application/service/CheckTransferServiceImpl.java` | modificato | campo `MetricsOutPort` (`requireNonNull(..., "metricsOutPort")`); un solo costruttore a 3 argomenti; chiamata `metricsOutPort.assessmentCompleted(assessment.status(), assessment.reasons())` dopo `assess` |
| `src/test/java/com/bank/fraud/application/service/RecordingMetricsOutPort.java` | nuovo (test) | fake che registra le chiamate |

**`fraud-micrometer-adapter`** (modulo nuovo)

| File | Responsabilità |
|---|---|
| `fraud-micrometer-adapter/pom.xml` | parent `fraud-service`, artifactId `fraud-micrometer-adapter`. Dipendenze: `fraud-application`, `io.micrometer:micrometer-core` (D7), `junit-jupiter` e `assertj-core` in test (come `fraud-memory-adapter`). `SimpleMeterRegistry` sta in `micrometer-core` |
| `src/main/java/com/bank/fraud/micrometer/port/out/micrometer/impl/MicrometerMetricsOutPortImpl.java` | implementa `MetricsOutPort` |
| `src/test/java/com/bank/fraud/micrometer/port/out/micrometer/impl/MicrometerMetricsOutPortImplTest.java` | test dell'adapter |

**`fraud-service/pom.xml`** (modificato): `<module>fraud-micrometer-adapter</module>` prima di `fraud-bootstrap`, e la voce in `dependencyManagement`.

**`fraud-bootstrap`**

| File | Tipo | Responsabilità |
|---|---|---|
| `pom.xml` | modificato | dipendenza `fraud-micrometer-adapter` |
| `src/main/java/com/bank/fraud/config/FraudConfig.java` | modificato | bean `MetricsOutPort` e nuovo parametro di `checkTransferInPort` |
| `src/test/java/com/bank/fraud/config/FraudConfigTest.java` | **modificato solo nel runner (D3 a)** | `.withBean(MeterRegistry.class, SimpleMeterRegistry::new)` |
| `src/test/java/com/bank/fraud/config/FraudMetricsConfigTest.java` | nuovo | wiring della porta |
| `src/test/java/com/bank/fraud/FraudMetricsIntegrationTest.java` | nuovo | integrazione |

**`fraud-service/CLAUDE.md`** (ultima fase, **da autorizzare**): riga `fraud-micrometer-adapter` nella tabella dei moduli e sezione "Metriche" con nomi, tag e regola "nessun IBAN, importo, `transferId` o testo del motivo nei tag".

## Test attesi (prima del codice, stesso package)
**`CheckTransferServiceMetricsTest`** (application, nuovo; `FakeBlockedIbanOutPort` + `RecordingMetricsOutPort`)

| Dato | Quando | Allora |
|---|---|---|
| caso 1 del contratto (1000.25, IBAN ordinari) | `checkTransfer` | una chiamata `(ALLOWED, [])` |
| caso 7 (20000.00, mittente bloccato) | `checkTransfer` | una chiamata `(REJECTED, [AMOUNT_OVER_THRESHOLD, SOURCE_IBAN_LOCKED])`, nell'ordine |
| caso 6 (entrambi bloccati) | `checkTransfer` | `(REJECTED, [SOURCE_IBAN_LOCKED, DESTINATION_IBAN_LOCKED])` |
| richiesta non valida (amount 0, IBAN errato, valuta USD) | `checkTransfer` | eccezione come oggi, **nessuna chiamata** |
| `BlockedIbanOutPort` che lancia o restituisce null | `checkTransfer` | eccezione come oggi, nessuna chiamata |
| costruttore a 3 argomenti con porta null | `new` | `NullPointerException` con il nome del campo |
| costruttore con porta null | `new` | `NullPointerException` con "metricsOutPort" nel messaggio |

**`MicrometerMetricsOutPortImplTest`** (adapter, `SimpleMeterRegistry`)

| Dato | Quando | Allora |
|---|---|---|
| registry vuoto | costruzione | esistono 2 contatori `bank.fraud.assessments` (ALLOWED, REJECTED) e 3 `bank.fraud.reasons`, tutti a 0 |
| — | `(ALLOWED, [])` | assessments ALLOWED = 1, nessun motivo incrementato |
| — | `(REJECTED, [AMOUNT_OVER_THRESHOLD, SOURCE_IBAN_LOCKED])` | REJECTED = 1, i due motivi a 1, DESTINATION a 0 |
| — | due chiamate REJECTED | valori cumulati (2) |
| qualsiasi sequenza | ispezione di `registry.getMeters()` con nome `bank.fraud.*` | chiavi dei tag ⊆ {`status`, `reason`}; ogni valore è un nome di enum (`^[A-Z_]+$`); nessun valore somiglia a un IBAN (`[A-Z]{2}\d{2}[A-Z0-9]{11,30}`) o a un numero |
| null come status o come lista | chiamata | `NullPointerException` |

**`FraudMetricsConfigTest`** (bootstrap, `ApplicationContextRunner` con `FraudConfig` e `SimpleMeterRegistry`): un solo bean `MetricsOutPort` di tipo `MicrometerMetricsOutPortImpl`. Dopo un `checkTransfer` tramite il bean `CheckTransferInPort`, il contatore del registry vale 1, cioè il service usa la porta reale e non quella che non fa nulla.

**`FraudConfigTest`** (esistente): C1-C7, E1-E6 verdi con il solo runner modificato.

**`FraudMetricsIntegrationTest`** (bootstrap, `@SpringBootTest` + `@AutoConfigureMockMvc(print = NONE)` + `OutputCaptureExtension`; asserzioni **a delta**, perché il contesto è condiviso)

| # | Dato | Quando | Allora |
|---|---|---|---|
| I1 | caso 1 | POST `/fraud-check` | assessments ALLOWED +1 |
| I2 | caso 7 | POST | REJECTED +1, AMOUNT_OVER_THRESHOLD +1, SOURCE_IBAN_LOCKED +1 |
| I3 | casi 8, 12, 13 (400) | POST | nessun contatore cambia |
| I4 | dopo I1-I3 | lettura di tutti i meter `bank.fraud.*` dal `MeterRegistry` | tag `application=fraud`; nessun tag con IBAN, importo (`1000.25`, `20000.00`) o `transferId` |
| I5 | dopo I1-I3 | GET `/actuator/prometheus`, con l'annotazione di Boot 4 che attiva l'export nei test, **da verificare** (D7) | 200; righe `bank_fraud_assessments_total{application="fraud",status="ALLOWED"}` e `bank_fraud_reasons_total{...}`; le righe `bank_` senza IBAN, importi o `transferId`. Se l'annotazione richiede una dipendenza non approvata, I5 si toglie e resta I4 |
| I6 | output catturato durante le richieste | — | nessun IBAN né importo (regola già esistente, ricontrollata) |

## Criteri di accettazione
- `.\mvnw.cmd test` da `fraud-service/` in BUILD SUCCESS senza Docker; i test esistenti verdi e invariati, salvo il runner di `FraudConfigTest` (D3) se autorizzato.
- `fraud-domain` invariato e senza Micrometer; `fraud-application` senza Micrometer (pom invariato).
- Micrometer importato solo in `fraud-micrometer-adapter` e in `FraudConfig`.
- Dipendenze nuove solo quelle approvate in D7.
- Metriche con i nomi, tipi e tag della tabella, registrate a zero all'avvio; nessun IBAN, importo, `transferId` o testo del motivo nei tag né nei log.
- Contratto `fraud-check.md` invariato e rispettato (le risposte non cambiano).

## Rischi
- **D2 b** cambia 8 righe di test esistenti (solo il costruttore): autorizzato dall'utente.
- **Annotazione di Boot 4 per l'export nei test:** il nome e il package (in Boot 3 era `@AutoConfigureObservability`) vanno verificati prima di scrivere I5.
- **Contatori per istanza, azzerati a ogni riavvio:** in dashboard si usano solo `rate()` e `increase()`.
- **Nomi:** il registry Prometheus aggiunge `_total` ai contatori, quindi i nomi Micrometer non devono finire con `.total`.

## Ordine di esecuzione
| Fase | Contenuto | Rischio |
|---|---|---|
| 0 | `.\mvnw.cmd test` come punto di partenza | — |
| 1 | `MetricsOutPort`, `RecordingMetricsOutPort`, `CheckTransferServiceMetricsTest` (rosso), poi la modifica al service (verde) | basso |
| 2 | modulo `fraud-micrometer-adapter` con test (dopo l'approvazione di D7) | basso |
| 3 | `FraudConfig`, runner di `FraudConfigTest` (D3), `FraudMetricsConfigTest`, `FraudMetricsIntegrationTest` | medio (annotazione Boot 4) |
| 4 | `fraud-service/CLAUDE.md` (da autorizzare) | basso |

## Scostamenti dal CLAUDE.md
Modifica di test esistenti (D2 b: 8 righe di costruttore; D3: una riga nel runner di `FraudConfigTest`) e dipendenze nuove (D7), tutte autorizzate dall'utente. Il modulo nuovo rispetta la convenzione `<svc>-<tecnologia>-adapter`.

## Aggiornamento dopo la review (approvato dall'utente)
- Storico: `NoOpMetricsOutPort` e il costruttore a 2 argomenti di `CheckTransferServiceImpl` erano stati implementati (D2 a) e sono stati poi **rimossi**
  (D2 b): resta un solo costruttore `(BlockedIbanOutPort, BigDecimal, MetricsOutPort)`. Aggiornate solo le
  righe `new CheckTransferServiceImpl(...)` di 3 test esistenti (8 punti, nessuna asserzione).
- Il piano originale metteva `NoOpMetricsOutPort` in `port.out` come classe package-private: due richieste
  incompatibili. L'implementazione l'aveva spostata in `application.service`; con la rimozione lo scostamento sparisce.
- Corretti i MINOR dei test (ricerca per sottostringa dell'IBAN, tag senza ordine fisso, codici di risposta in I6,
  caso con elemento null nella lista dei motivi, import e refuso).

# Piano fraud-06 — configurazione dei bean in fraud-bootstrap

> Piano CONDIZIONATO all'ambiguità A1 (bloccante): l'implementazione parte solo dopo la conferma dell'utente. Le altre ipotesi vanno confermate, ma non bloccano. Non esiste un file d'issue: il perimetro è "registra i bean", esteso come indicato dal coordinatore.

## Obiettivo
Registrare in `fraud-bootstrap` i bean `BlockedIbanOutPort` e `CheckTransferInPort`, con soglia e IBAN bloccati letti da `application.properties` (default `10000` e `IT60X0542811101000000000099`). Così `FraudApplicationTests` torna verde e un test di integrazione verifica `POST /fraud-check` contro i casi 1-16 del contratto.

## Ambiguità
1. **(BLOCCA) Doppio bean e soglia nel service.** `CheckTransferServiceImpl` è annotato `@Service` (decisione 4 di fraud-03) e ha il costruttore `(BlockedIbanOutPort, BigDecimal threshold)`. La scansione di `com.bank.fraud` lo istanzia da sé: oggi fallisce su `BlockedIbanOutPort`; registrata la porta, fallirebbe su `BigDecimal`. Un `@Bean CheckTransferInPort` in più produrrebbe due bean dello stesso tipo e `FraudCheckServiceDtoImpl` andrebbe in `NoUniqueBeanDefinitionException`. `@Value` nel service è vietato dal CLAUDE.md radice (solo `@Service`/`@Transactional`); in una `@Configuration` del bootstrap è ammesso (modello: `FraudConfig` del Transfer).
   - **(a) Ipotesi:** togliere `@Service` da `CheckTransferServiceImpl` (cambia solo l'annotazione e il suo import) e dichiarare il bean con `@Bean` in `FraudConfig`. I test di application e web adapter istanziano il service con `new`, quindi non cambiano. Revoca la decisione 4 di fraud-03 e si scosta dal Transfer, dove i service hanno `@Service`.
   - (b) Tenere `@Service` ed escludere `CheckTransferServiceImpl` con un `excludeFilters` su `FraudApplication`: si scosta da `TransferApplication`, l'annotazione resta ma non serve.
   - (c) Un bean `BigDecimal` iniettato per tipo: scartata, fragile.
   - (d) `@Value` sul costruttore del service: vietata.
   - (e) Cambiare il costruttore, ad es. in `(port, FraudPolicy)`: scartata, servirebbe modificare `CheckTransferServiceImplTest` e la decisione 3 di fraud-03.
2. **Nomi delle chiavi.** Il Transfer usa `fraud.stub.*`. **Ipotesi:** `fraud.threshold` e `fraud.blocked-ibans` (da env: `FRAUD_THRESHOLD`, `FRAUD_BLOCKED_IBANS`).
3. **Dove sta il default.** **Ipotesi:** i default stanno solo in `application.properties`, `@Value` senza fallback (`${fraud.threshold}`, `${fraud.blocked-ibans}`). Chiave mancante = avvio fallito (fail closed). Alternativa: fallback negli `@Value` uguali ai valori del file, con rischio di divergenza.
4. **`@Value` o `@ConfigurationProperties`.** `spring-boot-configuration-processor` non c'è in nessun pom. **Ipotesi:** `@Value` sui parametri del metodo `@Bean`, come nel modello del Transfer. Limite: `@Value` non legge la sintassi indicizzata.
5. **Formato della lista.** **Ipotesi:** una riga, valori separati da virgole; Spring toglie gli spazi ai bordi, spazi interni e minuscole li normalizza `Iban`. In `FraudConfig` si scartano le voci vuote o di soli spazi (`!s.isBlank()`), perché l'adapter le rifiuta (fraud-05). `fraud.blocked-ibans=` (vuoto) = nessun IBAN bloccato, avvio ok.
6. **IBAN o soglia non validi.** **Ipotesi:** l'avvio fallisce; causa radice `InvalidFraudRequestException` con messaggi fissi senza il valore. Una soglia non numerica (`abc`) fallisce nella conversione di Spring e il messaggio contiene il valore: è un parametro, non un dato di bonifico, accettato.
7. **Tipo di test di integrazione.** **Ipotesi:** `@SpringBootTest` (ambiente MOCK) con `@AutoConfigureMockMvc` (in Boot 4 package `org.springframework.boot.webmvc.test.autoconfigure`, da verificare). Nessuna porta, nessun Docker, nessuna dipendenza nuova (`spring-boot-starter-webmvc-test` già presente). MockMvc senza stampa (`print = MockMvcPrint.NONE`), altrimenti un fallimento stampa corpi con IBAN.
8. **Copertura end-to-end.** **Ipotesi:** tutti i casi 1-16 anche nel bootstrap, pur ripetendo in parte i test standalone del web adapter: qui girano il `JsonMapper` di Boot (Jackson 3), la Bean Validation reale e la configurazione reale. 500/503 non provocabili end-to-end: fuori perimetro.
9. **Log.** **Ipotesi:** nessun logger aggiunto. Un test con `OutputCaptureExtension` verifica che durante le richieste non finiscano in output IBAN o importi.
10. **`FraudApplicationTests`.** Non si modifica; tutti e 4 i test devono diventare/restare verdi (`serverPortIs8081` resta verde perché l'ambiente è MOCK).
11. **Numerazione del contratto.** Nella sezione 11 il numero 16 compare due volte (lato Fraud "IBAN uguali", lato Transfer "risponde ALLOWED"). Qui "caso 16" = lato Fraud. Correzione del contratto fuori perimetro, solo segnalata.
12. **Branch e file.** Lavoro sul branch `fraud-02-dominio`, con fraud-05 non committato. Nome del piano: `docs/plans/fraud-06-bootstrap-config.md`.

## Modifiche per modulo
**`fraud-application`** (solo con A1 = a; `pom.xml` invariato)
| File | Responsabilità |
|---|---|
| `com.bank.fraud.application.service.CheckTransferServiceImpl` (modifica) | Si toglie `@Service` e l'import `org.springframework.stereotype.Service`. Nient'altro. |

**`fraud-bootstrap`** (`pom.xml` invariato; Spring Boot arriva in modo transitivo da `fraud-web-adapter`)
| File | Package | Responsabilità |
|---|---|---|
| `src/main/java/com/bank/fraud/config/FraudConfig.java` (nuovo) | `com.bank.fraud.config` | `@Configuration`, package-private come nel Transfer. `@Bean BlockedIbanOutPort blockedIbanOutPort(@Value("${fraud.blocked-ibans}") List<String> blockedIbans)`: scarta le voci vuote, poi `new InMemoryBlockedIbanOutPortImpl(filtrate)`. `@Bean CheckTransferInPort checkTransferInPort(BlockedIbanOutPort port, @Value("${fraud.threshold}") BigDecimal threshold)`: `new CheckTransferServiceImpl(port, threshold)`. Nessun log, nessuna conversione in `Iban` (la fa l'adapter). |
| `src/main/resources/application.properties` (modifica) | — | Aggiunge `fraud.threshold=10000` e `fraud.blocked-ibans=IT60X0542811101000000000099`. `spring.application.name` e `server.port=8081` restano. Nessuna proprietà `spring.datasource`. |
| `src/test/java/com/bank/fraud/config/FraudConfigTest.java` (nuovo) | `com.bank.fraud.config` | `ApplicationContextRunner` con `FraudConfig` e proprietà esplicite: varianti di configurazione e fallimenti all'avvio. |
| `src/test/java/com/bank/fraud/FraudCheckEndToEndTest.java` (nuovo) | `com.bank.fraud` | `@SpringBootTest` + `@AutoConfigureMockMvc(print = NONE)` + `OutputCaptureExtension`: casi 1-16 sulla configurazione di default. |
| `src/test/java/com/bank/fraud/FraudApplicationTests.java` | — | **Non modificato.** |

Moduli non toccati: domain, web-adapter, persistence-adapter, contratto, transfer-service.

## Test attesi
Dati: `BLOCKED = IT60X0542811101000000000099`, `IBAN_1 = …0001`, `IBAN_2 = …0002`, `ID = 3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93`, `BLOCKED_MESSY = " it60 x054 2811 1010 0000 0000 099 "`.

**Ordine TDD** (da `fraud-service/`): 0) `.\mvnw.cmd test` come punto di partenza: solo `FraudApplicationTests.contextLoads` rosso. 1) `FraudConfigTest` C1 (rosso: `FraudConfig` non esiste) → crea `FraudConfig`; poi C2-C7 ed E1-E6. 2) `FraudApplicationTests` ancora rosso → applica A1 e aggiorna `application.properties` → verde. 3) `FraudCheckEndToEndTest` I1-I22; se un caso fallisce per colpa del web adapter (Jackson di Boot diverso dallo standalone), ci si ferma e si segnala senza correggere fuori perimetro. 4) `.\mvnw.cmd test` completo, senza Docker.

**FraudConfigTest** (`ApplicationContextRunner`, `withUserConfiguration(FraudConfig.class)`)
- C1 threshold `10000`, blocked `BLOCKED` → un solo bean per `BlockedIbanOutPort` e `CheckTransferInPort`; `findBlocked({BLOCKED, IBAN_1})` = `{BLOCKED}`
- C2 blocked `BLOCKED, IBAN_2` (spazio dopo la virgola) → `{BLOCKED, IBAN_2}` su `{BLOCKED, IBAN_1, IBAN_2}`
- C3 blocked vuoto → avvio ok; `findBlocked({BLOCKED})` vuoto
- C4 blocked `,BLOCKED,,   ,` → avvio ok; solo `BLOCKED` bloccato
- C5 blocked `BLOCKED_MESSY` → `{BLOCKED}`
- C6 threshold `5000`, `6000.00` EUR → REJECTED `["Amount must not exceed 5000 EUR"]`
- C7 threshold `10000.00`, `10000.01` → "Amount must not exceed 10000 EUR"
- E1 blocked `NOT-AN-IBAN` → avvio fallito, causa radice `InvalidFraudRequestException`
- E2 blocked `BLOCKED,IT60X054281110100000000009` (26 caratteri) → avvio fallito; nessun messaggio della catena contiene la stringa
- E3 threshold `0`, `-1`, `10.001` → avvio fallito, causa radice `InvalidFraudRequestException`
- E4 threshold `abc` / vuoto → avvio fallito
- E5 `fraud.threshold` assente / `fraud.blocked-ibans` assente → avvio fallito (ipotesi A3)
- E6 contesto avviato → esattamente 1 bean `CheckTransferServiceImpl` (nessun doppio bean)

**FraudCheckEndToEndTest** (proprietà di default, `Content-Type: application/json`, `transferId = ID`)
- I1 (1) `1000.25` EUR, IBAN_1 → IBAN_2 → 200 ALLOWED, `reasons` assente, `transferId = ID`
- I2 (2) `10000.00` → 200 ALLOWED; I3 (3) `10000.01` → 200 REJECTED "Amount must not exceed 10000 EUR"
- I4 (4) mittente BLOCKED; I5 (5) destinatario BLOCKED
- I6 (6) entrambi bloccati: classe annidata con `properties = "fraud.blocked-ibans=BLOCKED,IBAN_2"` → Source poi Destination
- I7 (7) `20000.00` + mittente BLOCKED → importo poi mittente
- I8 (8) amount `0`; I9 (9) `10.001`; I10 (10) senza `currency` → `details` = `["currency: must not be null"]`; I11 (11) `sourceIban` vuoto → 400 `INVALID_REQUEST`
- I12 (12) `USD` → 400 `UNSUPPORTED_CURRENCY`, "Only EUR is supported"
- I13 (13) corpo `testo qualunque` → 400 `MALFORMED_REQUEST`; I14 (14) `text/plain` → 415 `UNSUPPORTED_MEDIA_TYPE`
- I15 (15) caso 3 due volte → corpi identici
- I16 (16) `IBAN_1` e `" it60x0542811101000000000001 "` → 400 `INVALID_REQUEST`
- I17 `amount` stringa `"100.25"` → 400 `INVALID_REQUEST` `["amount: must be a number"]` (verifica `StrictDecimalDeserializer` con il mapper di Boot)
- I18 ogni risposta I1-I17: `Content-Type` `application/json`
- I19 ogni errore I8-I17: corpo senza IBAN_1, IBAN_2, BLOCKED, importo inviato, "Exception", "at com."
- I20 output catturato durante le richieste: senza IBAN_1, IBAN_2, BLOCKED, `1000.25`, `10000.01`, `20000.00`
- I21 transferId `"abc"` restituito identico
- I22 `Environment`: `fraud.threshold` = `10000`, `fraud.blocked-ibans` = `BLOCKED`; nessun `DataSource`

**FraudApplicationTests** (esistente, non modificato): `contextLoads`, `serverPortIs8081`, `applicationNameIsFraud`, `noDataSourceBeanIsPresent` tutti verdi.

## Criteri di accettazione
- `.\mvnw.cmd test` da `fraud-service/` in BUILD SUCCESS senza Docker, compresi i 4 test esistenti di `FraudApplicationTests`, non modificati.
- Nessun `pom.xml` modificato, nessuna dipendenza nuova.
- Nel contesto un solo `CheckTransferInPort` (un `CheckTransferServiceImpl`) e un solo `BlockedIbanOutPort` (un `InMemoryBlockedIbanOutPortImpl`). Nessun `DataSource`.
- `@Value` solo in `FraudConfig` (bootstrap). Nessun `@Value` né import Spring diverso da `@Service`/`@Transactional` in application; nessun import Spring nel persistence adapter.
- `application.properties` contiene `server.port=8081`, `fraud.threshold=10000`, `fraud.blocked-ibans=IT60X0542811101000000000099`.
- IBAN o soglia non validi fanno fallire l'avvio, e il messaggio non riporta l'IBAN.
- Casi 1-16 del contratto verdi end-to-end, con i testi dei motivi alla lettera.
- Nessun logger aggiunto; I20 verde.
- Nessun import di `com.bank.transfer`. Domain, web adapter, persistence adapter, contratto e transfer-service non modificati; in application cambia solo l'annotazione (A1).

## Rischi e fuori perimetro
**Rischi**: A1 revoca una decisione dell'utente (fraud-03, decisione 4), con (b) cambierebbe invece `FraudApplication`; il `JsonMapper` di Boot e il MockMvc standalone del web adapter possono comportarsi diversamente (scala di `amount`, `StrictDecimalDeserializer`, corpi vuoti): se I8-I17 falliscono, il difetto è nel web adapter e diventa un'issue separata; `@Value` non supporta liste indicizzate né YAML; la stampa di MockMvc in caso di fallimento mostrerebbe gli IBAN (va disattivata); `OutputCaptureExtension` cattura anche i log di avvio (asserzioni solo sull'output delle richieste); `@AutoConfigureMockMvc` ha cambiato package in Boot 4.1.1 (da verificare prima del test); il bootstrap dipende da Spring Boot solo in modo transitivo; il numero 16 è duplicato nel contratto; lavoro su `fraud-02-dominio` insieme a fraud-05 non committato; dipendenze nuove: nessuna.
**Fuori perimetro**: logging e audit (anche di `transferId` ed esito); 503/500 end-to-end; Actuator; tabella o file esterni per gli IBAN bloccati; JPA, Flyway, DataSource; modifiche a contratto, dominio, web adapter, persistence adapter, transfer-service (compreso il passaggio del Transfer dallo stub alla chiamata HTTP); correzione della numerazione del contratto; commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **A1 = (a):** si toglie `@Service` (e il suo import) da `CheckTransferServiceImpl`; il bean `CheckTransferInPort` è dichiarato con `@Bean` in `FraudConfig`. Revoca la decisione 4 di fraud-03 (`@Service` mantenuto).
2-12. Ipotesi del piano confermate (nessuna obiezione).

## Scostamento emerso in implementazione (accettato dal reviewer)
Il parametro del bean `BlockedIbanOutPort` in `FraudConfig` è `@Value("${fraud.blocked-ibans}") String[]` e non `List<String>` (ambiguità 5 / tabella `fraud-bootstrap`). Motivo: nel contesto minimo di `ApplicationContextRunner` (FraudConfigTest C2, C4) non c'è un `ConversionService` e `List<String>` non veniva diviso sulla virgola. `String[]` divide sulle virgole e toglie gli spazi ai bordi sia con l'editor di default sia con la conversione di Boot; proprietà vuota = array vuoto = nessun IBAN bloccato. Il filtro `isBlank` resta in `FraudConfig`.

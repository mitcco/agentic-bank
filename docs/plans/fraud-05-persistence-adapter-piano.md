# Piano fraud-05 — adapter in uscita del fraud-service (in memoria)

> Il nome `fraud-05-persistence-adapter.md` è occupato dal testo dell'issue; questo piano usa il suffisso `-piano`.
> Nessuna ambiguità blocca il piano (l'issue decide già i punti critici); le ipotesi vanno comunque confermate prima di implementare.

## Obiettivo
Implementare in `fraud-persistence-adapter` una classe Java pura che implementa `BlockedIbanOutPort` usando un insieme immutabile di `Iban`, costruito da una `Collection<String>` di configurazione. Senza Spring, senza database, senza log.

## Ambiguità (ipotesi da confermare)
1. **Nome della classe.** L'issue propone `InMemoryBlockedIbanRepository` "o il nome che il Transfer usa". Nel Transfer lo schema è `InMemory<NomePorta>Impl` (`InMemoryAccountRepositoryOutPortImpl`). **Ipotesi:** `InMemoryBlockedIbanOutPortImpl`.
2. **Annotazioni Spring nel modello Transfer.** Le classi in memoria del Transfer hanno `@Repository` e `@Profile("inmemory")`. **Ipotesi:** non si copiano (come chiede l'issue); nessuna annotazione; il bean lo registrerà `fraud-bootstrap` con un `@Bean`, come `transfer-bootstrap/.../config/FraudConfig`.
3. **"Nessuna dipendenza da Spring" e classpath reale.** Il pom di `fraud-persistence-adapter` ha solo `fraud-application` + JUnit/AssertJ test. `fraud-application` dichiara `spring-context` e `spring-tx` in compile, quindi Spring arriva comunque in modo transitivo e il compilatore non impedisce un `@Repository`. Escluderlo richiederebbe `<exclusions>` nel pom (modifica non richiesta). **Ipotesi:** pom invariato; il divieto = nessun import `org.springframework`, verificato con ricerca testuale.
4. **Firma della porta.** Quella reale: `Set<Iban> findBlocked(Set<Iban> ibans)`; Javadoc "sottoinsieme, mai `null`". **Ipotesi:** la porta non si tocca.
5. **Voci vuote nella configurazione.** Nel Transfer `FraudConfig` filtra le stringhe vuote (`!s.isBlank()`); `new Iban("")` lancia `InvalidFraudRequestException`. **Ipotesi:** l'adapter è rigido: voce vuota/di soli spazi → fallimento della costruzione. Filtrare spetta al bootstrap (da annotare per quella issue). Alternativa: l'adapter ignora le voci vuote in silenzio.
6. **Valori `null`.** Collezione `null` → `NullPointerException`; elemento `null` → `InvalidFraudRequestException` (passa da `new Iban(null)`; la conversione va fatta prima di `Set.copyOf`, che lancerebbe NPE); `findBlocked(null)` → NPE; candidato contenente `null` → NPE (fail closed, come decisione 10 di fraud-03).
7. **Bootstrap rosso.** `FraudApplicationTests.contextLoads` resta rosso (manca il bean della porta e la soglia): coerente con la decisione 4 di fraud-03 e con il "fuori perimetro" dell'issue. L'adapter non legge proprietà: le riceve dal bootstrap. **Ipotesi:** bootstrap non toccato; build verde misurata su `-pl fraud-persistence-adapter -am`.
8. **Nome del file del piano:** `fraud-05-persistence-adapter-piano.md`.

## Modifiche per modulo
**`fraud-persistence-adapter`** — unico modulo toccato; `pom.xml` invariato; oggi senza sorgenti.

| File | Package | Responsabilità |
|---|---|---|
| `src/main/java/com/bank/fraud/persistence/port/out/persistence/impl/inmemory/InMemoryBlockedIbanOutPortImpl.java` | `com.bank.fraud.persistence.port.out.persistence.impl.inmemory` | `public class InMemoryBlockedIbanOutPortImpl implements BlockedIbanOutPort` |
| `src/test/java/com/bank/fraud/persistence/port/out/persistence/impl/inmemory/InMemoryBlockedIbanOutPortImplTest.java` | stesso package | Test JUnit 5 + AssertJ, package-private, niente Spring, Mockito, Docker |

Dettaglio di `InMemoryBlockedIbanOutPortImpl`:
- Campo `private final Set<Iban> blocked`.
- Costruttore `public InMemoryBlockedIbanOutPortImpl(Collection<String> blockedIbans)`: 1) `requireNonNull` sulla collezione; 2) ogni voce → `new Iban(s)` (normalizzazione e validazione solo nel dominio, nessuna regex duplicata); 3) raccolta in insieme immutabile (`Collectors.toUnmodifiableSet()` o `Set.copyOf`): copia difensiva, i duplicati spariscono dopo la normalizzazione.
- `@Override public Set<Iban> findBlocked(Set<Iban> ibans)`: `requireNonNull`, poi `ibans.stream().filter(blocked::contains).collect(toUnmodifiableSet())`. Insieme vuoto immutabile se nessun candidato è bloccato; non modifica l'insieme in ingresso.
- Niente log, `toString` personalizzato, annotazioni; nessun import fuori da `java.*`, `com.bank.fraud.domain.*`, `com.bank.fraud.application.port.out.*`.

## Test attesi
Dati: `BLOCKED = "IT60X0542811101000000000099"`, `IBAN_1 = "IT60X0542811101000000000001"`, `IBAN_2 = "IT60X0542811101000000000002"`, `BLOCKED_MESSY = " it60 x054 2811 1010 0000 0000 099 "`.

**Ordine TDD** (da `fraud-service/`, `.\mvnw.cmd test -pl fraud-persistence-adapter -am` a ogni passo): 1) B1 rosso → crea classe con costruttore e `findBlocked` → verde; 2) aggiungi B2-B11 una alla volta (probabilmente già verdi: verificare che fallirebbero con un'implementazione sbagliata, es. senza copia difensiva o con `HashSet` mutabile); 3) aggiungi E1-E8 e completa i controlli `null` e l'ordine "conversione prima della copia".

**Funzionamento**
- B1 config `[BLOCKED]`, `findBlocked({BLOCKED, IBAN_1})` → `{Iban(BLOCKED)}`
- B2 config `[BLOCKED]`, `findBlocked({IBAN_1, IBAN_2})` → vuoto
- B3 config `[BLOCKED]`, `findBlocked({})` → vuoto
- B4 config `[]`, poi `findBlocked({BLOCKED, IBAN_1})` → nessuna eccezione, vuoto
- B5 config `[BLOCKED_MESSY]`, `findBlocked({new Iban(BLOCKED)})` → `{Iban(BLOCKED)}`
- B6 config `[BLOCKED, BLOCKED_MESSY]` → nessuna eccezione, risultato di 1 elemento
- B7 config `[BLOCKED, IBAN_2]`, `findBlocked({BLOCKED, IBAN_1, IBAN_2})` → `{BLOCKED, IBAN_2}`
- B8 `ArrayList` config `[BLOCKED]`; dopo la costruzione `clear()` e `add(IBAN_1)`; `findBlocked({BLOCKED, IBAN_1})` → `{BLOCKED}`
- B9 risultato non vuoto: `add`, `remove`, `clear` → `UnsupportedOperationException`
- B10 risultato vuoto: `add(Iban(IBAN_1))` → `UnsupportedOperationException`
- B11 `HashSet` candidato `{BLOCKED, IBAN_1}`, `findBlocked` due volte → stesso risultato, candidato non modificato

**Errori**
- E1 config `["NOT-AN-IBAN"]` → `InvalidFraudRequestException`
- E2 config `[BLOCKED, "IT60X054281110100000000009"]` (26 caratteri) → `InvalidFraudRequestException` (basta una voce errata, anche dopo voci valide)
- E3 config `[""]` e `["   "]` → `InvalidFraudRequestException`
- E4 config `Arrays.asList(BLOCKED, null)` → `InvalidFraudRequestException`, non NPE
- E5 config `null` → `NullPointerException`
- E6 voce a 26 caratteri di E2 → il messaggio non contiene la stringa configurata né le sue prime cifre
- E7 `findBlocked(null)` → `NullPointerException`
- E8 `HashSet` candidato con `null` → `NullPointerException`, mai un risultato parziale

## Criteri di accettazione
- `.\mvnw.cmd test -pl fraud-persistence-adapter -am` da `fraud-service/` in BUILD SUCCESS senza Docker, compresi i test esistenti di domain e application.
- `FraudApplicationTests` resta rosso come prima (decisione 4 di fraud-03). Bootstrap e relativi test non toccati.
- Nessun `pom.xml` modificato, nessuna dipendenza nuova.
- Nel modulo, ricerca di `org.springframework`, `jakarta.persistence`, `Logger`, `slf4j`, `System.out` = zero risultati. Nessuna `resources/db`, migration, entità o repository.
- `BlockedIbanOutPort` invariata. Nessun import di `com.bank.transfer`.
- Il risultato di `findBlocked` è sempre non `null` e immutabile. La normalizzazione passa solo da `com.bank.fraud.domain.Iban`.
- Domain, application, web-adapter, contratto e transfer-service non modificati.

## Rischi e fuori perimetro
**Rischi**: Spring transitivo da `fraud-application` (il divieto di annotazioni va rispettato a mano); l'eccezione all'avvio è `InvalidFraudRequestException`, che per nome sembra un errore di richiesta (non arriva mai al web handler; messaggio del dominio fisso, `Iban.toString()` mascherato); una voce vuota nella proprietà fa fallire l'avvio se il bootstrap non la filtra (da scrivere nell'issue di configurazione); `toUnmodifiableSet`/`Set.copyOf` lanciano NPE su `contains(null)` (voluto, E8); il lavoro avviene sul branch `fraud-02-dominio` (da verificare se voluto); dipendenze nuove: nessuna.
**Fuori perimetro**: `fraud-bootstrap` (`@Configuration`/`@Bean`, `@ConfigurationProperties`/`@Value`, `application.properties` con soglia `10000` e IBAN `IT60X0542811101000000000099` di default, filtro delle voci vuote, correzione di `FraudApplicationTests`); test di integrazione; tabella o file esterni; JPA/Flyway/DataSource; adapter web; logging/audit; modifiche a porta, dominio, contratto, transfer-service.

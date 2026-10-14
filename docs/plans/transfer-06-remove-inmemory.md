## Piano transfer-06: rimozione del profilo `inmemory`

### Obiettivo
Rimuovere dal transfer-service tutto ciò che esiste solo per il profilo `inmemory`: il modulo `transfer-memory-adapter`, `StubFraudCheck`, le proprietà `fraud.stub.*` e le annotazioni `@Profile`. JPA e `HttpFraudCheckOutPortImpl` diventano le uniche implementazioni e il comportamento in produzione non cambia.

### Ambiguità
Nessuna ambiguità blocca il piano. Ogni punto riporta un'ipotesi, da confermare.

1. **`FraudConfigTest` va toccato per forza, ed è una modifica a un test esistente.** Il file `transfer-bootstrap/src/test/java/com/bank/transfer/config/FraudConfigTest.java` importa `StubFraudCheck` (riga 7). Due suoi test verificano proprio il comportamento che si toglie:
   - `withTheInmemoryProfileTheStubIsTheOnlyFraudCheckBean` (righe 37-49);
   - `withTheInmemoryProfileTheHttpPropertiesAreNotNeeded` (righe 51-58).

   Dopo la rimozione non compilano, e con loro non compila tutto il modulo di test del bootstrap. Ipotesi: si cancellano i due metodi e l'import, come previsto per i test di ciò che si toglie. Gli altri 5 test restano invariati, compreso il nome `withoutProfile...`. Alternativa: riscriverli come "con `inmemory` il bean resta HTTP", ma così cambia il loro significato. Meglio coprire questo caso con il nuovo test del punto 4.
2. **(a) Test del modulo `transfer-fraud-adapter`: verificato, sono indipendenti dallo stub.** Il grep di `StubFraudCheck` trova solo `StubFraudCheck.java`, `StubFraudCheckTest.java`, `FraudConfig.java` e `FraudConfigTest.java`. `HttpFraudCheckOutPortImpl` e `FraudRestClientFactory` non lo usano. Restano `HttpFraudCheckOutPortImplTest`, `HttpFraudCheckOutPortImplNetworkTest` e `FraudCheckHttpRequestDtoTest`. `FraudCheckHttpResponseDtoTest` non esiste. Il pom del modulo resta invariato: lo stub usava `Iban` e `FraudStatusEnum`, che arrivano già da `transfer-application`.
3. **(b) Altri test del Transfer: nessuno usa il profilo o lo stub.**
   - `CreateTransferConcurrencyTest` definisce un proprio `FraudCheckOutPort` lambda `@Primary`. Oggi convive con il bean HTTP e continuerà a farlo.
   - `FraudCheckEndToEndTest` usa un finto Fraud su `HttpServer` raggiunto tramite `fraud.base-url`.
   - `TransferApplicationTests` e `FraudDefaultPropertiesTest` non controllano `fraud.stub.*`.
   - I test di rest, application, domain e postgres non usano profili. `DemoDataSeederTest` e i `Jpa*Test` usano solo `@Import` delle classi.
   - Quindi nessuna modifica oltre al punto 1.
4. **(c) Test aggiuntivi: vanno decisi.** Oggi nessun test asserisce il tipo dei bean `AccountRepositoryOutPort` e `TransferRepositoryOutPort` nel contesto completo. `FraudConfigTest` asserisce il tipo HTTP solo con la `FraudConfig` isolata, non nel contesto completo.
   - Proposta: una nuova classe `TransferOutPortBeansTest` (vedi Test attesi), su H2 come gli altri test di integrazione.
   - Contro: aggiunge 1-2 contesti Spring alla build, con qualche secondo in più.
   - Alternativa: nessun test nuovo, affidandosi a `FraudConfigTest` e a `TransferApplicationTests`. Non è raccomandata, perché nessun test esistente dimostra che il profilo `inmemory` è ormai senza effetto.
5. **Javadoc di `DemoDataSeeder` (riga 17): "Loads the same demo data as the in-memory implementations".** Dopo la rimozione cita classi che non esistono più. L'issue chiede di lasciare "invariato il resto delle classi". Ipotesi: lo si corregge in "Loads the demo data, only if the database is still empty". Va confermato.
6. **`CLAUDE.md` radice, righe 30 e 37-39.** `memory` resta un esempio valido alla riga 30, perché `fraud-memory-adapter` esiste ed è un'implementazione reale. Anche la regola delle righe 37-39 resta valida in generale. Tre opzioni:
   - (A, minima) non toccare il `CLAUDE.md` radice;
   - (B) cambiare solo l'esempio: "(per esempio un database e un client HTTP verso un altro servizio)";
   - (C) togliere l'esempio tra parentesi.

   Ipotesi: B, perché la versione attuale richiama un caso del Transfer che non c'è più. Nota: il file risulta già modificato e non committato.
7. **`transfer-service/CLAUDE.md`, sezione dei package.** La regola dice `com.bank.<svc>.<tecnologia>`, ma l'adapter fraud sta in `com.bank.transfer.port.out.fraud.impl`, senza il segmento `fraud.` dopo `transfer`. È un'incoerenza che esiste già: la si segnala e non si corregge, è fuori perimetro.
8. **`docs/plans/transfer-04-outbox-adapter.md`** è il piano della prossima tappa, non ancora implementata (`OutboxNotificationOutPortImpl` non esiste). Cita il profilo in più punti, elencati sotto. Va deciso se lo aggiorna chi lo implementerà o una revisione separata. Questa issue non lo modifica.

### Modifiche per modulo

**`transfer-service` (pom padre), `transfer-service/pom.xml`**
- Togliere `<module>transfer-memory-adapter</module>` (riga 36).
- Togliere il blocco `dependencyManagement` di `transfer-memory-adapter` (righe 69-73).

**`transfer-memory-adapter`: si cancella tutta la cartella `transfer-service/transfer-memory-adapter/`.** Contiene:
- `pom.xml`;
- `com.bank.transfer.memory.port.out.memory.impl.InMemoryAccountRepositoryOutPortImpl`;
- `com.bank.transfer.memory.port.out.memory.impl.InMemoryTransferRepositoryOutPortImpl`;
- i test `InMemoryAccountRepositoryOutPortImplTest` (8) e `InMemoryTransferRepositoryOutPortImplTest` (6);
- l'eventuale `target/`.

Nessun altro modulo importa queste classi. Il pom porta solo `transfer-application`, JUnit e AssertJ, quindi il bootstrap non perde nessuna dipendenza transitiva.

**`transfer-postgres-adapter`**
- `JpaAccountRepositoryOutPortImpl`: togliere `@Profile("!inmemory")` (riga 15) e `import org.springframework.context.annotation.Profile` (riga 11). `@Repository` resta.
- `JpaTransferRepositoryOutPortImpl`: stessa modifica (righe 15 e 11).
- `DemoDataSeeder`: togliere `@Profile("!inmemory")` (riga 20) e l'import (riga 13). Il Javadoc dipende dall'ambiguità 5.
- Nessuna modifica a pom, migration e test.

**`transfer-fraud-adapter`**
- Cancellare `com.bank.transfer.port.out.fraud.impl.StubFraudCheck`.
- Cancellare `StubFraudCheckTest` (8 test), stesso package.
- `FraudRestClientFactory`, `HttpFraudCheckOutPortImpl`, i DTO e il pom restano invariati.

**`transfer-bootstrap`**
- `pom.xml`: togliere la dipendenza `transfer-memory-adapter` (righe 29-32).
- `com.bank.transfer.config.FraudConfig`:
  - togliere il metodo `stubFraudCheckOutPort` e `@Profile("!inmemory")` sul bean HTTP;
  - resta un solo `@Bean FraudCheckOutPort httpFraudCheckOutPort(@Value("${fraud.base-url}") String, @Value("${fraud.connect-timeout}") Duration, @Value("${fraud.read-timeout}") Duration)`, con il corpo invariato;
  - togliere gli import `StubFraudCheck`, `Iban`, `BigDecimal`, `List`, `Set`, `Collectors` e `Profile`.
- `src/main/resources/application.properties`: togliere le righe 2-3 (`fraud.stub.threshold` e `fraud.stub.blocked-ibans`). Il grep conferma che nessun altro le legge, nemmeno `docker-compose.yaml` o `.env.example`.
- Test `com.bank.transfer.config.FraudConfigTest`: togliere 2 metodi e un import (ambiguità 1).
- Test nuovo `com.bank.transfer.TransferOutPortBeansTest` (ambiguità 4).

**Documentazione viva**
- `transfer-service/CLAUDE.md`:
  - togliere la riga 24 (`transfer-memory-adapter`);
  - la riga 25 diventa "`transfer-fraud-adapter` | Client HTTP verso il Fraud (`HttpFraudCheckOutPortImpl` implementa `FraudCheckOutPort`, `FraudRestClientFactory`, DTO HTTP)";
  - togliere la riga 38 (`- Memory: memory.port.out.memory.impl.`).
- `CLAUDE.md` radice: secondo l'ambiguità 6, con `memory` che resta alla riga 30.
- `docs/plans/notification.md`: non cita né il profilo né lo stub né il modulo memory, quindi nessuna modifica. Anche `docs/log-agenti.md`, `docker-compose.yaml` e `.env.example` non li citano.

**Piani storici: solo da elencare, non si modificano**
- `docs/plans/transfer-fraud-adapter.md` (righe 8, 30, 57-58, 104)
- `docs/plans/transfer-01-fraud-adapter.md` (6, 16-17, 46, 62-63, 82, 117, 131, 149, 152)
- `docs/plans/transfer-02-outbox-migration-test.md` (43)
- `docs/plans/transfer-05-two-decimals-rule.md` (16, 32, 66, 126)
- `docs/plans/fraud-skeleton-maven.md` (82)
- `docs/plans/fraud-05-persistence-adapter.md` (12)
- `docs/plans/fraud-05-persistence-adapter-piano.md` (10-11)
- `docs/plans/fraud-06-bootstrap-config.md` (15)

`transfer-03-notification-port.md` non contiene riferimenti.

**`docs/plans/transfer-04-outbox-adapter.md`: cosa andrebbe aggiornato (non lo si modifica qui)**
- Riga 35 e riga 84: `@Repository @Profile("!inmemory")` diventa solo `@Repository`.
- Righe 39-42: il sotto-punto "Profilo inmemory" decade, con le opzioni 1 e 2. Il dubbio della riga 42 è ora confermato: il profilo non evitava Postgres.
- Riga 52: tra gli esempi di prefisso togliere `InMemory...`.
- Righe 100 e 157: togliere `transfer-memory-adapter` e "moduli memory".
- Riga 143: il test C2 (profilo `inmemory`) decade.
- Riga 145: `FraudConfigTest` non è più "invariato" rispetto a oggi, perché avrà 5 test.
- Riga 165: il rischio "Profilo inmemory" decade.
- Riga 177: il fuori perimetro "Implementazione in-memory di NotificationOutPort" decade.

### Test attesi
Si scrivono prima delle rimozioni.

**`com.bank.transfer.TransferOutPortBeansTest`** (nuovo, `transfer-bootstrap`). Usa `@SpringBootTest` con H2 come `CreateTransferConcurrencyTest` (`jdbc:h2:mem:outportbeans;DB_CLOSE_DELAY=-1`, `sa`, password vuota) e `fraud.base-url` di default. Il confronto dei tipi va fatto con `AopUtils.getTargetClass(bean)`, perché `@Repository` può creare un proxy.

| # | Dato | Quando | Allora |
|---|---|---|---|
| B1 | contesto completo, nessun profilo | avvio | `getBeansOfType(FraudCheckOutPort)` ha 1 elemento, di tipo `HttpFraudCheckOutPortImpl` (verde prima e dopo) |
| B2 | come B1 | avvio | `AccountRepositoryOutPort`: 1 elemento, `JpaAccountRepositoryOutPortImpl`. `TransferRepositoryOutPort`: 1 elemento, `JpaTransferRepositoryOutPortImpl` (verde prima e dopo) |
| B3 | come B1 | avvio | nessun bean ha una classe del package `com.bank.transfer.memory` (verde prima, perché il profilo è spento) |
| B4 (classe annidata `@Nested` oppure seconda classe con `spring.profiles.active=inmemory`) | profilo `inmemory` attivo | avvio | stessi tipi di B1-B2: il profilo non ha più effetto. **Rosso prima della rimozione** (oggi ci sono `InMemory*` e `StubFraudCheck`), verde dopo i passi 2 e 3 |
| B5 (errore) | come B1, ma `fraud.base-url=` | avvio | il contesto non parte. Già coperto da `FraudConfigTest.emptyBaseUrlFailsTheStartup`: non va duplicato |

Il tester deve inoltre confermare che esistono e sono verdi, senza modifiche:
- `TransferApplicationTests` (richiede Postgres);
- `CreateTransferConcurrencyTest`;
- `FraudCheckEndToEndTest`;
- `FraudDefaultPropertiesTest`;
- i 5 test che restano in `FraudConfigTest`.

**Modifica a un test esistente (motivata nell'ambiguità 1):** in `FraudConfigTest` si cancellano `withTheInmemoryProfileTheStubIsTheOnlyFraudCheckBean` e `withTheInmemoryProfileTheHttpPropertiesAreNotNeeded`. Restano `withoutProfileTheHttpAdapterIsTheOnlyFraudCheckBean`, `missingBaseUrlFailsTheStartup`, `emptyBaseUrlFailsTheStartup`, `unreadableReadTimeoutFailsTheStartup` e `missingTimeoutsFailTheStartup`.

**(e) Conteggio dei test**

| Modulo | Prima | Rimossi | Dopo (senza i test nuovi) |
|---|---|---|---|
| domain | 42 | 0 | 42 |
| application | 54 | 0 | 54 |
| rest | 15 | 0 | 15 |
| postgres | 24 | 0 | 24 |
| memory | 14 | 14 (8 `InMemoryAccount...`, 6 `InMemoryTransfer...`) | modulo assente |
| fraud-adapter | 90 | 8 (`StubFraudCheckTest`, tutti `@Test` non parametrizzati) | 82 |
| bootstrap | 22 | 2 (`FraudConfigTest`) | 20, poi 20 + N nuovi (con B1-B4 in 4 metodi: 24) |
| **Totale** | **261** | **24** | **237 + N** (con la proposta: 241) |

**(d) Ordine di esecuzione** (da `transfer-service/`)
0. Scrivere `TransferOutPortBeansTest`. B1-B3 sono verdi, B4 è rosso: è il rosso atteso.
1. In un solo passo: cartella `transfer-memory-adapter`, `<module>` e `dependencyManagement` nel pom padre, dipendenza in `transfer-bootstrap/pom.xml`. Non vanno separati:
   - se resta `<module>` senza la cartella, il reactor fallisce;
   - se resta la dipendenza nel bootstrap senza il modulo, Maven può risolverla da un jar vecchio in `~/.m2` (falso verde) oppure fallire.

   Dopo questo passo la build è verde senza profilo. B4 resta rosso, perché i Jpa sono ancora `!inmemory`.
2. Togliere `@Profile` da `Jpa*` e `DemoDataSeeder`. Verde, tranne la parte di B4 sull'antifrode.
3. Ripulire `FraudConfig` e `application.properties` e togliere i 2 metodi da `FraudConfigTest` (stesso passo). Poi cancellare `StubFraudCheck` e `StubFraudCheckTest`. Se lo stub si cancella prima, il bootstrap non compila né nel main né nei test. **Qui la build torna verde del tutto, B4 compreso.**
4. Documentazione.

Verifica finale: `.\mvnw.cmd clean test` con Postgres attivo. Il `clean` serve a non lasciare classi vecchie in `target`.

### Criteri di accettazione
- `.\mvnw.cmd clean test` da `transfer-service`, con Postgres attivo: tutto verde, 237 + N test, con i numeri per modulo della tabella.
- Un grep di `inmemory|InMemory|StubFraudCheck|fraud\.stub|transfer-memory` su `transfer-service/` (esclusi i `target`) non trova nulla.
- Nei pom solo righe tolte: il `<module>`, la voce `dependencyManagement` e la dipendenza del bootstrap. Nessuna dipendenza aggiunta.
- `FraudConfig` ha un solo `@Bean`, senza `@Profile`. Il contesto completo ha un solo `FraudCheckOutPort` (`HttpFraudCheckOutPortImpl`) e un solo bean JPA per ogni porta repository, con o senza `spring.profiles.active=inmemory`.
- Nessuna modifica a migration, contratti, fraud-service, `NotificationOutPort` e `transfer-kafka-adapter`. I test esistenti cambiano solo come descritto, cioè i 2 metodi di `FraudConfigTest`.
- Comportamento in produzione invariato: stessa URL, stessi timeout, stessa logica fail closed, stesso seed demo.

### Rischi e fuori perimetro
**Rischi**
- **Artefatto vecchio in `~/.m2`.** Se `transfer-memory-adapter` è mai stato installato, una dipendenza dimenticata nel bootstrap verrebbe risolta da lì senza errori. Per questo si controlla il grep su `transfer-memory` in tutti i pom.
- **Proxy di `@Repository`.** Le asserzioni sui tipi devono usare `AopUtils.getTargetClass` oppure `isInstanceOf`, che funziona con i proxy CGLIB.
- **Effetto silenzioso del profilo.** Dopo la rimozione, `-Dspring.profiles.active=inmemory` viene ignorato senza errori e l'applicazione richiede Postgres e il Fraud come di default. Chi lo usa ancora non riceve nessun avviso.
- **Tempo di build.** `TransferOutPortBeansTest` aggiunge 1-2 contesti Spring su H2 (B4 usa un contesto diverso).
- **Test meno indipendenti dalla rete.** Sparisce l'unico `FraudCheckOutPort` senza rete. I test futuri che ne avranno bisogno useranno un doppio locale, come `CreateTransferConcurrencyTest`.
- **Albero di lavoro sporco.** `CLAUDE.md` e altri file hanno già modifiche non committate: chi implementa deve fare attenzione a non mescolare i diff.
- Nessuna dipendenza nuova.

**Fuori perimetro**
- fraud-service e il suo `fraud-memory-adapter`.
- Migration, `NotificationOutPort` e l'adapter dell'outbox (tappa 4), compreso l'aggiornamento del suo piano.
- I contratti in `docs/contracts/`.
- La riscrittura dei piani storici.
- L'incoerenza di package dell'adapter fraud (ambiguità 7).
- Qualsiasi cambio di comportamento in produzione.
- Commit.

## Decisioni dell'utente (prevalgono sul resto del piano)
1. **Ambiguità 1:** si cancellano i due metodi `withTheInmemoryProfile...` di `FraudConfigTest` e l'import di `StubFraudCheck`.
2. **Ambiguità 4:** NESSUN test nuovo. `TransferOutPortBeansTest` e B1-B4 NON si scrivono. Il "test prima" si riduce a: cancellare i test di ciò che si rimuove e confermare la baseline dei test esistenti.
3. **Ambiguità 5:** il Javadoc di `DemoDataSeeder` diventa "Loads the demo data, only if the database is still empty". Richiesta aggiuntiva dell'utente "aggiungi più dati" (al seed demo): IN ATTESA di precisazione, NON fa parte di questa issue finché l'utente non risponde.
4. **Ambiguità 6:** opzione B per il `CLAUDE.md` radice: l'esempio sul mescolare tecnologie diventa "(per esempio un database e un client HTTP verso un altro servizio)"; `memory` resta tra gli esempi di nome.
5. **Ambiguità 8:** `docs/plans/transfer-04-outbox-adapter.md` lo aggiorna l'orchestratore dopo questa issue, non l'implementer.
6. Ruoli: il tester cancella i file di test di ciò che si rimuove (`InMemory*Test`, `StubFraudCheckTest`) e modifica `FraudConfigTest`; l'implementer cancella il resto (modulo `transfer-memory-adapter`, `StubFraudCheck`, annotazioni, pom, proprietà, documentazione) e non tocca nessun test.

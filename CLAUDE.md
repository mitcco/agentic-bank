# agentic-bank

Microservizi bancari dimostrativi, in un unico repository. Ogni servizio ha il
proprio `CLAUDE.md` nella sua cartella: leggilo prima di lavorare su quel servizio.

| Servizio | Stato |
|---|---|
| `transfer-service` | in sviluppo |
| `fraud-service` | da fare |
| `notification-service` | da fare |
| `frontend` (React) | da fare |

## Stack
Java 21, Spring Boot, Maven (wrapper `mvnw`), JUnit 5, AssertJ, MapStruct,
Flyway, PostgreSQL 17. Sviluppo su Windows: `.\mvnw.cmd`, lanciato dalla
cartella del servizio.

## Struttura standard di ogni microservizio
`<svc>` = nome breve del servizio, senza il suffisso `-service`
(es. `transfer`, `fraud`, `notification`). Package base: `com.bank.<svc>`.

Ogni servizio `<svc>-service/` replica esattamente la struttura di
transfer-service. Non inventare moduli, package o livelli diversi: se serve
una deviazione, chiedi prima.

| Modulo | Contenuto |
|---|---|
| `<svc>-domain` | Java puro. Solo JUnit e AssertJ come dipendenze |
| `<svc>-application` | Porte, service, command, DTO, mapper |
| `<svc>-<tecnologia>-adapter` | Un modulo per ogni tecnologia o sistema collegato (web, persistence, messaging...) |
| `<svc>-bootstrap` | Applicazione Spring Boot, configurazione dei bean, test di integrazione |

- Il pom padre `<svc>-service/pom.xml` elenca i moduli; tutti gli `artifactId`
  iniziano con `<svc>-`.
- La struttura dei package degli adapter è descritta in
  `transfer-service/CLAUDE.md` ed è il modello da replicare.

## Architettura esagonale
- `domain/`: Java puro. Vietato Spring, JPA, Jackson e qualsiasi framework.
- `application/port/in`: interfacce degli use case.
- `application/port/out`: interfacce di ciò che serve al servizio dall'esterno.
- `application/service`: implementa port.in, usa port.out. Può usare solo
  `@Service` e `@Transactional` di Spring, nient'altro.
- `application/dto`, `application/command`: contratto degli use case.
  Le porte in non espongono oggetti del dominio.
- Gli adapter implementano o chiamano le porte; mai tra loro.

## Confini tra servizi
- Un servizio non importa classi di un altro: si parlano solo tramite i
  contratti in `docs/contracts/`.
- Ogni servizio ha un proprio database; nessuno legge le tabelle di un altro.

## Convenzioni di nome
Porte `...InPort` / `...OutPort`; implementazioni `...ServiceImpl`.

## Convenzioni di dominio
- Value object: record immutabili con validazione nel costruttore.
- Le entità sono immutabili: un'operazione restituisce una nuova istanza.
- Confronto di `BigDecimal` con `compareTo` / `isEqualByComparingTo`, mai `equals`.

## Convenzioni di test
Il test sta nello stesso package della classe testata.

## Persistenza
Schema gestito solo da Flyway (`ddl-auto=validate`). Mai modificare una
migration esistente: ogni cambio è un nuovo `V<n>__descrizione.sql`.

## Regole di lavoro
- Prima i test, poi il codice. Non modificare i test esistenti per farli passare.
- Non aggiungere dipendenze ai pom.xml senza chiedere.
- Non committare: proponi le modifiche e aspetta.
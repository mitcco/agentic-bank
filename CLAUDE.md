# agentic-bank

Microservizi bancari: transfer-service, fraud-service,
notification-service, frontend React. Monorepo.

## Stack
Java 21, Spring Boot, Maven (wrapper: mvnw), JUnit 5, AssertJ, ArchUnit.
Sviluppo su Windows: usa `.\mvnw.cmd`.

## Architettura (transfer-service)
Esagonale. Package base: com.bank.transfer
- domain/: Java puro. Vietato Spring, JPA, Jackson e qualsiasi framework.
- application/port/in: interfacce degli use case (cosa fa il microservizio)
- application/port/out: interfacce di ciò che serve al dominio (di cosa ha bisogno il microservizio)
- application/service: implementa port.in, usa port.out
- adapter/in/*, adapter/out/*: implementano/chiamano le porte; mai tra loro

## Convenzioni di dominio
- Value object (Money, Iban) sono record immutabili con validazione nel
  costruttore.
- Anche Account è immutabile: charge/credit restituiscono un nuovo Account.
- Il controllo "origine diversa da destinazione" si fa sugli Iban.
- Iban normalizzato (spazi rimossi, maiuscolo) nel costruttore.
- Confronto BigDecimal con compareTo / isEqualByComparingTo, mai equals.

## Regole di lavoro
- Prima i test, poi il codice. Non modificare i test esistenti per farli passare.
- Dopo ogni modifica lancia `.\mvnw.cmd test`: ArchitectureTest deve restare verde.
- Non aggiungere dipendenze al pom.xml senza chiedere.
- Non committare: proponi le modifiche e aspetta.
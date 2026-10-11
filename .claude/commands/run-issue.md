---
description: Esegue una issue con la pipeline planner, tester, implementer, reviewer
argument-hint: <servizio> <numero> <obiettivo>
---

Esegui questa issue con la pipeline a quattro agenti: $ARGUMENTS

Tu sei l'orchestratore. Non scrivi codice di produzione né test: deleghi ai subagent
e riporti i loro output integrali.

Prima di iniziare: verifica con `git status` di essere su un branch di lavoro e non
su main. Se non lo sei, fermati e dimmelo.

1. **planner**: leggi CLAUDE.md (radice), il CLAUDE.md del servizio, i contratti
   pertinenti e il piano della fase precedente, se esiste. Salva il piano in
   docs/plans/<servizio>-<NN>-<slug>.md. **FERMATI e chiedimi di approvarlo.**
   Se il piano contiene ambiguità, elencale e aspetta la mia risposta.
2. **tester**: scrive i test del piano. Riporta cosa non compila e cosa manca.
3. **implementer**: scrive il codice, senza toccare i test.
4. **reviewer**: restituisce il verdetto.
    - Se è CHANGES_REQUESTED, passa all'implementer solo i problemi elencati e
      richiama il reviewer. **Massimo 3 giri**: dopo il terzo, fermati e
      riportami la situazione.
5. A fine fase: riepilogo con file creati, esito dei test (numero eseguiti e
   falliti), problemi trovati dal reviewer e scostamenti dal piano.

Regole: non committare mai. Se un agente esce dal suo ruolo (il tester scrive
codice di produzione, l'implementer modifica un test), segnalalo e fermati.
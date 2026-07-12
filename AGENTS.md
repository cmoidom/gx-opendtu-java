# AGENTS.md

Conventions pour tout agent IA (ou humain) qui reprend ce dépôt. Voir
[`ARCHITECTURE.md`](ARCHITECTURE.md) pour la conception détaillée avant de
modifier quoi que ce soit. Ce projet est un **portage Java** du projet Python
[gx-opendtu](https://github.com/cmoidom/gx-opendtu) -- en cas de doute sur
l'intention d'une règle, le projet Python d'origine (son `ARCHITECTURE.md`/
`AGENTS.md`) fait référence.

## Invariants à ne pas casser

- **Signe de la puissance réseau** : `/Ac/Power` positif = soutirage,
  négatif = injection (`grid/ModbusGridMeter.java`, `control/SoftTargetController.java`).
  Ne pas inverser sans mettre à jour toute la chaîne de signes dans
  `SoftTargetController.computeTarget`.
- **Installation monophasée** : le code ne lit qu'un seul registre de
  puissance réseau (820). Si le projet évolue vers du triphasé, ça touche
  `grid/ModbusGridMeter.java` (lecture par phase) et potentiellement
  `allocator/WaterFillAllocator.java` (éviter l'export sur une phase isolée)
  -- ce n'est pas un simple ajout.
- **Un seul mode de déploiement, jamais de branche D-Bus/Venus OS** :
  contrairement au projet Python, ce port ne tourne **jamais** sur le Cerbo
  GX lui-même -- toujours sur une VM Linux séparée, toujours via Modbus TCP.
  Ne pas réintroduire de branchement "grid.source" ou de dépendance
  D-Bus/Venus OS sans une raison forte et une discussion préalable.
- **Modbus TCP : unit ID 100 (`ModbusConstants.SYSTEM_UNIT_ID`), registre
  820, jamais l'instance VRM du compteur** (`grid/ModbusGridMeter.java`).
  Unit ID 100 = service agrégat `com.victronenergy.system`, toujours
  disponible sans configuration par site ; ne pas remplacer par l'unit ID du
  compteur lui-même (variable par installation) sans une bonne raison.
- **Registres 32 bits Modbus : mot de poids fort au registre de plus basse
  adresse** (`modbus/RegisterCodec.combineBigEndianUint32`). Toute nouvelle
  lecture de registre 32 bits doit passer par ce codec, pas réinventer
  l'assemblage inline.
- **Client Modbus TCP maison, pas de bibliothèque tierce** : le client
  (`modbus/ModbusTcpClient.java`) a été écrit à la main précisément pour
  éviter le risque de casse d'API qu'une dépendance externe (type pymodbus
  côté Python) introduirait dans le temps. Ne pas réintroduire une
  dépendance Modbus externe sans en discuter d'abord.
- **Types de limite OpenDTU non-persistants uniquement**
  (`OpenDTUClient.LIMIT_TYPE_ABSOLUTE_NONPERSISTENT` /
  `..._RELATIVE_NONPERSISTENT`). Les variants persistants écrivent en flash
  côté onduleur -- ne pas les utiliser dans une boucle qui tourne toutes les
  quelques secondes.
- **Asservissement doux et peu bavard** : toute modification de
  `SoftTargetController` doit préserver la quantification par palier
  (`max(step_absolute_w, step_relative_pct%)`) et la rampe (1 palier par
  cycle de décision maximum). Ne pas revenir à un envoi de commande à chaque
  tick "pour plus de réactivité" sans validation explicite.
- **Fail-safe** : toute perte de communication (Modbus ou OpenDTU) doit
  ramener les onduleurs à une limite basse et sûre plutôt que de laisser le
  service "en roue libre" (`ControlLoop.applyFailsafe`).
- **Priorité charge batterie : le repli en cas de SOC illisible est
  `injectionActive=true` (sûr), jamais `false`** (`ControlLoop.run`, autour
  de `BatterySocUnavailableException`). Ne jamais inverser ce défaut :
  `false` débloque les onduleurs à 100 % sans supervision, ce qui
  risquerait une vraie injection réseau si la batterie était en fait pleine.
- **Hystérésis batterie sans yoyo** : `BatteryFullHysteresis` n'a que deux
  seuils asymétriques (`activateAtPct` pour passer ON, `deactivateBelowPct`
  pour repasser OFF) avec une zone morte entre les deux. Ne pas la remplacer
  par un seuil unique -- c'est exactement le yoyo à éviter. Voir
  `BatteryHysteresisTest.noYoyoAround100OnceActive` et
  `doesNotReactivateUntilBackTo100AfterDeactivating`.

## Frontière testable / non testable

- `allocator/WaterFillAllocator.java` et `control/*.java` sont **purs** (pas
  d'I/O) : tout ajout de logique doit rester testable unitairement, sans
  mock de socket ni de réseau.
- `loop/ControlLoop`'s méthodes package-private (`decisionCycle`,
  `applyFailsafe`, `releaseForCharging`, `offStateInvertersPayload`,
  `sendManualOverride`, `manualOverridePayload`, `minInverterFloorWarning`)
  sont testées via un **`FakeOpenDTUApi`** (`gxopendtu.loop` test package,
  implémente `OpenDTUApi`) -- pas de vrai HTTP. `ControlLoop.run(...)`
  lui-même n'est **pas** unit-testé (comme `main.run()` côté Python) : il
  construit les vraies implémentations Modbus/HTTP à partir de la config et
  ne peut être validé qu'avec du matériel réel ou le mode `--dry-run`.
- `modbus/ModbusTcpClient.java` et `opendtu/OpenDTUClient.java` sont testés
  contre un **serveur factice en boucle locale** (`FakeModbusServer` pour le
  Modbus, `com.sun.net.httpserver.HttpServer` embarqué pour OpenDTU) --
  ça exerce le vrai encodage/décodage de trame ou de requête HTTP, mais pas
  un vrai Cerbo GX/OpenDTU. La logique pure de décodage
  (`modbus/RegisterCodec.java`) est en revanche testée directement, sans
  socket.
- Avant d'affirmer qu'un détail de l'API OpenDTU est correct, vérifier
  contre la version réellement installée sur le firmware cible : ce projet
  (comme son origine Python) a été conçu à partir de documentation publique
  mais **jamais testé sur du matériel réel**.
- **Le logging complet à chaque cycle (grid/opendtu/soc/consigne/allocation)
  n'est pas la même chose que la fréquence des requêtes HTTP.** `decisionCycle`
  logue l'état à chaque cycle de décision (5 s par défaut), qu'il y ait un
  changement ou non -- mais n'écrit vers OpenDTU que si `decision.changed()`
  (mode normal) et jamais en `--dry-run`. Ne pas confondre les deux en
  modifiant l'un pour "corriger" l'autre.

## Style / conventions Java de ce projet

- **Records** pour toute donnée immuable (`ControlDecision`, `LimitStatus`,
  `InverterInfo`, les nœuds de `AppConfig`, `GridMeter.EnergyReading`) --
  pas de classes avec getters manuels pour ces cas.
- **Exceptions non vérifiées** (`RuntimeException`) pour toutes les erreurs
  de ce projet (`ModbusException`, `GridMeterUnavailableException`,
  `BatterySocUnavailableException`, `OpenDTUException`) -- cohérent avec le
  choix Python de lever des exceptions simples plutôt que des codes
  d'erreur ; évite de faire remonter des `throws` en cascade dans les
  interfaces (`GridMeter`, `BatterySoc`, `OpenDTUApi`).
- **Pas de framework web** : `com.sun.net.httpserver.HttpServer` (JDK) est
  utilisé volontairement à la place d'un micro-framework -- routage manuel
  par contexte, dispatch GET/POST à la main dans chaque handler. Ne pas
  ajouter Javalin/Spark/etc. sans en discuter d'abord.
- **Aucune bibliothèque Modbus tierce** (voir invariants plus haut) : le
  client maison dans `modbus/` couvre les seuls besoins de ce projet (Read
  Holding Registers, function code 3).
- **Jackson** (`jackson-databind`) est la seule dépendance runtime hors JDK
  -- utilisé pour la config, les réponses OpenDTU (format dynamique
  `{"v":...}` ou nombre brut, voir `opendtu/JsonValues.java`) et
  `/status.json`.
- Pas de commentaires expliquant le "quoi" ; uniquement le "pourquoi" quand
  ce n'est pas évident (voir les commentaires existants dans
  `control/`/`loop/ControlLoop.java` pour le ton attendu).
- Tests : JUnit 5 + AssertJ, doublons de test écrits à la main (pas de
  Mockito) -- voir `FakeOpenDTUApi` (`loop/`) et `FakeModbusServer`
  (`modbus/`, servie en boucle locale sur socket réel).

## État de vérification

`mvn test` passe intégralement (125 tests). `mvn package` produit un jar
exécutable fonctionnel (`java -jar target/gx-opendtu-java.jar --help`,
`--dry-run` avec le serveur web démarré et `/dashboard`, `/`, `/status.json`
répondant correctement). Non encore validé : contre du vrai matériel
Victron/OpenDTU -- seul un Modbus TCP/OpenDTU factice a été utilisé pour ce
smoke test. Avant tout déploiement réel :
1. Valider en `--dry-run` contre une installation réelle (Cerbo GX +
   OpenDTU) avant de laisser le service piloter réellement les onduleurs.
2. Relire `README.md` pour la procédure d'installation complète sur la VM
   cible (JRE/JDK 21, unité systemd).

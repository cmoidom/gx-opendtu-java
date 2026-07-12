# Architecture

Portage Java de [gx-opendtu](https://github.com/cmoidom/gx-opendtu) (Python),
pour une installation photovoltaïque **monophasée** avec micro-onduleurs
Hoymiles pilotés par [OpenDTU](https://github.com/tbnobody/OpenDTU) et un
compteur réseau Victron sur un Cerbo GX.

## Contexte et contraintes

- **Installation monophasée** : une seule valeur de puissance réseau à
  surveiller, pas de logique par phase.
- **Un seul mode de déploiement** : contrairement au projet Python
  d'origine (qui supportait aussi une exécution directe sur le Cerbo GX via
  D-Bus, avec les contraintes Venus OS que ça implique -- flash limité,
  `dbus-python` préinstallé, pas d'accès internet garanti), ce port Java
  tourne **exclusivement sur une VM Linux séparée** (ex. Proxmox). La
  puissance réseau et le SOC batterie sont donc **toujours** lus via
  **Modbus TCP** distant -- il n'y a pas de branche D-Bus/Venus OS à
  maintenir, et les bibliothèques Java "normales" (HTTP client, JSON,
  Modbus) sont utilisables sans restriction.
- OpenDTU est piloté **exclusivement en HTTP REST**, jamais en MQTT.
- Le compteur réseau Victron VM-3P75CT (ou équivalent) est lu via l'agrégat
  système `com.victronenergy.system` en Modbus TCP -- pas de configuration
  par installation nécessaire pour ce point (voir plus bas).

## Composants

```
gx-opendtu-java/
├── pom.xml                             Maven, Java 21, jackson-databind + maven-shade-plugin
├── src/main/java/gxopendtu/
│   ├── Main.java                       point d'entree (--config, --dry-run), cablage
│   ├── LoggingSetup.java               java.util.logging, format proche de logging.basicConfig
│   ├── config/
│   │   ├── AppConfig.java              records (OpenDTUConfig, GridConfig, ModbusGridConfig,
│   │   │                                ControlConfig, CapacityProbeConfig, BatteryConfig,
│   │   │                                InverterConfig, WebConfig, LoggingConfig)
│   │   └── ConfigLoader.java           chargement + validation JSON (Jackson)
│   ├── modbus/                         client Modbus TCP maison (pas d'equivalent Python --
│   │   │                                pymodbus faisait ce travail bas niveau)
│   │   ├── ModbusTcpClient.java        trame MBAP + function code 3 (Read Holding Registers)
│   │   ├── ModbusException.java
│   │   ├── ModbusConstants.java        SYSTEM_UNIT_ID = 100 (com.victronenergy.system)
│   │   └── RegisterCodec.java          toSigned16, combineBigEndianUint32 (pur, teste sans socket)
│   ├── grid/
│   │   ├── GridMeter.java              interface: readGridPowerW(), readEnergyKwh()
│   │   ├── GridMeterUnavailableException.java
│   │   └── ModbusGridMeter.java        unit 100 / registre 820 (puissance), 2634+2636 (energie)
│   ├── battery/
│   │   ├── BatterySoc.java             interface: readSocPct(), readPowerW()
│   │   ├── BatterySocUnavailableException.java
│   │   └── ModbusBatterySoc.java       registre 843 (SOC), 842 (puissance batterie)
│   ├── opendtu/
│   │   ├── OpenDTUApi.java             interface (4 methodes utilisees par la boucle -- permet
│   │   │                                un faux client dans les tests, sans HTTP reel)
│   │   ├── OpenDTUClient.java          implementation HTTP (java.net.http.HttpClient)
│   │   ├── OpenDTUException.java
│   │   ├── InverterInfo.java, LimitStatus.java   records
│   │   └── JsonValues.java             extraction {"v":...} ou nombre brut
│   ├── control/                        pur, sans I/O -- portage ~1:1 de controller.py
│   │   ├── ControlMath.java            clamp/quantize/rampLimit
│   │   ├── GridPowerSmoother.java
│   │   ├── PIController.java
│   │   ├── SoftTargetController.java   (+ record imbrique ControlDecision)
│   │   ├── CapacityEstimator.java
│   │   └── BatteryFullHysteresis.java
│   ├── allocator/
│   │   └── WaterFillAllocator.java     repartition water-filling multi-onduleurs (pure)
│   ├── state/                          thread-safe, ephemere sauf StateStore
│   │   ├── LiveState.java              tampon circulaire pour le tableau de bord
│   │   ├── HourlyEnergyHistory.java    energie reseau horaire (graphique en barres)
│   │   ├── ManualOverride.java         forcage %, expiration 5 min
│   │   ├── InjectionModeOverride.java  AUTO/ON/OFF sticky
│   │   └── StateStore.java             persistance state.json (hysterese batterie)
│   ├── loop/
│   │   └── ControlLoop.java            orchestration complete (run + methodes testables
│   │                                    decisionCycle/applyFailsafe/releaseForCharging/...)
│   └── webui/
│       ├── WebUiServer.java            com.sun.net.httpserver.HttpServer, routage
│       ├── ConfigPageHandler.java      page de config ("/"), /save, /apply
│       ├── DashboardHandler.java       sert dashboard.html (resource statique)
│       ├── StatusJsonHandler.java      GET /status.json?since=
│       ├── FetchInvertersHandler.java  GET /fetch-inverters (proxy decouverte OpenDTU)
│       └── OverrideHandlers.java       POST /override/pct, /pct/clear, /mode
├── src/main/resources/webui/dashboard.html   repris quasi verbatim du Python (100% cote client)
├── src/test/java/gxopendtu/...         tests miroir de tests/ (JUnit 5 + AssertJ)
├── config/config.example.json          un seul exemple (toujours grid.modbus.*)
└── deploy/systemd/gx-opendtu-zero-export.service
```

`allocator/WaterFillAllocator` et la partie logique de `control/` sont
**purement fonctionnels** (pas d'I/O) : c'est ce qui les rend testables sans
matériel. `grid/ModbusGridMeter`, `battery/ModbusBatterySoc` et
`opendtu/OpenDTUClient` sont de fins wrappers d'I/O, sans logique métier.
`loop/ControlLoop.run(...)` construit ces implémentations à partir de la
config (`makeGridReader`/`makeBatteryReader`, privés) -- il n'y a plus de
choix D-Bus/Modbus à faire (c'est toujours Modbus).

`webui/` démarre un `com.sun.net.httpserver.HttpServer` (JDK, aucune
dépendance) sur `config.web.port` (8080 par défaut, `config.web.enabled`
pour désactiver). `HttpServer.start()` lance son propre thread d'écoute et
retourne immédiatement -- pas besoin d'envelopper manuellement dans un
thread, contrairement à `threading.Thread(target=server.serve_forever)`
côté Python. Il lit/écrit directement `config.json` mais ne touche pas à
l'état en mémoire de la boucle de contrôle : "Enregistrer" (`POST /save`)
écrit le fichier et affiche un message, sans redémarrer le service -- cohérent
avec le choix de ne rien recharger à chaud. Pas d'authentification (comme
l'API OpenDTU) : accessible à quiconque sur le LAN.

"Enregistrer et appliquer" (`POST /apply`) valide et écrit la config comme
`/save`, puis programme `System.exit(1)` (via un `Thread` avec
`Thread.sleep(500)` pour laisser la réponse HTTP partir avant que le process
ne meure) : pas de hot-reload en mémoire, on relance tout le process et on
laisse systemd (`deploy/systemd/`) le redémarrer avec la nouvelle config.
Code de sortie 1 (pas 0) pour rester compatible avec `Restart=on-failure`.

Le bouton de découverte des onduleurs appelle `GET /fetch-inverters?base_url=...`
côté serveur (`webui/FetchInvertersHandler`, pas d'appel direct navigateur →
OpenDTU, donc pas de souci CORS), qui délègue à `OpenDTUClient.listInverters()`
(`opendtu/OpenDTUClient.java`) : combine `/api/livedata/status` (serial, name)
et `/api/limit/status` (max_power) -- il n'existe pas d'endpoint
`/api/inverter/list` dans le firmware OpenDTU standard.

`OpenDTUClient` envoie un en-tête `Authorization: Basic ...` sur **chaque**
requête (GET et POST) dès que `username` est renseigné (`password` optionnel,
chaîne vide sinon) -- OpenDTU ignore simplement cet en-tête sur les endpoints
qui n'en ont pas besoin. Nécessaire dès que `/api/limit/config` (écriture)
exige une authentification -- ce qui est courant même quand les endpoints de
lecture n'en demandent pas. Sans ces identifiants dans ce cas, toute écriture
échoue en 401 -- y compris le repli fail-safe.

`state/LiveState` est un tampon circulaire thread-safe (`ArrayDeque` +
`ReentrantLock`, ~900 échantillons par défaut, soit environ 30 min au
`grid.read_interval_s` par défaut de 2s) rempli par `ControlLoop.run` à
chaque tick de la boucle rapide (`recordGrid`, toujours) et à chaque cycle
de décision (`updateDecision`, reporté sur chaque échantillon rapide jusqu'au
prochain cycle de décision). `webui/StatusJsonHandler` le lit seulement --
expose `GET /status.json?since=<epoch>` (récupération incrémentale) et
la page `/dashboard`, qui interroge cet endpoint toutes les 2s. L'état est
perdu à chaque redémarrage du service : c'est une vue en direct, pas un
historique persistant.

Quand `injection_control=OFF` (charge batterie prioritaire),
`ControlLoop.run` n'appelle pas `decisionCycle` -- `offStateInvertersPayload`
(package-private, `loop/ControlLoop.java`) lit quand même
`client.getLivePowerW()` et construit une entrée par onduleur avec
`allocated_w=null` et `acknowledged=null` mais `actual_w` réel et
`limit_relative_pct=100` (débridé) -- `dashboard.html` affiche ces `null`
comme "débridé (charge batterie)" plutôt que de les confondre avec
`acknowledged=false` ("en attente RF") ou un état "ok" normal. Un échec de
cette lecture (`OpenDTUException`) redonne une liste vide sans perturber la
boucle de déblocage à 100%.

## Convention de signe

`/Ac/Power` du compteur réseau : **positif = soutirage réseau (import),
négatif = injection réseau (export)**. La cible de régulation est de
maintenir cette valeur autour d'un petit seuil positif (`export_setpoint_w`,
défaut 30 W) -- jamais négative -- pour absorber le bruit de mesure et la
latence de la boucle sans jamais réellement basculer en export.

## Boucle de contrôle

Deux cadences découplées, exécutées dans une seule boucle Java
(`ControlLoop.run`, un seul thread, `Thread.sleep` entre chaque tick) :

- **Lecture** (`grid.read_interval_s`, défaut 2 s) : lit `/Ac/Power`, alimente
  un filtre exponentiel (`control/GridPowerSmoother`, `filtered += ema_alpha *
  (raw - filtered)`, `grid.ema_alpha` défaut 0,5).
- **Décision** (`control.decision_interval_s`, défaut 5 s) : c'est le seul
  moment où des requêtes HTTP peuvent partir vers OpenDTU.

**Pourquoi 5 s, et pourquoi ne pas chercher à réagir plus vite en logiciel** :
le vrai facteur limitant est la **rampe de puissance physique de l'onduleur
Hoymiles lui-même** (réglable uniquement via l'appli/DTU officiels Hoymiles,
pas via OpenDTU), de l'ordre de 0,5 %Pn/s ≈ 3 W/s pour un onduleur 600 W --
un swing complet prend alors ~200 s. Le palier logiciel par défaut (100 W ou
10 % toutes les 5 s) est déjà bien au-dessus de ce que l'onduleur peut
physiquement suivre. Voir l'ARCHITECTURE.md du projet Python pour les
sources détaillées (discussion communautaire OpenDTU-OnBattery #908).

À chaque cycle de décision (`SoftTargetController.computeTarget`,
`control/SoftTargetController.java`) :

```
error            = grid_power_avg - export_setpoint_w
delta            = PI(error)                      // kp*error + integrale (anti-windup clampee)
current_actual   = somme des puissances AC actuelles (GET /api/livedata/status?inv=)
raw_target       = clamp(current_actual + delta, 0, capacite_totale)

step             = max(step_absolute_w, step_relative_pct% * capacite_totale)
quantized        = round(raw_target / step) * step        // palier
next_target      = last_sent + clamp(quantized - last_sent, -step, +step)  // rampe: 1 palier/cycle max

si next_target n'a pas bouge d'au moins min_change_w depuis le dernier envoi :
    -> rien n'est envoye ce cycle (zero requete HTTP)
sinon :
    -> repartition + envoi (voir ci-dessous)
```

## Répartition multi-onduleurs (water-filling)

`WaterFillAllocator.waterFillAllocate(totalTargetW, serials, capacityEstimates, ...)`
(`allocator/WaterFillAllocator.java`) : répartition égalitaire, plafonnée par
la capacité connue de chaque onduleur, avec redistribution itérative du
surplus tant qu'il reste des onduleurs non saturés. `CapacityEstimator`
(`control/CapacityEstimator.java`) démarre à la puissance nominale déclarée
en config, abaisse le plafond quand un onduleur reste durablement sous sa
part allouée malgré une limite acquittée (`limit_set_status == "Ok"`), et le
relève progressivement via une sonde périodique (`capacity_probe.step_w`).

`min_inverter_pct` (`config.control.minInverterPct`, défaut 10%) est
appliqué en post-traitement dans `waterFillAllocate` : pour chaque onduleur
ayant une capacité réelle, on relève sa part à `min(capacité, min_inverter_pct%
* puissance_nominale)` si elle était plus basse -- y compris quand cette part
est exactement 0. **Ce seuil est prioritaire sur le zero-export strict** :
il peut causer une injection réseau réelle si mal réglé. `ControlLoop`
détecte ce cas (`minInverterFloorWarning`, package-private) et logue un
`WARNING` avec une valeur recommandée, exposé au tableau de bord.

## Contrôles manuels du tableau de bord

Deux mécanismes distincts, tous deux dans `state/`, tous deux lus une fois
par cycle de décision dans `ControlLoop.run` :

- **`InjectionModeOverride`** (enum `AUTO`/`ON`/`OFF`) : sticky, pas
  d'expiration. `ON`/`OFF` écrivent directement `BatteryFullHysteresis.setActive(...)`
  -- reprendre `AUTO` relance `hysteresis.update()` normalement à partir de
  l'état où `ON`/`OFF` l'a laissé. Survit à un redémarrage via `state.json`.
- **`ManualOverride`** (25/50/75/100%) : expire après `DEFAULT_DURATION_S`
  (5 min), pas de persistance. Contourne entièrement `decisionCycle` --
  n'a d'effet que si `injectionActive` est vrai ce cycle ; si faux (charge
  batterie prioritaire), le déblocage à 100% reste prioritaire et l'override
  est ignoré pour ce cycle (mais reste actif, réappliqué dès qu'`injectionActive`
  redevient vrai). Le fail-safe (perte du compteur réseau) est vérifié plus
  haut dans la boucle et `continue` avant d'atteindre cette logique.

## Persistance de l'hystérèse batterie (`state.json`)

`state/StateStore.java` écrit `{"injection_active": bool}` dans `state.json`,
à côté de `config.json`, chaque fois que `hysteresis.isActive()` change de
valeur. Lu au démarrage pour initialiser `BatteryFullHysteresis(active=...)` ;
en l'absence d'état persisté, démarre à `true` (curtailment) plutôt que
`false` -- une lecture SOC réelle corrige ça en un cycle si la batterie n'est
en fait pas pleine.

## API OpenDTU utilisée

- `GET /api/livedata/status?inv=<serial>` → puissance AC actuelle,
  **un appel par onduleur** (`OpenDTUClient.getLivePowerW`). L'appel nu (sans
  `?inv=`) ne renvoie jamais le détail AC/DC par onduleur -- confirmé contre
  la doc officielle OpenDTU et un onduleur produisant réellement 700+W qui
  relisait 0 par l'appel nu.
- `GET /api/limit/status` → `{serial: {limit_relative, max_power,
  limit_set_status}}` (`OpenDTUClient.getLimitStatus`, record `LimitStatus`).
  `limitSetStatus` passe `Pending` → `Ok` après acquittement RF.
- `GET /api/livedata/status` (sans `?inv=`) → utilisé **uniquement** par
  `listInverters()` pour serial/name.
- `POST /api/limit/config`, form field `data=<json>` :
  `{"serial", "limit_type", "limit_value"}`. Seuls les types
  **non-persistants** sont utilisés (`0` = absolu, `1` = relatif,
  `OpenDTUClient.LIMIT_TYPE_*_NONPERSISTENT`).
- Basic Auth optionnelle, envoyée sur toutes les requêtes dès qu'un
  `username` est renseigné.

## Lecture réseau/batterie (Modbus TCP)

`modbus/ModbusTcpClient.java` est un client Modbus TCP minimal écrit à la
main (trame MBAP + function code 3, Read Holding Registers) : connexion TCP,
timeout, décodage de la réponse (transaction ID vérifié, code fonction
d'erreur `0x80` détecté et transformé en `ModbusException`). Aucune
dépendance externe -- élimine le risque de casse d'API qu'a subi le projet
Python avec `pymodbus` (mot-clé d'unit ID renommé `unit=`/`slave=`/`device_id=`
selon les versions, voir son `_read_holding_registers` avec repli sur trois
variantes).

- **`grid/ModbusGridMeter`** : unit ID 100 (`ModbusConstants.SYSTEM_UNIT_ID`,
  agrégat `com.victronenergy.system`, toujours disponible sans configuration
  par site), registre **820** = puissance active Grid L1 (`int16`, W,
  négatif = injection). Énergie cumulée : registres **2634/2636** ("Total
  Energy from/to net", `uint32`, échelle 100 → kWh) -- appartiennent au
  service **propre du compteur réseau**, pas à l'agrégat système, d'où
  `energy_unit_id` optionnel (défaut = `unit_id`).
- **`battery/ModbusBatterySoc`** : registre **843** = SOC (`uint16`, 0-100%,
  pas de conversion de signe), registre **842** = puissance batterie
  (`int16`, positif = charge, négatif = décharge). Utilisé seulement pour
  l'affichage tableau de bord -- l'hystérèse ON/OFF ne se base que sur le SOC.
- **`modbus/RegisterCodec.java`** isole la logique pure (`toSigned16`,
  `combineBigEndianUint32`) : les registres 32 bits ont le **mot de poids
  fort au registre de plus basse adresse** -- oublier cette conversion est un
  bug classique qui masquerait silencieusement les valeurs d'export
  (négatives) ou fausserait complètement l'énergie cumulée.

## Sécurité / repli

- Échec de lecture Modbus répété (`FAILSAFE_AFTER_CONSECUTIVE_FAILURES=3`,
  `ControlLoop`) ou échec de communication OpenDTU : tous les onduleurs sont
  ramenés à 0 % (`applyFailsafe`). Le service ne reste jamais "en roue libre".
- La marge de sécurité `export_setpoint_w` (> 0) garantit que le point de
  fonctionnement visé reste toujours légèrement côté import, jamais export.

## Priorité charge batterie (hystérésis, optionnel)

- `control/BatteryFullHysteresis.java` (pure, testée) : verrou à deux
  seuils -- `activateAtPct` (défaut 100 %) pour passer `ON`, `deactivateBelowPct`
  (défaut 98 %) pour repasser `OFF`. La zone morte entre les deux évite le
  yoyo. Activation anticipée si un export réseau réel ≥
  `exportConfirmsFullW` (défaut 50 W) est observé alors que le SOC est déjà
  ≥ `deactivateBelowPct` -- preuve empirique que la batterie ne peut plus
  absorber le surplus.
- **Repli si le SOC est illisible** : `injectionControl` reste `true` (ON,
  comme si la batterie était pleine) plutôt que de débloquer les onduleurs
  sans supervision -- ne jamais inverser ce défaut.
- Fonctionnalité **désactivée par défaut** (`battery.enabled = false`).

## Déploiement

Unité systemd (`deploy/systemd/gx-opendtu-zero-export.service`) : jar unique
produit par `mvn package` (plugin `maven-shade-plugin`), lancé via
`java -jar gx-opendtu-java.jar --config /etc/gx-opendtu/config.json`.
`Restart=on-failure` -- le service redémarre automatiquement et relit la
config au prochain démarrage.

## Mode dry-run

`Main` accepte `--dry-run` : `ControlLoop.run` traverse `decisionCycle`/
`applyFailsafe` normalement (lecture Modbus, lecture OpenDTU, calcul PI +
water-filling) mais n'appelle jamais `OpenDTUApi.setAbsoluteLimitW`/
`setRelativeLimitPct`. Chaque cycle logue la valeur du compteur réseau, la
production actuelle vue par OpenDTU et la consigne calculée -- mécanisme de
validation recommandé avant de laisser le service piloter réellement les
onduleurs. Testé par `loop/ControlLoopDryRunTest.java` via un
`FakeOpenDTUApi` (pas de HTTP réel), port direct de `tests/test_dry_run.py`.

## Limites connues / non couvert

- Conçu pour une installation **monophasée** ; une extension triphasée
  nécessiterait de revoir la lecture du compteur (par registre/phase) et
  potentiellement la logique de répartition.
- Les appels **réellement réseau** (le vrai socket Modbus TCP dans
  `ModbusTcpClient`, le vrai `java.net.http.HttpClient` dans `OpenDTUClient`)
  ne sont testés qu'au niveau du décodage de trame / de la construction de
  requêtes (via un serveur Modbus/HTTP factice en boucle locale dans les
  tests) -- pas contre du matériel Victron/OpenDTU réel. Le mode `--dry-run`
  reste le moyen recommandé de valider le comportement sur une installation
  réelle.
- Le mot de passe OpenDTU est encore transmis/stocké en clair dans
  `config.json`, comme dans le projet Python d'origine -- pas de coffre-fort
  de secrets, cohérent avec l'absence d'authentification sur la page de
  configuration elle-même.
- `mvn test` passe (125 tests) et `mvn package` produit un jar exécutable
  vérifié en `--dry-run` (serveur web + boucle de contrôle démarrent
  correctement, fail-safe déclenché face à un Modbus/OpenDTU factice
  injoignable) -- reste à valider contre du vrai matériel Victron/OpenDTU
  avant tout déploiement réel.

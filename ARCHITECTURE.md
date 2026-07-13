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
dépendance) sur `config.web.port` (8080 par défaut) -- toujours actif,
contrairement au projet Python d'origine, pas d'option pour le désactiver.
`HttpServer.start()` lance son propre thread d'écoute et
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
perdu à chaque redémarrage du service : c'est une vue en direct sur les
30 dernières minutes, pas un historique persistant -- voir `stats/StatsStore`
ci-dessous pour la persistance long terme.

**Graphique SOC/tension/courant (exception délibérée à "jamais de double
axe")** : `dashboard.html`'s `drawChart()` supporte un mode multi-axes
(`opts.axes`, un axe par unité -- %, V, A) utilisé uniquement par le
graphique "SOC batterie", qui superpose SOC (axe gauche, 0-100%), tension
batterie (axe droit) et courant batterie (deuxième axe droit empilé), chacun
dans sa propre couleur de graduation pour rester lisible. C'est un écart
assumé par rapport à la règle usuelle "jamais d'axe Y double" : demandé
explicitement par l'utilisateur (trois grandeurs de la même batterie, sur le
même pas de temps, plutôt que trois graphiques séparés à comparer visuellement).
Les graphiques à un seul axe (réseau/batterie, puissance par onduleur)
utilisent toujours un unique axe Y classique -- `opts.axes` absent construit
un axe "default" synthétique en interne, donc leur code n'a pas changé.

### Persistance long terme des courbes (`stats/StatsStore`)

`stats/StatsStore.java` persiste les courbes du tableau de bord (réseau
brut/EMA, SOC, puissance batterie, puissance par onduleur, énergie horaire)
dans un fichier **SQLite** (`stats.db`, à côté de `config.json`/`state.json`)
-- indépendamment de `LiveState`/`HourlyEnergyHistory`, qui restent
éphémères et servent uniquement la vue temps réel (~30min/48h en mémoire).
Aucun service externe (pas d'InfluxDB) : un seul fichier, une seule
dépendance légère (`org.xerial:sqlite-jdbc`), cohérent avec la philosophie
"un seul jar déployable" du projet.

Trois tables : `samples` (grid_raw_w, grid_ema_w, soc_pct, battery_power_w,
battery_voltage_v, battery_current_a, injection_control, une ligne par `t`
-- les deux dernières colonnes ajoutées après coup via une migration
`ALTER TABLE` idempotente au démarrage, `ensureColumn`, pour ne pas casser un
`stats.db` déjà déployé), `inverter_samples` (une ligne par
onduleur par `t`), `hourly_energy` (miroir persistant de
`HourlyEnergyHistory.snapshot()`, réécrit intégralement -- `INSERT OR
REPLACE` -- à chaque écriture, donc auto-cicatrisant si une écriture a été
manquée). `INSERT OR REPLACE` partout : idempotent, une même clé (`t`,
`t+serial`, ou `hour`) écrase la ligne existante plutôt que de dupliquer.

**Deux résolutions (2026-07-13)** : à l'origine, `samples`/`inverter_samples`
n'étaient écrites qu'au rythme grossier `config.stats.interval_s` (5 min par
défaut) -- ce qui voulait dire qu'au-delà des ~30 dernières minutes (la
fenêtre de `LiveState`), impossible de zoomer sur un instant précis dans
`stats.db`, seulement une tendance à 5 min près. Mesuré empiriquement (voir
ci-dessous) : garder du 2s (la cadence du direct, `grid.read_interval_s`) sur
toute la rétention de 2 ans coûterait ~11,3 Go et une écriture continue
toutes les 2s pour toujours -- disproportionné pour un usage "zoomer sur ce
qui vient de se passer". Compromis retenu : deux résolutions dans les mêmes
tables.
- `ControlLoop.run` appelle `StatsStore#recordLatestSample` à **chaque tick
  de la boucle rapide** (même cadence que `LiveState.recordGrid`, plus du
  tout gatée par `stats.interval_s`) -- chaque échantillon du direct est
  donc persisté tel quel, pendant `config.stats.high_res_retention_days`
  (30 jours par défaut).
- Une fois par jour (même boucle que la purge ci-dessous),
  `StatsStore#downsampleOlderThan(highResCutoff, intervalS)` regroupe tout
  ce qui a dépassé cette fenêtre : un seul point conservé (le plus récent)
  par bucket de `stats.interval_s` secondes (`DELETE ... WHERE t NOT IN
  (SELECT MAX(t) ... GROUP BY t/intervalS)`), puis nettoie les lignes
  `inverter_samples` orphelines (dont le `t` n'a plus de ligne `samples`
  correspondante). Un no-op sur des données déjà grossières (un seul point
  par bucket, ou des lignes 5 min pré-existantes d'avant cette migration --
  aucune migration de schéma nécessaire, les anciennes lignes restent
  telles quelles).
- `config.stats.interval_s` change donc de sens : ce n'est plus "à quelle
  fréquence écrire un échantillon" (c'est continu désormais) mais "la
  largeur du bucket de regroupement une fois hors de la fenêtre haute
  résolution" -- documenté explicitement dans le hint de la page de config
  pour éviter la confusion.
- `config.stats.retention_days` (défaut 730 ≈ 2 ans) borne toujours la
  taille totale de la base, à la résolution grossière au-delà de la fenêtre
  haute résolution ; doit être `>= high_res_retention_days` (validé au
  chargement de la config, `ConfigLoader.parseConfig`).

**Tailles mesurées** (schéma réel, 6 onduleurs, ~383,7 octets par
échantillon complet -- 1 ligne `samples` + 6 lignes `inverter_samples`) :
5 min partout (ancien design) ≈ 77 Mo/2 ans ; 2s partout ≈ 11,3 Go/2 ans ;
2s pendant 30 jours + 5 min au-delà (design retenu) ≈ 570 Mo/2 ans.

**Écriture immédiate sur "Enregistrer et appliquer"** : `webui/ConfigPageHandler`
appelle `statsStore.persistSnapshot(liveState, energyHistory, inverterEnergyHistory)` juste avant
de programmer `System.exit(1)` -- l'échantillon lui-même est déjà écrit en
continu (voir ci-dessus), mais ça flush aussi immédiatement `hourly_energy`
plutôt que de le laisser jusqu'à `stats.interval_s` en retard. Cet appel
relit simplement le dernier échantillon déjà en mémoire dans `LiveState`
(`snapshotSince(0).latest()`) : la page web n'a jamais besoin d'accéder
directement aux lectures de la boucle de contrôle.

**Flush avant arrêt (tout chemin de redémarrage, pas seulement `/apply`)** :
`Main.main` enregistre un `Runtime.getRuntime().addShutdownHook(...)` qui
appelle `statsStore.persistSnapshot(...)` puis `statsStore.close()` à la
réception de `SIGTERM` -- couvre `systemctl restart`/`stop`, `update.sh`, un
redémarrage de VM, pas seulement le bouton "Enregistrer et appliquer" de la
page de config (qui, lui, flush explicitement avant son propre
`System.exit(1)`, voir ci-dessus). Vu que l'échantillon est désormais écrit
en continu, ce hook ne protège plus qu'`hourly_energy` (quelques secondes de
perte possible au pire, pas un `stats.interval_s` entier comme avant).

**Recharge de `LiveState` au démarrage (`LiveState.seedHistory`)** : le
tableau de bord live (`/status.json`) ne lit **que** `LiveState` (mémoire),
jamais `stats.db` -- donc sans backfill, chaque redémarrage du process
(`update.sh`, `systemctl restart`, un crash) affichait des graphiques
complètement vides jusqu'à ré-accumuler ~30 min de données en direct, même
si `stats.db` contenait 2 ans d'historique juste à côté. `Main.main` appelle
`liveState.seedHistory(statsStore.loadRecentSamples(LiveState.DEFAULT_MAX_SAMPLES))`
juste après avoir ouvert `StatsStore`, avant de démarrer `WebUiServer`/
`ControlLoop` : les `DEFAULT_MAX_SAMPLES` (900) échantillons les plus
récents de `stats.db` repeuplent le tampon circulaire -- désormais à la
résolution fine du direct (2s) tant qu'on est dans la fenêtre
`high_res_retention_days`, donc les 900 échantillons ne couvrent plus que
~30 min (comme le tampon `LiveState` lui-même), ce qui est en fait plus
cohérent qu'avant (le backfill n'a plus besoin d'être évincé progressivement
par du direct plus fin, il l'est déjà). `consigne_w`, `min_inverter_floor_warning` et
`recommended_min_inverter_pct` ne sont pas persistés dans `stats.db`
(propres à la boucle de décision, pas pertinents sur un historique long
terme) -- `loadRecentSamples` les renvoie donc à leur valeur "non défini".

**`HourlyEnergyHistory` est backfillée de la même façon** : le graphique
"Energie reseau (par heure)" lit `HourlyEnergyHistory` (mémoire, 48h), pas
`stats.db`, donc sans backfill il se serait vidé à chaque redémarrage
exactement comme `LiveState` avant `seedHistory`. `Main.main` appelle
`energyHistory.seedBuckets(statsStore.loadHourlyEnergy(cutoff))` juste après
le `seedHistory` de `LiveState`, avec `cutoff` = maintenant -
`HourlyEnergyHistory.DEFAULT_RETAIN_HOURS` (48h). `seedBuckets` repeuple le
tampon mais laisse volontairement `lastFromKwh`/`lastToKwh` à `null` :
`record()` n'a de toute façon aucune base de comparaison cumulative tant que
la boucle de contrôle n'a pas fait sa première vraie lecture après le
redémarrage, donc la seule différence avec un démarrage à froid est que le
tampon contient déjà l'historique des heures précédentes au lieu d'être vide.

**`InverterEnergyHistory` : énergie par onduleur en Wh (2026-07-13)** :
même principe que `HourlyEnergyHistory` mais par onduleur plutôt qu'agrégé
réseau. Source : le compteur `YieldDay` (Wh) que
chaque onduleur/OpenDTU calcule déjà lui-même (`INV.0.YieldDay` dans
`/api/livedata/status?inv=<serial>` -- confirmé sur une installation réelle :
c'est déjà le total de l'onduleur, somme de tous ses canaux DC/MPPT, pas la
part d'un seul canal) plutôt que d'intégrer nous-mêmes `actual_w` : plus
fiable, aucune dérive possible sur les coupures de polling, et ça ne
réinvente pas ce que l'onduleur fait déjà. `OpenDTUApi#getYieldDayWh` réutilise
le même endpoint par onduleur que `getLivePowerW` (déjà un GET par onduleur
et par cycle, voir plus haut) -- pas d'appel HTTP supplémentaire. `ControlLoop.run`
appelle `inverterEnergyHistory.record(client.getYieldDayWh(serials), now)` à
la même cadence que `energyHistory.record(...)` (chaque cycle de décision),
**quel que soit l'état ON/OFF/OVERRIDE** : les onduleurs continuent de
produire (et OpenDTU de compter YieldDay) même pendant la charge batterie
prioritaire ou un forçage manuel. `YieldDay` se remet à zéro chaque jour
(minuit local sur l'onduleur, pas configurable côté logiciel) -- un delta
négatif est donc attendu une fois par jour et simplement ignoré, comme un
reset de compteur pour `HourlyEnergyHistory` ; l'écart perdu est au pire un
cycle de décision, négligeable. Persisté dans une quatrième table SQLite,
`inverter_hourly_energy (hour, serial, wh)`, avec les mêmes conventions que
`hourly_energy` (`INSERT OR REPLACE`, backfillé au démarrage via
`loadInverterHourlyEnergy`, purgé par `pruneOlderThan`). Affiché en barres
groupées sur `dashboard.html` ("Energie par onduleur (par heure)"),
toujours en Wh (jamais basculé en kWh comme `fmtEnergy` le fait pour
l'énergie réseau) -- une décision explicite pour garder les valeurs
lisibles onduleur par onduleur, qui sont typiquement de quelques centaines
de Wh/heure.

**Affichage/masquage des courbes par clic sur la légende (2026-07-13)** :
chaque graphique de `dashboard.html` (SOC/tension/courant, réseau/batterie,
puissance par onduleur, énergie réseau, énergie par onduleur) a désormais
une légende cliquable -- `toggleSeries(chartId, key)` bascule une entrée
dans `hiddenSeries` (un `Set` par graphique, donc masquer un onduleur sur le
graphique de puissance n'affecte pas le graphique d'énergie) et redessine.
Les séries masquées sont retirées de la liste **avant** `drawChart`/
`drawBarChart` (`visibleSeries`/`visibleBarSeries`), pas juste rendues
invisibles visuellement -- donc elles n'influencent plus non plus l'échelle
Y auto-calculée de leur axe, ni les tooltips/points de survol.

**Ne rompt pas les courbes du dashboard sur les données rechargées**
(`dashboard.html`) : `drawChart` refuse de relier deux points par une ligne
si l'écart entre eux dépasse un certain seuil (pour ne pas tracer un trait
pendant une vraie coupure OFF/panne) -- ce seuil était une constante fixe de
60s, calibrée pour la cadence du direct (`grid.read_interval_s`, ~2s). Les
points rechargés depuis `stats.db` au démarrage sont espacés de
`stats.interval_s` (5 min par défaut, donc bien plus de 60s d'écart) : avec
un seuil fixe, **chaque point rechargé devenait son propre segment isolé et
invisible**, et seul le petit paquet de points du direct (dense, en fin de
fenêtre) restait visible -- symptôme observé en prod : graphiques qui
semblent vides après un redémarrage alors que `_chartData` contient bien les
points.

Une première correction a calculé ce seuil dynamiquement par série (médiane
des écarts × 4) plutôt qu'une constante -- mais **ça ne suffit pas** : peu
après un redémarrage, dès que les échantillons du direct (denses, ~2s)
deviennent plus nombreux que les échantillons rechargés (épars, ~5 min) dans
le tampon, la médiane retombe sur ~2s et reproduit exactement le même bug
pour la portion rechargée, qui reste pourtant majoritaire en étendue
temporelle. Un seuil inféré des écarts n'est pas fiable quand le mélange
direct/historique varie dans le temps.

**Solution retenue** : chaque échantillon transporte un booléen
`backfilled` (ajouté par `StatsStore#loadRecentSamples` = `true`,
`LiveState#recordGrid` = `false`), et `dashboard.html` relie **toujours**
deux points consécutifs si les deux viennent du rechargement (un écart entre
eux est normal, pas une coupure), et n'applique le seuil fixe de 60s que si
au moins un des deux est un point du direct -- seul cas où un écart signale
une vraie interruption en cours de fonctionnement. Robuste par construction,
contrairement à une statistique inférée des écarts -- et ça reste vrai après
le passage aux deux résolutions ci-dessus : que les points rechargés soient
espacés de 2s (dans la fenêtre haute résolution) ou de `stats.interval_s`
(au-delà, une fois regroupés), la règle "toujours relier deux points
rechargés" ne dépend jamais de l'écart réel entre eux.

**Navigation dans les 2 ans d'historique (`GET /history.json`, `dashboard.html`)** :
même après le backfill au démarrage, le tableau de bord ne pouvait remonter
que dans les ~30 dernières minutes (la fenêtre de `LiveState`, `history` côté
JS) -- rien ne permettait de consulter le reste de `stats.db` malgré ses 2 ans
de rétention. `webui/HistoryJsonHandler` (`GET /history.json?since=&until=`)
expose `StatsStore#loadSamplesBetween(since, until)` (même forme de map que
`loadRecentSamples`, `backfilled=true`) pour interroger une plage arbitraire.
Côté client, `dashboard.html`'s `maybeLoadOlderHistory(requestedTMin)` est
appelée par le glisser-déposer (pan) existant : dès que l'utilisateur tente de
remonter avant `loadedTMin` (la borne la plus ancienne déjà chargée, qu'elle
vienne du backfill initial ou d'un fetch précédent), elle déclenche une requête
`/history.json` pour le morceau manquant, fusionne le résultat dans `history`,
et avance `loadedTMin` -- que des données aient été trouvées ou non, pour ne
pas requêter la même plage vide en boucle. `clampView` utilise désormais
`loadedTMin` (pas `history[0].t`) comme borne basse du pan/zoom, ce qui lève
concrètement le mur des 30 minutes. La molette de zoom garde son comportement
inchangé (plafonné à l'étendue déjà chargée) -- volontairement pas modifiée,
risque plus élevé pour un gain marginal vu que le glisser-déposer est le geste
naturel pour "remonter dans le temps". `poll()` ne purge plus le tampon à
`MAX_POINTS` (900) tant qu'une vue zoomée est active (`viewTMin`/`viewTMax`
non nuls), pour ne pas évincer un morceau d'historique que l'utilisateur vient
de charger explicitement ; la purge normale reprend dès le retour à la vue
par défaut. Chaque requête est plafonnée à `HISTORY_FETCH_MAX_SPAN_S` (7
jours) pour qu'un zoom-arrière total sur 2 ans ne tente pas de récupérer toute
la table en un seul aller-retour HTTP.

**Taille et nombre de lignes affichés sur la page de config** :
`ConfigPageHandler` affiche `statsStore.sizeBytes()` (taille du fichier
`stats.db` principal, sans les fichiers annexes `-wal`/`-shm` du mode WAL)
et `statsStore.sampleCount()` (nombre de lignes de la table `samples`) à
côté des champs `stats.interval_s`/`stats.retention_days`, pour donner une
idée concrète de la croissance de la base sans avoir à s'y connecter en SSH.

Toutes les opérations SQLite passent par un même `ReentrantLock` : `StatsStore`
est appelé depuis deux threads (la boucle de contrôle, et les threads HTTP du
serveur web pour le flush immédiat), et bien que SQLite sérialise déjà les
écritures au niveau fichier, le verrou évite qu'une opération multi-requêtes
(le batch insert par onduleur, par exemple) ne s'entrelace avec un appel
concurrent. Une erreur d'écriture/purge est loguée et absorbée, jamais
propagée : un accroc de persistance long terme ne doit jamais interrompre la
boucle de contrôle en direct.

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

si battery_power_w < 0 (decharge) :                      // voir "Priorite solaire sur batterie" ci-dessous
    raw_target = clamp(max(raw_target, current_actual + |battery_power_w|), 0, capacite_totale)

step             = max(step_absolute_w, step_relative_pct% * capacite_totale)
quantized        = round(raw_target / step) * step        // palier
next_target      = last_sent + clamp(quantized - last_sent, -step, +step)  // rampe: 1 palier/cycle max

si next_target n'a pas bouge d'au moins min_change_w depuis le dernier envoi :
    -> rien n'est envoye ce cycle (zero requete HTTP)
sinon :
    -> repartition + envoi (voir ci-dessous)
```

### Priorité solaire sur batterie

Le PI ci-dessus ne regarde que la puissance réseau -- il peut donc être
"satisfait" (réseau proche de `export_setpoint_w`) alors que la batterie
comble en silence un écart que le solaire pourrait couvrir. Règle explicite
de l'utilisateur : **interdit de tirer sur la batterie si le solaire peut
fournir cette puissance à la place** -- sauf si la consigne est déjà au
maximum de la capacité disponible (plus aucun onduleur n'a de marge), auquel
cas la batterie doit prendre le relais.

Implémenté en plancher sur `rawTarget` dans `SoftTargetController.computeTarget`
(jamais dans l'intégrale du PI, pour éviter tout risque de *windup* : si le
plancher sature `rawTarget` à `totalCapacityW` pendant des heures -- typiquement
la nuit -- l'intégrale ne doit surtout pas continuer à s'accumuler, sinon elle
produirait un dépassement une fois le soleil revenu). Le `clamp(..., 0,
totalCapacityW)` implémente naturellement l'exception : une fois tous les
onduleurs déjà à leur plafond, le plancher ne peut plus rien élever davantage,
et le reste de la décharge batterie est accepté sans logique séparée.

## Répartition multi-onduleurs (water-filling)

`WaterFillAllocator.waterFillAllocate(totalTargetW, serials, capacityEstimates,
nominalPowerW, ...)` (`allocator/WaterFillAllocator.java`) : répartition
égalitaire **en % de la puissance nominale de chacun**, pas en Watts absolus
-- `nominalPowerW` est donc un paramètre requis, pas juste une donnée pour le
plancher `minInverterPct`. Un onduleur plafonné par sa capacité connue est
capé à sa capacité réelle, et le surplus est redistribué itérativement sur
les onduleurs restants (on recalcule un nouveau pourcentage commun sur eux).

**Pourquoi le %, pas les Watts** (exigence explicite utilisateur) : avec un
partage en Watts absolus, réduire ou augmenter la production totale change
chaque onduleur du même nombre de Watts, indépendamment de sa puissance
nominale -- un petit onduleur de 400W à 87% de son propre maximum et un grand
de 1000W à 50% du sien recevraient pourtant la même variation en Watts. En %,
ils convergent vers le **même pourcentage de leur propre nominal** : baisser
la consigne totale réduit d'abord (proportionnellement plus) celui qui était
déjà le plus haut en %, et l'augmenter favorise d'abord celui qui était le
plus bas en % -- symétrique dans les deux sens, par construction de
l'algorithme (pas une règle de priorité codée à part). Exemple : cible 650W
sur deux onduleurs de 400W et 1000W nominaux (tous deux disponibles sans
ombrage) → 185,7W (46,4% de 400) et 464,3W (46,4% de 1000), pas 325W chacun.

`capacity_estimates` (`CapacityEstimator`, `control/CapacityEstimator.java`)
démarre à la puissance nominale déclarée en config. Si un onduleur reste
durablement sous sa part allouée alors qu'OpenDTU confirme que la limite est
bien appliquée (`limit_set_status == "Ok"`, donc ce n'est pas la limite qui le
bride), on suppose qu'il est limité par l'irradiance réelle et son plafond est
abaissé à sa production mesurée. Une sonde périodique (`capacity_probe.step_w`
/ `interval_s`) relève ce plafond par petits pas vers le nominal.

Ce jugement "limité par l'irradiance" n'est appliqué que si la part allouée
était déjà **proche du plafond actuel** (`>= NEAR_CEILING_RATIO`, 90%, de
`ceilingsW.get(serial)`) : la consigne zero-export vise rarement le maximum
physique (juste assez pour couvrir la charge sans exporter), donc la part
allouée à un onduleur est souvent bien inférieure à son plafond ; sans ce
garde-fou, un simple bruit de mesure (production réelle légèrement sous une
part déjà modeste) faisait dégringoler le plafond en plein soleil, et la
remontée lente (`probeTick`) ne compensait jamais assez vite -- la batterie
comblait l'écart à la place du solaire.

`min_inverter_pct` (`config.control.minInverterPct`, défaut **5%**,
**par onduleur** -- pas à confondre avec `control.stepRelativePct`, qui lui
s'applique au total agrégé) est appliqué en post-traitement sur la map
`allocation` retournée : pour chaque onduleur ayant une capacité réelle
(`capacityEstimates[serial] > 0` -- donc pas nuit/ombrage total), on relève à
`min(capacityEstimates[serial], minInverterPct% * nominalPowerW[serial])` si
sa part calculée était plus basse -- **y compris quand cette part est
exactement 0**, tant qu'il y a de la capacité. La config est prioritaire : ce
plancher peut donc causer une injection réseau réelle si `totalTargetW`
calculé par le PI est plus bas que ce que le plancher impose. Fail-safe et le
déblocage à 100% pendant la charge batterie prioritaire ne passent pas par
`waterFillAllocate` du tout (`setRelativeLimitPct` direct), donc ce plancher
ne les concerne jamais.

`ControlLoop.minInverterFloorWarning` (package-private) détecte ce cas après
coup (pas dans `WaterFillAllocator`, qui reste pure logique d'allocation sans
connaissance du contexte réseau) et logue un `WARNING` avec un pourcentage
recommandé, exposé au tableau de bord.

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

## Persistance de l'hystérèse batterie et du mode (`state.json`)

`state/StateStore.java` écrit dans `state.json`, à côté de `config.json` :
`injection_active` (bool), chaque fois que `hysteresis.isActive()` change de
valeur, lu au démarrage pour initialiser `BatteryFullHysteresis(active=...)` ;
en l'absence d'état persisté, démarre à `true` (curtailment) plutôt que
`false` -- une lecture SOC réelle corrige ça en un cycle si la batterie n'est
en fait pas pleine. Et `injection_mode` ("AUTO"/"ON"/"OFF"), écrit par
`webui/OverrideHandlers` à chaque changement via `POST /override/mode`, lu au
démarrage dans `Main.java` pour restaurer `InjectionModeOverride`.

Les deux champs partagent le même fichier -- chaque écriture relit d'abord le
contenu existant et fusionne (`StateStore.loadRaw`/`writeRaw`), pour qu'une
sauvegarde de l'un n'écrase jamais l'autre. **Corrige un bug hérité du projet
Python d'origine** : son README affirme que le mode "survit à un
redémarrage", mais `src/state_store.py` n'a jamais persisté que
`injection_active` -- après tout redémarrage (y compris "Enregistrer et
appliquer"), le sélecteur de mode du tableau de bord revenait silencieusement
à AUTO même si `ON`/`OFF` avait été explicitement choisi, et en AUTO le
prochain cycle de décision pouvait alors faire basculer le verrou lui-même
sans aucune indication visible du changement.

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

- **Aucun unit ID Modbus n'est configurable** (`config.json` ne porte que
  `grid.modbus.host`/`port`) : tous sont des constantes dans le code,
  spécifiques à cette installation Victron, pas des réglages par
  déploiement -- décision explicite pour éviter des champs de config qui
  n'auraient jamais besoin de changer en pratique.
- **`grid/ModbusGridMeter`** : unit ID **100** (`ModbusConstants.SYSTEM_UNIT_ID`,
  agrégat `com.victronenergy.system`, toujours disponible sans configuration
  par site), registre **820** = puissance active Grid L1 (`int16`, W,
  négatif = injection). Énergie cumulée : registres **2634/2636** ("Total
  Energy from/to net", `uint32`, échelle 100 → kWh) -- appartiennent en
  théorie au service **propre du compteur réseau** (pas forcément le même
  unit ID que l'agrégat système sur toute installation), mais sur celle-ci
  il partage l'unit ID de l'agrégat système, donc pas de constante séparée.
- **`battery/ModbusBatterySoc`** : registre **843** = SOC (`uint16`, 0-100%,
  pas de conversion de signe), registre **842** = puissance batterie
  (`int16`, positif = charge, négatif = décharge), registre **841** = courant
  batterie (`int16`, échelle 10 → A, même convention de signe que la
  puissance). Ces trois registres vivent sur l'agrégat système (unit ID
  100, `ModbusConstants.SYSTEM_UNIT_ID`). Registre **259** = tension
  batterie (`uint16`, échelle 100 → V) : contrairement au SOC/puissance/
  courant, ce registre appartient au service **propre du moniteur de
  batterie** (`com.victronenergy.battery`), pas à l'agrégat système -- unit
  ID **225** (constante `VOLTAGE_UNIT_ID` dans `ModbusBatterySoc.java`).
  Utilisé seulement pour l'affichage tableau de bord -- l'hystérèse ON/OFF
  ne se base que sur le SOC.
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

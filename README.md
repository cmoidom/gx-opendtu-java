# gx-opendtu-java — zero-injection PV controller (portage Java)

Empêche toute injection réseau ("zero export") sur une installation
photovoltaïque monophasée équipée de micro-onduleurs Hoymiles pilotés par
[OpenDTU](https://github.com/tbnobody/OpenDTU), en s'appuyant sur un Victron
Cerbo GX (Venus OS) et son compteur réseau.

Portage Java de [gx-opendtu](https://github.com/cmoidom/gx-opendtu) (Python),
pour un déploiement **exclusivement sur une VM Linux séparée** (ex. Proxmox)
-- jamais directement sur le Cerbo GX. La lecture de la puissance réseau et
du SOC batterie se fait donc toujours en **Modbus TCP** distant ; la
communication avec OpenDTU se fait exclusivement en HTTP (pas de MQTT).

Voir [`ARCHITECTURE.md`](ARCHITECTURE.md) pour le détail de la conception, et
[`AGENTS.md`](AGENTS.md) pour les conventions à respecter en cas de reprise du
code par un agent IA.

## Fonctionnement en un coup d'œil

1. **Lecture** de la puissance réseau instantanée (positif = soutirage,
   négatif = injection) via Modbus TCP -- unit ID 100
   (`com.victronenergy.system`, agrégat toujours disponible), registre 820 =
   puissance active Grid L1.
2. **Décision** (`gxopendtu.control`) : boucle PI, quantifiée par paliers
   (100 W ou 10 % du parc, la plus grande des deux), limitée en rampe à un
   palier par cycle de décision. Une hystérésis anti-dithering évite qu'un
   bruit de charge oscillant autour d'un palier fasse basculer la consigne
   (et donc tous les onduleurs pilotables à la fois) à chaque cycle.
3. **Répartition** (`gxopendtu.allocator`) : la puissance cible totale est
   répartie de façon égalitaire (en % de la puissance nominale de chacun)
   entre les onduleurs **pilotables**, avec redistribution automatique
   (water-filling) quand un onduleur ne peut pas suivre sa part -- son
   plafond est alors abaissé (`CapacityEstimator`), puis remonté dès qu'il
   suit à nouveau ce qu'on lui demande (remontée quasi immédiate si le
   soleil revient vraiment, pas juste une lente sonde périodique). Un
   onduleur marqué `controllable: false` en config est exclu de tout ceci
   -- lu et affiché, mais jamais commandé, quel que soit le mode.
4. **Commande** (`gxopendtu.opendtu`) : écriture des limites via
   `POST /api/limit/config` (types non-persistants uniquement), lecture via
   `GET /api/livedata/status` et `GET /api/limit/status`.
5. **Repli sécurité** (`gxopendtu.loop.ControlLoop`) : en cas de perte du
   compteur réseau ou d'OpenDTU injoignable, tous les onduleurs sont ramenés
   à 0 % en attendant le rétablissement de la communication.
6. **Priorité charge batterie** (optionnel, `battery.enabled`) : tant que le
   SOC batterie n'a pas atteint 100 %, le contrôle d'injection est désactivé
   pour laisser l'ESS Victron charger la batterie avec le surplus PV. Voir
   `ARCHITECTURE.md`.

## Prérequis

- **Sur la VM** : Java 21 (JRE suffit pour l'exécution ; JDK 21 + Maven pour
  compiler), réseau IP vers le Cerbo GX et OpenDTU.
- **Sur le Cerbo GX** : **Settings > Services > Modbus/TCP** activé (port 502
  par défaut). Compteur réseau reconnu nativement sur D-Bus (installation
  **monophasée**).
- OpenDTU déjà flashé et configuré, joignable en HTTP sur le réseau local.
  Si l'API OpenDTU exige une authentification (Basic Auth, souvent
  utilisateur `admin`), renseigner `opendtu.username`/`opendtu.password` en
  config -- sans ça, `POST /api/limit/config` échoue en `401 Unauthorized`
  et le contrôleur ne peut **plus limiter les onduleurs, y compris le repli
  fail-safe**.

## Configuration

Copier `config/config.example.json`, puis l'adapter (URL OpenDTU, IP Modbus
du Cerbo GX, numéros de série et puissance nominale de chaque onduleur, gains
PI, paliers) -- voir `ARCHITECTURE.md` pour la signification de chaque
paramètre. Même schéma JSON que le déploiement VM/Modbus du projet Python
d'origine, à l'exception de `grid.source` qui n'existe plus (toujours Modbus).

`control.min_inverter_pct` (défaut 5%) : seuil plancher **par onduleur**, en
% de la puissance nominale de chacun -- un onduleur qui a de la capacité
réelle disponible n'est jamais commandé sous ce seuil. Mettre `0` pour
désactiver. À ne pas confondre avec `control.step_relative_pct`, qui
s'applique lui au palier de quantification **agrégé** (somme de tous les
onduleurs) -- deux grandeurs différentes qui se ressemblent en config.
**Ce seuil est prioritaire sur le zero-export strict** : s'il est réglé plus
haut que le vrai besoin du moment, il peut causer une injection réseau
réelle -- le tableau de bord affiche un avertissement quand ça arrive.

Pour activer la priorité de charge batterie :
```json
"battery": { "enabled": true, "activate_at_pct": 100, "deactivate_below_pct": 98, "export_confirms_full_w": 50, "export_confirms_full_duration_s": 60 }
```

`control.min_battery_discharge_w` (défaut 150 W) : en dessous de cette
puissance, une décharge batterie est traitée comme du bruit normal
(auto-consommation/flottaison à batterie pleine) et n'oblige pas la
consigne à remonter -- au-dessus, la consigne remonte pour que le solaire
couvre la décharge plutôt que la batterie. Sans ce seuil, une décharge
minime et permanente à SOC ~100% peut bloquer la consigne à un niveau trop
haut en continu (incident réel corrigé le 2026-07-13, voir `AGENTS.md`).

Par onduleur, `controllable` (défaut `true`) : mettre `false` pour un
onduleur que ce logiciel ne doit **jamais** commander (fail-safe compris)
-- toujours lu et affiché sur le tableau de bord, mais sa limite reste
telle qu'elle est réglée ailleurs.
```json
"inverters": [
  { "serial": "114181801234", "nominal_power_w": 600 },
  { "serial": "114181805678", "nominal_power_w": 380, "controllable": false }
]
```

`capacity_probe.step_w`/`interval_s` (défauts 50 W / 30 s) : quand un
onduleur ne peut pas suivre son allocation (nuage, tombée du jour), son
plafond est abaissé à sa production mesurée, puis remonté -- directement
au nominal (pas pas à pas) dès qu'il redevient saturé et suit malgré tout
ce qu'on lui demande, avec un court palier de prudence après un essai raté
pour ne pas re-tester en boucle pendant un vrai crépuscule. `step_w` ne
règle plus que la vitesse de cette remontée de prudence et celle des
onduleurs pas encore sollicités -- voir `ARCHITECTURE.md` pour le détail.

### Page web de configuration

Une page web intégrée (`gxopendtu.webui`, aucune dépendance supplémentaire
hors JDK) permet d'éditer tous les paramètres, y compris l'ajout/suppression
d'onduleurs. Toujours active (pas d'option pour la désactiver), sur le port
8080 par défaut (`web.port`, configurable) : `http://<ip-du-service>:8080/config`
(la racine `http://<ip-du-service>:8080/` sert le tableau de bord, voir plus bas).

- **"Enregistrer"** écrit `config.json` sans rien redémarrer (les
  changements ne sont pris en compte qu'au prochain redémarrage manuel).
  **"Enregistrer et appliquer"** écrit puis redémarre le service tout de
  suite (confirmation demandée avant d'agir).
- Aucune authentification (comme l'API OpenDTU) -- accessible à quiconque
  sur le LAN.
- Bouton **"Charger la liste depuis OpenDTU"** : interroge
  `/api/livedata/status` et `/api/limit/status` sur l'URL OpenDTU
  actuellement saisie, affiche chaque onduleur détecté sous forme de case à
  cocher.
- Bouton **"Réinitialiser les réglages de tuning"** : pré-remplit le
  formulaire avec les valeurs par défaut pour les seuls réglages de
  comportement (PI, plancher batterie, sondage de capacité, hystérésis
  batterie, graphiques, journalisation, historique) -- ne touche jamais aux
  identifiants OpenDTU, à la config Modbus, aux onduleurs, au setpoint
  d'export ni à l'activation de la batterie. Rien n'est écrit avant de
  cliquer sur "Enregistrer".
- Chaque champ numérique vide affiche sa valeur par défaut en placeholder
  grisé.

### Tableau de bord temps réel

`http://<ip-du-service>:8080/dashboard` affiche l'état courant du pilotage :
bandeau d'avertissement `min_inverter_pct`, contrôle manuel (mode
AUTO/ON/OFF, forçage 25/50/75/100% pendant 5 min), tuiles (réseau brut/EMA,
SOC/puissance batterie, estimation de fin de charge/décharge, puissance
solaire totale, régulation, consigne), trois graphiques temporels avec
zoom/pan synchronisé, graphiques "énergie par onduleur"/"énergie réseau"
par heure et par jour, tableau détaillé par onduleur (puissance, âge de la
dernière lecture RF -- coloré si trop vieux, signe possible d'un souci de
communication avec cet onduleur précis -- limite, nominale, état). Repris
en grande partie du projet Python (JS/CSS/HTML déjà 100% côté client,
aucune dépendance externe -- tracé en `<canvas>` HTML5 fait main), étendu
pour ce portage.

### État interne / débogage

`http://<ip-du-service>:8080/internal` expose ce que le tableau de bord ne
montre pas : erreur et intégrale du PI, `rawTarget` avant/après le plancher
anti-décharge batterie, plafonds par onduleur (`CapacityEstimator`), état de
l'hystérésis batterie (streak d'export soutenu), override manuel/mode
d'injection. Lecture seule (aucune décision de contrôle n'en dépend) --
pensé pour diagnostiquer un comportement inattendu (ex. consigne bloquée)
sans avoir à relire le code source ou reconstruire l'historique depuis les
logs.

### Historique long terme (`stats.db`)

Les courbes du tableau de bord (réseau, SOC, batterie, par onduleur, énergie
horaire) sont aussi persistées dans un fichier SQLite (`stats.db`, à côté de
`config.json`), indépendamment de la vue temps réel (~30min/48h en mémoire,
perdue à chaque redémarrage). Voir `ARCHITECTURE.md` pour le détail.

Deux résolutions pour limiter la taille du fichier : chaque échantillon est
écrit tel quel (même cadence que le direct, ~2s) pendant
`high_res_retention_days`, puis les points plus vieux sont regroupés à
l'intervalle `interval_s` (un seul point conservé par intervalle). Réglages
dans `config.json` (section `stats`, éditables aussi depuis la page de
config) :
```json
"stats": { "interval_s": 300, "retention_days": 730, "high_res_retention_days": 30 }
```
- `high_res_retention_days` (défaut 30) : pendant ces N derniers jours, le
  tableau de bord peut zoomer sur n'importe quel instant précis.
- `interval_s` (défaut 300 = 5 min) : largeur du regroupement une fois la
  fenêtre haute résolution dépassée -- ne pilote plus la fréquence
  d'écriture (voir ci-dessous), juste la résolution du passé lointain.
- `retention_days` (défaut 730 ≈ 2 ans) : purge automatique au-delà,
  quotidiennement. Doit être `>= high_res_retention_days`.

Les échantillons haute résolution sont accumulés en mémoire et vidés en une
seule transaction toutes les 60 secondes (pas un `INSERT` par échantillon) --
une coupure brutale (crash, coupure secteur) peut donc perdre jusqu'à 1 min
de données très récentes, mais un redémarrage propre ("Enregistrer et
appliquer", `systemctl restart`, `update.sh`) vide explicitement ce tampon
avant de couper, donc ne perd rien.

## Compilation et déploiement

Sur une machine avec **JDK 21 + Maven** installés :

```sh
mvn -q package
```

Produit un jar unique `target/gx-opendtu-java.jar` (via `maven-shade-plugin`).

Sur la VM Linux séparée (Debian/Ubuntu + systemd) :

```sh
sudo useradd --system --home /opt/gx-opendtu --shell /usr/sbin/nologin gx-opendtu
sudo mkdir -p /opt/gx-opendtu /etc/gx-opendtu
sudo cp target/gx-opendtu-java.jar /opt/gx-opendtu/
sudo cp config/config.example.json /etc/gx-opendtu/config.json
# editer /etc/gx-opendtu/config.json : IP du Cerbo GX (grid.modbus.host),
# URL OpenDTU, numeros de serie et puissances nominales des onduleurs
sudo chown -R gx-opendtu:gx-opendtu /opt/gx-opendtu /etc/gx-opendtu

sudo cp deploy/systemd/gx-opendtu-zero-export.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now gx-opendtu-zero-export
journalctl -u gx-opendtu-zero-export -f
```

Sur le Cerbo GX : activer **Settings > Services > Modbus/TCP**.

### Mises à jour (`update.sh`)

Une fois cette première installation faite, `update.sh` (à la racine du
dépôt, sur la VM) automatise tout le cycle de mise à jour :

```sh
./update.sh              # git pull --ff-only -> mvn package (tests inclus)
                          # -> installe le jar + l'unité systemd -> redémarre
./update.sh --skip-tests  # plus rapide, sans lancer la suite de tests
./update.sh -y            # ne demande pas confirmation si des modifications
                           # locales non commitées existent
```

S'arrête au premier échec (`git pull`, compilation ou tests) : ne redémarre
jamais le service avec un jar potentiellement cassé. Ne touche pas à
`/etc/gx-opendtu/config.json` (seuls le jar et l'unité systemd sont
réinstallés).

## Mode test (`--dry-run`)

```sh
java -jar target/gx-opendtu-java.jar --config config/config.json --dry-run
```

Le service tourne normalement (lecture du compteur réseau, lecture SOC
batterie si activé, lecture OpenDTU) mais **n'envoie jamais rien à OpenDTU**
(ni limite, ni repli sécurité, ni déblocage charge batterie). Chaque cycle de
décision trace l'état complet, que ça change ou non -- mêmes lignes de log
que le projet Python d'origine, voir `ARCHITECTURE.md`.

Utile pour valider l'asservissement sur une installation réelle avant de le
laisser piloter effectivement les onduleurs.

## Tests

```sh
mvn test
```

Logique pure (PI, quantification, rampe, water-filling, hystérésis) et
logique de la boucle de décision (via un faux client OpenDTU, sans HTTP
réel), testables sans matériel Victron/OpenDTU. Le client Modbus TCP maison
et le client HTTP OpenDTU sont testés contre un serveur factice en boucle
locale (vrai socket, faux Cerbo GX/OpenDTU) -- voir `AGENTS.md` pour le
détail de la frontière testable/non testable.

> `mvn test` passe intégralement (0 échec) et le jar produit par `mvn package`
> a été fumé-testé en `--dry-run` (serveur web + boucle de contrôle,
> fail-safe déclenché face à un Modbus/OpenDTU factice injoignable). Reste
> à valider contre du vrai matériel Victron/OpenDTU avant tout déploiement.

## Dépannage

- Logs du service : `journalctl -u gx-opendtu-zero-export -f`.
- `limit_set_status` reste sur `Pending` : latence RF normale (secondes), si
  ça persiste vérifier la portée radio entre le récepteur OpenDTU et les
  onduleurs.
- La puissance réseau ne converge pas vers le setpoint : vérifier le signe
  (positif en soutirage) et les numéros de série/puissances nominales
  déclarés dans la config.
- `Connection refused` (Modbus) → Modbus/TCP pas activé sur le Cerbo GX
  (Settings > Services) ou pare-feu bloquant le port 502. Les unit ID
  Modbus (agrégat système 100, tension batterie 225) sont des constantes,
  pas des champs de config -- rien à vérifier/modifier de ce côté.
- `injection_control=OFF` qui ne repasse jamais à `ON` : le SOC n'a pas
  encore atteint `battery.activate_at_pct` (100 % par défaut) -- comportement
  voulu (priorité charge batterie), pas un bug.
- Un onduleur affiche une limite à un très faible % de sa puissance
  nominale en fin de journée (ex. 10%, alors qu'il était à 80-100% en plein
  jour) : normal, pas un bug -- `CapacityEstimator` a détecté qu'il ne peut
  plus suivre l'allocation demandée (plus assez de lumière) et a abaissé
  son plafond réel à sa production mesurée ; le % affiché reste rapporté à
  la puissance nominale, donc mécaniquement petit une fois le plafond réel
  bien inférieur au nominal. Remonte de lui-même (voir `capacity_probe`
  ci-dessus) si la lumière revient.
- Âge de la dernière lecture (`data_age`) élevé et coloré en rouge sur le
  tableau de bord pour un onduleur précis (pas les autres) : signale un
  souci de communication RF avec cet onduleur (portée, pile/alimentation),
  pas un bug logiciel -- OpenDTU interroge ses onduleurs un par un sur un
  seul module RF, donc une valeur qui grimpe pour un seul onduleur pointe
  vers ce matériel-là spécifiquement.

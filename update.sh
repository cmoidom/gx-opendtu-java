#!/usr/bin/env bash
# Met a jour, recompile et redemarre gx-opendtu-java sur la VM de deploiement.
#
# Usage (depuis la VM, dans le clone du depot) :
#   ./update.sh              # met a jour, tests inclus
#   ./update.sh --skip-tests # plus rapide, sans lancer la suite de tests
#   ./update.sh -y           # ne demande pas confirmation si des
#                            # modifications locales non commitees existent
#
# Etapes : git pull --ff-only -> mvn package (tests par defaut) -> copie du
# jar + de l'unite systemd -> systemctl restart. S'arrete au premier echec
# (set -e) : ne redemarre jamais le service avec un jar potentiellement
# casse si le pull, le build ou les tests echouent.
#
# Suppose une installation deja en place (utilisateur systeme gx-opendtu,
# /opt/gx-opendtu, /etc/gx-opendtu/config.json) -- voir README.md pour la
# premiere installation.

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/opt/gx-opendtu"
SERVICE_NAME="gx-opendtu-zero-export"
JAR_NAME="gx-opendtu-java.jar"
SERVICE_USER="gx-opendtu"

SKIP_TESTS=0
ASSUME_YES=0
for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    -y|--yes) ASSUME_YES=1 ;;
    *)
      echo "Argument inconnu : $arg" >&2
      exit 2
      ;;
  esac
done

cd "$REPO_DIR"

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
CURRENT_COMMIT="$(git rev-parse --short HEAD)"
TESTS_LABEL="inclus"
MVN_ARGS=()
if [[ "$SKIP_TESTS" -eq 1 ]]; then
  TESTS_LABEL="ignores (--skip-tests)"
  MVN_ARGS+=("-DskipTests")
fi

echo "================================================================"
echo " gx-opendtu-java - mise a jour"
echo "================================================================"
echo " Depot         : $REPO_DIR"
echo " Branche       : $CURRENT_BRANCH"
echo " Commit actuel : $CURRENT_COMMIT"
echo " Installation  : ${INSTALL_DIR}/${JAR_NAME}"
echo " Service       : $SERVICE_NAME"
echo " Tests         : $TESTS_LABEL"
echo "================================================================"
echo " Etapes prevues :"
echo "   1. git pull --ff-only"
echo "   2. mvn package ${MVN_ARGS[*]:-}"
echo "   3. installer target/${JAR_NAME} -> ${INSTALL_DIR}/${JAR_NAME}"
echo "   4. mettre a jour l'unite systemd (${SERVICE_NAME}.service) + daemon-reload"
echo "   5. systemctl restart ${SERVICE_NAME}"
echo "================================================================"
echo

if [[ -n "$(git status --porcelain)" ]]; then
  echo "!! Modifications locales non commitees detectees -- 'git pull --ff-only' pourrait echouer."
  if [[ "$ASSUME_YES" -ne 1 ]]; then
    read -r -p "   Continuer quand meme ? [y/N] " reponse
    if [[ ! "$reponse" =~ ^[Yy]$ ]]; then
      echo "Annule."
      exit 1
    fi
  fi
fi

echo "==> [1/5] git pull --ff-only (depuis $CURRENT_COMMIT)"
git pull --ff-only
NEW_COMMIT="$(git rev-parse --short HEAD)"
if [[ "$NEW_COMMIT" == "$CURRENT_COMMIT" ]]; then
  echo "    Deja a jour (commit $CURRENT_COMMIT) -- on recompile/redemarre quand meme."
else
  echo "    $CURRENT_COMMIT -> $NEW_COMMIT"
  echo "    Changements :"
  git log --oneline "${CURRENT_COMMIT}..${NEW_COMMIT}" | sed 's/^/      /'
fi

echo "==> [2/5] Compilation (mvn package, tests ${TESTS_LABEL})"
mvn -q package "${MVN_ARGS[@]}"

if [[ ! -f "target/${JAR_NAME}" ]]; then
  echo "!! target/${JAR_NAME} introuvable apres le build -- abandon, service non touche." >&2
  exit 1
fi
JAR_SIZE="$(du -h "target/${JAR_NAME}" | cut -f1)"
echo "    OK : target/${JAR_NAME} (${JAR_SIZE})"

echo "==> [3/5] Installation du jar dans ${INSTALL_DIR}"
sudo install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 644 "target/${JAR_NAME}" "${INSTALL_DIR}/${JAR_NAME}"
echo "    ${INSTALL_DIR}/${JAR_NAME} installe (${JAR_SIZE}, ${SERVICE_USER}:${SERVICE_USER})"

echo "==> [4/5] Mise a jour de l'unite systemd (au cas ou elle aurait change)"
sudo cp "deploy/systemd/${SERVICE_NAME}.service" "/etc/systemd/system/${SERVICE_NAME}.service"
sudo systemctl daemon-reload
echo "    /etc/systemd/system/${SERVICE_NAME}.service a jour, daemon-reload fait"

echo "==> [5/5] Redemarrage du service ${SERVICE_NAME}"
sudo systemctl restart "${SERVICE_NAME}"

sleep 2
echo "==> Statut du service :"
sudo systemctl status "${SERVICE_NAME}" --no-pager -l || true

echo "================================================================"
echo " Termine (commit ${NEW_COMMIT}). Suivre les logs :"
echo "   journalctl -u ${SERVICE_NAME} -f"
echo "================================================================"

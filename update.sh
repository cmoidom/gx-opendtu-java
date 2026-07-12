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
echo "==> Depot : $REPO_DIR"

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

echo "==> git pull --ff-only"
git pull --ff-only

echo "==> Compilation (mvn package$([[ $SKIP_TESTS -eq 1 ]] && echo ', tests ignores' || echo ', tests inclus'))"
if [[ "$SKIP_TESTS" -eq 1 ]]; then
  mvn -q package -DskipTests
else
  mvn -q package
fi

if [[ ! -f "target/${JAR_NAME}" ]]; then
  echo "!! target/${JAR_NAME} introuvable apres le build -- abandon, service non touche." >&2
  exit 1
fi

echo "==> Installation du jar dans ${INSTALL_DIR}"
sudo install -o "$SERVICE_USER" -g "$SERVICE_USER" -m 644 "target/${JAR_NAME}" "${INSTALL_DIR}/${JAR_NAME}"

echo "==> Mise a jour de l'unite systemd (au cas ou elle aurait change)"
sudo cp "deploy/systemd/${SERVICE_NAME}.service" "/etc/systemd/system/${SERVICE_NAME}.service"
sudo systemctl daemon-reload

echo "==> Redemarrage du service ${SERVICE_NAME}"
sudo systemctl restart "${SERVICE_NAME}"

sleep 2
echo "==> Statut du service :"
sudo systemctl status "${SERVICE_NAME}" --no-pager -l || true

echo "==> Termine. Suivre les logs : journalctl -u ${SERVICE_NAME} -f"

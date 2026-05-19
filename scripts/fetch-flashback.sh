#!/usr/bin/env bash
# Télécharge le jar Flashback compile-only dans libs/.
# Le jar n'est PAS redistribué — chaque dev le récupère lui-même.
set -euo pipefail

cd "$(dirname "$0")/.."

JAR_NAME="${1:-Flashback-0.39.5-for-MC1.21.10.jar}"
URL="${2:-https://cdn.modrinth.com/data/4das1Fjq/versions/otIAhzwL/Flashback-0.39.5-for-MC1.21.10.jar}"

mkdir -p libs

if [[ -f "libs/${JAR_NAME}" ]]; then
    echo "[fetch-flashback] libs/${JAR_NAME} déjà présent, skip."
    exit 0
fi

echo "[fetch-flashback] Téléchargement de ${JAR_NAME}…"
curl -fL --progress-bar -o "libs/${JAR_NAME}" "${URL}"
echo "[fetch-flashback] OK → libs/${JAR_NAME}"

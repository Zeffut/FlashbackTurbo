#!/usr/bin/env bash
# Compare deux vidéos image par image et renvoie le SSIM moyen (qualité visuelle).
# Sert à valider qu'une optimisation d'encodage ne dégrade pas la qualité (barre projet : ≥ 0.99).
#
# Usage : ./scripts/ssim-compare.sh reference.mp4 candidate.mp4
# Exit 0 si SSIM moyen ≥ 0.99, sinon 1. Nécessite ffmpeg dans le PATH.
set -euo pipefail
REF="${1:?usage: ssim-compare.sh reference.mp4 candidate.mp4}"
CAND="${2:?usage: ssim-compare.sh reference.mp4 candidate.mp4}"

OUT=$(ffmpeg -i "$REF" -i "$CAND" -lavfi "ssim=stats_file=-" -f null - 2>&1 \
        | grep -oE 'All:[0-9.]+' | tail -1 | cut -d: -f2)

if [[ -z "${OUT:-}" ]]; then
    echo "ERREUR : impossible de calculer le SSIM (résolutions/durées différentes ?)" >&2
    exit 2
fi

echo "SSIM moyen = $OUT"
awk -v s="$OUT" 'BEGIN{ exit (s+0 >= 0.99 ? 0 : 1) }'

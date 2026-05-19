# FlashbackTurbo — Roadmap

État au 2026-05-19. Voir [SPEC.md](SPEC.md) et [HOOKS.md](HOOKS.md) pour le détail technique.

## ✅ Done (0.2.0 published)

1. **Feasibility scan** — classes Flashback cibles toutes `public class` non-final, mixinables.
2. **Build system** — Gradle 9.4.1, Loom 1.16.2 pour 1.21.x (Yarn), Loom 1.15-SNAPSHOT pour 26.1.x (Mojang mappings).
3. **Hooks H2 + H3 + H4 + H6 + H7** — implémentés, mixiconfigurés, runtime-validés.
4. **Config loadable** — `<game>/config/flashbackturbo.json`, défauts safe, override par hook.
5. **Validation runtime** — MC + Flashback + FlashbackTurbo bootent, Mixins appliquent, export PNG génère des frames lossless.
6. **Benchmark comparatif** — vanilla vs turbo sur replay 1.21.11 réel : **10.95× speedup à 1080p**, pixels décodés identiques.
7. **Cross-version** — builds publiés pour 1.21.9 / 1.21.10 / 1.21.11 (Java 21) et 26.1.x (Java 25).
8. **Modrinth release** — `flashbackturbo` slug, deux versions listées (`0.2.0` et `0.2.0+26.1`).

## 🔜 À faire

### Important

1. **Permission Moulberry** — poster sur Discord `flashback-tool` salon `#addons` pour confirmer que les addons Mixin externes sont OK sous la licence Flashback (qui dit "all rights reserved" sans mention explicite). Précédent : MultiView existe sans contestation. Modrinth a publié sans review-block — bon signe mais pas garantie juridique.

2. **Suivi modération Modrinth** — les versions sont en statut `processing` au moment du push. Vérifier qu'elles passent en `approved` (~24-48h).

### Améliorations 0.3+

3. **UI integration** — exposer les toggles `TurboConfig` directement dans l'UI export de Flashback via Mixin sur `StartExportWindow` (ajouter une section "FlashbackTurbo" avec checkboxes + slider zlib).

4. **H5 GPU RGB→YUV** — voir [HOOKS.md §H5](HOOKS.md). Gros chantier (~200-500 lignes Mixin sur 3 classes + shader GLSL BT.709). Gain estimé +10-25% additionnel sur MP4. À faire après baseline benchmark MP4 vanilla pour confirmer que le `rescaleThread` est bien le bottleneck mesuré.

5. **Mantenir compat versions futures** — quand Flashback bump à 0.41+, rerunner les vérifs ASM (chaque sélecteur Mixin doit toujours trouver sa cible) et rebuild.

### Long terme

6. **Demand validation publique** — collecter retours utilisateurs sur Discord/Modrinth, mesurer speedup sur configs variées (Windows + NVIDIA, Linux + AMD, etc.).

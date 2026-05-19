# FlashbackTurbo — Next steps

État au 2026-05-19. Voir [SPEC.md](SPEC.md) et [HOOKS.md](HOOKS.md) pour le détail.

## ✅ Faits (0.1.x / 0.2.x)

1. **Feasibility scan** — classes Flashback cibles toutes `public class` non-final, mixinables. Audit dans SPEC §1.
2. **Scaffold Gradle/Loom/Fabric** — build vert, jar produit. Wrapper Gradle 9.4.1, Loom 1.16.2.
3. **Hooks H4 + H6** — cap 4K levé, threading FFmpeg tuné. Mixin compile contre Flashback 0.39.5.
4. **Hooks H2 + H3 + H7** — refonte PNG writer (parallèle + zlib configurable + color type 2). 3 tests round-trip JUnit verts.
5. **Config loadable** — `<game>/config/flashbackturbo.json`, défauts safe.
6. **Env de test isolé** — `src/test/java/` exclus du jar, `./gradlew runClient` pour sandbox MC, `docs/TESTING.md`.

## 🔜 À faire avant release publique

### Bloquants

1. **Validation runtime MC + Flashback** — lancer `./gradlew runClient`, installer Flashback dans `run/mods/`, faire un export PNG sequence vanilla puis avec turbo on, vérifier :
   - Aucun crash, aucun NPE dans les logs
   - Frames PNG bien produites avec les bons noms de fichier
   - Decoded pixels identiques entre les deux exports (script de comparaison à écrire)
   - Mesurer le temps d'export et confirmer le gain

2. **Permission Moulberry** — poster sur Discord `flashback-tool` salon `#addons` pour confirmer que les addons Mixin externes sont OK sous la licence Flashback (qui dit "all rights reserved" sans mention explicite des addons). Précédent : MultiView existe.

3. **Baseline benchmark** — sur un replay référence (à versionner ou pointer), mesurer :
   - Temps total wall clock vanilla vs turbo (PNG sequence et MP4 séparément)
   - Avant de coder H5, valider que le rescaleThread est bien le bottleneck mesuré

### Améliorations 0.3+

4. **Test cross-version** — valider H4/H6/H2/H3/H7 sur 1.21.10, 1.21.11, 26.1.x (jars Flashback 0.39.5 / 0.40.0).
5. **UI integration** — exposer les toggles `TurboConfig` dans l'UI export de Flashback (Mixin sur `StartExportWindow`).
6. **H5 GPU RGB→YUV** — voir HOOKS.md §H5. Implémentation grosse, à faire après baseline benchmark.

### Long terme

7. **Demand validation** — 3+ utilisateurs Flashback interrogés sur Discord/Modrinth — leur temps d'export typique et leur intérêt pour le gain proposé.
8. **Release Modrinth** — uniquement après bloquants 1+2 levés et validation manuelle multi-GPU.

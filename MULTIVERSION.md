# Multiversion maintenance (like FireballAlarm)

Iustitia ships for **two Minecraft versions out of one repository**, kept in two long-lived branches
that carry the same feature set. 1.21.11 is the primary target; 26.3 is kept alive in lockstep.

| Branch | Minecraft | Names | Toolchain | Jar |
| --- | --- | --- | --- | --- |
| `main` | 1.21.11 | Yarn `1.21.11+build.6` | Java 21, Loom 1.17.11, Loader 0.19.x, Fabric API 0.141.x | `iustitia-<v>-1.21.11.jar` |
| `port/26.3` | 26.3 | Mojang official (game jar is unobfuscated) | Java 25, Loom 1.19.4, Loader 0.20.1, Fabric API 0.165.x | `iustitia-<v>-26.3.jar` |

## Version policy

- **One version number per release, shared by both branches.** `1.6.0` means "this feature set" on
  1.21.11 *and* on 26.3; the Minecraft target is in the jar name, not in the mod version.
- Bug fixes that are not platform-specific bump both branches together.
- A branch may carry a version-specific port note (e.g. `PORTING-26.3.md`) but the feature list must
  match.

## What lives where

- **Shared in intent, duplicated in code:** every check, config field, language key and command.
  The two trees differ only in the platform glue: names (Yarn vs official), rendering entry points
  (world render events vs 26.3 submit phase), screen extraction API, HUD element API, mixin targets.
- **Branch-only:** 26.3's client gametest harness (`src/gametest`, `runClientGameTest`) and the
  `PORTING-26.3.md` porting notes; 1.21.11's `docs/` examples target Yarn names.
- **Config format is shared.** New fields are additive and version-agnostic. `renderEngine` is stored
  by **name** (`INGAME`/`OVERRIDE`) so an unknown value degrades to INGAME instead of failing the
  parse. A config written on one version loads on the other.

## Sync checklist (when a feature lands on one branch)

1. `IustitiaConfig` field + `ConfigManager` serialize/parse + YACL row.
2. `en_us.json` and `zh_cn.json` keys (currently 689 keys each, must stay in parity —
   `python scripts/verify_contribution.py --static` checks it).
3. Keybind ids (`Keybinds`) + dispatch in `Iustitia.onKeybind`.
4. Registration in `IustitiaClientMod`.
5. Version bump in `build.gradle.kts` on both branches.
6. README / USERMANUAL section, plus the branch's porting notes where applicable.
7. Build: `gradlew build` on 1.21.11 (JDK 21) and on 26.3 (JDK 25); the 26.3 branch additionally runs
   `gradlew runClientGameTest` when detection paths change.

## Release

```
# 1.21.11
git checkout main && ./gradlew build           # JAVA_HOME = JDK 21
# 26.3
git checkout port/26.3 && ./gradlew build      # JAVA_HOME = JDK 25
```

Tag releases as `v<version>-mc1.21.11` and `v<version>-mc26.3` so two branches can share one version
number without colliding tags, and attach both jars to the same GitHub release.

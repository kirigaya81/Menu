# Feather World Menu

Mod client **Fabric 1.21.8** — HUD personnalisé (récompenses, AFK, potions, panneau **Enchant** avec procs et cumuls depuis `latest.log`).

## Fonctionnalités

- Suivi des gains par zone (Lac, Mine, Ferme)
- Panneau **Enchant** : compteur de procs, cumuls par type (`[+49M perles +725K exp]`)
- Options par monde (Lac / Mine / Ferme)
- Reconnexion AFK, potions persistantes, lecture des logs serveur

## Prérequis

- **JDK 21**
- Minecraft **1.21.8** + **Fabric Loader** + **Fabric API**

## Compilation

```bash
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

Le JAR se trouve dans `build/libs/` (ex. `world-menu-1.0.0.jar`).

## Installation

1. Copier le `.jar` dans le dossier `mods` de ton profil Fabric 1.21.8
2. Relancer le jeu

## Licence

MIT — voir [LICENSE](LICENSE).

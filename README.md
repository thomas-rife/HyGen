# HyGen

This mod transforms Hytale into a hero-driven experience where players control and manage a team of unique characters, each with distinct roles, abilities, and combat behaviors.

---

## Overview

HyGen introduces a structured gameplay loop centered around party composition, wave-based combat, and level progression. Players assemble a team of heroes, enter curated levels, and fight through increasingly difficult enemy waves and boss encounters.

The system emphasizes real-time tactical control, allowing players to swap between heroes, activate abilities, and coordinate positioning to survive complex encounters.

---

## Core Features

### Party-Based Combat

- Build a team of heroes with unique characteristics.
- Swap between characters dynamically during combat.
- Companion AI controls inactive heroes with role-aware behavior.

### Hero System

- Modular ability system with diverse mechanics:
  - Shields and defensive barriers
  - Healing auras and sustain abilities
  - Rage and burst damage
  - Freeze and crowd control
  - Invulnerability windows
  - Archer poison and critical hit effects

### Level & Encounter Design

- Handcrafted levels with structured progression.
- Wave-based enemy encounters with per-level tuning.
- Boss fights with staged mechanics.
- Spawn control and difficulty scaling per level.

### Game Flow

- Custom main menu and UI/HUD.
- Hero selection and party configuration.
- Level map navigation.
- Return-to-menu loop for adjusting strategy and replaying content.

### Systems & Tooling

- Companion AI for autonomous hero behavior.
- Run cleanup and death handling systems.
- Persistent level configuration and tuning.
- Admin tools for:
  - Editing maps
  - Adjusting camera states
  - Configuring encounters

---

## (Beta) Custom Terrain From Text

Create large custom landscapes from a simple description. The mod connects to a small local server on your computer, generates a terrain package from your prompt, and places it into Endless Mode.

Everything runs locally. Your prompts and generated terrain stay on your machine, with no external generation service required.

- Here is the link to the server: [GitHub](https://github.com/thomas-rife/HyGen-Local-Server#)

## 1. Requirements

**Python:** Be sure to install Python 3.13 or newer.

**Local server:** The terrain server must be running in the background while you play the game.

**Model files:** Keep the included `.pt` model files in the server folder.

**Hardware:** CPU works, but an NVIDIA GPU with CUDA is recommended for faster generation.

## 2. Setup

Open a terminal in the terrain server folder and create a virtual environment:

```bash
python -m venv venv
```

Activate the virtual environment:

```bash
# Windows
venv\Scripts\activate

# Mac/Linux
source venv/bin/activate
```

Install the package dependencies:

```bash
pip install -r requirements.txt
```

## 3. Start the Local Server

Run:

```bash
python terrain_server.py
```

The game talks to the server locally at:

```text
http://127.0.0.1:8080
```

Keep this window open while generating terrain.

To force CPU mode:

```bash
python terrain_server.py --device cpu
```

## 4. Use In-Game

Start the local server, then open the game and go to the Endless Mode area in the main menu. Select the Mode: Map Select button in the top right. Enter a terrain description and generate a world.

Example prompts:

```text
grand canyon
snowy mountain pass
dense forest
tropical island
desert dunes
```

Each generation creates a new version by default. Seeds can be used when you want to recreate the same result.

## 5. What It Can Make

The system can build terrain with:

**Landforms:** mountains, canyons, valleys, islands, beaches, plains, cliffs, basins, and dunes.

**Terrain blocks:** grass, dirt, stone, sand, snow, mud, gravel, ash, sandstone, basalt, and other biome-based materials.

**Natural details:** trees, plants, reeds, cactus, rocks, boulders, driftwood, snow patches, and other environment details when available.

**Playable space:** each generated area attempts to include a usable section blended into the landscape.

---

**Note:** These models are in beta and can generate artifacts that should not be there. It also has trouble placing water blocks, which is why maps that include water may look strange. Feel free to give any feedback

---

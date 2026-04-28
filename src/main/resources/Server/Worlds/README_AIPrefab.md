# AI Prefab World Generation

Use `AIPrefabWorld.example.json` as the world config shape for a dimension that is generated entirely from an external `.prefab.json` file.

At runtime the plugin creates `ai-prefab-config.json` in the universe directory if it does not exist. Put the exported prefab JSON in the universe `prefabs` folder, or change the `prefabFile` value in the config.

Example runtime config:

```json
{
  "prefabFile": "prefabs/my_hytale_map.prefab.json",
  "originX": 0,
  "originY": 0,
  "originZ": 0,
  "spawnX": 0,
  "spawnY": 128,
  "spawnZ": 0,
  "environment": "Default",
  "flatWorld": true,
  "flatSurfaceY": -1,
  "flatBaseBlock": "Rock_Stone",
  "flatSubSurfaceBlock": "Soil_Dirt",
  "flatSurfaceBlock": "Soil_Grass",
  "snowBlockFallback": "Soil_Grass"
}
```

When `flatWorld` is true, the generator creates a flat base first. If `flatSurfaceY` is `-1`, the flat surface is generated at `spawnY - 1`. Prefab blocks are then overlaid directly into generated chunk data.

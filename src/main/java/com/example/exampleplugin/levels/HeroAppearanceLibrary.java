package com.example.exampleplugin.levels;

import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPart;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPartTexture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public final class HeroAppearanceLibrary {
    private static final Map<String, Long> ROLE_SEEDS = Map.ofEntries(
        Map.entry("HyGen_Companion_Vanguard", 104_729L),
        Map.entry("HyGen_Companion_Archer", 130_363L),
        Map.entry("HyGen_Companion_Barbarian", 150_001L),
        Map.entry("HyGen_Companion_Mage", 170_141L),
        Map.entry("HyGen_Companion_Wizard", 190_027L),
        Map.entry("HyGen_Companion_Warden", 210_011L),
        Map.entry("HyGen_Companion_Monk", 230_003L),
        Map.entry("HyGen_Companion_Oracle", 250_007L),
        Map.entry("HyGen_Companion_Support", 270_001L),
        Map.entry("HyGen_Companion_Melee", 290_011L),
        Map.entry("HyGen_Companion_Bruiser", 310_019L),
        Map.entry("HyGen_Companion_Scout", 330_041L)
    );

    private static final ConcurrentHashMap<String, PlayerSkin> SKIN_BY_ROLE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> FACE_TEXTURE_BY_ROLE = new ConcurrentHashMap<>();

    private HeroAppearanceLibrary() {
    }

    @Nonnull
    public static PlayerSkin getFixedSkin(@Nonnull String roleId) {
        PlayerSkin cached = SKIN_BY_ROLE.computeIfAbsent(roleId, HeroAppearanceLibrary::generateSkin);
        return new PlayerSkin(cached);
    }

    @Nullable
    public static String getFaceTexturePath(@Nonnull String roleId) {
        String cached = FACE_TEXTURE_BY_ROLE.get(roleId);
        if (cached != null) {
            return cached;
        }

        String texturePath = resolveFaceTexturePath(getFixedSkin(roleId));
        if (texturePath != null && !texturePath.isBlank()) {
            FACE_TEXTURE_BY_ROLE.put(roleId, texturePath);
        }
        return texturePath;
    }

    @Nonnull
    private static PlayerSkin generateSkin(@Nonnull String roleId) {
        long seed = ROLE_SEEDS.getOrDefault(roleId, stableSeed(roleId));
        return CosmeticsModule.get().generateRandomSkin(new Random(seed));
    }

    @Nullable
    private static String resolveFaceTexturePath(@Nonnull PlayerSkin skin) {
        if (skin.face == null) {
            return null;
        }

        PlayerSkinPart facePart = CosmeticsModule.get().getRegistry().getFaces().get(skin.face);
        if (facePart == null) {
            return null;
        }
        if (facePart.getGreyscaleTexture() != null && !facePart.getGreyscaleTexture().isBlank()) {
            return facePart.getGreyscaleTexture();
        }

        Map<String, PlayerSkinPartTexture> textures = facePart.getTextures();
        if (textures == null || textures.isEmpty()) {
            return null;
        }
        return textures.entrySet().stream()
            .filter(entry -> entry.getValue() != null && entry.getValue().getTexture() != null && !entry.getValue().getTexture().isBlank())
            .min(Comparator.comparing(Map.Entry::getKey))
            .map(Map.Entry::getValue)
            .map(PlayerSkinPartTexture::getTexture)
            .orElse(null);
    }

    private static long stableSeed(@Nonnull String roleId) {
        long hash = 1125899906842597L;
        for (int i = 0; i < roleId.length(); i++) {
            hash = 31L * hash + roleId.charAt(i);
        }
        return hash;
    }
}

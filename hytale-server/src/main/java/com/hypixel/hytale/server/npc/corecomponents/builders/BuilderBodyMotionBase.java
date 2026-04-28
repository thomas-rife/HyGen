package com.hypixel.hytale.server.npc.corecomponents.builders;

import com.hypixel.hytale.server.npc.instructions.BodyMotion;
import javax.annotation.Nonnull;

public abstract class BuilderBodyMotionBase extends BuilderMotionBase<BodyMotion> {
   public BuilderBodyMotionBase() {
   }

   @Nonnull
   @Override
   public final Class<BodyMotion> category() {
      return BodyMotion.class;
   }
}

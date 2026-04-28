package com.hypixel.hytale.server.core.plugin;

public enum PluginState {
   NONE,
   SETUP,
   START,
   ENABLED,
   SHUTDOWN,
   DISABLED,
   FAILED;

   private PluginState() {
   }

   public boolean isInactive() {
      return switch (this) {
         case NONE, DISABLED, FAILED -> true;
         default -> false;
      };
   }
}

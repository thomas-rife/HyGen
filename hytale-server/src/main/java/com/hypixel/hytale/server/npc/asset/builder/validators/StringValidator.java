package com.hypixel.hytale.server.npc.asset.builder.validators;

public abstract class StringValidator extends Validator {
   public StringValidator() {
   }

   public abstract boolean test(String var1);

   public abstract String errorMessage(String var1);

   public abstract String errorMessage(String var1, String var2);
}

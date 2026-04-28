package com.hypixel.hytale.server.npc.asset.builder.validators;

public abstract class StringArrayValidator extends Validator {
   public StringArrayValidator() {
   }

   public abstract boolean test(String[] var1);

   public abstract String errorMessage(String var1, String[] var2);

   public abstract String errorMessage(String[] var1);
}

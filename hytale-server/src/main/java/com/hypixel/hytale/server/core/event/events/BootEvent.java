package com.hypixel.hytale.server.core.event.events;

import com.hypixel.hytale.event.IEvent;
import javax.annotation.Nonnull;

public class BootEvent implements IEvent<Void> {
   public BootEvent() {
   }

   @Nonnull
   @Override
   public String toString() {
      return "BootEvent{}";
   }
}

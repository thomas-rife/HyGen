package com.example.exampleplugin.features;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import javax.annotation.Nonnull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class OptionalFeatureInstaller {
    private OptionalFeatureInstaller() {
    }

    public static void installIfPresent(@Nonnull JavaPlugin plugin, @Nonnull String className) {
        invokeOptional(className, "install", new Class<?>[]{JavaPlugin.class}, new Object[]{plugin});
    }

    public static void shutdownIfPresent(@Nonnull String className) {
        invokeOptional(className, "shutdown", new Class<?>[0], new Object[0]);
    }

    private static void invokeOptional(
        @Nonnull String className,
        @Nonnull String methodName,
        @Nonnull Class<?>[] parameterTypes,
        @Nonnull Object[] args
    ) {
        Class<?> featureClass;
        try {
            featureClass = Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return;
        }

        try {
            Method method = featureClass.getMethod(methodName, parameterTypes);
            method.invoke(null, args);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Optional feature " + className + " is missing " + methodName + ".", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Optional feature " + className + " " + methodName + " is not accessible.", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("Optional feature " + className + " failed to run " + methodName + ".", cause);
        }
    }
}

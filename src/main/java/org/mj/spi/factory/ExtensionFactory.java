package org.mj.spi.factory;


import org.mj.spi.annotation.Spi;

@Spi("spi")
public interface ExtensionFactory {
    <T> T getExtension(String key,Class<T> clazz);
}

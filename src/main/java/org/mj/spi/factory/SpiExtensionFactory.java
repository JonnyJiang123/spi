package org.mj.spi.factory;



import org.mj.spi.loader.ExtensionLoader;

import java.util.Optional;
import org.mj.spi.annotation.Spi;

public class SpiExtensionFactory implements ExtensionFactory{

    @Override
    public <T> T getExtension(String key, Class<T> clazz) {
        return Optional
                .ofNullable(clazz)
                .filter(Class::isInterface)
                .filter(tClass -> tClass.isAnnotationPresent(Spi.class))
                .map(tClass -> ExtensionLoader.getExtension(clazz,key))
                .orElse(null);
    }
}

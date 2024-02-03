package org.mj.spi.loader;

import com.sun.org.slf4j.internal.Logger;
import com.sun.org.slf4j.internal.LoggerFactory;
import org.mj.spi.annotation.Spi;
import org.mj.spi.annotation.SpiProvider;
import org.mj.spi.factory.ExtensionFactory;
import org.mj.spi.util.MapUtils;
import org.mj.spi.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * example:
 * ServiceLoadBalancer serviceLoadBalancer =
 *        ExtensionLoader.getExtension(ServiceLoadBalancer.class,"random");
 * file: META-INF/services/org.mj.ServiceLoadBalancer   content: random=org.mj.random.RandomServiceLoadBalancer
 *
 * @param <T>
 */
public class ExtensionLoader<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionLoader.class);
    private static final String SERVICES_DIRECTORY = "META-INF/services/";
    private static final String[] SPI_DIRECTORIES = {
            SERVICES_DIRECTORY
    };
    /**
     * 每个spi服务接口->ExtensionLoader
     */
    private static final Map<Class<?>,ExtensionLoader<?>> LOADERS = new ConcurrentHashMap<>();
    /**
     * 当前ExtensionLoader加载的Spi接口
     */
    private final Class<T> clazz;
    private final ClassLoader classLoader;
    /**
     * 每个spi提供者name->具体的实现类
     */
    private final Holder<Map<String,Class<?>>> cachedClasses = new Holder<>();
    /**
     * 服务提供者name->服务提供者实例
     */
    private final Map<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    /**
     * 每个spi服务提供者的class -> 具体的实例
     */
    private final Map<Class<?>,Object> spiProviders = new ConcurrentHashMap<>();
    /**
     * spi扩展文件中的key
     */
    private String cacheDefaultName;
    private ExtensionLoader(final Class<T> clazz, final ClassLoader cl){
        this.clazz = clazz;
        this.classLoader =cl;
        if (!Objects.equals(clazz, ExtensionFactory.class)){
            ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getExtensionClasses();
        }
    }

    public static <T> ExtensionLoader<T> getExtensionLoader(final Class<T> clazz,final ClassLoader cl) {
        Objects.requireNonNull(clazz,"extension class is null");
        if (!clazz.isInterface()){
            throw new IllegalArgumentException("extension class ("  + clazz + ") is not an interface");
        }
        if (!clazz.isAnnotationPresent(Spi.class)){
            throw new IllegalArgumentException("extension class (" + clazz + ") without @" + Spi.class + " annotation");
        }
        ExtensionLoader<T> extensionLoader = (ExtensionLoader<T>) LOADERS.get(clazz);
        if (Objects.nonNull(extensionLoader)){
            return extensionLoader;
        }
        LOADERS.putIfAbsent(clazz,new ExtensionLoader<T>(clazz,cl));
        return (ExtensionLoader<T>)LOADERS.get(clazz);
    }

    /**
     * 1. 每个spi服务提供者name只能有一个对应的类，name和类 唯一绑定
     * 2. 每个spi服务可以有多个实现，但是需要不同的spi提供者名称
     * <pre>
     * 1. 获取或者创建一个spi接口的extensionLoader <br>
     * 2. 加载实现了指定spi接口的spi实现类<br>
     * 3. 从cachedInstances查看当前Spi提供者名称是否有加载过的实例<br>
     * 4. 从cachedClasses获取Spi提供者对应的类<br>
     * 5. 从spiProviders获取Spi提供者类对应的实例<br>
     * 6. 直接通过反射实例化spi提供者实例<br>
     * </pre>
     * @param clazz spi接口
     * @param name 具体服务提供者名称
     * @return spi服务提供者实例
     * @param <T> spi
     */
    public static <T> T getExtension(final Class<T> clazz,String name){
        return StringUtils.isBlank(name) ? getExtensionLoader(clazz).getDefaultSpiProviderInstance()
                : getExtensionLoader(clazz).getSpiProviderInstance(name);
    }

    public static <T> ExtensionLoader<T> getExtensionLoader(final Class<T> clazz){
        return getExtensionLoader(clazz,ExtensionLoader.class.getClassLoader());
    }
    public T getDefaultSpiProviderInstance(){
        getExtensionClasses();
        if (StringUtils.isBlank(cacheDefaultName)){
            return null;
        }
        return getSpiProviderInstance(cacheDefaultName);
    }

    public T getSpiProviderInstance(final String name){
        if (StringUtils.isBlank(name)){
            throw new NullPointerException("get spi provider name is null");
        }
        Holder<Object> holder = cachedInstances.get(name);
        if (Objects.isNull(holder)){
            cachedInstances.putIfAbsent(name,new Holder<>());
            holder = cachedInstances.get(name);
        }
        Object value = holder.value;
        if (Objects.isNull(value)){
            synchronized (cachedInstances){
                value = holder.value;
                if (Objects.isNull(value)){
                    value = createExtension(name);
                    holder.setValue(value);
                }
            }
        }
        return (T)value;
    }

    public List<T> getSpiProviderInstances(){
        Map<String,Class<?>> extensionClasses =  getExtensionClasses();
        if (MapUtils.isEmpty(extensionClasses)){
            return Collections.emptyList();
        }
        if (Objects.equals(extensionClasses.size(),cachedInstances.size())){
            return (List<T>) this.cachedInstances.values().stream().map(e -> e.getValue()
            ).collect(Collectors.toList());
        }
        List<T> instances = new ArrayList<>();
        extensionClasses.forEach((name,v)->{
            T spiProviderInstance = this.getSpiProviderInstance(name);
            instances.add(spiProviderInstance);
        });
        return instances;
    }

    @SuppressWarnings("unchecked")
    private T createExtension(final String name){
        Class<?> clazz =  getExtensionClasses().get(name);
        if (Objects.isNull(clazz)){
            throw new IllegalArgumentException(String.format("load %s error, name is %s",this.clazz.getName(),name));
        }
        Object provider = spiProviders.get(clazz);
        if (Objects.isNull(provider)){
            try{
                spiProviders.putIfAbsent(clazz,clazz.newInstance());
                provider = spiProviders.get(clazz);
            }catch (Exception e){
                throw new IllegalStateException("extension name: " + name + " class: "
                        + clazz +" cannot be instanced, error: " + e.getMessage(),e);
            }
        }
        return (T)provider;
    }

    public Map<String,Class<?>> getExtensionClasses(){
        Map<String, Class<?>> classMap = cachedClasses.getValue();
        if (Objects.isNull(classMap)){
            synchronized (cachedClasses){
                classMap = cachedClasses.getValue();
                if (Objects.isNull(classMap)){
                    classMap = loadExtensionClass();
                    cachedClasses.setValue(classMap);
                }
            }
        }
        return classMap;
    }

    private Map<String,Class<?>> loadExtensionClass(){
        Spi spi = clazz.getAnnotation(Spi.class);
        if (Objects.nonNull(spi)){
            String value = spi.value();
            if (value != null && !value.isEmpty()){
                cacheDefaultName = value;
            }
        }
        Map<String,Class<?>> classes = new HashMap<>(16);
        loadDirectory(classes);
        return classes;
    }
    private void loadDirectory(final Map<String,Class<?>> classMap){
        for (String spiDirecter : SPI_DIRECTORIES) {
            String fileName = spiDirecter + clazz.getName();
            try{
                Enumeration<URL> urls = Objects.nonNull(this.classLoader) ? this.classLoader.getResources(fileName)
                        : ClassLoader.getSystemResources(fileName);
                if (Objects.nonNull(urls)){
                    while (urls.hasMoreElements()){
                        URL url = urls.nextElement();
                        loadResources(classMap,url);
                    }
                }
            }catch (Exception e){
                LOGGER.error("load extension class error: {}",e.getMessage(),e);
            }
        }
    }

    private void loadResources(final Map<String,Class<?>> classMap,final URL url){
        try(InputStream inputStream = url.openStream()){
            Properties properties = new Properties();
            properties.load(inputStream);
            properties.forEach((k,v)->{
                String name = (String) k;
                String classPath = (String) v;
                if (StringUtils.isNotBlank(name) && StringUtils.isNotBlank(classPath)){
                    try {
                        loadClass(classMap,name,classPath);
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException("load extension resources error", e);
                    }
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("load extension resources error",e);
        }
    }

    private void loadClass(final Map<String,Class<?>> classMap,final String name,String path) throws ClassNotFoundException {
        Class<?> subClazz = Objects.nonNull(this.classLoader) ? Class.forName(path,true,this.classLoader) : Class.forName(path);
        if (!clazz.isAssignableFrom(subClazz)){
            throw new IllegalStateException("load extension resource error, " + subClazz + " subtype is not of " + clazz);
        }
        if (!subClazz.isAnnotationPresent(SpiProvider.class)){
            throw new IllegalStateException("load extension resource error, " + subClazz + " without @" + SpiProvider.class + " annotation");
        }
        Class<?> oldClass = classMap.get(name);
        if (Objects.isNull(oldClass)){
            classMap.putIfAbsent(name,subClazz);
        } else if (!Objects.equals(oldClass, subClazz)) {
            throw new IllegalStateException("load extension resource error, duplicated spi provider for "
                    + clazz.getName() + " on " + oldClass.getName() + " or " + subClazz.getName());
        }
    }

    /**
     * 提供可见性
     * @param <T>
     */
    public static class Holder<T>{
        private volatile T value;
        public T getValue(){
            return value;
        }
        public void setValue(final T value){
            this.value = value;
        }
    }
}

package ru.aristar.jnuget.sources;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import javax.activation.UnsupportedDataTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.aristar.jnuget.common.CustomProxyAuthenticator;
import ru.aristar.jnuget.common.CustomProxySelector;
import ru.aristar.jnuget.common.OptionConverter;
import ru.aristar.jnuget.common.Options;
import ru.aristar.jnuget.common.ProxyOptions;
import ru.aristar.jnuget.common.StorageOptions;
import ru.aristar.jnuget.common.TriggerOptions;
import ru.aristar.jnuget.files.Nupkg;
import ru.aristar.jnuget.sources.push.ModifyStrategy;
import ru.aristar.jnuget.sources.push.AfterTrigger;
import ru.aristar.jnuget.sources.push.BeforeTrigger;
import ru.aristar.jnuget.sources.push.ModifyStrategy;

/**
 * Фабрика источников данных
 *
 * @author sviridov
 */
public class PackageSourceFactory {

    //TODO Переписать ифабрику под использование ObjectDescriptor
    /**
     * Экземпляр фабрики
     */
    private volatile static PackageSourceFactory instance;
    /**
     * Настройки сервера
     */
    private final Options options;

    /**
     * Конструктор, перечитывающий настройки
     */
    public PackageSourceFactory() {
        this.options = Options.loadOptions();
    }
    /**
     * Источник пакетов
     */
    private volatile RootPackageSource packageSource = null;
    /**
     * Логгер
     */
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * Создает индексируемую обертку для хранилища пакетов
     *
     * @param packageSource хранилище, которое необходимо индексировать
     * @param storageName имя, используемое для сохранения индекса
     * @param refreshInterval интервал обновления информации в индекск
     * @return индексируемое хранилище
     */
    protected PackageSource<Nupkg> createIndexForStorage(PackageSource<Nupkg> packageSource,
            String storageName, Integer refreshInterval) {
        logger.debug("Создание индекса для хранилища {}", new Object[]{packageSource});
        IndexedPackageSource indexedPackageSource = new IndexedPackageSource();
        indexedPackageSource.setUnderlyingSource(packageSource);
        if (storageName != null) {
            File storageFile = IndexedPackageSource.getIndexSaveFile(Options.getNugetHome(), storageName);
            indexedPackageSource.setIndexStoreFile(storageFile);
        }
        if (refreshInterval != null) {
            logger.info("Интервал обновления для хранилища {} установлен в {}",
                    new Object[]{packageSource, refreshInterval});
            indexedPackageSource.setRefreshInterval(refreshInterval);
        }
        return indexedPackageSource;
    }

    /**
     * Создание нового корневого хранилища на основе настроек
     *
     * @param serviceOptions настройки приложения
     * @return хранилище пакетов
     */
    protected RootPackageSource createRootPackageSource(Options serviceOptions) {
        //Создание корневого хранилища
        logger.info("Инициализация файлового хранища");
        RootPackageSource rootPackageSource = new RootPackageSource();
        ModifyStrategy pushStrategy = null;
        pushStrategy = new ModifyStrategy(true);
        logger.warn("Для корневого репозитория разрешается публикация "
                + "пакетов. (поведение по умолчанию)");
        rootPackageSource.setPushStrategy(pushStrategy);
        for (StorageOptions storageOptions : serviceOptions.getStorageOptionsList()) {
            try {
                PackageSource<Nupkg> childSource = createPackageSource(storageOptions);
                rootPackageSource.getSources().add(childSource);
            } catch (Exception e) {
                logger.warn("Ошибка создания хранилища пакетов", e);
            }
        }
        logger.info("Создано {} хранилищ", new Object[]{rootPackageSource.getSources().size()});
        return rootPackageSource;
    }

    /**
     * Производит поиск сеттера для свойства
     *
     * @param sourceClass класс, в котором производится поиск сеттера
     * @param propertyName имя свойства
     * @return метод - сеттер
     * @throws NoSuchMethodException метод не найден
     */
    protected Method findSetter(Class<?> sourceClass, String propertyName) throws NoSuchMethodException {
        String setterName = "set" + propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1);
        for (Method method : sourceClass.getMethods()) {
            if (method.getName().equals(setterName) && method.getParameterTypes().length == 1) {
                return method;
            }
        }
        throw new NoSuchMethodException("Метод " + setterName + " не найден в классе " + sourceClass.getName());
    }

    /**
     * Создание нового дочернего хранилища на основе настроек
     *
     * @param storageOptions настройки хранилища
     * @return хранилище пакетов
     * @throws Exception ошибка создания хранилища
     */
    protected PackageSource<Nupkg> createPackageSource(StorageOptions storageOptions)
            throws Exception {
        //Создание файлового хранилища
        logger.info("Инициализация хранилища типа {}", new Object[]{storageOptions.getClassName()});
        Class<?> sourceClass = Class.forName(storageOptions.getClassName());
        Object object = sourceClass.newInstance();
        if (!(object instanceof PackageSource)) {
            throw new UnsupportedDataTypeException("Класс " + sourceClass + " не является хранилищем пакетов");
        }
        @SuppressWarnings("unchecked")
        PackageSource<Nupkg> newSource = (PackageSource) object;
        setObjectProperties(storageOptions.getProperties(), object);
        ModifyStrategy pushStrategy = createPushStrategy(storageOptions);
        newSource.setPushStrategy(pushStrategy);
        logger.info("Установлена стратегия фиксации");
        if (storageOptions.isIndexed()) {
            newSource = createIndexForStorage(newSource, storageOptions.getStorageName(), storageOptions.getRefreshInterval());
        }
        logger.info("Хранилище создано");
        return newSource;
    }

    /**
     * Создает стратегию фиксации пакетов
     *
     * @param storegeOptions настройки стратегии
     * @return стратегия фиксации
     * @throws Exception ошибка создания стратегии
     */
    protected ModifyStrategy createPushStrategy(StorageOptions storegeOptions) throws Exception {
        //Создание стратегии фиксации
        //TODO убрать настройки стратегии, оставить только разрешение/запрещение публикации
        logger.info("Инициализация стратегии фиксации и удаления");
        ModifyStrategy pushStrategy = new ModifyStrategy(storegeOptions.isCanPush(), storegeOptions.isCanDelete());
        //Триггеры BEFORE
        pushStrategy.getBeforePushTriggers().addAll(createTriggers(storegeOptions.getBeforeTriggersOptions(), BeforeTrigger.class));
        //Триггеры Afther
        pushStrategy.getAftherPushTriggers().addAll(createTriggers(storegeOptions.getAftherTriggersOptions(), AfterTrigger.class));
        logger.info("Стратегия создана");
        return pushStrategy;
    }

    /**
     * Создает коллекцию триггеров из коллекции настроек
     *
     * @param <T> класс требуемого объекта
     * @param options коллекция настроек
     * @param triggerClass класс требуемого объекта
     * @return коллекция настроек
     * @throws Exception ошибка создания триггера
     */
    protected <T> Collection<T> createTriggers(Collection<TriggerOptions> options, Class<T> triggerClass) throws Exception {
        ArrayList<T> pushTriggers = new ArrayList<>();
        for (TriggerOptions triggerOptions : options) {
            T pushTrigger = createTrigger(triggerOptions, triggerClass);
            pushTriggers.add(pushTrigger);
        }
        return pushTriggers;
    }

    /**
     * Создает триггер фиксации пакета
     *
     * @param <T> класс требуемого объекта
     * @param triggerOptions настройки триггера
     * @param triggerClass класс требуемого объекта
     * @return триггер
     * @throws Exception ошибка создания триггера
     */
    protected <T> T createTrigger(TriggerOptions triggerOptions, Class<T> triggerClass) throws Exception {
        logger.info("Создание триггера типа {}", new Object[]{triggerOptions.getClassName()});
        Class<?> sourceClass = Class.forName(triggerOptions.getClassName());
        if (!triggerClass.isAssignableFrom(sourceClass)) {
            throw new UnsupportedDataTypeException("Класс " + sourceClass
                    + " не является " + BeforeTrigger.class.getCanonicalName());
        }
        Object object = sourceClass.newInstance();
        @SuppressWarnings("unchecked")
        T trigger = (T) object;
        setObjectProperties(triggerOptions.getProperties(), trigger);
        logger.info("Триггер создан");
        return trigger;
    }

    /**
     * Устанавливает свойства объекту
     *
     * @param properties карта свойств
     * @param newObject объект
     * @throws Exception ошибка установки свойств
     */
    private void setObjectProperties(Map<String, String> properties, Object newObject)
            throws Exception {
        Class<?> sourceClass = newObject.getClass();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Method method = findSetter(sourceClass, entry.getKey());
            Class<?> valueType = method.getParameterTypes()[0];
            String stringValue = OptionConverter.replaceVariables(entry.getValue());
            Object value = getValueFromString(valueType, stringValue);
            method.invoke(newObject, value);
        }
    }

    /**
     * Возвращает экземпляр фабрики, или создает новый
     *
     * @return экземпляр фабрики
     */
    public static PackageSourceFactory getInstance() {
        if (instance == null) {
            synchronized (PackageSourceFactory.class) {
                if (instance == null) {
                    instance = new PackageSourceFactory();
                }
            }
        }
        return instance;
    }

    /**
     * Возвращает источник пакетов
     *
     * @param reinitialize необходима переинициализация
     * @return источник пакетов
     */
    public RootPackageSource getPackageSource(boolean reinitialize) {
        if (reinitialize || packageSource == null) {
            synchronized (this) {
                if (packageSource == null) {
                    initializeProxyOptions(options.getProxyOptions());
                    packageSource = createRootPackageSource(options);
                }
            }
        }
        return packageSource;
    }

    /**
     * Возвращает источник пакетов
     *
     * @return источник пакетов
     */
    public RootPackageSource getPackageSource() {
        return getPackageSource(false);
    }

    /**
     * Инициализация настроек прокси
     *
     * @param proxyOptions настройки прокси сервера
     */
    private void initializeProxyOptions(ProxyOptions proxyOptions) {
        if (proxyOptions.getUseSystemProxy() != null && proxyOptions.getUseSystemProxy()) {
            logger.info("Используется системный прокси");
            System.setProperty("java.net.useSystemProxies", "true");
        } else if (proxyOptions.getNoProxy() != null && proxyOptions.getNoProxy()) {
            logger.info("Прокси отключен");
            ProxySelector.setDefault(new CustomProxySelector());
        } else {
            logger.info("Используется прокси {}:{}",
                    new Object[]{proxyOptions.getHost(), proxyOptions.getPort()});
            ProxySelector.setDefault(new CustomProxySelector(
                    proxyOptions.getHost(),
                    proxyOptions.getPort()));
            Authenticator.setDefault(new CustomProxyAuthenticator(
                    proxyOptions.getLogin(),
                    proxyOptions.getPassword()));
        }
    }

    /**
     * @return настройки приложения
     */
    public Options getOptions() {
        return options;
    }

    /**
     * Производит попытку создать значение указанного типа из строки
     *
     * @param <T> тип значения
     * @param valueType тип значения
     * @param stringValue строковое представление типа значения
     * @return распознанное значение
     * @throws InstantiationException ошибка вызова конструктора
     * @throws NoSuchMethodException не найден конструктор, принимающий как
     * аргумент строку
     * @throws IllegalArgumentException не найден конструктор, принимающий как
     * аргумент строку
     * @throws InvocationTargetException ошибка вызова конструктора
     * @throws SecurityException конструктор с указанными параметрами не
     * является публичным
     * @throws IllegalAccessException ошибка вызова конструктора
     */
    public static <T> T getValueFromString(Class<T> valueType, String stringValue) throws
            InstantiationException,
            NoSuchMethodException,
            IllegalArgumentException,
            InvocationTargetException,
            SecurityException,
            IllegalAccessException {
        T value;
        if (valueType.isPrimitive()) {
            value = getPrimitiveValue(stringValue, valueType);
        } else {
            final Constructor<T> constructor = valueType.getConstructor(String.class);
            value = constructor.newInstance(stringValue);
        }
        return value;
    }

    /**
     * Возвращает значение для примитивного типа
     *
     * @param <T> тип значения
     * @param string строковое значение
     * @param targetClass тип значения, в которое требуется преобразовать строку
     * @return преобразованное значение примитивного типа
     */
    @SuppressWarnings("unchecked")
    private static <T> T getPrimitiveValue(String string, Class<T> targetClass) {
        if (targetClass == java.lang.Boolean.TYPE) {
            return (T) Boolean.valueOf(string);
        } else if (targetClass == java.lang.Character.TYPE) {
            return (T) Character.valueOf(string.charAt(0));
        } else if (targetClass == java.lang.Byte.TYPE) {
            return (T) Byte.valueOf(string);
        } else if (targetClass == java.lang.Short.TYPE) {
            return (T) Short.valueOf(string);
        } else if (targetClass == java.lang.Integer.TYPE) {
            return (T) Integer.valueOf(string);
        } else if (targetClass == java.lang.Long.TYPE) {
            return (T) Long.valueOf(string);
        } else if (targetClass == java.lang.Float.TYPE) {
            return (T) Float.valueOf(string);
        } else if (targetClass == java.lang.Double.TYPE) {
            return (T) Double.valueOf(string);
        } else {
            throw new UnsupportedOperationException("Primitive type "
                    + targetClass + " is unsupported");
        }
    }
}

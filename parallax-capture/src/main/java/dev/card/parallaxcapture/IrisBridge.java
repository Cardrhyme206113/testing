package dev.card.parallaxcapture;

import java.lang.reflect.Method;

public final class IrisBridge {
    private final Class<?> irisClass;
    private final Object irisConfig;
    private final Method reload;
    private final Method getCurrentPackName;
    private final Method getShaderPackName;
    private final Method areShadersEnabled;
    private final Method setShaderPackName;
    private final Method setShadersEnabled;
    private final Method save;

    public IrisBridge() throws ReflectiveOperationException {
        irisClass = Class.forName("net.irisshaders.iris.Iris");
        irisConfig = irisClass.getMethod("getIrisConfig").invoke(null);
        if (irisConfig == null) throw new IllegalStateException("Iris config is not initialized yet");

        reload = irisClass.getMethod("reload");
        getCurrentPackName = irisClass.getMethod("getCurrentPackName");
        getShaderPackName = irisConfig.getClass().getMethod("getShaderPackName");
        areShadersEnabled = irisConfig.getClass().getMethod("areShadersEnabled");
        setShaderPackName = irisConfig.getClass().getMethod("setShaderPackName", String.class);
        setShadersEnabled = irisConfig.getClass().getMethod("setShadersEnabled", boolean.class);
        save = irisConfig.getClass().getMethod("save");
    }

    public String currentPackName() {
        try {
            Object v = getCurrentPackName.invoke(null);
            return v == null ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean shadersEnabled() {
        try { return (boolean) areShadersEnabled.invoke(irisConfig); }
        catch (Exception e) { return false; }
    }

    public String configuredPackName() {
        try {
            Object optional = getShaderPackName.invoke(irisConfig);
            Method orElse = optional.getClass().getMethod("orElse", Object.class);
            Object value = orElse.invoke(optional, new Object[]{null});
            return value == null ? null : value.toString();
        } catch (Exception e) {
            return currentPackName();
        }
    }

    public void switchTo(String packName) throws Exception {
        setShaderPackName.invoke(irisConfig, packName);
        setShadersEnabled.invoke(irisConfig, true);
        save.invoke(irisConfig);
        reload.invoke(null);
    }

    public void restore(String packName, boolean enabled) throws Exception {
        setShaderPackName.invoke(irisConfig, packName);
        setShadersEnabled.invoke(irisConfig, enabled);
        save.invoke(irisConfig);
        reload.invoke(null);
    }
}

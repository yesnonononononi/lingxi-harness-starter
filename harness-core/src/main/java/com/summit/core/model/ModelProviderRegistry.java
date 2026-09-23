package com.summit.core.model;


import com.summit.core.conf.ModelConfig;
import com.summit.core.exception.NoSuchModelProviderException;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class ModelProviderRegistry<T>  {
    private final Map<String, ModelProvider<T>> providers = new ConcurrentHashMap<>();




    public T create(ModelConfig config) {
        Objects.requireNonNull(config, "Model config is required");
        String provider = config.getProvider();
        ModelProvider<T> modelProvider = providers.get(provider.trim());
        return modelProvider.create(config);
    }


    public ModelProvider<T> get(String providerName){
        if(!contains(providerName))throw new NoSuchModelProviderException("No such model provider: " + providerName);
        return this.providers.get(providerName);
    }




    public  void register(ModelProvider<T> modelProvider){
        if(modelProvider == null)throw new IllegalArgumentException("Model provider is required");
        String name = modelProvider.name();
        if(name == null || name.isBlank())throw new IllegalArgumentException("Model provider name is required");
        ModelProvider<T> existing = providers.putIfAbsent(name.trim(), modelProvider);
        if (existing != null && existing != modelProvider) {
            throw new IllegalArgumentException("Duplicate model provider: " + name.trim());
        }
    }

    public boolean contains(String provider) {
        return provider != null && !provider.isBlank() && providers.containsKey(provider.trim());
    }


    public  void unregister(String provider){
        if(provider == null || provider.isBlank())throw new IllegalArgumentException("Provider is required");
        providers.remove(provider);
    }
}

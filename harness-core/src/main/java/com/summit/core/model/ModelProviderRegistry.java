package com.summit.core.model;


import com.summit.core.exception.NoSuchModelProviderException;import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModelProviderRegistry<T>  {
    private final Map<String, ModelProvider<T>> providers = new ConcurrentHashMap<>();


    public T create(ModelConfig config) {
        String provider = config.getProvider();

        if(provider == null || provider.isBlank())throw new IllegalArgumentException("Provider is required");

        ModelProvider<T> modelProvider = providers.get(provider);

        if(modelProvider == null)throw new NoSuchModelProviderException("No such model provider: " + provider);

        return modelProvider.create(config);
    }




    public  void register(ModelProvider<T> modelProvider){
        if(modelProvider == null)throw new IllegalArgumentException("Model provider is required");
        providers.put(modelProvider.name(), modelProvider);
    }


    public  void unregister(String provider){
        if(provider == null || provider.isBlank())throw new IllegalArgumentException("Provider is required");
        providers.remove(provider);
    }
}

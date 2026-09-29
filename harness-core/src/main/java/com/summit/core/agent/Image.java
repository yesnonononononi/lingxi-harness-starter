package com.summit.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NonNull;

import java.net.URI;
import java.util.Objects;
@Data
public class Image {
    private final URI url;
    private final String base64Data;
    private final String mimeType;
    private final String revisedPrompt;
    private final DetailLevel detailLevel;

    @JsonCreator
    private Image(
            @JsonProperty("url") URI url,
            @JsonProperty("base64Data") String base64Data,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("revisedPrompt") String revisedPrompt,
            @JsonProperty("detailLevel") DetailLevel detailLevel) {
        this.url = url;
        this.base64Data = base64Data;
        this.mimeType = mimeType;
        this.revisedPrompt = revisedPrompt;
        this.detailLevel = detailLevel;
    }



    public enum DetailLevel {
        /**
         * Low detail.
         */
        LOW,

        /**
         * Medium detail. A balance between detail, cost, and latency.
         */
        MEDIUM,

        /**
         * High detail.
         */
        HIGH,

        /**
         * Ultra-high detail. Highest token count, required for specific use cases such as computer use.
         */
        ULTRA_HIGH,

        /**
         * Auto detail.
         */
        AUTO
    }
    public static final String DEFAULT_MIME_TYPE = "image/png";

    public static Image from(@NonNull URI url){
        return new Image(url, null, DEFAULT_MIME_TYPE, null, DetailLevel.AUTO);
    }

    public static Image from(String base64Data){
        if(base64Data == null || base64Data.isEmpty()) throw new IllegalArgumentException("base64Data cannot be null");
        return new Image(null, base64Data, DEFAULT_MIME_TYPE, null, DetailLevel.AUTO);
    }

    public static Image from(String base64Data, String mimeType){
        if(mimeType == null || mimeType.isEmpty()) throw new IllegalArgumentException("mimeType cannot be null");
        return new Image(null, base64Data, mimeType, null, DetailLevel.AUTO);
    }

    public static Image from(String base64Data, String mimeType, String revisedPrompt){
        if(revisedPrompt == null || revisedPrompt.isEmpty()) throw new IllegalArgumentException("revisedPrompt cannot be null");
        return new Image(null, base64Data, mimeType, revisedPrompt, DetailLevel.AUTO);
    }

    public static Image from(String base64Data, String mimeType, String revisedPrompt, DetailLevel detailLevel){
        if(detailLevel == null) throw new IllegalArgumentException("detailLevel cannot be null");
        return new Image(null, base64Data, mimeType, revisedPrompt, detailLevel);
    }

    public @NonNull String mimeType() {
        return Objects.requireNonNullElse(this.mimeType, DEFAULT_MIME_TYPE);
    }


    public @NonNull DetailLevel detailLevel(){
        return Objects.requireNonNullElse(this.detailLevel, DetailLevel.AUTO);
    }



}

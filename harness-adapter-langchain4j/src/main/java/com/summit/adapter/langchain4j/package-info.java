/**
 * langchain4j framework adapter module (the ONLY module in the project allowed to reference langchain4j).
 *
 * <p>Package layout:</p>
 * <ul>
 *   <li>{@code codec}    - implementations of the core codec SPIs: {@link com.summit.harnesscore.adapter.MessageCodec},
 *       {@link com.summit.harnesscore.adapter.ToolCodec}, {@link com.summit.harnesscore.adapter.TokenEstimator}</li>
 *   <li>{@code model}    - adapters for core {@code ChatModel} / {@code StreamingChatModel} (accept any langchain4j model instance)</li>
 *   <li>{@code provider} - model factories per protocol (OpenAI protocol, wired to the core Provider SPI and configuration)</li>
 * </ul>
 *
 * <p>To support another model framework, create a sibling module (e.g. harness-adapter-spring-ai) following the same pattern;
 * core / runtime need no changes.</p>
 */
package com.summit.adapter.langchain4j;

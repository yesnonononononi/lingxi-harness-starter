# Per-call image output options

`ImageGenerationOptions` accepts optional `background` and `outputFormat` fields in addition to size, quality, and count. The three-argument constructor and old persisted JSON remain compatible. Unspecified output fields retain provider defaults.

The LangChain4j adapter forwards both fields through the SDK's native builder for generation, editing, and masked editing. Each override uses an isolated SDK wrapper while retaining the provider's shared HTTP transport. Legacy providers reject unsupported output overrides instead of silently discarding them.

```java
ImageGenerationOptions options = new ImageGenerationOptions(
        "1024x1536", "high", 1, "transparent", "png");
ChatResponseEntity response = model.edit(image, mask, prompt, options);
```

These are protocol requests, not guarantees of model compliance. Consumers requiring a particular file format or transparency must validate returned bytes. The framework does not add image processing or business-specific asset validation.

The current SDK supports a single image with an optional mask. Multi-image editing is intentionally not implemented.

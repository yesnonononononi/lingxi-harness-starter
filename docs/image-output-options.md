# Per-call image output options

`ImageGenerationOptions` accepts optional `background` and `outputFormat` fields in addition to size, quality, and count. The three-argument constructor and old persisted JSON remain compatible. Unspecified output fields retain provider defaults.

The built-in image model sends both fields with generation, editing, and masked editing requests. Overrides apply to a single request.

```java
ImageGenerationOptions options = new ImageGenerationOptions(
        "1024x1536", "high", 1, "transparent", "png");
ChatResponseEntity response = model.edit(image, mask, prompt, options);
```

These are protocol requests, not guarantees of model compliance. Consumers requiring a particular file format or transparency must validate returned bytes. The framework does not add image processing or business-specific asset validation.

Editing accepts one source image and an optional mask.

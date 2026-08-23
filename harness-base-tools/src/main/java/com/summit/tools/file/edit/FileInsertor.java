package com.summit.tools.file.edit;


import com.summit.tools.arguments.EditFileRequest;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;

public class FileInsertor  {
    public static FileEditorResult insert(EditFileRequest editFileRequest, @NonNull File targetFile, Charset charset, Integer aroundLines) {
        int insertIndex;
        EditType type = EditType.fromString(editFileRequest.getType());
        String newText = Objects.requireNonNullElse(editFileRequest.getNewText(), "");
        String anchor = editFileRequest.getAnchor();
        try {

            //1, validate newText whether duplicate or not
            String content = Files.readString(targetFile.toPath(), charset);
            if (!content.isBlank()) {
                if (anchor == null || anchor.isEmpty())
                    return FileEditorResult.err("anchor is empty. There are no ways to insure position is correct");
                if ((insertIndex = content.indexOf(anchor)) == -1) {
                    return FileEditorResult.err("anchor not found");
                }
                if (content.indexOf(anchor, insertIndex + anchor.length()) != -1) {
                    Map<Integer, String> duplicateArea = FileEditor.resolveDuplicateArea(anchor, content, aroundLines);
                    return FileEditorResult.err(FileEditor.handleDuplicateStr(duplicateArea));
                }
                String newContent;
                if (type.equals(EditType.INSERT_BEFORE)) {
                    newContent = content.substring(0, insertIndex) + newText + content.substring(insertIndex);
                } else {
                    newContent = content.substring(0, insertIndex + anchor.length()) + newText + content.substring(insertIndex + anchor.length());
                }
                Files.writeString(targetFile.toPath(), newContent, charset);
                return FileEditorResult.success();
            } else {
                if (newText.isBlank()) return FileEditorResult.err("both the file and the inserted content are empty");
                // write all text to file
                Files.writeString(targetFile.toPath(), newText, charset);
            }
            return FileEditorResult.success();
        } catch (
                Exception e) {
            return FileEditorResult.err(e.getMessage());
        }
    }
}

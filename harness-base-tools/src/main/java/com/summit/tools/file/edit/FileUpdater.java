package com.summit.tools.file.edit;


import com.summit.tools.arguments.EditFileRequest;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;

public class FileUpdater {
    public static FileEditorResult update(EditFileRequest editFileRequest, @NonNull File targetFile, Charset charset, Integer aroundLines){
        int oldIndex ;
        String newText = Objects.requireNonNullElse(editFileRequest.getNewText(),"");
        String oldText = editFileRequest.getOldText();

        try {
            EditType type = EditType.fromString(editFileRequest.getType());
            if (oldText == null || oldText.isBlank()) return FileEditorResult.err("old text is empty");

            //1, validate newText whether duplicate or not
            String content = Files.readString(targetFile.toPath(), charset);

            if(content.isEmpty())   return FileEditorResult.err("file is empty");

            // ensure old text exist in file
            if ((oldIndex = content.indexOf(oldText)) == -1) {
                 return FileEditorResult.err("old text not found");
            }
            // ensure old text not duplicate in file
            if(content.indexOf(oldText,oldIndex+oldText.length()) != -1){
                Map<Integer, String> duplicateArea = FileEditor.resolveDuplicateArea(oldText, content, aroundLines);
                return FileEditorResult.err(FileEditor.handleDuplicateStr(duplicateArea));
            }

            //2, edit. replace old text with new text and is equal result for DELETE type when newText is empty
            //2.1 handle DELETE type. force new text to empty
            if(type.equals(EditType.DELETE)) newText = "";

            String newContent =
                    content.substring(0, oldIndex)
                            + newText
                            + content.substring(oldIndex + oldText.length());

            Files.writeString(targetFile.toPath(), newContent, charset);

            return FileEditorResult.success();
        } catch (Exception e){
            return FileEditorResult.err(e.getMessage());
        }

    }
}

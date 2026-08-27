package com.summit.tools.file.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归一化文本匹配工具：去除空白字符与引号（半角/全角）后进行模糊匹配。
 * <p>
 * 作为 edit_file 精确匹配失败时的兜底策略，避免模型输出的 oldText/anchor
 * 与文件内容因空格、引号等细微差异而匹配失败。归一化细节全部封装在本类中，
 * 主流程只关心匹配结果区间。
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * 归一化匹配到的原文区间。
     *
     * @param start 原文中的起始索引（含）
     * @param end   原文中的结束索引（不含）
     */
    public record Match(int start, int end) {
    }

    /**
     * 归一化：去除所有空白字符（含全角空格）与引号（半角/全角）。
     */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // append char to result if not whitespace and not quote
            if (isKept(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 在原文中查找所有"归一化后包含 target 归一化形式"的区间，结果映射回原文位置。
     * 匹配为空（target 全为空白/引号）时返回空列表。
     */
    public static List<Match> findAll(String content, String target) {
        List<Match> result = new ArrayList<>();
        if (content == null || target == null) return result;
        // normalize content and target
        String normContent = normalize(content);
        String normTarget = normalize(target);
        if (normContent.isEmpty() || normTarget.isEmpty()) return result;

        int from = 0;
        while (true) {
            int normIdx = normContent.indexOf(normTarget, from);
            if (normIdx < 0) break;
            result.add(mapBackToOriginal(content, normIdx, normIdx + normTarget.length()));
            from = normIdx + normTarget.length();
        }
        return result;
    }

    /**
     * 构建归一化重复匹配的候选区域消息（行号 + 上下文），
     * 输出格式与 FileEditor 的精确重复提示保持一致。
     */
    public static String buildDuplicateArea(List<Match> matches, String content, int aroundLine) {
        Map<Integer, String> map = new LinkedHashMap<>();
        int around = Math.max(aroundLine, 1);
        for (Match m : matches) {
            int start = FileEditor.findStartLineIndex(content, m.start());
            int end = FileEditor.findEndLineIndex(content, m.end());
            for (int j = 0; j < around; j++) {
                if (start != 0) start = FileEditor.findStartLineIndex(content, start - 1);
                if (end != content.length()) end = FileEditor.findEndLineIndex(content, end + 1);
            }
            map.put(FileEditor.findLineNumber(content, m.start()), content.substring(start, end));
        }
        return FileEditor.handleDuplicateStr(map);
    }

    /** 把归一化文本中的 [normStart, normEnd) 映射回原文区间 */
    private static Match mapBackToOriginal(String content, int normStart, int normEnd) {
        int start = -1;
        int end = -1;
        int norm = 0;
        for (int i = 0; i < content.length(); i++) {
            if (!isKept(content.charAt(i))) continue;  // continue if char is invalid
            if (norm == normStart) start = i;
            if (norm == normEnd - 1) {
                end = i + 1;
                break;
            }
            norm++;
        }
        return new Match(start, end);
    }

    private static boolean isKept(char c) {
        return !Character.isWhitespace(c) && !isQuote(c);
    }

    private static boolean isQuote(char c) {
        return c == '\'' || c == '"' || c == '`'
                || c == '‘' || c == '’'   // '' 中文单引号
                || c == '“' || c == '”';  // "" 中文双引号
    }
}

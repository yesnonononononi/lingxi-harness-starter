package com.summit.runtime.plan;

import com.summit.core.plan.PlanDecision;
import com.summit.core.plan.PlanStep;
import com.summit.core.plan.PlanStepStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tolerant parser that turns a raw agent AI message (the plan text produced in the
 * PLANNING round) into a structured {@link PlanDecision}.
 *
 * <p>Rules:
 * <ul>
 *   <li>the first meaningful (non-blank) line becomes the plan title;</li>
 *   <li>the following lines are turned into steps after stripping common list
 *       markers ({@code 1.}, {@code -}, {@code *}, {@code #}, markdown checkboxes);</li>
 *   <li>a plan without any list line degrades to title only (empty steps);
 *       parsing never throws — malformed text simply yields an empty decision.</li>
 * </ul>
 */
public class PlanTextParser {

    private static final Pattern LIST_MARKER = Pattern.compile(
            "^\\s*(?:[-*+]\\s+|\\d+\\s*[.)]\\s+|#+\\s*|(?:-\\s*)?\\[([ xX])\\]\\s*)");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^\\s*(?:-{3,}|\\*{3,}|_{3,}|={3,}|~{3,})\\s*$");

    public PlanDecision parse(String text) {
        if (text == null || text.isBlank()) {
            return new PlanDecision("", List.of());
        }
        String[] rawLines = text.split("\r?\n");
        List<String> titleAndBody = new ArrayList<>();
        for (String line : rawLines) {
            if (line.isBlank() || HORIZONTAL_RULE.matcher(line).matches()) {
                continue;
            }
            titleAndBody.add(line.trim());
        }
        if (titleAndBody.isEmpty()) {
            return new PlanDecision("", List.of());
        }

        String title = titleAndBody.get(0);
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 1; i < titleAndBody.size(); i++) {
            String cleaned = stripListMarker(titleAndBody.get(i));
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }
            steps.add(new PlanStep(UUID.randomUUID().toString(), cleaned, PlanStepStatus.PENDING));
        }
        return new PlanDecision(title, List.copyOf(steps));
    }

    private String stripListMarker(String line) {
        return LIST_MARKER.matcher(line).replaceFirst("");
    }
}

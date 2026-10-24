package com.summit.runtime.compact;

import com.summit.core.agent.CompactionState;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextCompacter;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.agent.AgentConfig;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * Local pruning of the tool history: the fallback band that runs once summarization is closed.
 *
 * <p>Pruning keeps the <b>structure</b> of the conversation and drops the payload. No model is
 * called, so a pass takes milliseconds and can never block the loop on a model timeout.</p>
 *
 * <h2>Segments (§3.5)</h2>
 * <p>A segment is one assistant message plus every tool result it owns. Ownership is resolved
 * through the tool-call id ({@code AiMessageEntity.toolCalls[*].id == ToolMessageEntity.id}), never
 * through adjacency: adjacency breaks as soon as the results of one assistant message are committed
 * in several batches, the history was partly trimmed, or the provider returned them in another
 * order. A tool result whose id matches no assistant message is an <b>orphan</b> and is dropped
 * with a warning — it is exactly what a provider rejects. User and system messages belong to no
 * segment and are never trimmed.</p>
 *
 * <h2>Input premise (§3.5.1)</h2>
 * <ul>
 *   <li><b>Complete segment</b>: the assistant message has tool calls and every call id has a
 *       result — graded {@code FULL / SKELETON / DROP};</li>
 *   <li><b>Active / incomplete segment</b>: at least one call id has no result — kept verbatim and
 *       marked {@code FROZEN}, thinking included. Such a group is still mid-protocol: trimming it
 *       could orphan a call that is waiting for its result, and dropping its thinking could violate
 *       Anthropic's replay rules;</li>
 *   <li><b>Plain-text assistant segment</b>: no tool calls at all — graded like any other segment
 *       ({@code DROP} omits it, {@code SKELETON} keeps its text). Grading it explicitly is what
 *       keeps these messages from silently disappearing from the segment model.</li>
 * </ul>
 *
 * <h2>Two phases (§3.6)</h2>
 * <ol>
 *   <li><b>Allocation, newest first.</b> The non-cuttable part (all system messages, all user
 *       messages, every {@code FROZEN} segment) is subtracted from
 *       {@code pruneTargetRatio × maxTokens}; then each gradable segment, walking from newest to
 *       oldest, becomes {@code FULL} (only within the newest {@code fullSegmentCount} segments and
 *       only with budget), else {@code SKELETON} (with budget), else {@code DROP}. This order
 *       expresses importance, not output order.</li>
 *   <li><b>Output in original message order.</b> All system messages first — the single reordering
 *       providers require — then the remaining messages in their original sequence, each segment
 *       rendered at its level.</li>
 * </ol>
 *
 * <p>Pruning never gives up because the non-cuttable part is large (§3.7): it degrades every
 * gradable segment as far as the budget allows and accepts the overflow with a warning. Trimming
 * what can be trimmed still reclaims every token it trims.</p>
 *
 * <p>Pruning is idempotent: a placeholder written by an earlier pass is recognized and kept
 * verbatim, and rewriting an already rewritten assistant message changes nothing, so a second pass
 * over the same context produces the same product.</p>
 */
@Slf4j
public class DefaultContextPruner implements ContextCompacter {

    /** Prefix of the placeholder replacing a trimmed tool result; also the idempotency guard. */
    private static final String OMITTED_PREFIX = "[output omitted ·";

    /** How much of a segment survives a prune pass. */
    private enum Level {
        /** Assistant text, tool calls and tool results kept: thinking removed, output capped. */
        FULL,
        /** Assistant text and tool calls kept; every tool result replaced by one placeholder line. */
        SKELETON,
        /** Whole segment omitted. */
        DROP
    }

    /**
     * One graded unit of the context.
     *
     * @param aiIndex     position of the assistant message in the original list
     * @param ai          the assistant message itself
     * @param toolIndexes positions of the tool results it owns, in original order
     * @param tools       the tool results it owns, in the same order
     * @param frozen      whether the segment is kept verbatim (§3.5.1)
     */
    private record Segment(int aiIndex, AiMessageEntity ai, List<Integer> toolIndexes,
                           List<ToolMessageEntity> tools, boolean frozen) {
    }

    /**
     * One produced message together with the input index it came from, so ordering stays verifiable.
     *
     * @param message     the message written into the product
     * @param sourceIndex index of its source in the pre-prune list
     */
    private record OutputEntry(Message message, int sourceIndex) {
    }

    private final AgentConfig agentConfig;
    private final ContextUsageReporter usage;
    private final Tokenizer tokenizer;

    /**
     * @param agentConfig the prune policy (target ratio, full-segment window, tool-output cap)
     * @param usage       the reporter that publishes the squeeze events; its tokenizer is reused so
     *                    budget accounting and the reported usage agree
     */
    public DefaultContextPruner(AgentConfig agentConfig, ContextUsageReporter usage) {
        this.agentConfig = Objects.requireNonNull(agentConfig, "agentConfig");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.tokenizer = Objects.requireNonNull(usage.tokenizer, "usage.tokenizer");
    }

    /**
     * Prunes the execution's context once.
     *
     * <p>Every pass publishes a paired {@code SQUEEZE_STARTED} / {@code SQUEEZE_COMPLETED} event,
     * including a pass that trims nothing: consumers key the in-flight state off the pair, and a
     * lone {@code STARTED} would leave them stuck on it.</p>
     *
     * @param request the compaction request; this band ignores the decision it carries
     * @return {@code true} when the context changed
     */
    @Override
    public boolean compact(ContextCompactRequest request) {
        Execution execution = Objects.requireNonNull(request, "request").execution();
        usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_STARTED,
                ContextUpdateEvent.Band.PRUNE, "Context prune started");
        boolean changed;
        try {
            changed = prune(execution);
        } catch (RuntimeException e) {
            // §7: a failing prune is reported and skipped; the loop continues on the unpruned context.
            log.error("【context-prune】prune failed, context left untouched: executionId={}",
                    execution.getId(), e);
            usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED,
                    ContextUpdateEvent.Band.PRUNE, "Context prune failed");
            return false;
        }
        usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED,
                ContextUpdateEvent.Band.PRUNE,
                changed ? "Context prune completed" : "Context prune finished without changes");
        return changed;
    }

    /**
     * Runs one prune pass: grades the segments against the budget, writes the product back onto the
     * execution and records the prune generation.
     *
     * @param execution the execution whose messages are pruned in place
     * @return {@code true} when at least one message differs from the input
     */
    private boolean prune(Execution execution) {
        List<Message> input = execution.getMessages() == null
                ? new ArrayList<>() : new ArrayList<>(execution.getMessages());
        if (input.isEmpty()) {
            log.info("【context-prune】empty context, prune skipped: executionId={}", execution.getId());
            return false;
        }

        List<Segment> segments = segmentsOf(input, execution.getId());
        int budget = agentConfig.pruneBudgetTokens();
        int nonCuttable = nonCuttableTokens(input, segments);
        int remaining = budget - nonCuttable;
        if (remaining < 0) {
            log.warn("【context-prune】the non-cuttable part ({} tokens) alone exceeds the target budget ({}); "
                    + "every gradable segment is dropped and the overflow is accepted: executionId={}",
                    nonCuttable, budget, execution.getId());
        }
        if (nonCuttable > agentConfig.maxTokens()) {
            log.error("【context-prune】the non-cuttable part ({} tokens) already exceeds the context cap ({}): "
                    + "executionId={}", nonCuttable, agentConfig.maxTokens(), execution.getId());
        }

        Map<Integer, Level> levels = allocate(segments, remaining);
        List<OutputEntry> product = render(input, segments, levels);
        verifyProduct(input, product, segments);

        List<Message> messages = new ArrayList<>(product.size());
        for (OutputEntry entry : product) {
            messages.add(entry.message());
        }
        boolean changed = differs(input, messages);
        execution.setMessages(messages);
        // §3.3.4: pruning is the single writer of the prune generation of the context it rewrote.
        CompactionState state = execution.getCompactionState();
        execution.setCompactionState(state.withPruneGeneration(state.pruneGeneration() + 1));

        log.info("【context-prune】prune pass done: changed={}, budget={}, nonCuttable={}, segments={}, "
                        + "levels={}, executionId={}", changed, budget, nonCuttable, segments.size(),
                levels.values(), execution.getId());
        return changed;
    }

    // ------------------------------------------------------------- segmentation (§3.5)

    /** Builds the segment list, resolving ownership through the tool-call ids. */
    private List<Segment> segmentsOf(List<Message> input, String executionId) {
        Map<String, Integer> ownerByCallId = new LinkedHashMap<>();
        for (int i = 0; i < input.size(); i++) {
            if (input.get(i) instanceof AiMessageEntity ai && ai.getToolCalls() != null) {
                for (ToolCallRequest call : ai.getToolCalls()) {
                    if (call != null && call.id() != null) {
                        ownerByCallId.putIfAbsent(call.id(), i);
                    }
                }
            }
        }

        Map<Integer, List<Integer>> indexesByOwner = new LinkedHashMap<>();
        Map<Integer, List<ToolMessageEntity>> toolsByOwner = new LinkedHashMap<>();
        Map<Integer, Set<String>> answeredByOwner = new HashMap<>();
        for (int i = 0; i < input.size(); i++) {
            if (!(input.get(i) instanceof ToolMessageEntity tool)) {
                continue;
            }
            Integer owner = tool.getId() == null ? null : ownerByCallId.get(String.valueOf(tool.getId()));
            if (owner == null) {
                log.warn("【context-prune】dropping a tool result whose id matches no assistant tool call "
                                + "(an orphan is rejected by the provider): id={}, name={}, executionId={}",
                        tool.getId(), tool.getName(), executionId);
                continue;
            }
            indexesByOwner.computeIfAbsent(owner, key -> new ArrayList<>()).add(i);
            toolsByOwner.computeIfAbsent(owner, key -> new ArrayList<>()).add(tool);
            answeredByOwner.computeIfAbsent(owner, key -> new HashSet<>()).add(String.valueOf(tool.getId()));
        }

        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            if (!(input.get(i) instanceof AiMessageEntity ai)) {
                continue;
            }
            List<Integer> toolIndexes = List.copyOf(indexesByOwner.getOrDefault(i, List.of()));
            List<ToolMessageEntity> tools = List.copyOf(toolsByOwner.getOrDefault(i, List.of()));
            boolean frozen = isFrozen(ai, toolIndexes, answeredByOwner.getOrDefault(i, Set.of()), i);
            segments.add(new Segment(i, ai, toolIndexes, tools, frozen));
        }
        return segments;
    }

    /**
     * Whether a segment must be kept verbatim (§3.5.1).
     *
     * <p>Frozen when the segment asks for tools and at least one call has no result yet, and also
     * when one of its results appears <em>before</em> the assistant message. The second case cannot
     * occur in a well-formed history; keeping such a group verbatim is the only way not to reorder
     * messages the provider paired by position.</p>
     */
    private boolean isFrozen(AiMessageEntity ai, List<Integer> toolIndexes, Set<String> answered,
                             int aiIndex) {
        if (ai.getToolCalls() == null || ai.getToolCalls().isEmpty()) {
            return false;
        }
        for (ToolCallRequest call : ai.getToolCalls()) {
            if (call == null || call.id() == null || !answered.contains(call.id())) {
                return true;
            }
        }
        for (Integer toolIndex : toolIndexes) {
            if (toolIndex < aiIndex) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- allocation (§3.6 phase 1)

    /** Tokens of everything never trimmed: system messages, user messages, frozen segments. */
    private int nonCuttableTokens(List<Message> input, List<Segment> segments) {
        int tokens = 0;
        for (Message message : input) {
            if (message instanceof SystemMessageEntity || message instanceof UserMessageEntity) {
                tokens += tokenizer.count(message);
            }
        }
        for (Segment segment : segments) {
            if (segment.frozen()) {
                for (OutputEntry entry : verbatim(segment)) {
                    tokens += tokenizer.count(entry.message());
                }
            }
        }
        return tokens;
    }

    /**
     * Grades every gradable segment, newest first, against the remaining budget.
     *
     * @param segments  all segments in original order
     * @param remaining budget left after the non-cuttable part; may be negative
     * @return the level of each gradable segment, keyed by the assistant message index
     */
    private Map<Integer, Level> allocate(List<Segment> segments, int remaining) {
        List<Segment> gradable = new ArrayList<>();
        for (Segment segment : segments) {
            if (!segment.frozen()) {
                gradable.add(segment);
            }
        }
        int recentFrom = Math.max(0, gradable.size() - agentConfig.fullSegmentCount());
        Map<Integer, Level> levels = new HashMap<>();
        int budgetLeft = remaining;
        for (int position = gradable.size() - 1; position >= 0; position--) {
            Segment segment = gradable.get(position);
            int fullCost = costOf(segment, Level.FULL);
            int skeletonCost = costOf(segment, Level.SKELETON);
            Level level;
            if (position >= recentFrom && budgetLeft >= fullCost) {
                level = Level.FULL;
            } else if (budgetLeft >= skeletonCost) {
                level = Level.SKELETON;
            } else {
                level = Level.DROP;
            }
            levels.put(segment.aiIndex(), level);
            budgetLeft -= switch (level) {
                case FULL -> fullCost;
                case SKELETON -> skeletonCost;
                case DROP -> 0;
            };
        }
        return levels;
    }

    /** Tokens the segment occupies at the given level. */
    private int costOf(Segment segment, Level level) {
        if (level == Level.DROP) {
            return 0;
        }
        int tokens = 0;
        for (OutputEntry entry : rendered(segment, level)) {
            tokens += tokenizer.count(entry.message());
        }
        return tokens;
    }

    // ------------------------------------------------------------- rendering (§3.6 phase 2)

    /**
     * Builds the product: all system messages, then every non-system message in its original order.
     *
     * @param input    the pre-prune context
     * @param segments all segments in original order
     * @param levels   the level of each gradable segment
     * @return the product in output order
     */
    private List<OutputEntry> render(List<Message> input, List<Segment> segments,
                                     Map<Integer, Level> levels) {
        Map<Integer, Segment> segmentByAiIndex = new HashMap<>();
        Map<Integer, Segment> ownerByToolIndex = new HashMap<>();
        for (Segment segment : segments) {
            segmentByAiIndex.put(segment.aiIndex(), segment);
            for (Integer toolIndex : segment.toolIndexes()) {
                ownerByToolIndex.put(toolIndex, segment);
            }
        }

        List<OutputEntry> product = new ArrayList<>(input.size());
        // System messages are hoisted to the front, keeping their relative order — the single
        // reordering providers require.
        for (int i = 0; i < input.size(); i++) {
            if (input.get(i) instanceof SystemMessageEntity) {
                product.add(new OutputEntry(input.get(i), i));
            }
        }
        // Every remaining message is emitted at its own original index, one by one, so the product
        // keeps a strictly increasing sourceIndex even when a tool result is not adjacent to the
        // assistant message that owns it (assertion 7). A tool result is never flushed together with
        // its segment head; it is rendered here by its own index according to its segment's level.
        for (int i = 0; i < input.size(); i++) {
            Message message = input.get(i);
            if (message instanceof SystemMessageEntity) {
                continue; // already hoisted
            }
            if (message instanceof ToolMessageEntity tool) {
                Segment owner = ownerByToolIndex.get(i);
                if (owner == null) {
                    continue; // orphan: already dropped (and warned) during segmentation
                }
                if (owner.frozen()) {
                    product.add(new OutputEntry(message, i)); // verbatim, thinking/identity intact
                    continue;
                }
                Level level = levels.getOrDefault(owner.aiIndex(), Level.SKELETON);
                if (level == Level.DROP) {
                    continue;
                }
                String output = level == Level.FULL ? capOutput(tool.text()) : placeholderOf(tool);
                product.add(new OutputEntry(copyTool(tool, output), i));
                continue;
            }
            Segment segment = segmentByAiIndex.get(i);
            if (segment == null) {
                product.add(new OutputEntry(message, i)); // a user message or other standalone message
                continue;
            }
            if (segment.frozen()) {
                product.add(new OutputEntry(segment.ai(), i)); // verbatim, thinking included
                continue;
            }
            Level level = levels.getOrDefault(i, Level.SKELETON);
            if (level == Level.DROP) {
                continue;
            }
            product.add(new OutputEntry(rewriteAi(segment.ai()), i));
        }
        return product;
    }

    /** The segment exactly as it was, used for frozen segments (thinking included). */
    private List<OutputEntry> verbatim(Segment segment) {
        List<OutputEntry> entries = new ArrayList<>(segment.tools().size() + 1);
        entries.add(new OutputEntry(segment.ai(), segment.aiIndex()));
        for (int i = 0; i < segment.tools().size(); i++) {
            entries.add(new OutputEntry(segment.tools().get(i), segment.toolIndexes().get(i)));
        }
        return entries;
    }

    /** The segment rendered at the given level; tool results follow their assistant message. */
    private List<OutputEntry> rendered(Segment segment, Level level) {
        List<OutputEntry> entries = new ArrayList<>(segment.tools().size() + 1);
        entries.add(new OutputEntry(rewriteAi(segment.ai()), segment.aiIndex()));
        for (int i = 0; i < segment.tools().size(); i++) {
            ToolMessageEntity tool = segment.tools().get(i);
            String output = level == Level.FULL ? capOutput(tool.text()) : placeholderOf(tool);
            entries.add(new OutputEntry(copyTool(tool, output), segment.toolIndexes().get(i)));
        }
        return entries;
    }

    /**
     * Copies an assistant message into its pruned form: text and tool calls survive, thinking and
     * its signature do not (§3.6.1 — FULL is not "keep thinking"; a rewritten historical message
     * carries no thinking because the signature would be orphaned).
     *
     * <p>An assistant message that already carries no thinking is returned unchanged, so a second
     * prune pass produces the very same instances and can report "nothing changed".</p>
     */
    private static AiMessageEntity rewriteAi(AiMessageEntity source) {
        boolean thinkingFree = (source.getThinking() == null || source.getThinking().isBlank())
                && (source.getAttributes() == null || source.getAttributes().isEmpty());
        if (thinkingFree) {
            return source;
        }
        return AiMessageEntity.builder()
                .text(source.getText())
                .thinking(null)
                .attributes(Map.of())
                .toolCalls(source.getToolCalls() == null ? List.of() : List.copyOf(source.getToolCalls()))
                .build();
    }

    /**
     * Copies a tool result with a replaced output, keeping its identity and error flag.
     *
     * <p>Returns the source unchanged when the output would not change, so an idempotent pass can be
     * recognized as a no-op.</p>
     */
    private static ToolMessageEntity copyTool(ToolMessageEntity source, String output) {
        if (Objects.equals(source.text(), output)) {
            return source;
        }
        return ToolMessageEntity.builder()
                .id(source.getId())
                .name(source.getName())
                .isError(source.isError())
                .exitCode(source.getExitCode())
                .toolOutput(output)
                .build();
    }

    /**
     * Caps a tool result kept in a {@code FULL} segment.
     *
     * <p>The text is cut at a token boundary without any marker: the acquisition-time truncation
     * marker must not appear here, it would be indistinguishable from the collector's own truncation
     * and invite the model to re-run the tool.</p>
     */
    private String capOutput(String output) {
        if (output == null) {
            return null;
        }
        // Never short-circuit on OMITTED_PREFIX: a genuine placeholder is already tiny and the count
        // check below keeps it unchanged, while a normal tool output that merely starts with that
        // text must still be capped.
        int cap = agentConfig.fullToolOutputCap();
        if (tokenizer.count(countable(output)) <= cap) {
            return output; // within budget (also idempotent: a previously capped product fits again)
        }
        // The average chars-per-token ratio is not safe to extrapolate: a dense prefix (CJK, symbols)
        // can carry far more tokens per char than the suffix. Cut by a bounded search over the prefix
        // length, re-counting with the real tokenizer at every probe, so the kept prefix truly fits.
        return output.substring(0, longestPrefixWithinCap(output, cap));
    }

    /**
     * Length in characters of the longest prefix of {@code output} whose token count does not exceed
     * {@code cap}, measured by re-tokenizing each candidate prefix.
     *
     * <p>Binary search over the (monotonic in practice) character-length axis, followed by a small
     * linear shrink that guarantees the result really fits even if the tokenizer is not perfectly
     * monotone. Never returns zero: an empty tool output is rejected by providers, so at least one
     * character is always kept.</p>
     *
     * @param output the string to shrink
     * @param cap    the inclusive token ceiling
     * @return a prefix length in {@code [1, output.length()]} whose token count is {@code <= cap}
     *         (or {@code 1} when even a single character exceeds a pathologically small cap)
     */
    private int longestPrefixWithinCap(String output, int cap) {
        int lo = 1;
        int hi = output.length();
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (tokenCountOfPrefix(output, mid) <= cap) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        // Guard against a non-monotone tokenizer: shrink until the chosen prefix truly fits.
        while (best > 1 && tokenCountOfPrefix(output, best) > cap) {
            best--;
        }
        return Math.max(1, best);
    }

    /** Token count of the first {@code length} characters of {@code output}, via the tokenizer SPI. */
    private int tokenCountOfPrefix(String output, int length) {
        return tokenizer.count(countable(output.substring(0, length)));
    }

    /** Measures an arbitrary string through the tokenizer SPI. */
    private ToolMessageEntity countable(String output) {
        return ToolMessageEntity.builder().id("measure").name("measure").toolOutput(output).build();
    }

    /**
     * The one-line placeholder that replaces a trimmed tool result (§4.4).
     *
     * <p>Carries the size and the exit status so the model can still tell a failed command from a
     * successful one. Never blank (providers dislike empty tool content), never claims to be
     * recoverable, and never contains the acquisition-time truncation marker.</p>
     */
    private String placeholderOf(ToolMessageEntity tool) {
        String text = tool.text();
        if (isGeneratedPlaceholder(text)) {
            return text; // already OUR one-line placeholder: keep it verbatim (idempotent)
        }
        List<String> parts = new ArrayList<>(3);
        int lines = lineCount(text);
        if (lines > 0) {
            parts.add(lines + " lines");
        }
        parts.add(sizeOf(text));
        parts.add(tool.getExitCode() == null ? "ERROR" : "exit=" + tool.getExitCode());
        return OMITTED_PREFIX + " " + String.join(" / ", parts) + "]";
    }

    /** Upper bound on a genuine placeholder's length; a long numeric look-alike exceeds it. */
    private static final int MAX_PLACEHOLDER_LENGTH = 200;
    /** Matches exactly the one-line placeholder this class generates, with bounded numeric fields. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "\\[output omitted · (?:\\d{1,7} lines / )?\\d{1,10}(?:B|KB|MB) / (?:exit=-?\\d{1,5}|ERROR)\\]");

    /**
     * True only when {@code text} is exactly a placeholder THIS class generated: it must match the
     * full structural format (prefix + optional line count + size + exit status) with bounded numeric
     * fields, and fit within {@link #MAX_PLACEHOLDER_LENGTH}. A normal output that merely resembles
     * the prefix — even a single short line — is not a placeholder and is regenerated.
     */
    private static boolean isGeneratedPlaceholder(String text) {
        return text != null
                && text.length() <= MAX_PLACEHOLDER_LENGTH
                && PLACEHOLDER_PATTERN.matcher(text).matches();
    }

    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\n", -1).length;
    }

    private static String sizeOf(String text) {
        int bytes = text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return Math.max(1, Math.round(bytes / 1024f)) + "KB";
        }
        return Math.max(1, Math.round(bytes / (1024f * 1024f))) + "MB";
    }

    // ------------------------------------------------------------- hard assertions (§3.8)

    /**
     * Verifies the seven exit assertions on the product and refuses to write anything when one of
     * them fails.
     *
     * <ol>
     *   <li>no rewritten historical assistant message carries thinking (frozen segments keep it);</li>
     *   <li>no orphan tool result, and every tool result resolves to an assistant tool call;</li>
     *   <li>all input system messages present, same content, same relative order;</li>
     *   <li>all input user messages present, same content, same relative order;</li>
     *   <li>all frozen segments present verbatim;</li>
     *   <li>all system messages before every non-system message;</li>
     *   <li>non-system messages in exactly the input order.</li>
     * </ol>
     *
     * @param input    the pre-prune context
     * @param product  the rendered product
     * @param segments all segments in original order
     * @throws IllegalStateException when an assertion fails
     */
    private void verifyProduct(List<Message> input, List<OutputEntry> product, List<Segment> segments) {
        int lastSystem = -1;
        int firstNonSystem = Integer.MAX_VALUE;
        for (int i = 0; i < product.size(); i++) {
            if (product.get(i).message() instanceof SystemMessageEntity) {
                lastSystem = Math.max(lastSystem, i);
            } else {
                firstNonSystem = Math.min(firstNonSystem, i);
            }
        }
        require(lastSystem < firstNonSystem, "assertion 6: every system message must precede the rest");

        int previousSource = -1;
        for (OutputEntry entry : product) {
            if (entry.message() instanceof SystemMessageEntity) {
                continue;
            }
            require(entry.sourceIndex() > previousSource,
                    "assertion 7: non-system messages must keep the input order (index "
                            + entry.sourceIndex() + " after " + previousSource + ")");
            previousSource = entry.sourceIndex();
        }

        List<String> inputSystems = textsOf(input, SystemMessageEntity.class);
        List<String> outputSystems = systemTextsOf(product);
        require(inputSystems.equals(outputSystems),
                "assertion 3: system messages must all survive unchanged and in order");

        List<String> inputUsers = textsOf(input, UserMessageEntity.class);
        List<String> outputUsers = textsOf(orderedUserMessages(product));
        require(inputUsers.equals(outputUsers),
                "assertion 4: user messages must all survive unchanged and in order");

        Set<Integer> productSources = new HashSet<>();
        for (OutputEntry entry : product) {
            productSources.add(entry.sourceIndex());
        }
        for (Segment segment : segments) {
            if (!segment.frozen()) {
                continue;
            }
            require(productSources.contains(segment.aiIndex()),
                    "assertion 5: a frozen segment must be present verbatim");
            for (Integer toolIndex : segment.toolIndexes()) {
                require(productSources.contains(toolIndex),
                        "assertion 5: a frozen segment keeps all of its tool results");
            }
        }

        for (OutputEntry entry : product) {
            if (!(entry.message() instanceof AiMessageEntity ai)) {
                continue;
            }
            Message source = input.get(entry.sourceIndex());
            boolean rewritten = source != ai;
            if (!rewritten) {
                continue; // a frozen segment is kept as is, thinking included
            }
            require(ai.getThinking() == null || ai.getThinking().isBlank(),
                    "assertion 1: a rewritten historical assistant message must carry no thinking");
            require(ai.getAttributes() == null || ai.getAttributes().isEmpty(),
                    "assertion 1: a rewritten historical assistant message must carry no thinking signature");
        }

        Set<String> producedCallIds = new HashSet<>();
        for (OutputEntry entry : product) {
            if (entry.message() instanceof AiMessageEntity ai && ai.getToolCalls() != null) {
                for (ToolCallRequest call : ai.getToolCalls()) {
                    if (call != null && call.id() != null) {
                        producedCallIds.add(call.id());
                    }
                }
            }
        }
        for (OutputEntry entry : product) {
            if (entry.message() instanceof ToolMessageEntity tool) {
                require(tool.getId() != null && producedCallIds.contains(String.valueOf(tool.getId())),
                        "assertion 2: every tool result must resolve to an assistant tool call");
            }
        }
    }

    private static List<Message> orderedUserMessages(List<OutputEntry> product) {
        List<Message> users = new ArrayList<>();
        for (OutputEntry entry : product) {
            if (entry.message() instanceof UserMessageEntity) {
                users.add(entry.message());
            }
        }
        return users;
    }

    private static List<String> textsOf(List<Message> messages, Class<? extends Message> type) {
        List<String> texts = new ArrayList<>();
        for (Message message : messages) {
            if (type.isInstance(message)) {
                texts.add(message.text());
            }
        }
        return texts;
    }

    /** Texts of the system messages of the product, in output order. */
    private static List<String> systemTextsOf(List<OutputEntry> product) {
        List<String> texts = new ArrayList<>();
        for (OutputEntry entry : product) {
            if (entry.message() instanceof SystemMessageEntity) {
                texts.add(entry.message().text());
            }
        }
        return texts;
    }

    private static List<String> textsOf(List<Message> messages) {
        List<String> texts = new ArrayList<>();
        for (Message message : messages) {
            texts.add(message.text());
        }
        return texts;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("【context-prune】" + message);
        }
    }

    /** Whether the product differs from the input, message by message. */
    private static boolean differs(List<Message> input, List<Message> product) {
        if (input.size() != product.size()) {
            return true;
        }
        for (int i = 0; i < input.size(); i++) {
            if (input.get(i) != product.get(i)) {
                return true;
            }
        }
        return false;
    }
}

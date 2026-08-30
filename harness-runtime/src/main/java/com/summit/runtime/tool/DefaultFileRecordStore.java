package com.summit.runtime.tool;

import com.summit.core.tool.FileRecord;
import com.summit.core.tool.FileRecordStore;
import org.jspecify.annotations.NonNull;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory {@link FileRecordStore}. Records of one session are kept in
 * insertion order; {@link #update} replaces a record in place so the record
 * order (and therefore the reject order) is stable.
 */
public class DefaultFileRecordStore implements FileRecordStore {
    private final Map<Serializable, List<FileRecord>> records = new ConcurrentHashMap<>();

    @Override
    public void put(@NonNull FileRecord record) {
        Serializable id = record.id();
        if (id == null) throw new IllegalArgumentException("record id cannot be null");
        this.records.computeIfAbsent(record.sessionId(), k -> new CopyOnWriteArrayList<>()).add(record);
    }

    @Override
    public Optional<FileRecord> get(@NonNull Serializable sessionId, @NonNull Serializable recordId) {
        List<FileRecord> list = this.records.get(sessionId);
        return list == null ? Optional.empty()
                : list.stream().filter(r -> recordId.equals(r.id())).findFirst();
    }

    @Override
    public List<FileRecord> listBySessionFull(@NonNull Serializable sessionId) {
        List<FileRecord> list = this.records.get(sessionId);
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public Collection<SimpleRecord> listBySessionId(Serializable sessionId) {
        return this.listBySessionFull(sessionId).stream()
                .map(r -> new SimpleRecord(r.id(), r.filePath(), plusLines(r), minusLines(r)))
                .collect(Collectors.toList());
    }

    @Override
    public void update(@NonNull FileRecord record) {
        List<FileRecord> list = this.records.get(record.sessionId());
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (record.id().equals(list.get(i).id())) {
                list.set(i, record);
                return;
            }
        }
    }

    @Override
    public void clearBySessionId(@NonNull Serializable sessionId) {
        this.records.remove(sessionId);
    }

    @Override
    public boolean removeById(@NonNull Serializable sessionId, @NonNull Serializable recordId) {
        List<FileRecord> list = this.records.get(sessionId);
        return list != null && list.removeIf(r -> recordId.equals(r.id()));
    }

    @Override
    public Map<Serializable, List<FileRecord>> getAll() {
        return this.records.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    @Override
    public void clear() {
        this.records.clear();
    }

    private Integer plusLines(FileRecord record) {
        return record.diff() == null ? null : countLines(record.diff(), '+');
    }

    private Integer minusLines(FileRecord record) {
        return record.diff() == null ? null : countLines(record.diff(), '-');
    }

    private int countLines(String diff, char marker) {
        return (int) diff.lines().filter(l -> !l.isEmpty() && l.charAt(0) == marker).count();
    }
}

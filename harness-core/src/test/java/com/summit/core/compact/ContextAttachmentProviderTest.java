package com.summit.core.compact;

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContextAttachmentProviderTest {
    @Test
    void lowerOrderRunsFirstAndTiesRetainRegistrationOrder() {
        List<Serializable> identities = new ArrayList<>();
        ContextAttachmentProvider later = provider(20, "later", identities);
        ContextAttachmentProvider firstTie = provider(-10, "first", identities);
        ContextAttachmentProvider secondTie = provider(-10, "second", identities);
        ContextAttachmentProvider combined = ContextAttachmentProvider.compose(List.of(later, firstTie, secondTie));
        assertEquals(Optional.of("first\n\nsecond\n\nlater"), combined.attachment("execution"));
        assertEquals(List.of("execution", "execution", "execution"), identities);
    }

    @Test
    void emptyAndBlankProvidersDoNotCreateAnAttachment() {
        assertEquals(Optional.empty(), ContextAttachmentProvider.compose(List.of()).attachment(1L));
        ContextAttachmentProvider blank = id -> Optional.of(" \n");
        assertEquals(Optional.empty(), ContextAttachmentProvider.compose(List.of(ContextAttachmentProvider.NONE, blank)).attachment(1L));
        assertEquals(0, blank.order());
    }

    @Test
    void contentIsReadAgainWithoutRetainingTheMutableRegistrationList() {
        AtomicReference<String> state = new AtomicReference<>("before");
        List<ContextAttachmentProvider> providers = new ArrayList<>(List.of(id -> Optional.of(state.get())));
        ContextAttachmentProvider combined = ContextAttachmentProvider.compose(providers);
        assertEquals(Optional.of("before"), combined.attachment("1"));
        providers.clear(); state.set("after");
        assertEquals(Optional.of("after"), combined.attachment("1"));
    }

    @Test
    void aProviderFailureCannotSilentlyDiscardProtectedState() {
        IllegalStateException failure = new IllegalStateException("unavailable");
        ContextAttachmentProvider broken = id -> { throw failure; };
        ContextAttachmentProvider combined = ContextAttachmentProvider.compose(List.of(ContextAttachmentProvider.NONE, broken));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> combined.attachment("1")));
    }

    private ContextAttachmentProvider provider(int order, String text, List<Serializable> identities) {
        return new ContextAttachmentProvider() {
            @Override
            public int order() { return order; }

            @Override
            public Optional<String> attachment(Serializable executionId) {
                identities.add(executionId);
                return Optional.of(text);
            }
        };
    }
}

package com.u1.durableThreads.snapshot;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

public record ThreadSnapshot(
        Instant capturedAt,
        String threadName,
        List<FrameSnapshot> frames,
        List<ObjectSnapshot> heap
) implements Serializable {

    /** Number of stack frames in this snapshot. */
    public int frameCount() {
        return frames.size();
    }

    /** The topmost (deepest) frame — the one that was executing when frozen. */
    public FrameSnapshot topFrame() {
        return frames.get(frames.size() - 1);
    }

    /** The bottom frame — the entry point of the call chain. */
    public FrameSnapshot bottomFrame() {
        return frames.get(0);
    }
}

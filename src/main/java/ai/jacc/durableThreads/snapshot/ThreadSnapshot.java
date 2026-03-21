package ai.jacc.durableThreads.snapshot;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ThreadSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Instant capturedAt;
    private final String threadName;
    private final List<FrameSnapshot> frames;
    private final List<ObjectSnapshot> heap;

    public ThreadSnapshot(Instant capturedAt, String threadName,
                          List<FrameSnapshot> frames, List<ObjectSnapshot> heap) {
        this.capturedAt = capturedAt;
        this.threadName = threadName;
        this.frames = frames;
        this.heap = heap;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public String threadName() {
        return threadName;
    }

    public List<FrameSnapshot> frames() {
        return frames;
    }

    public List<ObjectSnapshot> heap() {
        return heap;
    }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ThreadSnapshot)) return false;
        ThreadSnapshot that = (ThreadSnapshot) o;
        return Objects.equals(capturedAt, that.capturedAt)
                && Objects.equals(threadName, that.threadName)
                && Objects.equals(frames, that.frames)
                && Objects.equals(heap, that.heap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(capturedAt, threadName, frames, heap);
    }

    @Override
    public String toString() {
        return "ThreadSnapshot[capturedAt=" + capturedAt
                + ", threadName=" + threadName
                + ", frames=" + frames
                + ", heap=" + heap + "]";
    }
}

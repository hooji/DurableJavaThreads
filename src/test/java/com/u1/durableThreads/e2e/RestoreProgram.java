package com.u1.durableThreads.e2e;

import com.u1.durableThreads.Durable;
import com.u1.durableThreads.snapshot.ThreadSnapshot;

import java.io.*;
import java.nio.file.*;

/**
 * Child-JVM program that restores a previously frozen thread.
 *
 * <p>Usage: RestoreProgram &lt;snapshotFile&gt;</p>
 *
 * <p>Reads the snapshot from the file, calls Durable.restore(), and starts
 * the restored thread. The restored thread continues from after the freeze
 * point, printing its state.</p>
 */
public class RestoreProgram {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: RestoreProgram <snapshotFile>");
            System.exit(1);
        }
        String snapshotFile = args[0];

        byte[] bytes = Files.readAllBytes(Path.of(snapshotFile));
        ThreadSnapshot snapshot = deserialize(bytes);

        System.out.println("SNAPSHOT_LOADED=true");
        System.out.println("FRAME_COUNT=" + snapshot.frameCount());
        System.out.flush();

        Thread restored = Durable.restore(snapshot);
        restored.start();
        restored.join(30_000);

        System.out.println("RESTORE_COMPLETE");
        System.out.flush();
    }

    private static ThreadSnapshot deserialize(byte[] bytes) throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (ThreadSnapshot) ois.readObject();
        }
    }
}

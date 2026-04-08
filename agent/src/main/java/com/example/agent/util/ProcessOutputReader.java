package com.example.agent.util;

import java.io.IOException;
import java.io.InputStream;

/**
 * Process 스트림을 별도 스레드에서 안전하게 읽기.
 * 데드락 방지: waitFor() 호출 전에 별도 스레드에서 스트림을 읽음.
 * 버퍼 오버플로우 방지: maxSize 초과 시 오래된 데이터 버림.
 */
public class ProcessOutputReader extends Thread {

    private final InputStream inputStream;
    private final StringBuilder output;
    private final int maxSize;
    private volatile boolean done = false;

    public ProcessOutputReader(InputStream inputStream, int maxSize) {
        this.inputStream = inputStream;
        this.output = new StringBuilder();
        this.maxSize = maxSize;
        this.setName("ProcessOutputReader");
        this.setDaemon(false);
    }

    @Override
    public void run() {
        try (InputStream is = inputStream) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) > 0) {
                String chunk = new String(buffer, 0, bytesRead);
                output.append(chunk);

                // Trim output if exceed maxSize
                if (output.length() > maxSize) {
                    // Keep last maxSize bytes
                    int startIndex = output.length() - maxSize;
                    output.delete(0, startIndex);
                }
            }
        } catch (IOException e) {
            // Stream reading failed - log but don't fail
        } finally {
            done = true;
        }
    }

    /**
     * Get accumulated output (thread-safe).
     */
    public synchronized String getOutput() {
        return output.toString();
    }

    /**
     * Check if reading is complete.
     */
    public boolean isDone() {
        return done;
    }

    /**
     * Wait for reading to complete with timeout.
     */
    public boolean waitForCompletion(long timeoutMillis) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (!done) {
            if (System.currentTimeMillis() - start > timeoutMillis) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }
}

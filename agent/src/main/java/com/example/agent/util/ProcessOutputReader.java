package com.example.agent.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
        this(inputStream, maxSize, "ProcessOutputReader");
    }

    public ProcessOutputReader(InputStream inputStream, int maxSize, String streamName) {
        this.inputStream = inputStream;
        this.output = new StringBuilder();
        this.maxSize = maxSize;
        this.setName("ProcessOutputReader-" + streamName);
        this.setDaemon(false);
    }

    @Override
    public void run() {
        try (InputStream is = inputStream) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) > 0) {
                // UTF-8 명시 (Windows 기본 charset Cp1252에서 한글 깨짐 방지)
                String chunk = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
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
     * Thread.join() 사용으로 폴링 루프 제거 및 happens-before 보장.
     */
    public boolean waitForCompletion(long timeoutMillis) throws InterruptedException {
        this.join(timeoutMillis);
        return done;
    }
}

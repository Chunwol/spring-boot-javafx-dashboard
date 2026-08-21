package com.example.agent.scmate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

class ScmateClientDisplayStateReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void actualPathComparisonRejectsDifferentExecutable() throws Exception {
        Path expected = Files.writeString(tempDir.resolve("SCMateClient.exe"), "client");
        Path same = expected.toRealPath();
        Path other = Files.writeString(tempDir.resolve("other.exe"), "client");

        assertThat(ScmateClientDisplayStateReader.sameExecutable(expected, same)).isTrue();
        assertThat(ScmateClientDisplayStateReader.sameExecutable(expected, other)).isFalse();
    }

    @Test
    void hashCacheInvalidatesWhenContentChangesWithoutMetadataChange() throws Exception {
        Path client = Files.writeString(tempDir.resolve("SCMateClient.exe"), "alpha");
        String expectedHash = ScmateClientDisplayStateReader.sha256(client);
        FileTime originalTime = Files.getLastModifiedTime(client);
        ScmateClientDisplayStateReader.clearVerificationCache();

        assertThat(ScmateClientDisplayStateReader.verifyFile(client, expectedHash).supportedHash()).isTrue();

        Files.writeString(client, "bravo");
        Files.setLastModifiedTime(client, originalTime);

        assertThat(ScmateClientDisplayStateReader.verifyFile(client, expectedHash).supportedHash()).isFalse();
    }
}

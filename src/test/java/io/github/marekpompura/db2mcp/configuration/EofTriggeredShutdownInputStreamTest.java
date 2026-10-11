package io.github.marekpompura.db2mcp.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EofTriggeredShutdownInputStreamTest {

    @Test
    void doesNotFireWhileDataRemains() throws IOException {
        AtomicInteger fired = new AtomicInteger();
        EofTriggeredShutdownInputStream in =
                new EofTriggeredShutdownInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3}), fired::incrementAndGet);

        in.read();
        in.read(new byte[2]);

        assertThat(fired.get()).isZero();
    }

    @Test
    void firesExactlyOnceWhenSingleByteReadHitsEof() throws IOException {
        AtomicInteger fired = new AtomicInteger();
        EofTriggeredShutdownInputStream in =
                new EofTriggeredShutdownInputStream(new ByteArrayInputStream(new byte[0]), fired::incrementAndGet);

        assertThat(in.read()).isEqualTo(-1);
        assertThat(in.read()).isEqualTo(-1);

        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void firesExactlyOnceWhenBufferReadHitsEof() throws IOException {
        AtomicInteger fired = new AtomicInteger();
        EofTriggeredShutdownInputStream in =
                new EofTriggeredShutdownInputStream(new ByteArrayInputStream(new byte[0]), fired::incrementAndGet);
        byte[] buffer = new byte[8];

        assertThat(in.read(buffer, 0, buffer.length)).isEqualTo(-1);
        assertThat(in.read(buffer, 0, buffer.length)).isEqualTo(-1);

        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void firesOnlyAfterActualDataIsExhausted() throws IOException {
        AtomicInteger fired = new AtomicInteger();
        EofTriggeredShutdownInputStream in =
                new EofTriggeredShutdownInputStream(new ByteArrayInputStream(new byte[] {9}), fired::incrementAndGet);

        assertThat(in.read()).isEqualTo(9);
        assertThat(fired.get()).isZero();

        assertThat(in.read()).isEqualTo(-1);
        assertThat(fired.get()).isEqualTo(1);
    }
}

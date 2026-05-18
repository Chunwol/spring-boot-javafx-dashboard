package com.example.agent.heartbeat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficSampleAggregatorTest {

    @Test
    void consumeSamples_returnsAllSamplesAndResetsBuffer() {
        TrafficSampleAggregator aggregator = new TrafficSampleAggregator();
        aggregator.record(new double[]{100.0, 200.0});
        aggregator.record(new double[]{300.0, 100.0});

        List<double[]> first = aggregator.consumeSamples();
        assertThat(first).hasSize(2);
        assertThat(first.get(0)).containsExactly(100.0, 200.0);
        assertThat(first.get(1)).containsExactly(300.0, 100.0);

        List<double[]> second = aggregator.consumeSamples();
        assertThat(second).hasSize(1);
        assertThat(second.get(0)).containsExactly(300.0, 100.0);
    }

    @Test
    void consumeSamples_sanitizesInvalidSamples() {
        TrafficSampleAggregator aggregator = new TrafficSampleAggregator();
        aggregator.record(new double[]{Double.NaN, -10.0});
        aggregator.record(new double[]{50.0, Double.POSITIVE_INFINITY});

        List<double[]> samples = aggregator.consumeSamples();
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0)).containsExactly(0.0, 0.0);
        assertThat(samples.get(1)).containsExactly(50.0, 0.0);
    }
}

package com.example.agent.heartbeat;

/**
 * Collects traffic samples between heartbeat sends and returns them as raw list for storage.
 */
final class TrafficSampleAggregator {

    private double latestInboundKbps = 0.0;
    private double latestOutboundKbps = 0.0;
    private final java.util.List<double[]> samples = new java.util.ArrayList<>();

    synchronized void record(double[] sample) {
        double[] sanitized = sanitize(sample);
        latestInboundKbps = sanitized[0];
        latestOutboundKbps = sanitized[1];
        samples.add(sanitized);
    }

    synchronized java.util.List<double[]> consumeSamples() {
        java.util.List<double[]> result = new java.util.ArrayList<>(samples);
        samples.clear();
        if (result.isEmpty()) {
            result.add(new double[]{latestInboundKbps, latestOutboundKbps});
        }
        return result;
    }

    private double[] sanitize(double[] sample) {
        if (sample == null || sample.length < 2) {
            return new double[]{0.0, 0.0};
        }
        double inbound = Double.isFinite(sample[0]) ? Math.max(0.0, sample[0]) : 0.0;
        double outbound = Double.isFinite(sample[1]) ? Math.max(0.0, sample[1]) : 0.0;
        return new double[]{inbound, outbound};
    }
}

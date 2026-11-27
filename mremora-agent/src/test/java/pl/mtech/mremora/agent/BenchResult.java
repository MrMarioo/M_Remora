package pl.mtech.mremora.agent;

record BenchResult(Scenario scenario, int frames, long totalBytes, double avgEncodeMillis) {

    double megabitsPerSecondAt(int fps) {
        return (double) totalBytes * 8 / frames * fps / 1_000_000;
    }
}

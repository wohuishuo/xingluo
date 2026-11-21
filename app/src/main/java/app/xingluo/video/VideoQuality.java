package app.xingluo.video;

/** Compares resolutions in either orientation, using bitrate only within a resolution. */
final class VideoQuality {
    private VideoQuality() {}

    static int shortSide(int width, int height) {
        if (width <= 0) return Math.max(0, height);
        if (height <= 0) return width;
        return Math.min(width, height);
    }

    static boolean better(int size, long bitrate, int bestSize, long bestBitrate, int preferred) {
        if (size == bestSize) return bitrate > bestBitrate;
        if (size <= 0) return false;
        if (bestSize <= 0) return true;
        boolean withinPreference = size <= preferred;
        boolean bestWithinPreference = bestSize <= preferred;
        if (withinPreference != bestWithinPreference) return withinPreference;
        return withinPreference ? size > bestSize : size < bestSize;
    }
}

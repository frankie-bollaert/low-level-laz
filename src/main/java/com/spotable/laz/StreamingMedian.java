package com.spotable.laz;

/**
 * The 5-element running median predictor used by the LASzip XY/dx/dy fields
 * (port of laz-perf's {@code utils::streaming_median<int>}). {@link #get()} returns
 * the current median; {@link #add(int)} folds in a new sample.
 */
final class StreamingMedian {
    private final int[] values = new int[5];
    private boolean high = true;

    int get() {
        return values[2];
    }

    void add(int v) {
        if (high) {
            if (v < values[2]) {
                values[4] = values[3];
                values[3] = values[2];
                if (v < values[0]) {
                    values[2] = values[1];
                    values[1] = values[0];
                    values[0] = v;
                } else if (v < values[1]) {
                    values[2] = values[1];
                    values[1] = v;
                } else {
                    values[2] = v;
                }
            } else {
                if (v < values[3]) {
                    values[4] = values[3];
                    values[3] = v;
                } else {
                    values[4] = v;
                }
                high = false;
            }
        } else {
            if (values[2] < v) {
                values[0] = values[1];
                values[1] = values[2];
                if (values[4] < v) {
                    values[2] = values[3];
                    values[3] = values[4];
                    values[4] = v;
                } else if (values[3] < v) {
                    values[2] = values[3];
                    values[3] = v;
                } else {
                    values[2] = v;
                }
            } else {
                if (values[1] < v) {
                    values[0] = values[1];
                    values[1] = v;
                } else {
                    values[0] = v;
                }
                high = true;
            }
        }
    }
}

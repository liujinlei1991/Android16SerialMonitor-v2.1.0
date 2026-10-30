package com.jinle.serialmonitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Collects four views and converts the 16 radar layers into ellipse measurements. */
final class MeasurementSession {
    static final int FACE_FRONT = 0;
    static final int FACE_LEFT = 1;
    static final int FACE_BACK = 2;
    static final int FACE_RIGHT = 3;
    static final int FACE_COUNT = 4;
    static final int LAYER_COUNT = 16;
    static final String ALGORITHM_VERSION = "2.1.4-experimental";
    static final int MIN_SAMPLES = 20;
    // Constant residual correction, fitted on four subjects (two repeats each).
    // This is NOT a measured radar offset and does not alter the raw contour.
    static final double WAIST_CORRECTION_CM = 8.959187807243989;
    static final double HIP_CORRECTION_CM = 13.001079962193373;
    private final boolean calibrated;
    static final double SENSOR_CENTER_DISTANCE_MM = 500.0;
    static final double LOWEST_SENSOR_HEIGHT_MM = 635.0;
    static final double SENSOR_SPACING_MM = 40.0;
    static final double TOP_SENSOR_HEIGHT_MM = LOWEST_SENSOR_HEIGHT_MM +
            SENSOR_SPACING_MM * (LAYER_COUNT - 1);

    static final String[] FACE_NAMES = {"正面", "左侧面", "背面", "右侧面"};

    @SuppressWarnings("unchecked")
    private final List<Double>[][] samples = new ArrayList[FACE_COUNT][LAYER_COUNT];

    MeasurementSession() {
        this(true);
    }

    MeasurementSession(boolean calibrated) {
        this.calibrated = calibrated;
        for (int phase = 0; phase < FACE_COUNT; phase++) {
            for (int layer = 0; layer < LAYER_COUNT; layer++) {
                samples[phase][layer] = new ArrayList<>();
            }
        }
    }

    synchronized void addSample(int phase, int layer, double distanceMm) {
        if (phase < 0 || phase >= FACE_COUNT || layer < 0 || layer >= LAYER_COUNT) return;
        // Broad physical gate. Temporal median and cross-height smoothing reject remaining spikes.
        if (!Double.isFinite(distanceMm) || distanceMm <= 180.0 || distanceMm > 600.0) return;
        samples[phase][layer].add(distanceMm);
    }

    synchronized int getSampleCount(int phase, int layer) {
        return samples[phase][layer].size();
    }

    synchronized int getFaceSampleCount(int phase) {
        int count = 0;
        for (int layer = 0; layer < LAYER_COUNT; layer++) count += samples[phase][layer].size();
        return count;
    }

    synchronized int getFaceValidLayerCount(int phase) {
        int count = 0;
        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            if (samples[phase][layer].size() >= MIN_SAMPLES) count++;
        }
        return count;
    }

    synchronized void clearFace(int phase) {
        if (phase < 0 || phase >= FACE_COUNT) return;
        for (int layer = 0; layer < LAYER_COUNT; layer++) samples[phase][layer].clear();
    }

    synchronized Result calculate() {
        double[][] distances = new double[FACE_COUNT][LAYER_COUNT];
        int[][] counts = new int[FACE_COUNT][LAYER_COUNT];
        for (int phase = 0; phase < FACE_COUNT; phase++) {
            for (int layer = 0; layer < LAYER_COUNT; layer++) {
                counts[phase][layer] = samples[phase][layer].size();
                distances[phase][layer] = samples[phase][layer].size() >= MIN_SAMPLES
                        ? median(samples[phase][layer]) : Double.NaN;
            }
        }

        double[] height = new double[LAYER_COUNT];
        double[] width = new double[LAYER_COUNT];
        double[] depth = new double[LAYER_COUNT];
        double[] circumference = new double[LAYER_COUNT];
        Arrays.fill(width, Double.NaN);
        Arrays.fill(depth, Double.NaN);
        Arrays.fill(circumference, Double.NaN);

        int validLayers = 0;
        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            // Hardware layer numbers run from top to bottom: layer 1 = 1235 mm, layer 16 = 635 mm.
            height[layer] = TOP_SENSOR_HEIGHT_MM - SENSOR_SPACING_MM * layer;
            double frontRadius = SENSOR_CENTER_DISTANCE_MM - distances[FACE_FRONT][layer];
            double leftRadius = SENSOR_CENTER_DISTANCE_MM - distances[FACE_LEFT][layer];
            double backRadius = SENSOR_CENTER_DISTANCE_MM - distances[FACE_BACK][layer];
            double rightRadius = SENSOR_CENTER_DISTANCE_MM - distances[FACE_RIGHT][layer];
            if (!validRadius(frontRadius) || !validRadius(leftRadius) ||
                    !validRadius(backRadius) || !validRadius(rightRadius)) continue;

            depth[layer] = frontRadius + backRadius;
            width[layer] = leftRadius + rightRadius;
            if (depth[layer] < 120.0 || depth[layer] > 650.0 ||
                    width[layer] < 120.0 || width[layer] > 650.0) {
                depth[layer] = Double.NaN;
                width[layer] = Double.NaN;
                continue;
            }
            circumference[layer] = ellipseCircumference(width[layer] / 2.0, depth[layer] / 2.0);
            validLayers++;
        }

        double[] smoothWidth = smoothMedian3(width);
        double[] smoothDepth = smoothMedian3(depth);
        double[] smoothCircumference = new double[LAYER_COUNT];
        Arrays.fill(smoothCircumference, Double.NaN);
        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            if (Double.isFinite(smoothWidth[layer]) && Double.isFinite(smoothDepth[layer])) {
                smoothCircumference[layer] = ellipseCircumference(
                        smoothWidth[layer] / 2.0, smoothDepth[layer] / 2.0);
            }
        }

        // Adult anatomical search bands for top-to-bottom layers in the 1235..635 mm installation.
        // Locate the lower-body peak first: a thigh trough must not become the waist
        // and then exclude the true hip. Require at least 80 mm between the sites.
        int hip = findMaximum(smoothCircumference, 5, 14);
        int waist = hip >= 0 ? findMinimum(smoothCircumference, 3, Math.min(10, hip - 2)) : -1;

        String warning = null;
        if (validLayers < 10 || waist < 0 || hip < 0) {
            warning = "有效层数不足，请保持站位并重新测量";
        } else if (smoothCircumference[hip] <= smoothCircumference[waist]) {
            warning = "臀围未明显大于腰围，请检查站位或衣物";
        }

        int unstable = 0;
        for (int face = 0; face < FACE_COUNT; face++) {
            for (int layer = 0; layer < LAYER_COUNT; layer++) {
                if (counts[face][layer] < MIN_SAMPLES) continue;
                List<Double> deviations = new ArrayList<>();
                for (double value : samples[face][layer])
                    deviations.add(Math.abs(value - distances[face][layer]));
                if (median(deviations) > 15.0) unstable++;
            }
        }
        if (unstable > 0) warning = (warning == null ? "" : warning + "；") +
                unstable + "路波动较大，请复测";
        if (waist == 3 || hip == 14) warning = (warning == null ? "" : warning + "；") +
                "选层靠近搜索边界，请核对软尺高度";

        return new Result(height, smoothWidth, smoothDepth, smoothCircumference,
                distances, counts, validLayers, waist, hip, warning, calibrated);
    }

    private static boolean validRadius(double radius) {
        return Double.isFinite(radius) && radius >= 40.0 && radius <= 350.0;
    }

    private static double median(List<Double> source) {
        List<Double> sorted = new ArrayList<>(source);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static double[] smoothMedian3(double[] source) {
        double[] result = new double[source.length];
        Arrays.fill(result, Double.NaN);
        for (int i = 0; i < source.length; i++) {
            // Do not manufacture a valid measurement at a missing/bad layer.
            if (!Double.isFinite(source[i])) continue;
            List<Double> neighborhood = new ArrayList<>(3);
            for (int j = Math.max(0, i - 1); j <= Math.min(source.length - 1, i + 1); j++) {
                if (Double.isFinite(source[j])) neighborhood.add(source[j]);
            }
            if (neighborhood.size() >= 2 || (neighborhood.size() == 1 && Double.isFinite(source[i]))) {
                result[i] = median(neighborhood);
            }
        }
        return result;
    }

    private static double ellipseCircumference(double a, double b) {
        double h = Math.pow(a - b, 2.0) / Math.pow(a + b, 2.0);
        return Math.PI * (a + b) * (1.0 + 3.0 * h / (10.0 + Math.sqrt(4.0 - 3.0 * h)));
    }

    private static int findMinimum(double[] values, int from, int to) {
        int selected = -1;
        for (int i = Math.max(0, from); i <= Math.min(values.length - 1, to); i++) {
            if (Double.isFinite(values[i]) && (selected < 0 || values[i] < values[selected])) selected = i;
        }
        return selected;
    }

    private static int findMaximum(double[] values, int from, int to) {
        int selected = -1;
        for (int i = Math.max(0, from); i <= Math.min(values.length - 1, to); i++) {
            if (Double.isFinite(values[i]) && (selected < 0 || values[i] > values[selected])) selected = i;
        }
        return selected;
    }

    static final class Result {
        final double[] heightsMm;
        final double[] widthsMm;
        final double[] depthsMm;
        final double[] circumferencesMm;
        final double[][] medianDistancesMm;
        final int[][] sampleCounts;
        final int validLayers;
        final int waistLayer;
        final int hipLayer;
        final String warning;
        final boolean calibrated;

        Result(double[] heightsMm, double[] widthsMm, double[] depthsMm,
               double[] circumferencesMm, double[][] medianDistancesMm,
               int[][] sampleCounts, int validLayers, int waistLayer, int hipLayer,
               String warning, boolean calibrated) {
            this.calibrated = calibrated;
            this.heightsMm = heightsMm;
            this.widthsMm = widthsMm;
            this.depthsMm = depthsMm;
            this.circumferencesMm = circumferencesMm;
            this.medianDistancesMm = medianDistancesMm;
            this.sampleCounts = sampleCounts;
            this.validLayers = validLayers;
            this.waistLayer = waistLayer;
            this.hipLayer = hipLayer;
            this.warning = warning;
        }

        boolean hasMeasurement() {
            return validLayers >= 10 && waistLayer >= 0 && hipLayer >= 0 &&
                    Double.isFinite(circumferencesMm[waistLayer]) &&
                    Double.isFinite(circumferencesMm[hipLayer]);
        }

        double waistCm() {
            return rawWaistCm() + (calibrated ? WAIST_CORRECTION_CM : 0.0);
        }

        double hipCm() {
            return rawHipCm() + (calibrated ? HIP_CORRECTION_CM : 0.0);
        }

        double rawWaistCm() {
            return hasMeasurement() ? circumferencesMm[waistLayer] / 10.0 : Double.NaN;
        }

        double rawHipCm() {
            return hasMeasurement() ? circumferencesMm[hipLayer] / 10.0 : Double.NaN;
        }

        double waistHipRatio() {
            return hasMeasurement() ? waistCm() / hipCm() : Double.NaN;
        }

        String summary() {
            if (!hasMeasurement()) return "数据不足，无法计算腰臀围";
            return String.format(Locale.CHINA, "腰围 %.1f cm · 臀围 %.1f cm · 腰臀比 %.2f",
                    waistCm(), hipCm(), waistHipRatio());
        }
    }
}

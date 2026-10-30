package com.jinle.serialmonitor;

import org.junit.Test;
import static org.junit.Assert.*;

public class MeasurementSessionTest {
    private MeasurementSession fixture(boolean calibrated, int missing) {
        MeasurementSession session = new MeasurementSession(calibrated);
        // Hip at layer 10; a smaller thigh trough at layer 11 reproduces old selection failure.
        double[] diameters = {260,250,245,240,235,240,255,270,290,310,200,215,220,210,205,200};
        for (int layer = 0; layer < 16; layer++) {
            if (layer == missing) continue;
            for (int face = 0; face < 4; face++)
                for (int sample = 0; sample < 30; sample++)
                    session.addSample(face, layer, 500 - diameters[layer] / 2);
        }
        return session;
    }

    @Test public void hipFirstDoesNotSelectThighAsWaist() {
        MeasurementSession.Result r = fixture(false, -1).calculate();
        assertTrue(r.hasMeasurement());
        assertTrue(r.waistLayer < 7);
        assertTrue(r.hipLayer >= 7 && r.hipLayer <= 9);
        assertTrue(r.hipLayer - r.waistLayer >= 2);
    }

    @Test public void missingLayerMustNotBeInventedBySmoothing() {
        MeasurementSession.Result r = fixture(false, 8).calculate();
        assertTrue(Double.isNaN(r.circumferencesMm[8]));
        assertEquals(15, r.validLayers);
    }

    @Test public void calibrationIsOptionalAndLeavesContourUntouched() {
        MeasurementSession.Result raw = fixture(false, -1).calculate();
        MeasurementSession.Result corrected = fixture(true, -1).calculate();
        assertEquals(MeasurementSession.WAIST_CORRECTION_CM, corrected.waistCm()-raw.waistCm(),1e-9);
        assertEquals(MeasurementSession.HIP_CORRECTION_CM, corrected.hipCm()-raw.hipCm(),1e-9);
        assertArrayEquals(raw.circumferencesMm, corrected.circumferencesMm, 1e-9);
        assertEquals(corrected.waistCm()/corrected.hipCm(), corrected.waistHipRatio(),1e-9);
    }

    @Test public void blindZoneAndNonFiniteSamplesAreRejected() {
        MeasurementSession session = new MeasurementSession();
        for (double d : new double[]{170,180,601,Double.NaN,Double.POSITIVE_INFINITY}) session.addSample(0,0,d);
        assertEquals(0, session.getSampleCount(0,0));
        session.addSample(0,0,181);
        assertEquals(1, session.getSampleCount(0,0));
        assertFalse(session.calculate().hasMeasurement());
    }

    @Test public void inadequateCoverageCannotReturnMeasurement() {
        MeasurementSession session = new MeasurementSession();
        for(int l=3;l<9;l++) for(int f=0;f<4;f++) for(int n=0;n<30;n++) session.addSample(f,l,370);
        assertFalse(session.calculate().hasMeasurement());
        assertTrue(Double.isNaN(session.calculate().waistCm()));
    }
}

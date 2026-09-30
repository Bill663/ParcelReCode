package com.parcelrecode.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScanSessionControllerTest {
    @Test
    public void newerScanInvalidatesOlderResult() {
        ScanSessionController controller = new ScanSessionController();

        int first = controller.beginScan();
        int second = controller.beginScan();

        assertFalse(controller.isCurrent(first));
        assertTrue(controller.isCurrent(second));
        assertTrue(controller.isScanInProgress());
    }

    @Test
    public void clearCancelsOutstandingResultAndBusyState() {
        ScanSessionController controller = new ScanSessionController();
        int scan = controller.beginScan();

        controller.cancelOutstanding();

        assertFalse(controller.isCurrent(scan));
        assertFalse(controller.isScanInProgress());
    }
}

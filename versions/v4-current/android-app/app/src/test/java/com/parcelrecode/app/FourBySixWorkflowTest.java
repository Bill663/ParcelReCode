package com.parcelrecode.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FourBySixWorkflowTest {
    @Test
    public void firstAndSecondScansFillSlotsInOrder() {
        FourBySixWorkflow<String> workflow = new FourBySixWorkflow<>();

        assertEquals(FourBySixWorkflow.CaptureOutcome.TOP_SAVED, workflow.accept("qr1", "TNO1"));
        assertEquals("TNO1", workflow.topPayload());
        assertEquals(2, workflow.nextSlotNumber());
        assertFalse(workflow.isReady());

        assertEquals(FourBySixWorkflow.CaptureOutcome.BOTTOM_SAVED, workflow.accept("qr2", "TNO2"));
        assertEquals("TNO2", workflow.bottomPayload());
        assertEquals(0, workflow.nextSlotNumber());
        assertTrue(workflow.isReady());
    }

    @Test
    public void readyWorkflowDoesNotOverwriteSlotsWithThirdScan() {
        FourBySixWorkflow<String> workflow = readyWorkflow();

        assertEquals(FourBySixWorkflow.CaptureOutcome.ALREADY_READY, workflow.accept("qr3", "TNO3"));
        assertEquals("TNO1", workflow.topPayload());
        assertEquals("TNO2", workflow.bottomPayload());
    }

    @Test
    public void manualFieldEditUpdatesTheActiveScannedSlot() {
        FourBySixWorkflow<String> workflow = new FourBySixWorkflow<>();

        workflow.accept("qr1", "TNO1");
        assertTrue(workflow.replaceActive("qr1b", "TNO1B"));
        assertEquals("TNO1B", workflow.topPayload());

        workflow.accept("qr2", "TNO2");
        assertTrue(workflow.replaceActive("qr2b", "TNO2B"));
        assertEquals("TNO2B", workflow.bottomPayload());
    }

    @Test
    public void explicitBottomScanCanHappenBeforeTopScan() {
        FourBySixWorkflow<String> workflow = new FourBySixWorkflow<>();

        assertTrue(workflow.replaceBottom("qr2", "TNO2"));
        assertEquals("TNO2", workflow.bottomPayload());
        assertEquals(1, workflow.nextSlotNumber());
        assertFalse(workflow.isReady());

        assertTrue(workflow.replaceTop("qr1", "TNO1"));
        assertEquals("TNO1", workflow.topPayload());
        assertTrue(workflow.isReady());
    }

    @Test
    public void printMarksSessionToClearOnNextScan() {
        FourBySixWorkflow<String> workflow = readyWorkflow();

        workflow.markPrinted();
        assertTrue(workflow.shouldClearOnNextScan());
        assertTrue(workflow.prepareForNewScan());
        assertTrue(workflow.isEmpty());
        assertFalse(workflow.shouldClearOnNextScan());
    }

    private FourBySixWorkflow<String> readyWorkflow() {
        FourBySixWorkflow<String> workflow = new FourBySixWorkflow<>();
        workflow.accept("qr1", "TNO1");
        workflow.accept("qr2", "TNO2");
        return workflow;
    }
}

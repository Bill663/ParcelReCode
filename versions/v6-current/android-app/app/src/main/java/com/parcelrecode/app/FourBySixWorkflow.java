package com.parcelrecode.app;

final class FourBySixWorkflow<T> {
    enum CaptureOutcome {
        IGNORED,
        TOP_SAVED,
        BOTTOM_SAVED,
        ALREADY_READY
    }

    static final class Slot<T> {
        final T qr;
        final String payload;

        Slot(T qr, String payload) {
            this.qr = qr;
            this.payload = payload;
        }
    }

    private Slot<T> topSlot;
    private Slot<T> bottomSlot;
    private int activeSlot;
    private boolean clearOnNextScan;

    CaptureOutcome accept(T qr, String payload) {
        if (!isValid(qr, payload)) return CaptureOutcome.IGNORED;
        clearOnNextScan = false;
        if (topSlot == null) {
            topSlot = new Slot<>(qr, payload);
            activeSlot = 1;
            return CaptureOutcome.TOP_SAVED;
        }
        if (bottomSlot == null) {
            bottomSlot = new Slot<>(qr, payload);
            activeSlot = 2;
            return CaptureOutcome.BOTTOM_SAVED;
        }
        return CaptureOutcome.ALREADY_READY;
    }

    boolean replaceActive(T qr, String payload) {
        if (!isValid(qr, payload)) return false;
        if (activeSlot == 1 && topSlot != null) {
            topSlot = new Slot<>(qr, payload);
            return true;
        }
        if (activeSlot == 2 && bottomSlot != null) {
            bottomSlot = new Slot<>(qr, payload);
            return true;
        }
        return false;
    }

    boolean replaceTop(T qr, String payload) {
        if (!isValid(qr, payload)) return false;
        topSlot = new Slot<>(qr, payload);
        activeSlot = 1;
        clearOnNextScan = false;
        return true;
    }

    boolean replaceBottom(T qr, String payload) {
        if (!isValid(qr, payload)) return false;
        bottomSlot = new Slot<>(qr, payload);
        activeSlot = 2;
        clearOnNextScan = false;
        return true;
    }

    boolean prepareForNewScan() {
        if (!clearOnNextScan) return false;
        clear();
        return true;
    }

    void markPrinted() {
        if (isReady()) clearOnNextScan = true;
    }

    void clear() {
        topSlot = null;
        bottomSlot = null;
        activeSlot = 0;
        clearOnNextScan = false;
    }

    boolean isReady() {
        return topSlot != null && bottomSlot != null;
    }

    boolean isEmpty() {
        return topSlot == null && bottomSlot == null;
    }

    int nextSlotNumber() {
        if (topSlot == null) return 1;
        if (bottomSlot == null) return 2;
        return 0;
    }

    Slot<T> topSlot() {
        return topSlot;
    }

    Slot<T> bottomSlot() {
        return bottomSlot;
    }

    String topPayload() {
        return topSlot == null ? null : topSlot.payload;
    }

    String bottomPayload() {
        return bottomSlot == null ? null : bottomSlot.payload;
    }

    boolean shouldClearOnNextScan() {
        return clearOnNextScan;
    }

    private boolean isValid(T qr, String payload) {
        return qr != null && payload != null && !payload.trim().isEmpty();
    }
}

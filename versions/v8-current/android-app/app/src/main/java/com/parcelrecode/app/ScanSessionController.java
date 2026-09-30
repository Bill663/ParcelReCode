package com.parcelrecode.app;

final class ScanSessionController {
    private int currentSessionId;
    private boolean scanInProgress;

    int beginScan() {
        scanInProgress = true;
        return ++currentSessionId;
    }

    void cancelOutstanding() {
        currentSessionId++;
        scanInProgress = false;
    }

    boolean isCurrent(int sessionId) {
        return sessionId == currentSessionId;
    }

    void setScanInProgress(boolean scanInProgress) {
        this.scanInProgress = scanInProgress;
    }

    boolean isScanInProgress() {
        return scanInProgress;
    }
}

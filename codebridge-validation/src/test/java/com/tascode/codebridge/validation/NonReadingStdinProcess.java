package com.tascode.codebridge.validation;

/** Test child that deliberately leaves stdin unread so the parent pipe can fill. */
public final class NonReadingStdinProcess {
    private NonReadingStdinProcess() { }

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(30_000);
    }
}

package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class SshTermuxSession extends TerminalSession {

    public interface Transport {
        void write(byte[] data, int offset, int count);
        void resize(int columns, int rows);
    }

    private final Integer mTranscriptRows;
    private final Transport mTransport;
    private final ByteArrayOutputStream mPendingOutput = new ByteArrayOutputStream();
    private boolean mClosed;

    public SshTermuxSession(Integer transcriptRows, TerminalSessionClient client, Transport transport) {
        super("/system/bin/sh", "/", new String[0], new String[0], transcriptRows, client);
        mTranscriptRows = transcriptRows;
        mTransport = transport;
        mShellPid = 1;
    }

    @Override
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            mEmulator.resize(columns, rows);
        }
        if (!mClosed) {
            mTransport.resize(columns, rows);
        }
    }

    @Override
    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);
        byte[] pending = mPendingOutput.toByteArray();
        if (pending.length > 0) {
            mEmulator.append(pending, pending.length);
            mPendingOutput.reset();
        }
        notifyScreenUpdate();
        if (!mClosed) {
            mTransport.resize(columns, rows);
        }
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (mClosed || count <= 0) return;
        mTransport.write(data, offset, count);
    }

    @Override
    public void finishIfRunning() {
        markClosed();
    }

    @Override
    public synchronized boolean isRunning() {
        return !mClosed;
    }

    @Override
    public synchronized int getExitStatus() {
        return mClosed ? 0 : super.getExitStatus();
    }

    @Override
    public String getCwd() {
        return null;
    }

    public void appendRemoteOutput(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        appendRemoteOutput(chunk.getBytes(StandardCharsets.UTF_8));
    }

    public void appendRemoteOutput(byte[] data) {
        appendRemoteOutput(data, 0, data.length);
    }

    public void appendRemoteOutput(byte[] data, int offset, int count) {
        if (mClosed || count <= 0) return;
        byte[] payload = offset == 0 && count == data.length
            ? data
            : Arrays.copyOfRange(data, offset, offset + count);
        if (mEmulator == null) {
            mPendingOutput.write(payload, 0, payload.length);
            return;
        }
        mEmulator.append(payload, payload.length);
        notifyScreenUpdate();
    }

    public void markClosed() {
        if (mClosed) return;
        mClosed = true;
        mShellPid = -1;
        mShellExitStatus = 0;
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        mClient.onSessionFinished(this);
    }
}

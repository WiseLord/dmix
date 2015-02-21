/*
 * Copyright (C) 2004 Felipe Gustavo de Almeida
 * Copyright (C) 2010-2015 The MPDroid Project
 *
 * All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice,this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.anpmech.mpd.connection;

import com.anpmech.mpd.Log;
import com.anpmech.mpd.concurrent.MPDExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * This class is a {@link MPDConnection} status tracker.
 */
public class MPDConnectionStatus {

    /**
     * This flag enables or disables debug log output.
     */
    private static final boolean DEBUG = false;

    /**
     * The class log identifier.
     */
    private static final String TAG = "ConnectionStatus";

    /**
     * The callbacks to inform of changes.
     */
    private final Collection<MPDConnectionListener> mConnectionListeners = new ArrayList<>();

    /**
     * The connection status binary semaphore.
     * <p/>
     * This Semaphore starts off with no available permits, denoting a lack of connection until set
     * otherwise.
     */
    private final Semaphore mConnectionStatus = new Semaphore(0);

    /**
     * This boolean tracks whether the connection was cancelled by the client.
     */
    private volatile boolean mIsCancelled;

    /**
     * The 'connecting' connection status tracking field.
     */
    private volatile boolean mIsConnecting;

    /**
     * This field stores the last time the status of this connection changed.
     */
    private long mLastChangeTime = -1L;

    /**
     * This method outputs the {@code line} parameter to a {@link Log#debug(String, String)} if
     * {@link #DEBUG} is set to {@code true}.
     *
     * @param line The {@link String} to output to the log.
     */
    private static void debug(final String line) {
        if (DEBUG) {
            Log.debug(TAG, line);
        }
    }

    /**
     * Adds a listener for the connection status.
     *
     * @param listener A listener for connection status.
     */
    public void addListener(final MPDConnectionListener listener) {
        if (!mConnectionListeners.contains(listener)) {
            mConnectionListeners.add(listener);
        }
    }

    /**
     * Returns the last time a status change occurred.
     *
     * @return Returns the last time the connection status was changed in milliseconds since epoch.
     * If this connection has never had a connected state, {@link Long#MIN_VALUE} will be returned.
     */
    public long getChangeTime() {
        return mLastChangeTime;
    }

    /**
     * Whether the connection was cancelled by the client.
     *
     * @return True if cancelled by the local client, false otherwise.
     */
    public boolean isCancelled() {
        return mIsCancelled;
    }

    /**
     * Checks this connection for connected status.
     *
     * @return True if this connection is connected, false otherwise.
     */
    public boolean isConnected() {
        return mConnectionStatus.availablePermits() == 1;
    }

    /**
     * Checks this connection for connecting status.
     *
     * @return True if this connection is connecting, false otherwise.
     */
    public boolean isConnecting() {
        return mIsConnecting;
    }

    /**
     * Remove a listener from this connection.
     *
     * @param listener The listener to add for this connection.
     */
    public void removeListener(final MPDConnectionListener listener) {
        if (mConnectionListeners.contains(listener)) {
            mConnectionListeners.remove(listener);
        }
    }

    /**
     * This is called when the connection is dropped by the client, itself.
     */
    void statusChangeCancelled() {
        mIsCancelled = true;
        statusChangeDisconnected("Cancelled by client.");
    }

    /**
     * Changes the status of the connection to connected.
     *
     * @see #statusChangeDisconnected(String)
     */
    void statusChangeConnected() {
        try {
            if (!mConnectionStatus.tryAcquire()) {
                debug("Status changed to connected.");
                mIsCancelled = false;
                mIsConnecting = false;
                mLastChangeTime = System.currentTimeMillis();

                for (final MPDConnectionListener listener : mConnectionListeners) {
                    MPDExecutor.submitCallback(new Runnable() {
                        @Override
                        public void run() {
                            listener.connectionConnected();
                        }
                    });
                }
            }
        } finally {
            mConnectionStatus.release();
        }
    }

    /**
     * Changes the status of this connection to a transient 'Connecting' status.
     */
    void statusChangeConnecting() {
        if (!mIsConnecting) {
            mLastChangeTime = System.currentTimeMillis();
            debug("Status changed to connecting");
            mIsCancelled = false;
            mIsConnecting = true;

            /**
             * Acquire a permit, if available. This signifies that we're disconnected, which is
             * implied by connecting.
             */
            mConnectionStatus.tryAcquire();
            for (final MPDConnectionListener listener : mConnectionListeners) {
                MPDExecutor.submitCallback(new Runnable() {
                    @Override
                    public void run() {
                        listener.connectionConnecting();
                    }
                });
            }
        }
    }

    /**
     * Changes the status of the connection to disconnected.
     *
     * @see #statusChangeConnected()
     */
    void statusChangeDisconnected(final String reason) {
        if (mConnectionStatus.tryAcquire() || mIsConnecting) {
            debug("Status changed to disconnected: " + reason);
            mIsConnecting = false;
            mLastChangeTime = System.currentTimeMillis();

            for (final MPDConnectionListener listener : mConnectionListeners) {
                MPDExecutor.submitCallback(new Runnable() {
                    @Override
                    public void run() {
                        listener.connectionDisconnected(reason);
                    }
                });
            }
        }
    }

    /**
     * This unsets the cancelled connection status, allowing new connections to initiate.
     */
    void unsetCancelled() {
        mIsCancelled = false;
    }

    /**
     * This method blocks indefinitely, when not connected, until connection, unless interrupted.
     *
     * @throws InterruptedException If the current thread is interrupted.
     * @see #waitForConnection(long, TimeUnit)
     */
    public void waitForConnection() throws InterruptedException {
        try {
            mConnectionStatus.acquire();
        } finally {
            mConnectionStatus.release();
        }
    }

    /**
     * This method blocks when not connected until connected, or timeout.
     *
     * @param timeout The maximum time to wait for a connection.
     * @param unit    The time unit of the {@code timeout} argument.
     * @return True if the connected within time limit, false otherwise.
     * @throws InterruptedException If the current thread is interrupted.
     * @see #waitForConnection()
     */
    @SuppressWarnings("BooleanMethodNameMustStartWithQuestion")
    public boolean waitForConnection(final long timeout, final TimeUnit unit)
            throws InterruptedException {
        boolean connectionAcquired = false;

        try {
            connectionAcquired = mConnectionStatus.tryAcquire(timeout, unit);
        } finally {
            if (connectionAcquired) {
                mConnectionStatus.release();
            }
        }

        return connectionAcquired;
    }
}

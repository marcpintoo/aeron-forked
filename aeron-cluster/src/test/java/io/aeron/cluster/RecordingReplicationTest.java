/*
 * Copyright 2014-2024 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ControlResponsePoller;
import io.aeron.archive.client.ReplicationParams;
import io.aeron.archive.codecs.RecordingSignal;
import io.aeron.cluster.client.ClusterException;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.TimeUnit;

import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecordingReplicationTest
{
    private static final long SRC_RECORDING_ID = 1;
    private static final long DST_RECORDING_ID = 2;
    private static final long REPLICATION_ID = 4;

    private static final String ENDPOINT = "localhost:20123";
    private static final int SRC_STREAM_ID = 982734;
    private static final String REPLICATION_CHANNEL = "aeron:udp?endpoint=localhost:0";
    private static final long PROGRESS_CHECK_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long PROGRESS_CHECK_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1);

    private final CountersReader countersReader = mock(CountersReader.class);
    private final AeronArchive aeronArchive = mock(AeronArchive.class);
    private final ControlResponsePoller controlResponsePoller = mock(ControlResponsePoller.class);
    private final Subscription subscription = mock(Subscription.class);

    @BeforeEach
    void setUp()
    {
        final AeronArchive.Context ctx = mock(AeronArchive.Context.class);
        final Aeron aeron = mock(Aeron.class);
        when(aeronArchive.context()).thenReturn(ctx);
        when(ctx.aeron()).thenReturn(aeron);
        when(aeron.countersReader()).thenReturn(countersReader);

        when(aeronArchive.controlResponsePoller()).thenReturn(controlResponsePoller);
        when(controlResponsePoller.subscription()).thenReturn(subscription);
        when(aeronArchive.replicate(anyLong(), anyInt(), any(), any())).thenReturn(REPLICATION_ID);
    }

    @Test
    void shouldIndicateAppropriateStatesAsSignalsAreReceived()
    {
        final long stopPosition = 982734;
        final long nowNs = 0;

        final ReplicationParams replicationParams = new ReplicationParams()
            .dstRecordingId(DST_RECORDING_ID)
            .stopPosition(stopPosition)
            .replicationChannel(REPLICATION_CHANNEL)
            .replicationSessionId(Aeron.NULL_VALUE);

        final RecordingReplication logReplication = new RecordingReplication(
            aeronArchive,
            SRC_RECORDING_ID,
            ENDPOINT,
            SRC_STREAM_ID,
            replicationParams,
            PROGRESS_CHECK_TIMEOUT_NS,
            PROGRESS_CHECK_INTERVAL_NS,
            nowNs);

        logReplication.onSignal(REPLICATION_ID, DST_RECORDING_ID, NULL_POSITION, RecordingSignal.REPLICATE_END);
        assertTrue(logReplication.hasReplicationEnded());
        assertFalse(logReplication.hasSynced());
        assertFalse(logReplication.hasStopped());

        logReplication.onSignal(REPLICATION_ID, DST_RECORDING_ID, NULL_POSITION, RecordingSignal.STOP);
        assertTrue(logReplication.hasReplicationEnded());
        assertFalse(logReplication.hasSynced());
        assertTrue(logReplication.hasStopped());

        logReplication.onSignal(REPLICATION_ID, DST_RECORDING_ID, NULL_POSITION, RecordingSignal.SYNC);
        assertTrue(logReplication.hasReplicationEnded());
        assertTrue(logReplication.hasSynced());
        assertTrue(logReplication.hasStopped());
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSignal.class, names = { "START", "SYNC", "EXTEND", "REPLICATE", "MERGE" })
    void shouldStopReplicationIfNotAlreadyStopped(final RecordingSignal recordingSignal)
    {
        final long stopPosition = 982734;
        final long nowNs = 0;

        final ReplicationParams replicationParams = new ReplicationParams()
            .dstRecordingId(DST_RECORDING_ID)
            .stopPosition(stopPosition)
            .replicationChannel(REPLICATION_CHANNEL)
            .replicationSessionId(Aeron.NULL_VALUE);

        final RecordingReplication logReplication = new RecordingReplication(
            aeronArchive,
            SRC_RECORDING_ID,
            ENDPOINT,
            SRC_STREAM_ID,
            replicationParams,
            PROGRESS_CHECK_TIMEOUT_NS,
            PROGRESS_CHECK_INTERVAL_NS,
            nowNs);

        logReplication.onSignal(REPLICATION_ID, DST_RECORDING_ID, stopPosition, recordingSignal);
        logReplication.poll(nowNs);
        assertFalse(logReplication.hasReplicationEnded());

        logReplication.close();
        verify(aeronArchive).tryStopReplication(REPLICATION_ID);
    }

    @Test
    void shouldNotStopReplicationIfStopSignalled()
    {
        final long stopPosition = 982734;
        final long nowNs = 0;

        final ReplicationParams replicationParams = new ReplicationParams()
            .dstRecordingId(DST_RECORDING_ID)
            .stopPosition(stopPosition)
            .replicationChannel(REPLICATION_CHANNEL)
            .replicationSessionId(Aeron.NULL_VALUE);

        final RecordingReplication logReplication = new RecordingReplication(
            aeronArchive,
            SRC_RECORDING_ID,
            ENDPOINT,
            SRC_STREAM_ID,
            replicationParams,
            PROGRESS_CHECK_TIMEOUT_NS,
            PROGRESS_CHECK_INTERVAL_NS,
            nowNs);

        logReplication.onSignal(REPLICATION_ID, DST_RECORDING_ID, stopPosition, RecordingSignal.STOP);

        logReplication.close();
        verify(aeronArchive, never()).stopReplication(anyLong());
    }

    @Test
    void shouldFailIfRecordingLogIsDeletedDuringReplication()
    {
        final RecordingSignal recordingSignal = RecordingSignal.DELETE;
        final long stopPosition = 982734;
        final long nowNs = 0;

        final ReplicationParams replicationParams = new ReplicationParams()
            .dstRecordingId(DST_RECORDING_ID)
            .stopPosition(stopPosition)
            .replicationChannel(REPLICATION_CHANNEL)
            .replicationSessionId(Aeron.NULL_VALUE);

        final RecordingReplication logReplication = new RecordingReplication(
            aeronArchive,
            SRC_RECORDING_ID,
            ENDPOINT,
            SRC_STREAM_ID,
            replicationParams,
            PROGRESS_CHECK_TIMEOUT_NS,
            PROGRESS_CHECK_INTERVAL_NS,
            nowNs);

        assertThrows(
            ClusterException.class,
            () -> logReplication.onSignal(REPLICATION_ID, DST_RECORDING_ID, stopPosition - 1, recordingSignal));
    }

    @Test
    void shouldPollForProgressAndFailIfNotProgressing()
    {
        final long stopPosition = 982734;
        final long t0 = 20L;

        final ReplicationParams replicationParams = new ReplicationParams()
            .dstRecordingId(DST_RECORDING_ID)
            .stopPosition(stopPosition)
            .replicationChannel(REPLICATION_CHANNEL)
            .replicationSessionId(Aeron.NULL_VALUE);

        final RecordingReplication logReplication = new RecordingReplication(
            aeronArchive,
            SRC_RECORDING_ID,
            ENDPOINT,
            SRC_STREAM_ID,
            replicationParams,
            PROGRESS_CHECK_TIMEOUT_NS,
            PROGRESS_CHECK_INTERVAL_NS,
            t0);

        logReplication.onSignal(REPLICATION_ID, DST_RECORDING_ID, 0, RecordingSignal.EXTEND);
        logReplication.poll(t0);
        logReplication.poll(t0 + (PROGRESS_CHECK_INTERVAL_NS - 1));
        logReplication.poll(t0 + PROGRESS_CHECK_INTERVAL_NS);

        assertThrows(
            ClusterException.class,
            () -> logReplication.poll(t0 + PROGRESS_CHECK_INTERVAL_NS + PROGRESS_CHECK_TIMEOUT_NS));
    }
}

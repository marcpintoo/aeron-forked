/*
 * Copyright 2014-2023 Real Logic Limited.
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
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.response.ResponseClient;
import io.aeron.response.ResponseServer;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import io.aeron.test.SystemTestWatcher;
import io.aeron.test.Tests;
import io.aeron.test.driver.TestMediaDriver;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(InterruptingTestCallback.class)
public class ResponseChannelsTest
{
    private static final String REQUEST_ENDPOINT = "localhost:10000";
    private static final int REQUEST_STREAM_ID = 10000;
    private static final String RESPONSE_CONTROL = "localhost:10001";
    private static final int RESPONSE_STREAM_ID = 10001;

    @RegisterExtension
    final SystemTestWatcher watcher = new SystemTestWatcher();

    private TestMediaDriver driver1;
    private TestMediaDriver driver2;

    @BeforeEach
    void setUp()
    {
        final MediaDriver.Context context = new MediaDriver.Context()
            .publicationTermBufferLength(LogBufferDescriptor.TERM_MIN_LENGTH)
            .threadingMode(ThreadingMode.SHARED)
            .enableExperimentalFeatures(true);

        driver1 = TestMediaDriver.launch(
            context.clone().aeronDirectoryName(context.aeronDirectoryName() + "-1"), watcher);
        driver2 = TestMediaDriver.launch(
            context.clone().aeronDirectoryName(context.aeronDirectoryName() + "-2"), watcher);
        watcher.dataCollector().add(driver1.context().aeronDirectory());
        watcher.dataCollector().add(driver2.context().aeronDirectory());
    }

    @AfterEach
    void tearDown()
    {
        CloseHelper.quietCloseAll(driver1, driver2);
    }

    @Test
    @InterruptAfter(10)
    void shouldReceiveResponsesOnAPerClientBasis() throws Exception
    {
        final String textA = "hello from client A";
        final String textB = "hello from client B";
        final MutableDirectBuffer messageA = new UnsafeBuffer(textA.getBytes(UTF_8));
        final MutableDirectBuffer messageB = new UnsafeBuffer(textB.getBytes(UTF_8));
        final IdleStrategy idleStrategy = YieldingIdleStrategy.INSTANCE;
        final List<String> responsesA = new ArrayList<>();
        final List<String> responsesB = new ArrayList<>();
        final FragmentHandler fragmentHandlerA =
            (buffer, offset, length, header) -> responsesA.add(buffer.getStringWithoutLengthUtf8(offset, length));
        final FragmentHandler fragmentHandlerB =
            (buffer, offset, length, header) -> responsesB.add(buffer.getStringWithoutLengthUtf8(offset, length));

        try (Aeron server = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron clientA = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron clientB = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver2.aeronDirectoryName()));
            ResponseServer responseServer = new ResponseServer(
                server, (image) -> new EchoHandler(), REQUEST_ENDPOINT, REQUEST_STREAM_ID,
                RESPONSE_CONTROL, RESPONSE_STREAM_ID, null, null);
            ResponseClient responseClientA = new ResponseClient(
                clientA, fragmentHandlerA, REQUEST_ENDPOINT, REQUEST_STREAM_ID, RESPONSE_CONTROL, RESPONSE_STREAM_ID);
            ResponseClient responseClientB = new ResponseClient(
                clientB, fragmentHandlerB, REQUEST_ENDPOINT, REQUEST_STREAM_ID, RESPONSE_CONTROL, RESPONSE_STREAM_ID))
        {
            final Supplier<String> msg = () -> "responseServer.sessionCount=" + responseServer.sessionCount() + " " +
                "clientA=" + responseClientA + " clientB=" + responseClientB;

            while (responseServer.sessionCount() < 2 ||
                !responseClientA.isConnected() ||
                !responseClientB.isConnected())
            {
                idleStrategy.idle(run(responseServer, responseClientA, responseClientB));
                Tests.checkInterruptStatus(msg);
            }

            while (0 > responseClientA.offer(messageA))
            {
                idleStrategy.idle(run(responseServer, responseClientA, responseClientB));
                Tests.checkInterruptStatus("unable to offer message to client A");
            }

            while (0 > responseClientB.offer(messageB))
            {
                idleStrategy.idle(run(responseServer, responseClientA, responseClientB));
                Tests.checkInterruptStatus("unable to offer message to client B");
            }

            while (!responsesA.contains(textA) || !responsesB.contains(textB))
            {
                idleStrategy.idle(run(responseServer, responseClientA, responseClientB));
                Tests.checkInterruptStatus("failed to receive responses");
            }

            assertEquals(1, responsesA.size(), "A=" + responsesA + ", B=" + responsesB);
            assertEquals(1, responsesB.size(), "A=" + responsesA + ", B=" + responsesB);
        }
    }

    @Test
    @InterruptAfter(15)
    void shouldConnectResponsePublicationUsingImage()
    {
        try (Aeron server = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron client = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Subscription subReq = server.addSubscription(
                "aeron:udp?endpoint=localhost:10001", REQUEST_STREAM_ID);
            Subscription subRsp = client.addSubscription(
                "aeron:udp?control-mode=response|control=localhost:10002", RESPONSE_STREAM_ID);
            Publication pubReq = client.addPublication(
                "aeron:udp?endpoint=localhost:10001|response-correlation-id=" + subRsp.registrationId(),
                REQUEST_STREAM_ID))
        {
            Tests.awaitConnected(subReq);
            Tests.awaitConnected(pubReq);
            Objects.requireNonNull(subRsp);

            final Image image = subReq.imageAtIndex(0);
            final String url = "aeron:udp?control-mode=response|control=localhost:10002|response-correlation-id=" +
                image.correlationId();

            try (Publication pubRsp = server.addPublication(url, RESPONSE_STREAM_ID))
            {
                Tests.awaitConnected(subRsp);
                Tests.awaitConnected(pubRsp);
            }
        }
    }

    @Test
    @InterruptAfter(20)
    void shouldCorrectlyHandleSubscriptionClosesOnPartiallyCreatedResponseSubscriptions()
    {
        final int responseStreamIdB = RESPONSE_STREAM_ID + 1;
        try (Aeron server = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron client = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Subscription subReq = server.addSubscription("aeron:udp?endpoint=localhost:10001", REQUEST_STREAM_ID);
            Subscription subRspB = client.addSubscription(
                "aeron:udp?control-mode=response|control=localhost:10002|endpoint=localhost:10003",
                responseStreamIdB))
        {
            Objects.requireNonNull(subRspB);

            try (
                Subscription subRspA = client.addSubscription(
                    "aeron:udp?control-mode=response|control=localhost:10002|endpoint=localhost:10003",
                    RESPONSE_STREAM_ID);
                Publication pubReqA = client.addPublication(
                    "aeron:udp?endpoint=localhost:10001|response-correlation-id=" + subRspA.registrationId(),
                    REQUEST_STREAM_ID))
            {
                Tests.awaitConnected(subReq);
                Tests.awaitConnected(pubReqA);

                final Image image = subReq.imageAtIndex(0);
                final String url = "aeron:udp?control-mode=response|control=localhost:10002|response-correlation-id=" +
                    image.correlationId();

                try (Publication pubRsp = client.addPublication(url, RESPONSE_STREAM_ID))
                {
                    Tests.awaitConnected(subRspA);
                    Tests.awaitConnected(pubRsp);
                }

                while (subRspA.isConnected())
                {
                    Tests.yield();
                }
            }

            while (subReq.isConnected())
            {
                Tests.yield();
            }

            try (
                Publication pubReqB = client.addPublication(
                    "aeron:udp?endpoint=localhost:10001|response-correlation-id=" + subRspB.registrationId(),
                    REQUEST_STREAM_ID))
            {
                Tests.awaitConnected(subReq);
                Tests.awaitConnected(pubReqB);

                final Image image = subReq.imageAtIndex(0);
                final String url = "aeron:udp?control-mode=response|control=localhost:10002|response-correlation-id=" +
                    image.correlationId();

                try (Publication pubRsp = client.addPublication(url, responseStreamIdB))
                {
                    Tests.awaitConnected(subRspB);
                    Tests.awaitConnected(pubRsp);
                }

                while (subRspB.isConnected())
                {
                    Tests.yield();
                }
            }
        }
    }

    @Test
    @InterruptAfter(15)
    void shouldNotConnectSecondResponseSubscriptionUntilMatchingPublicationIsCreated()
    {
        try (Aeron server = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron client = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Subscription subReq = server.addSubscription(
                "aeron:udp?endpoint=localhost:10001", REQUEST_STREAM_ID);
            Subscription subRsp = client.addSubscription(
                "aeron:udp?control-mode=response|control=localhost:10002", RESPONSE_STREAM_ID);
            Subscription subRspAux = client.addSubscription(
                "aeron:udp?control-mode=response|control=localhost:10002", RESPONSE_STREAM_ID);
            Publication pubReq = client.addPublication(
                "aeron:udp?endpoint=localhost:10001|response-correlation-id=" + subRsp.registrationId(),
                REQUEST_STREAM_ID))
        {
            Tests.awaitConnected(subReq);
            Tests.awaitConnected(pubReq);
            Objects.requireNonNull(subRsp);

            final Image image = subReq.imageAtIndex(0);
            final String url = "aeron:udp?control-mode=response|control=localhost:10002|response-correlation-id=" +
                image.correlationId();

            try (Publication pubRsp = client.addPublication(url, RESPONSE_STREAM_ID))
            {
                Tests.awaitConnected(subRsp);
                Tests.awaitConnected(pubRsp);

                final long deadlineMs = System.currentTimeMillis() + 1_000;
                while (System.currentTimeMillis() <= deadlineMs)
                {
                    assertFalse(subRspAux.isConnected());
                }
            }
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldUseResponseCorrelationIdAsAPublicationMatchingCriteria()
    {
        try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Subscription sub = aeron.addSubscription("aeron:udp?endpoint=localhost:10000", 10001);
            Publication pubA = aeron.addPublication("aeron:udp?endpoint=localhost:10000", 10001);
            Publication pubB = aeron.addPublication("aeron:udp?endpoint=localhost:10000", 10001))
        {
            Tests.awaitConnected(sub);
            Tests.awaitConnected(pubA);
            Tests.awaitConnected(pubB);

            assertEquals(pubA.originalRegistrationId(), pubB.originalRegistrationId());
        }

        try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Subscription sub = aeron.addSubscription("aeron:udp?endpoint=localhost:10000", 10001);
            Subscription rspSub = aeron.addSubscription("aeron:udp?control-mode=response", 10001);
            Publication pubA = aeron.addPublication(
                "aeron:udp?endpoint=localhost:10000|response-correlation-id=" + rspSub.registrationId(), 10001);
            Publication pubB = aeron.addPublication(
                "aeron:udp?endpoint=localhost:10000|response-correlation-id=" + rspSub.registrationId(), 10001))
        {
            Tests.awaitConnected(sub);
            Tests.awaitConnected(pubA);
            Tests.awaitConnected(pubB);

            assertEquals(pubA.originalRegistrationId(), pubB.originalRegistrationId());
        }

        try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Subscription sub = aeron.addSubscription("aeron:udp?endpoint=localhost:10000", 10001);
            Subscription rspSubA = aeron.addSubscription("aeron:udp?control-mode=response", 10001);
            Subscription rspSubB = aeron.addSubscription("aeron:udp?control-mode=response", 10001);
            Publication pubA = aeron.addPublication(
                "aeron:udp?endpoint=localhost:10000|response-correlation-id=" + rspSubA.registrationId(), 10001);
            Publication pubB = aeron.addPublication(
                "aeron:udp?endpoint=localhost:10000|response-correlation-id=" + rspSubB.registrationId(), 10001))
        {
            Tests.awaitConnected(sub);
            Tests.awaitConnected(pubA);
            Tests.awaitConnected(pubB);

            assertNotEquals(pubA.originalRegistrationId(), pubB.originalRegistrationId());
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldErrorCreatingResponsePublicationWithImageThatDidNotRequestAResponseChannel()
    {
        watcher.ignoreErrorsMatching(s -> s.contains("did not request a response channel"));

        final int reqStreamId = 10001;
        final int rspStreamId = 10002;

        try (Aeron server = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron client = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Subscription subReq = server.addSubscription(
                "aeron:udp?endpoint=localhost:10001", reqStreamId);
            Publication pubReq = client.addPublication(
                "aeron:udp?endpoint=localhost:10001", reqStreamId))
        {
            Tests.awaitConnected(subReq);
            Tests.awaitConnected(pubReq);

            final Image image = subReq.imageAtIndex(0);
            final String url = "aeron:udp?control-mode=response|control=localhost:10002|response-correlation-id=" +
                image.correlationId();

            assertThrows(Exception.class, () -> client.addPublication(url, rspStreamId));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldErrorCreatingResponsePublicationWithMissingPublicationImage()
    {
        watcher.ignoreErrorsMatching(s -> s.contains("image.correlationId=") && s.contains(" not found"));

        final int reqStreamId = 10001;
        final int rspStreamId = 10002;

        try (Aeron server = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron client = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver2.aeronDirectoryName()));
            Subscription subReq = server.addSubscription(
                "aeron:udp?endpoint=localhost:10001", reqStreamId);
            Publication pubReq = client.addPublication(
                "aeron:udp?endpoint=localhost:10001", reqStreamId))
        {
            Tests.awaitConnected(subReq);
            Tests.awaitConnected(pubReq);

            final Image image = subReq.imageAtIndex(0);
            final String url = "aeron:udp?control-mode=response|control=localhost:10002|response-correlation-id=" +
                image.correlationId();

            assertThrows(Exception.class, () -> client.addPublication(url, rspStreamId));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldErrorCreatingResponsePublicationWithUnknownImage()
    {
        watcher.ignoreErrorsMatching(s -> s.contains("not found"));

        final int reqStreamId = 10001;
        final int rspStreamId = 10002;

        try (Aeron server = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron client = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver2.aeronDirectoryName()));
            Subscription subReq = server.addSubscription(
                "aeron:udp?endpoint=localhost:10001", reqStreamId);
            Publication pubReq = client.addPublication(
                "aeron:udp?endpoint=localhost:10001", reqStreamId))
        {
            Tests.awaitConnected(subReq);
            Tests.awaitConnected(pubReq);

            final Image image = subReq.imageAtIndex(0);
            final long wrongCorrelationId = image.correlationId() + 10;
            final String url =
                "aeron:udp?control-mode=response|control=localhost:10002|response-correlation-id=" + wrongCorrelationId;

            assertThrows(Exception.class, () -> client.addPublication(url, rspStreamId));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldErrorIfResponseCorrelationIdIsMissingFromAControlModeResponsePublication()
    {
        watcher.ignoreErrorsMatching(
            s -> s.contains("control-mode=response was specified, but no response-correlation-id set"));

        final int reqStreamId = 10001;
        final int rspStreamId = 10002;

        try (Aeron server = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron client = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver2.aeronDirectoryName()));
            Subscription subReq = server.addSubscription(
                "aeron:udp?endpoint=localhost:10001", reqStreamId);
            Publication pubReq = client.addPublication(
                "aeron:udp?endpoint=localhost:10001", reqStreamId))
        {
            Tests.awaitConnected(subReq);
            Tests.awaitConnected(pubReq);

            final String url = "aeron:udp?control-mode=response|control=localhost:10002";

            assertThrows(Exception.class, () -> client.addPublication(url, rspStreamId));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldBeAbleToProcessMultipleTermsWithMultipleResponseChannels() throws Exception
    {
        final UnsafeBuffer message = new UnsafeBuffer(new byte[4096]);
        message.setMemory(0, 4096, (byte)'x');
        final long stopPosition = 4 * 64 * 1024 + 1;

        final IdleStrategy idleStrategy = YieldingIdleStrategy.INSTANCE;
        final MutableLong pubACount = new MutableLong(0);
        final MutableLong pubBCount = new MutableLong(0);
        final MutableLong subACount = new MutableLong(0);
        final MutableLong subBCount = new MutableLong(0);

        final FragmentHandler recvA = (buffer, offset, length, header) -> subACount.set(header.reservedValue());
        final FragmentHandler recvB = (buffer, offset, length, header) -> subBCount.set(header.reservedValue());

        try (Aeron server = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron clientA = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron clientB = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver2.aeronDirectoryName()));
            ResponseServer responseServer = new ResponseServer(
                server, (image) -> new EchoHandler(), REQUEST_ENDPOINT, REQUEST_STREAM_ID,
                RESPONSE_CONTROL, RESPONSE_STREAM_ID, null, "aeron:udp?term-length=64k");
            ResponseClient responseClientA = new ResponseClient(
                clientA, recvA, REQUEST_ENDPOINT, REQUEST_STREAM_ID, RESPONSE_CONTROL, RESPONSE_STREAM_ID,
                "aeron:udp?term-length=64k", null);
            ResponseClient responseClientB = new ResponseClient(
                clientB, recvB, REQUEST_ENDPOINT, REQUEST_STREAM_ID, RESPONSE_CONTROL, RESPONSE_STREAM_ID,
                "aeron:udp?term-length=64k", null))
        {
            final Supplier<String> msg = () -> "responseServer.sessionCount=" + responseServer.sessionCount() + " " +
                "clientA=" + responseClientA + " clientB=" + responseClientB;

            while (responseServer.sessionCount() < 2 ||
                !responseClientA.isConnected() ||
                !responseClientB.isConnected())
            {
                idleStrategy.idle(run(responseServer, responseClientA, responseClientB));
                Tests.checkInterruptStatus(msg);
            }

            long subAStopPosition = 0;
            long subBStopPosition = 0;

            final Publication pubA = responseClientA.publication();
            final Publication pubB = responseClientB.publication();
            final MutableLong timeOfLastSubPositionChange = new MutableLong(System.currentTimeMillis());

            final Supplier<String> errorMessage =
                () ->
                "pubA.position=" + pubA.position() +
                ", subA.position=" + responseClientA.subscription().imageAtIndex(0).position() +
                ", pubA.count=" + (pubACount.get() - 1) +
                ", pubB.position=" + pubB.position() +
                ", subB.position=" + responseClientB.subscription().imageAtIndex(0).position() +
                ", pubB.count=" + (pubBCount.get() - 1) +
                ", idleTime=" + (System.currentTimeMillis() - timeOfLastSubPositionChange.get()) + "ms";

            long lastSubAPosition = 0;

            while (pubA.position() < stopPosition ||
                responseClientA.subscription().imageAtIndex(0).position() < subAStopPosition ||
                pubB.position() < stopPosition ||
                responseClientB.subscription().imageAtIndex(0).position() < subBStopPosition)
            {
                idleStrategy.idle(run(responseServer, responseClientA, responseClientB));
                Tests.checkInterruptStatus(errorMessage);

                if (pubA.position() < stopPosition)
                {
                    if (0 > pubA.offer(
                        message, 0, pubA.maxPayloadLength(),
                        (termBuffer, termOffset, frameLength) -> pubACount.getAndIncrement()))
                    {
                        Tests.yieldingIdle(errorMessage);
                    }
                }
                subAStopPosition = pubA.position();

                if (pubB.position() < stopPosition)
                {
                    if (0 > pubB.offer(
                        message, 0, pubB.maxPayloadLength(),
                        (termBuffer, termOffset, frameLength) -> pubBCount.getAndIncrement()))
                    {
                        Tests.yieldingIdle(errorMessage);
                    }
                }

                subBStopPosition = pubB.position();

                final long subAPosition = responseClientA.subscription().imageAtIndex(0).position();
                if (lastSubAPosition != subAPosition)
                {
                    lastSubAPosition = subAPosition;
                    timeOfLastSubPositionChange.set(System.currentTimeMillis());
                }
            }

            assertEquals(pubACount.get() - 1, subACount.get());
            assertEquals(pubBCount.get() - 1, subBCount.get());
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldErrorIfNoResponseSubscriptionFound()
    {
        watcher.ignoreErrorsMatching(s -> s.contains("unable to find response subscription"));

        try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Subscription rspSub = aeron.addSubscription("aeron:udp?control-mode=response", 10001))
        {
            final long wrongCorrelationId = rspSub.registrationId() + 10;
            assertThrows(Exception.class, () -> aeron.addPublication(
                "aeron:udp?endpoint=localhost:10000|response-correlation-id=" + wrongCorrelationId, 10001));
        }
    }

    @Test
    @InterruptAfter(15)
    void shouldHandleMultipleConnectionsToTheResponseChannel()
    {
        try (Aeron server = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Aeron client = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver1.aeronDirectoryName()));
            Subscription subReq = server.addSubscription(
                "aeron:udp?endpoint=localhost:10001", REQUEST_STREAM_ID);
            Subscription subRsp = client.addSubscription(
                "aeron:udp?control-mode=response|control=localhost:10002", RESPONSE_STREAM_ID);
            Publication pubReq = client.addPublication(
                "aeron:udp?endpoint=localhost:10001|response-correlation-id=" + subRsp.registrationId(),
                REQUEST_STREAM_ID))
        {
            Tests.awaitConnected(subReq);
            Tests.awaitConnected(pubReq);
            Objects.requireNonNull(subRsp);

            final Image image = subReq.imageAtIndex(0);
            final String url = "aeron:udp?control-mode=response|control=localhost:10002|response-correlation-id=" +
                image.correlationId();

            try (Publication pubRspA = client.addPublication(url, RESPONSE_STREAM_ID))
            {
                Tests.awaitConnected(subRsp);
                Tests.awaitConnected(pubRspA);

                try (Publication pubRspB = client.addPublication(url, RESPONSE_STREAM_ID))
                {
                    Tests.awaitConnected(pubRspB);

                    assertEquals(pubRspA.originalRegistrationId(), pubRspB.originalRegistrationId());
                }
            }
        }
    }

    private static final class EchoHandler implements ResponseServer.ResponseHandler
    {
        public boolean onMessage(
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header,
            final Publication responsePublication)
        {
            return 0 < responsePublication.offer(
                buffer, offset, length, (termBuffer, termOffset, frameLength) -> header.reservedValue());
        }
    }

    private static int run(final Agent... agents) throws Exception
    {
        int work = 0;

        for (final Agent agent : agents)
        {
            work += agent.doWork();
        }

        return work;
    }
}

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
package io.aeron.cluster.service;

import io.aeron.Aeron;
import io.aeron.Counter;
import io.aeron.RethrowingErrorHandler;
import io.aeron.cluster.codecs.mark.ClusterComponentType;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static io.aeron.cluster.service.ClusterMarkFile.ERROR_BUFFER_MIN_LENGTH;
import static io.aeron.cluster.service.ClusteredServiceContainer.Configuration.MARK_FILE_DIR_PROP_NAME;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusteredServiceContainerContextTest
{
    @TempDir
    private File clusterDir;
    private ClusteredServiceContainer.Context context;
    private final int serviceId = 1;

    @BeforeEach
    void setUp()
    {
        final RethrowingErrorHandler errorHandler = mock(RethrowingErrorHandler.class);
        final Aeron.Context aeronContext = mock(Aeron.Context.class);
        when(aeronContext.aeronDirectoryName()).thenReturn("funny");
        when(aeronContext.subscriberErrorHandler()).thenReturn(errorHandler);
        final Aeron aeron = mock(Aeron.class);
        when(aeron.addCounter(
            anyInt(), any(DirectBuffer.class), anyInt(), anyInt(), any(DirectBuffer.class), anyInt(), anyInt()))
            .thenAnswer(invocation -> mock(Counter.class));
        when(aeron.context()).thenReturn(aeronContext);
        final AtomicCounter errorCounter = mock(AtomicCounter.class);
        final ClusteredService clusteredService = mock(ClusteredService.class);
        context = new ClusteredServiceContainer.Context()
            .aeron(aeron)
            .errorCounter(errorCounter)
            .errorHandler(errorHandler)
            .clusteredService(clusteredService)
            .clusterDir(clusterDir);
    }

    @Test
    void throwsIllegalStateExceptionIfAnActiveMarkFileExists()
    {
        final ClusteredServiceContainer.Context anotherInstance = context.clone();

        try
        {
            context.conclude();

            final RuntimeException exception = assertThrowsExactly(RuntimeException.class, anotherInstance::conclude);
            final Throwable cause = exception.getCause();
            assertInstanceOf(IllegalStateException.class, cause);
            assertEquals("active Mark file detected", cause.getMessage());
        }
        finally
        {
            context.close();
        }
    }

    @Test
    void concludeShouldCreateMarkFileDirSetViaSystemProperty(final @TempDir File tempDir) throws IOException
    {
        final File rootDir = new File(tempDir, "root");
        final File markFileDir = new File(rootDir, "mark-file/../dir");
        assertFalse(markFileDir.getCanonicalFile().exists());
        context.serviceId(serviceId);

        System.setProperty(MARK_FILE_DIR_PROP_NAME, markFileDir.getAbsolutePath());
        try
        {
            assertNull(context.markFileDir());

            context.conclude();

            assertEquals(markFileDir.getCanonicalFile(), context.markFileDir());
            assertTrue(markFileDir.getCanonicalFile().exists());
            assertTrue(
                new File(context.clusterDir(), ClusterMarkFile.linkFilenameForService(context.serviceId())).exists());
        }
        finally
        {
            System.clearProperty(MARK_FILE_DIR_PROP_NAME);
            CloseHelper.quietClose(context::close);
        }
    }

    @Test
    void concludeShouldCreateMarkFileDirSetDirectly(final @TempDir File tempDir) throws IOException
    {
        final File rootDir = new File(tempDir, "root");
        final File markFileDir = new File(rootDir, "mark/.././file-dir");
        assertFalse(markFileDir.exists());
        context.serviceId(serviceId).markFileDir(markFileDir);

        try
        {
            context.conclude();

            assertEquals(markFileDir.getCanonicalFile(), context.markFileDir());
            assertTrue(markFileDir.getCanonicalFile().exists());
            assertTrue(
                new File(context.clusterDir(), ClusterMarkFile.linkFilenameForService(context.serviceId())).exists());
        }
        finally
        {
            CloseHelper.quietClose(context::close);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void shouldRemoveLinkIfMarkFileIsInClusterDir(final boolean isSet) throws IOException
    {
        final File markFileDir = isSet ? context.clusterDir() : null;

        context.serviceId(serviceId).markFileDir(markFileDir);
        final File oldLinkFile = new File(context.clusterDir(), ClusterMarkFile.linkFilenameForService(serviceId));
        assertTrue(oldLinkFile.createNewFile());
        assertTrue(oldLinkFile.exists());

        try
        {
            context.conclude();

            assertFalse(oldLinkFile.exists());
        }
        finally
        {
            CloseHelper.quietClose(context::close);
        }
    }

    @Test
    void concludeShouldCreateMarkFileLinkInTheParentDirectoryOfTheClusterMarkFile(
        final @TempDir File clusterDir,
        final @TempDir File markFileDir,
        final @TempDir File otherDir) throws IOException
    {
        final ClusterMarkFile clusterMarkFile = new ClusterMarkFile(
            new File(otherDir, "test.not"),
            ClusterComponentType.CONSENSUS_MODULE,
            ERROR_BUFFER_MIN_LENGTH,
            SystemEpochClock.INSTANCE,
            10);
        context
            .serviceId(serviceId)
            .clusterDir(clusterDir)
            .markFileDir(markFileDir)
            .clusterMarkFile(clusterMarkFile);

        try
        {
            context.conclude();

            assertEquals(clusterDir.getCanonicalFile(), context.clusterDir());
            assertEquals(markFileDir.getCanonicalFile(), context.markFileDir());
            assertEquals(otherDir, context.clusterMarkFile().parentDirectory());
            assertTrue(clusterDir.getCanonicalFile().exists());
            assertTrue(markFileDir.getCanonicalFile().exists());
            final File linkFile = new File(context.clusterDir(), ClusterMarkFile.linkFilenameForService(serviceId));
            assertTrue(linkFile.exists());
            assertEquals(otherDir.getCanonicalPath(), new String(Files.readAllBytes(linkFile.toPath()), US_ASCII));
        }
        finally
        {
            CloseHelper.quietClose(context::close);
        }
    }

    @Test
    void shouldInitializeClusterDirectoryNameFromTheAssignedClusterDir(@TempDir final Path dir) throws IOException
    {
        final Path clusterDir = dir.resolve("explicit/cluster/./dir");
        context.clusterDir(clusterDir.toFile()).clusterDirectoryName("replace-me");

        try
        {
            context.conclude();

            assertEquals(clusterDir.toFile().getCanonicalFile(), context.clusterDir());
            assertEquals(context.clusterDir().getAbsolutePath(), context.clusterDirectoryName());
        }
        finally
        {
            CloseHelper.quietClose(context::close);
        }
    }

    @Test
    void shouldInitializeClusterDirectoryFromTheGivenDirectoryName(@TempDir final Path temp) throws IOException
    {
        final Path dir = Paths.get(temp.toString(), "/some/../path");
        context.clusterDir(null).clusterDirectoryName(dir.toString());

        try
        {
            context.conclude();

            assertEquals(dir.toFile().getCanonicalFile(), context.clusterDir());
            assertEquals(context.clusterDir().getAbsolutePath(), context.clusterDirectoryName());
        }
        finally
        {
            CloseHelper.quietClose(context::close);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldSetServiceNameIfNotSpecified(final String serviceName)
    {
        context.clusterId(7).serviceId(5).serviceName(serviceName);

        try
        {
            context.conclude();

            assertEquals("clustered-service-7-5", context.serviceName());
        }
        finally
        {
            CloseHelper.quietClose(context::close);
        }
    }

    @Test
    void shouldSetServiceNameExplicitly()
    {
        context.clusterId(7).serviceId(5).serviceName("test 13");

        try
        {
            context.conclude();

            assertEquals("test 13", context.serviceName());
        }
        finally
        {
            CloseHelper.quietClose(context::close);
        }
    }

    @Test
    void shouldNotSetClientName()
    {
        context.clusterId(42).serviceId(0).serviceName("test 13");

        try
        {
            context.conclude();

            verify(context.aeron().context(), never()).clientName(anyString());
        }
        finally
        {
            CloseHelper.quietClose(context::close);
        }
    }
}

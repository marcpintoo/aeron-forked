/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver;

/**
 * Tracks a aeron client interest registration in a {@link NetworkPublication} or {@link IpcPublication}.
 */
public class PublicationLink implements DriverManagedResource
{
    private final long registrationId;
    private final NetworkPublication networkPublication;
    private final IpcPublication ipcPublication;
    private final AeronClient client;
    private boolean reachedEndOfLife = false;

    public PublicationLink(final long registrationId, final AeronClient client, final NetworkPublication publication)
    {
        this.registrationId = registrationId;
        this.client = client;

        this.networkPublication = publication;
        this.ipcPublication = null;
        publication.incRef();
    }

    public PublicationLink(final long registrationId, final AeronClient client, final IpcPublication publication)
    {
        this.registrationId = registrationId;
        this.client = client;

        this.networkPublication = null;
        this.ipcPublication = publication;
        publication.incRef();
    }

    public void close()
    {
        if (null != networkPublication)
        {
            networkPublication.decRef();
        }

        if (null != ipcPublication)
        {
            ipcPublication.decRef();
        }
    }

    public long registrationId()
    {
        return registrationId;
    }

    public void onTimeEvent(final long timeNs, final long timeMs, final DriverConductor conductor)
    {
        if (client.hasTimedOut(timeNs))
        {
            reachedEndOfLife = true;
        }
    }

    public boolean hasReachedEndOfLife()
    {
        return reachedEndOfLife;
    }
}

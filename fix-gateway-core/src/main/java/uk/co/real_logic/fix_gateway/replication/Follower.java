/*
 * Copyright 2015 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.replication;

import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.aeron.logbuffer.FragmentHandler;
import uk.co.real_logic.aeron.logbuffer.Header;
import uk.co.real_logic.agrona.DirectBuffer;

public class Follower implements Role, FragmentHandler, ControlHandler
{
    private final ControlSubscriber controlSubscriber = new ControlSubscriber(this);

    private final short id;
    private final ControlPublication controlPublication;
    private final FragmentHandler delegate;
    private final Subscription dataSubscription;
    private final Subscription controlSubscription;
    private final Replicator replicator;
    private final long replyTimeoutInMs;

    private long latestNextReceiveTimeInMs;
    private long position;
    private boolean receivedHeartbeat = false;

    public Follower(
        final short id,
        final ControlPublication controlPublication,
        final FragmentHandler delegate,
        final Subscription dataSubscription,
        final Subscription controlSubscription,
        final Replicator replicator,
        final long timeInMs,
        long replyTimeoutInMs)
    {
        this.id = id;
        this.controlPublication = controlPublication;
        this.delegate = delegate;
        this.dataSubscription = dataSubscription;
        this.controlSubscription = controlSubscription;
        this.replicator = replicator;
        this.replyTimeoutInMs = replyTimeoutInMs;
        this.latestNextReceiveTimeInMs = timeInMs + replyTimeoutInMs;
    }

    public int poll(final int fragmentLimit, final long timeInMs)
    {
        final int readControlMessages =
            controlSubscription.poll(controlSubscriber, fragmentLimit);

        final int readDataMessages = dataSubscription.poll(this, fragmentLimit);

        if (readDataMessages > 0)
        {
            controlPublication.saveMessageAcknowledgement(position, id);
            onReceivedMessage(timeInMs);
        }

        if (receivedHeartbeat)
        {
            onReceivedMessage(timeInMs);
            receivedHeartbeat = false;
        }

        if (timeInMs > latestNextReceiveTimeInMs)
        {
            replicator.becomeCandidate();
        }

        return readControlMessages + readDataMessages;
    }

    private void onReceivedMessage(final long timeInMs)
    {
        latestNextReceiveTimeInMs = timeInMs + replyTimeoutInMs;
    }

    public void onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        delegate.onFragment(buffer, offset, length, header);
        position = header.position();
    }

    public void onMessageAcknowledgement(final long newAckedPosition, final short nodeId)
    {
        // not interested in this message
    }

    public void onRequestVote(final short candidateId, final long lastAckedPosition)
    {
        // TODO
    }

    public void onConcensusHeartbeat(final short nodeId)
    {
        receivedHeartbeat = true;
    }
}

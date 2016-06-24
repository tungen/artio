/*
 * Copyright 2015-2016 Real Logic Ltd.
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

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ReceiveChannelEndpointSupplier;
import io.aeron.driver.SendChannelEndpointSupplier;
import io.aeron.driver.ext.DebugReceiveChannelEndpoint;
import io.aeron.driver.ext.DebugSendChannelEndpoint;
import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.fix_gateway.DebugLogger;
import uk.co.real_logic.fix_gateway.TestFixtures;
import uk.co.real_logic.fix_gateway.engine.logger.ArchiveMetaData;
import uk.co.real_logic.fix_gateway.engine.logger.ArchiveReader;
import uk.co.real_logic.fix_gateway.engine.logger.Archiver;

import static io.aeron.CommonContext.AERON_DIR_PROP_DEFAULT;
import static io.aeron.driver.ThreadingMode.SHARED;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static org.agrona.BitUtil.SIZE_OF_SHORT;
import static org.mockito.Mockito.mock;
import static uk.co.real_logic.fix_gateway.TestFixtures.cleanupDirectory;
import static uk.co.real_logic.fix_gateway.engine.EngineConfiguration.DEFAULT_LOGGER_CACHE_NUM_SETS;
import static uk.co.real_logic.fix_gateway.engine.EngineConfiguration.DEFAULT_LOGGER_CACHE_SET_SIZE;
import static uk.co.real_logic.fix_gateway.replication.ClusterNodeConfiguration.DEFAULT_DATA_STREAM_ID;

class NodeRunner implements AutoCloseable
{
    private static final long TIMEOUT_IN_MS = 1000;
    private static final String AERON_CHANNEL = TestFixtures.clusteredAeronChannel();

    private final SwitchableLossGenerator outboundLossGenerator = new SwitchableLossGenerator();
    private final SwitchableLossGenerator inboundLossGenerator = new SwitchableLossGenerator();

    private final Int2IntHashMap nodeIdToId = new Int2IntHashMap(-1);
    private final MediaDriver mediaDriver;
    private final Aeron aeron;
    private final ClusterAgent clusterNode;
    private final int nodeId;
    private final ControlledFragmentHandler handler;
    private final ClusterableSubscription subscription;

    private long replicatedPosition = -1;

    NodeRunner(final int nodeId, final int... otherNodes)
    {
        this.nodeId = nodeId;
        this.handler = (buffer, offset, length, header) ->
        {
            replicatedPosition = offset + length;
            DebugLogger.log("%d: position %d\n", nodeId, replicatedPosition);
            return CONTINUE;
        };

        final int termBufferLength = 1024 * 1024;
        final MediaDriver.Context context = new MediaDriver.Context();
        context
            .threadingMode(SHARED)
            .sharedIdleStrategy(new YieldingIdleStrategy())
            .receiveChannelEndpointSupplier(newReceiveChannelEndpointSupplier())
            .sendChannelEndpointSupplier(newSendChannelEndpointSupplier())
            .dirsDeleteOnStart(true)
            .aeronDirectoryName(AERON_DIR_PROP_DEFAULT + nodeId)
            .publicationTermBufferLength(termBufferLength)
            .ipcTermBufferLength(termBufferLength);

        final IntHashSet otherNodeIds = new IntHashSet(40, -1);
        for (final int node : otherNodes)
        {
            otherNodeIds.add(node);
        }

        mediaDriver = MediaDriver.launch(context);
        final Aeron.Context clientContext = new Aeron.Context();
        clientContext.aeronDirectoryName(context.aeronDirectoryName());
        aeron = Aeron.connect(clientContext);

        final StreamIdentifier dataStream = new StreamIdentifier(AERON_CHANNEL, DEFAULT_DATA_STREAM_ID);
        final ArchiveMetaData metaData = AbstractReplicationTest.archiveMetaData((short) nodeId);
        final ArchiveReader archiveReader = new ArchiveReader(
            metaData, DEFAULT_LOGGER_CACHE_NUM_SETS, DEFAULT_LOGGER_CACHE_SET_SIZE, dataStream);
        final Archiver archiver = new Archiver(
            metaData, DEFAULT_LOGGER_CACHE_NUM_SETS, DEFAULT_LOGGER_CACHE_SET_SIZE, dataStream);
        final UnsafeBuffer nodeState = new UnsafeBuffer(new byte[SIZE_OF_SHORT]);
        nodeState.putShort(0, (short) nodeId);

        final ClusterNodeConfiguration configuration = new ClusterNodeConfiguration()
            .nodeId((short) nodeId)
            .aeron(aeron)
            .otherNodes(otherNodeIds)
            .timeoutIntervalInMs(TIMEOUT_IN_MS)
            .failCounter(mock(AtomicCounter.class))
            .aeronChannel(AERON_CHANNEL)
            .archiver(archiver)
            .archiveReader(archiveReader)
            .nodeState(nodeState)
            .nodeStateHandler(new NodeIdStasher());

        clusterNode = new ClusterAgent(configuration, System.currentTimeMillis());
        subscription = clusterNode.clusterStreams().subscription(1);
    }

    private SendChannelEndpointSupplier newSendChannelEndpointSupplier()
    {
        return (udpChannel, statusIndicator, context) ->
            new DebugSendChannelEndpoint(
                udpChannel, statusIndicator, context, outboundLossGenerator, outboundLossGenerator);
    }

    private ReceiveChannelEndpointSupplier newReceiveChannelEndpointSupplier()
    {
        return (udpChannel, dispatcher, statusIndicator, context) ->
            new DebugReceiveChannelEndpoint(
                udpChannel, dispatcher, statusIndicator, context, inboundLossGenerator, inboundLossGenerator);
    }

    public int poll(final int fragmentLimit)
    {
        return clusterNode.doWork() + subscription.controlledPoll(handler, fragmentLimit);
    }

    public void dropFrames(final boolean dropFrames)
    {
        dropFrames(dropFrames, dropFrames);
    }

    public void dropFrames(final boolean dropInboundFrames, final boolean dropOutboundFrames)
    {
        DebugLogger.log("Dropping frames to %d: %b\n", nodeId, dropInboundFrames);
        DebugLogger.log("Dropping frames from %d: %b\n", nodeId, dropOutboundFrames);
        inboundLossGenerator.dropFrames(dropInboundFrames);
        outboundLossGenerator.dropFrames(dropOutboundFrames);
    }

    public boolean isLeader()
    {
        return clusterNode.isLeader();
    }

    public ClusterAgent raftNode()
    {
        return clusterNode;
    }

    public long replicatedPosition()
    {
        return replicatedPosition;
    }

    public int leaderSessionId()
    {
        return raftNode().termState().leaderSessionId().get();
    }

    public Int2IntHashMap nodeIdToId()
    {
        return nodeIdToId;
    }

    public void close()
    {
        CloseHelper.close(aeron);
        CloseHelper.close(mediaDriver);
        cleanupDirectory(mediaDriver);
    }

    private class NodeIdStasher implements NodeStateHandler
    {

        public void onNewNodeState(final short nodeId,
                                   final int aeronSessionId,
                                   final DirectBuffer nodeStateBuffer,
                                   final int nodeStateLength)
        {
            if (nodeStateLength == SIZE_OF_SHORT)
            {
                nodeIdToId.put(nodeId, nodeStateBuffer.getShort(0));
            }
        }

        public void onNewLeader(final int leaderSessionId)
        {

        }
    }
}

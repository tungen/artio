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
package uk.co.real_logic.fix_gateway.library;

import io.aeron.Subscription;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.LangUtil;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.fix_gateway.DebugLogger;
import uk.co.real_logic.fix_gateway.FixGatewayException;
import uk.co.real_logic.fix_gateway.GatewayProcess;
import uk.co.real_logic.fix_gateway.LivenessDetector;
import uk.co.real_logic.fix_gateway.engine.SessionInfo;
import uk.co.real_logic.fix_gateway.messages.*;
import uk.co.real_logic.fix_gateway.protocol.GatewayPublication;
import uk.co.real_logic.fix_gateway.protocol.LibraryProtocolHandler;
import uk.co.real_logic.fix_gateway.protocol.LibraryProtocolSubscription;
import uk.co.real_logic.fix_gateway.protocol.SessionSubscription;
import uk.co.real_logic.fix_gateway.session.*;
import uk.co.real_logic.fix_gateway.timing.LibraryTimers;
import uk.co.real_logic.fix_gateway.timing.Timer;
import uk.co.real_logic.fix_gateway.util.AsciiBuffer;
import uk.co.real_logic.fix_gateway.util.MutableAsciiBuffer;
import uk.co.real_logic.fix_gateway.validation.AuthenticationStrategy;
import uk.co.real_logic.fix_gateway.validation.MessageValidationStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import static io.aeron.Publication.BACK_PRESSURED;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static uk.co.real_logic.fix_gateway.engine.FixEngine.GATEWAY_LIBRARY_ID;
import static uk.co.real_logic.fix_gateway.messages.ConnectionType.INITIATOR;
import static uk.co.real_logic.fix_gateway.messages.GatewayError.UNABLE_TO_CONNECT;
import static uk.co.real_logic.fix_gateway.messages.LogonStatus.LIBRARY_NOTIFICATION;
import static uk.co.real_logic.fix_gateway.messages.SessionReplyStatus.MISSING_MESSAGES;
import static uk.co.real_logic.fix_gateway.messages.SessionState.ACTIVE;

/**
 * FIX Library instances represent a process in the gateway where session management,
 * message parsing and API users configure the gateway.
 * <p>
 * Libraries can be run in the same process as the engine, or in a
 * different process.
 * <p>
 * FixLibrary instances are not thread safe and should be run on
 * their own thread.
 *
 * @see uk.co.real_logic.fix_gateway.engine.FixEngine
 */
public final class FixLibrary extends GatewayProcess
{
    public static final int NO_MESSAGE_REPLAY = -1;

    private final Subscription inboundSubscription;
    private final GatewayPublication outboundPublication;
    private final Long2ObjectHashMap<SessionSubscriber> connectionIdToSession = new Long2ObjectHashMap<>();
    private final List<Session> sessions = new ArrayList<>();
    private final List<Session> unmodifiableSessions = unmodifiableList(sessions);

    private final EpochClock clock;
    private final LibraryConfiguration configuration;
    private final SessionIdStrategy sessionIdStrategy;
    private final Timer sessionTimer;
    private final Timer receiveTimer;
    private final LivenessDetector livenessDetector;
    private final SessionExistsHandler sessionExistsHandler;
    private final int libraryId;
    private final IdleStrategy idleStrategy;
    private final SentPositionHandler sentPositionHandler;

    // The state consists of a connection id if an operation is happening, or the status if its done.
    private final Long2ObjectHashMap<Object> correlationIdToState = new Long2ObjectHashMap<>();

    /** Correlation Id is initialised to a random number to reduce the chance of correlation id collision. */
    private long currentCorrelationId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    private Session incomingSession;
    private SessionConfiguration sessionConfiguration;
    private GatewayError errorType;
    private String errorMessage;
    private SessionReplyStatus replyStatus;

    private FixLibrary(final LibraryConfiguration configuration)
    {
        configuration.conclude();

        init(configuration);

        final LibraryTimers timers = new LibraryTimers();
        sessionTimer = timers.sessionTimer();
        receiveTimer = timers.receiveTimer();
        initMonitoringAgent(timers.all(), configuration);

        this.configuration = configuration;
        this.sessionIdStrategy = configuration.sessionIdStrategy();
        this.libraryId = configuration.libraryId();
        idleStrategy = configuration.libraryIdleStrategy();
        sentPositionHandler = configuration.sentPositionHandler();

        inboundSubscription = inboundLibraryStreams.subscription();
        outboundPublication = outboundLibraryStreams.gatewayPublication(idleStrategy);
        processProtocolHandler.sessionId = outboundPublication.sessionId();

        clock = new SystemEpochClock();
        livenessDetector = LivenessDetector.forLibrary(
            outboundPublication,
            configuration.libraryId(),
            configuration.replyTimeoutInMs());
        sessionExistsHandler = configuration.sessionExistsHandler();
    }

    private FixLibrary connect()
    {
        try
        {
            if (outboundPublication.saveLibraryConnect(libraryId) == BACK_PRESSURED)
            {
                return connectError("BackPressured upon connection");
            }

            final long latestReplyArrivalTime = latestReplyArrivalTime();
            while (!livenessDetector.isConnected() && errorType == null)
            {
                final int workCount = poll(1);

                checkTime(latestReplyArrivalTime);

                idleStrategy.idle(workCount);
            }

            if (errorType != null)
            {
                return connectError(errorType.toString());
            }

            start();
        }
        catch (Exception e)
        {
            // We won't be returning an instance of ourselves to callers in the connect,
            // so we must clean up after ourselves
            try
            {
                close();
            }
            catch (Exception closeException)
            {
                e.addSuppressed(closeException);
            }

            LangUtil.rethrowUnchecked(e);
        }

        return this;
    }

    private FixLibrary connectError(final String message)
    {
        throw new FixGatewayException(String.format(
                "Unable to connect to engine: %s", message
        ));
    }

    // ------------- Public API -------------

    /**
     * Connect to an engine. This method blocks until the connection is complete and then returns.
     *
     * @param configuration the configuration for this library instance.
     * @return the library instance once it has connected.
     * @throws FixGatewayException
     *         if there's an error connecting to the FIX Gateway or if there's a timeout talking to
     *         the FixEngine.
     */
    public static FixLibrary connect(final LibraryConfiguration configuration)
    {
        return new FixLibrary(configuration).connect();
    }

    /**
     * Poll the library all of its component sessions to process any messages
     * and events that have received from or should be sent to the engine.
     *
     * @param fragmentLimit the maximum number of events to read from the engine.
     * @return 0 if no work was performed, > 0 otherwise.
     */
    public int poll(final int fragmentLimit)
    {
        final long timeInMs = clock.time();
        return inboundSubscription.poll(outboundSubscription, fragmentLimit) +
               pollSessions(timeInMs) +
               livenessDetector.poll(timeInMs);
    }

    /**
     * Check if the library is connected to an engine.
     * <p>
     * Note that this refers to whether a library is connected to a FIX Engine,
     * not whether of its sessions are connected.
     *
     * @return true if the library is connected to an engine, false otherwise.
     * @see Session#isConnected()
     * @see uk.co.real_logic.fix_gateway.engine.FixEngine
     */
    public boolean isConnected()
    {
        return livenessDetector.isConnected();
    }

    /**
     * Get the identifier of the library.
     *
     * @return the identifier of the library.
     */
    public int libraryId()
    {
        return libraryId;
    }

    /**
     * Get a list of the currently active sessions.
     * <p>
     * Note: the list is unmodifiable.
     *
     * @return a list of the currently active sessions.
     */
    public List<Session> sessions()
    {
        return unmodifiableSessions;
    }

    /**
     * Close the Library.
     */
    public void close()
    {
        connectionIdToSession.values().forEach(SessionSubscriber::close);
        super.close();
    }

    /**
     * Initiate a FIX session with a FIX acceptor. This method blocks and returns only once the Session
     * object has connected to the acceptor.
     *
     * @param configuration the configuration to use for the session.
     * @return the session object for the session that you've initiated.
     * @throws IllegalStateException
     *         if you're trying to initiate two sessions at the same time or if there's a timeout talking to
     *         the FixEngine.
     *         This probably indicates that there's a problem in your code or that your engine isn't running.
     * @throws FixGatewayException
     *         if you're unable to connect to the accepting gateway.
     *         This probably indicates a configuration problem related to the external gateway.
     */
    public Session initiate(final SessionConfiguration configuration)
    {
        requireNonNull(configuration, "configuration");

        if (sessionConfiguration != null || incomingSession != null || errorType != null)
        {
            return concurrentError();
        }

        sessionConfiguration = configuration;

        try
        {
            final List<String> hosts = configuration.hosts();
            final List<Integer> ports = configuration.ports();
            final int size = hosts.size();
            for (int i = 0; i < size; i++)
            {
                final String host = hosts.get(i);
                final int port = ports.get(i);

                outboundPublication.saveInitiateConnection(
                    libraryId,
                    host,
                    port,
                    configuration.senderCompId(),
                    configuration.senderSubId(),
                    configuration.senderLocationId(),
                    configuration.targetCompId(),
                    configuration.sequenceNumberType(),
                    configuration.initialSequenceNumber(),
                    configuration.username(),
                    configuration.password(),
                    this.configuration.defaultHeartbeatIntervalInS());

                awaitReply(() -> incomingSession == null && errorType == null);

                if (incomingSession != null)
                {
                    final Session session = incomingSession;
                    session.address(host, port);
                    return session;
                }
                else if (errorType != UNABLE_TO_CONNECT)
                {
                    throw new FixGatewayException(String.format("%s: %s", errorType, errorMessage));
                }

                errorType = null;
            }

            throw new FixGatewayException("Unable to connect to any of the addresses specified");
        }
        finally
        {
            sessionConfiguration = null;
            errorType = null;
            incomingSession = null;
        }
    }

    /**
     * Release this session object to the gateway to manage. If the release
     * operation has successfully completed then it will return {@link SessionReplyStatus#OK}
     *
     * @param session the session to release
     * @return the result of this operation.
     */
    public SessionReplyStatus releaseToGateway(final Session session)
    {
        requireNonNull(session, "session");
        if (replyStatus != null)
        {
            return concurrentError();
        }

        outboundPublication.saveReleaseSession(
            libraryId,
            session.connectionId(),
            ++currentCorrelationId,
            session.state(),
            session.heartbeatIntervalInMs(),
            session.lastSentMsgSeqNum(),
            session.lastReceivedMsgSeqNum(),
            session.username(),
            session.password());

        awaitReply(() -> replyStatus == null);

        final SessionReplyStatus replyStatus = this.replyStatus;
        this.replyStatus = null;
        if (replyStatus == SessionReplyStatus.OK)
        {
            sessions.remove(session);
            session.disable();
        }

        return replyStatus;
    }

    /**
     * Accquire control of a session. If this session is being managed by
     * the gateway then your {@link SessionAcquireHandler} will receive a callback
     * and this method will return {@link SessionReplyStatus#OK}.
     *
     * If another library has acquired the session then this method will return
     * {@link SessionReplyStatus#OTHER_SESSION_OWNER}. If the connection id refers
     * to an unknown session then the method returns {@link SessionReplyStatus#UNKNOWN_SESSION}.
     * If this library instance is unknown to the gateway, for example if its heartbeating
     * mechanism has timed out due to {@link this#poll(int)} not being called often enough.
     *
     * Equivalent to calling {@link this#requestSession(long, int)} and then waiting for the
     * reply.
     *
     * @param sessionId the id of the session to acquire.
     * @param lastReceivedSequenceNumber the last received message sequence number
     *                                   that you know about. You will get a stream
     *                                   of messages replayed to you from
     *                                   <code>lastReceivedMessageSequenceNumber + 1</code>
     *                                   to the latest message sequence number.
     *                                   If you don't care about message replay then
     *                                   use {@link this#acquireSession(long)}
     * @return the result of this operation.
     */
    public SessionReplyStatus acquireSession(final long sessionId, final int lastReceivedSequenceNumber)
    {
        final long correlationId = requestSession(sessionId, lastReceivedSequenceNumber);

        awaitReply(() -> requestSessionAwaitingReply(correlationId));

        return pollRequestStatus(correlationId);
    }

    /**
     * Request a session be acquired from the Gateway. This is the non-blocking version
     * of {@link this#acquireSession(long)}. It returns an id that can be used to
     * inspect {@link this#pollRequestStatus(long)}.
     *
     * @param sessionId the id of the session to acquire.
     * @param lastReceivedSequenceNumber the last received message sequence number
     *                                   that you know about. You will get a stream
     *                                   of messages replayed to you from
     *                                   <code>lastReceivedMessageSequenceNumber + 1</code>
     *                                   to the latest message sequence number.
     *                                   If you don't care about message replay then
     *                                   use {@link this#acquireSession(long)}
     * @return the correlation id corresponding to this request.
     */
    public long requestSession(final long sessionId, final int lastReceivedSequenceNumber)
    {
        if (correlationIdToState.get(sessionId) != null)
        {
            return concurrentError();
        }

        final long correlationId = ++this.currentCorrelationId;
        correlationIdToState.put(correlationId, Long.valueOf(sessionId));
        outboundPublication.saveRequestSession(libraryId, sessionId, correlationId, lastReceivedSequenceNumber);
        return correlationId;
    }

    /**
     * Poll the status of a request to acquire a session. The correlation id
     * used should have been returned by {@link this#requestSession(long, int)}.
     *
     * @param correlationId the identifier of the request message that was sent to the gateway.
     * @return the status of the reply, or <code>null</code> if there was no reply.
     */
    public SessionReplyStatus pollRequestStatus(final long correlationId)
    {
        if (requestSessionAwaitingReply(correlationId))
        {
            return null;
        }
        else
        {
            return (SessionReplyStatus) correlationIdToState.remove(correlationId);
        }
    }

    /**
     * Variant of {@link this#acquireSession(long)} (long, String, SessionState)} that doesn't
     * replay any messages.
     *
     * @param connectionId the id of the connection to acquire.
     * @return the result of this operation.
     */
    public SessionReplyStatus acquireSession(final long connectionId)
    {
        return acquireSession(connectionId, NO_MESSAGE_REPLAY);
    }

    // ------------- End Public API -------------

    private <T> T concurrentError()
    {
        throw new IllegalStateException("You can't perform this operation concurrently");
    }

    private void awaitReply(final BooleanSupplier notReady)
    {
        final IdleStrategy idleStrategy = this.idleStrategy;
        final long latestReplyArrivalTime = latestReplyArrivalTime();

        while (notReady.getAsBoolean())
        {
            final int workCount = poll(5);

            checkTime(latestReplyArrivalTime);

            idleStrategy.idle(workCount);
        }

        idleStrategy.reset();
    }

    private long latestReplyArrivalTime()
    {
        return clock.time() + configuration.replyTimeoutInMs();
    }

    private void checkTime(final long latestReplyArrivalTime)
    {
        if (clock.time() > latestReplyArrivalTime)
        {
            throw new IllegalStateException(String.format(
                "Failed to receive a reply from the engine within %dms, are you sure its running?",
                this.configuration.replyTimeoutInMs()));
        }
    }

    private boolean requestSessionAwaitingReply(final long correlationId)
    {
        return correlationIdToState.get(correlationId) instanceof Long;
    }

    private int pollSessions(final long timeInMs)
    {
        final List<Session> sessions = this.sessions;
        int total = 0;

        for (int i = 0, size = sessions.size(); i < size; i++)
        {
            final Session session = sessions.get(i);
            total += session.poll(timeInMs);
        }

        return total;
    }

    private final FixLibraryProtocolHandler processProtocolHandler = new FixLibraryProtocolHandler();
    private final FragmentHandler outboundSubscription =
        new SessionSubscription(processProtocolHandler)
            .andThen(new LibraryProtocolSubscription(processProtocolHandler));

    private class FixLibraryProtocolHandler implements LibraryProtocolHandler, SessionHandler
    {
        private int sessionId;
        private final AsciiBuffer asciiBuffer = new MutableAsciiBuffer();

        public Action onManageConnection(
            final int libraryId,
            final long connectionId,
            final ConnectionType type,
            final int lastSentSequenceNumber,
            final int lastReceivedSequenceNumber,
            final DirectBuffer buffer,
            final int addressOffset,
            final int addressLength,
            final SessionState state,
            final int heartbeatIntervalInS)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                if (type == INITIATOR)
                {
                    DebugLogger.log("Init Connect: %d, %d\n", connectionId, libraryId);
                    final Session session = initiateSession(
                        connectionId, lastSentSequenceNumber, lastReceivedSequenceNumber, state);
                    newSession(connectionId, session);
                    incomingSession = session;
                }
                else
                {
                    DebugLogger.log("Acct Connect: %d, %d\n", connectionId, libraryId);
                    asciiBuffer.wrap(buffer);
                    final String address = asciiBuffer.getAscii(addressOffset, addressLength);
                    final Session session = acceptSession(connectionId, address, state, heartbeatIntervalInS);
                    newSession(connectionId, session);
                }
            }

            return CONTINUE;
        }

        public Action onLogon(
            final int libraryId,
            final long connectionId,
            final long sessionId,
            int lastSentSequenceNumber,
            int lastReceivedSequenceNumber,
            final LogonStatus status,
            final String senderCompId,
            final String senderSubId,
            final String senderLocationId,
            final String targetCompId,
            final String username,
            final String password)
        {
            final boolean thisLibrary = libraryId == FixLibrary.this.libraryId;
            if (thisLibrary && status == LogonStatus.NEW)
            {
                DebugLogger.log("Library Logon: %d, %d\n", connectionId, sessionId);
                final SessionSubscriber subscriber = connectionIdToSession.get(connectionId);
                if (subscriber != null)
                {
                    final SessionState state = subscriber.session().state();
                    lastSentSequenceNumber = acceptorSequenceNumber(lastSentSequenceNumber, state);
                    lastReceivedSequenceNumber = acceptorSequenceNumber(lastReceivedSequenceNumber, state);
                    final CompositeKey compositeKey = senderCompId.length() == 0 ? null :
                        sessionIdStrategy.onLogon(senderCompId, senderSubId, senderLocationId, targetCompId);
                    subscriber.onLogon(
                        sessionId,
                        lastSentSequenceNumber,
                        lastReceivedSequenceNumber,
                        compositeKey,
                        username,
                        password);
                }
            }
            else if (libraryId == GATEWAY_LIBRARY_ID || thisLibrary && status == LIBRARY_NOTIFICATION)
            {
                sessionExistsHandler.onSessionExists(
                    FixLibrary.this,
                    sessionId,
                    senderCompId,
                    senderSubId,
                    senderLocationId,
                    targetCompId,
                    username,
                    password
                );
            }

            return CONTINUE;
        }

        private int acceptorSequenceNumber(int lastSequenceNumber, final SessionState state)
        {
            if (!configuration.acceptorSequenceNumbersResetUponReconnect() &&
                lastSequenceNumber != SessionInfo.UNKNOWN_SESSION)
            {
                return lastSequenceNumber;
            }

            return state == ACTIVE ? 1 : 0;
        }

        public Action onMessage(
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final int libraryId,
            final long connectionId,
            final long sessionId,
            final int messageType,
            final long timestamp,
            final long position)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                DebugLogger.log("Received %s\n", buffer, offset, length);
                DebugLogger.log("(%d)\n", libraryId);
                final SessionSubscriber subscriber = connectionIdToSession.get(connectionId);
                if (subscriber != null)
                {
                    subscriber.onMessage(
                        buffer, offset, length, libraryId, connectionId, sessionId, messageType, timestamp, position);
                }
            }

            return CONTINUE;
        }

        public Action onDisconnect(final int libraryId, final long connectionId, final DisconnectReason reason)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                final SessionSubscriber subscriber = connectionIdToSession.remove(connectionId);
                DebugLogger.log("Library Disconnect %d, %s\n", connectionId, reason);
                if (subscriber != null)
                {
                    final Action action = subscriber.onDisconnect(libraryId, connectionId, reason);
                    if (action != ABORT)
                    {
                        final Session session = subscriber.session();
                        session.close();
                        sessions.remove(session);
                    }
                    return action;
                }
            }
            return CONTINUE;
        }

        public Action onError(final GatewayError errorType, final int libraryId, final String message)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                FixLibrary.this.errorType = errorType;
                FixLibrary.this.errorMessage = message;
            }
            configuration.gatewayErrorHandler().onError(errorType, libraryId, message);

            return CONTINUE;
        }

        public Action onApplicationHeartbeat(final int libraryId)
        {
            if (libraryId == FixLibrary.this.libraryId)
            {
                livenessDetector.onHeartbeat(clock.time());
            }

            return CONTINUE;
        }

        public Action onReleaseSessionReply(final long correlationId, final SessionReplyStatus status)
        {
            if (FixLibrary.this.currentCorrelationId == correlationId)
            {
                FixLibrary.this.replyStatus = status;
            }

            return CONTINUE;
        }

        public Action onRequestSessionReply(final long correlationId, final SessionReplyStatus status)
        {
            final Object state = correlationIdToState.get(correlationId);
            if (state instanceof Long)
            {
                if (status == MISSING_MESSAGES)
                {
                    // Ensure session not left in a bad state as a result of missing messages.
                    final long sessionId = (long) state;

                    final SessionSubscriber subscriber = connectionIdToSession.get(sessionId);
                    if (subscriber != null)
                    {
                        subscriber.startCatchup(0);
                    }
                }
                correlationIdToState.put(correlationId, status);
            }

            return CONTINUE;
        }

        public Action onCatchup(final int libraryId, final long connectionId, final int messageCount)
        {
            if (FixLibrary.this.libraryId == libraryId)
            {
                final SessionSubscriber subscriber = connectionIdToSession.get(connectionId);
                if (subscriber != null)
                {
                    subscriber.startCatchup(messageCount);
                }
            }

            return CONTINUE;
        }

        public Action onNewSentPosition(final int sessionId, final long position)
        {
            if (this.sessionId == sessionId)
            {
                sentPositionHandler.onSendCompleted(position);
            }

            return CONTINUE;
        }
    }

    private void newSession(final long connectionId, final Session session)
    {
        final AuthenticationStrategy authenticationStrategy = configuration.authenticationStrategy();
        final MessageValidationStrategy validationStrategy = configuration.messageValidationStrategy();
        final SessionParser parser = new SessionParser(
            session, sessionIdStrategy, authenticationStrategy, validationStrategy);
        final SessionHandler handler = configuration.sessionAcquireHandler().onSessionAcquired(session);
        final SessionSubscriber subscriber = new SessionSubscriber(parser, session, handler,
            receiveTimer, sessionTimer);
        connectionIdToSession.put(connectionId, subscriber);
        sessions.add(session);
    }

    private Session initiateSession(final long connectionId,
                                    final int lastSequenceNumber,
                                    final int lastReceivedSequenceNumber,
                                    final SessionState state)
    {
        final int defaultInterval = configuration.defaultHeartbeatIntervalInS();
        final GatewayPublication publication = outboundLibraryStreams.gatewayPublication(idleStrategy);

        final SessionProxy sessionProxy = sessionProxy(connectionId);
        // First time we're initiated
        // TODO: should we even have this special casing?
        if (sessionConfiguration != null)
        {
            final CompositeKey key = sessionIdStrategy.onLogon(
                sessionConfiguration.senderCompId(), sessionConfiguration.senderSubId(),
                sessionConfiguration.senderLocationId(), sessionConfiguration.targetCompId());
            sessionProxy.setupSession(-1, key);
        }

        return new InitiatorSession(
            defaultInterval,
            connectionId,
            clock,
            sessionProxy,
            publication,
            sessionIdStrategy,
            configuration.sendingTimeWindowInMs(),
            fixCounters.receivedMsgSeqNo(connectionId),
            fixCounters.sentMsgSeqNo(connectionId),
            libraryId,
            configuration.sessionBufferSize(),
            initiatorInitialSequenceNumber(lastSequenceNumber),
            state)
            .lastReceivedMsgSeqNum(initiatorInitialSequenceNumber(lastReceivedSequenceNumber) - 1);
    }

    private int initiatorInitialSequenceNumber(
        final int lastSequenceNumber)
    {
        // TODO: send appropriate configuration around
        if (sessionConfiguration == null)
        {
            return 1;
        }

        if (sessionConfiguration.hasCustomInitialSequenceNumber())
        {
            return sessionConfiguration.initialSequenceNumber();
        }

        if (sessionConfiguration.sequenceNumbersPersistent() && lastSequenceNumber != SessionInfo.UNKNOWN_SESSION)
        {
            return lastSequenceNumber + 1;
        }

        return 1;
    }

    private Session acceptSession(final long connectionId,
                                  final String address,
                                  final SessionState state,
                                  final int heartbeatIntervalInS)
    {
        final GatewayPublication publication = outboundLibraryStreams.gatewayPublication(idleStrategy);
        final int split = address.lastIndexOf(':');
        final int start = address.startsWith("/") ? 1 : 0;
        final String host = address.substring(start, split);
        final int port = Integer.parseInt(address.substring(split + 1));
        final long sendingTimeWindow = configuration.sendingTimeWindowInMs();
        final AtomicCounter receivedMsgSeqNo = fixCounters.receivedMsgSeqNo(connectionId);
        final AtomicCounter sentMsgSeqNo = fixCounters.sentMsgSeqNo(connectionId);
        final int sessionBufferSize = configuration.sessionBufferSize();

        return new AcceptorSession(
            heartbeatIntervalInS,
            connectionId,
            clock,
            sessionProxy(connectionId),
            publication,
            sessionIdStrategy,
            sendingTimeWindow,
            receivedMsgSeqNo,
            sentMsgSeqNo,
            libraryId,
            sessionBufferSize,
            // If a persisted sequence number is needed then it will be set with the logon message.
            1,
            state)
            .address(host, port);
    }

    private SessionProxy sessionProxy(final long connectionId)
    {
        return new SessionProxy(
            configuration.encoderBufferSize(),
            outboundLibraryStreams.gatewayPublication(idleStrategy),
            sessionIdStrategy,
            configuration.sessionCustomisationStrategy(),
            new SystemEpochClock(),
            connectionId,
            libraryId);
    }
}

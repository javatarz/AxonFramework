/*
 * Copyright (c) 2010-2017. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.eventhandling.tokenstore.jpa;

import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.UnableToClaimTokenException;
import org.axonframework.eventsourcing.eventstore.TrackingToken;
import org.axonframework.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.temporal.TemporalAmount;
import java.util.Collections;
import java.util.List;

import static java.lang.String.format;
import static org.axonframework.common.DateTimeUtils.formatInstant;

/**
 * Implementation of a token store that uses JPA to save and load tokens. This implementation uses {@link TokenEntry}
 * entities.
 *
 * @author Rene de Waele
 */
public class JpaTokenStore implements TokenStore {

    private static final Logger logger = LoggerFactory.getLogger(JpaTokenStore.class);

    private final EntityManagerProvider entityManagerProvider;
    private final Serializer serializer;
    private final TemporalAmount claimTimeout;
    private final String nodeId;

    /**
     * Initializes a token store with given {@code entityManagerProvider} and {@code serializer}.
     *
     * @param entityManagerProvider The provider of the entity manager
     * @param serializer            The serializer used to serialize tokens
     */
    public JpaTokenStore(EntityManagerProvider entityManagerProvider, Serializer serializer) {
        this(entityManagerProvider, serializer, Duration.ofSeconds(10), ManagementFactory.getRuntimeMXBean().getName());
    }

    /**
     * Initialize the JpaTokenStore with given resources. The given {@code claimTimeout} is used to 'steal' any claim
     * that has not been updated since that amount of time.
     *
     * @param entityManagerProvider provides the EntityManager to access the underlying database
     * @param serializer            The serializer to serialize tokens with
     * @param claimTimeout          The timeout after which this process will force a claim
     * @param nodeId                The identifier to identify ownership of the tokens
     */
    public JpaTokenStore(EntityManagerProvider entityManagerProvider, Serializer serializer,
                         TemporalAmount claimTimeout, String nodeId) {
        this.entityManagerProvider = entityManagerProvider;
        this.serializer = serializer;
        this.claimTimeout = claimTimeout;
        this.nodeId = nodeId;
    }

    @Override
    public void initializeTokenSegments(String processorName, int segmentCount) throws UnableToClaimTokenException {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        if (fetchSegments(processorName).length > 0) {
            throw new UnableToClaimTokenException("Could not initialize segments. Some segments were already present.");
        }
        for (int segment = 0; segment < segmentCount; segment++) {
            TokenEntry token = new TokenEntry(processorName, segment, null, serializer);
            entityManager.persist(token);
        }
        entityManager.flush();
    }

    @Override
    public void storeToken(TrackingToken token, String processorName, int segment) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        TokenEntry tokenEntry = loadOrCreateToken(processorName, segment, entityManager);
        tokenEntry.updateToken(token, serializer);
    }

    @Override
    public void releaseClaim(String processorName, int segment) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        int updates = entityManager.createQuery("UPDATE TokenEntry te SET te.owner = null " +
                                                        "WHERE te.owner = :owner AND te.processorName = :processorName " +
                                                        "AND te.segment = :segment")
                                   .setParameter("processorName", processorName)
                                   .setParameter("segment", segment)
                                   .setParameter("owner", nodeId)
                                   .executeUpdate();
        if (updates == 0) {
            logger.warn("Releasing claim of token {}/{} failed. It was not owned by {}", processorName, segment,
                        nodeId);
        }
    }

    @Override
    public TrackingToken fetchToken(String processorName, int segment) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        return loadOrCreateToken(processorName, segment, entityManager).getToken(serializer);
    }

    @Override
    public void extendClaim(String processorName, int segment) throws UnableToClaimTokenException {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        int updates = entityManager.createQuery("UPDATE TokenEntry te SET te.timestamp = :timestamp " +
                                                        "WHERE te.processorName = :processorName " +
                                                        "AND te.segment = :segment " +
                                                        "AND te.owner = :owner")
                                   .setParameter("processorName", processorName)
                                   .setParameter("segment", segment)
                                   .setParameter("owner", nodeId)
                                   .setParameter("timestamp", formatInstant(TokenEntry.clock.instant()))
                                   .executeUpdate();

        if (updates == 0) {
            throw new UnableToClaimTokenException("Unable to extend the claim on token for processor '" +
                                                          processorName + "[" + segment + "]'. It is either claimed " +
                                                          "by another process, or there is no such token.");
        }
    }

    @Override
    public int[] fetchSegments(String processorName) {

        EntityManager entityManager = entityManagerProvider.getEntityManager();
        final List<Integer> resultList = entityManager.createQuery("SELECT te.segment FROM TokenEntry te " +
                                                                           "WHERE te.processorName = :processorName ORDER BY te.segment ASC",
                                                                   Integer.class)
                                                      .setParameter("processorName", processorName)
                                                      .getResultList();
        return resultList.stream().mapToInt(i -> i).toArray();
    }

    /**
     * Loads an existing {@link TokenEntry} or creates a new one using the given {@code entityManager} for given {@code
     * processorName} and {@code segment}.
     *
     * @param processorName the name of the event processor
     * @param segment       the segment of the event processor
     * @param entityManager the entity manager instance to use for the query
     * @return the token entry for the given processor name and segment
     * @throws UnableToClaimTokenException if there is a token for given {@code processorName} and {@code segment}, but
     *                                     it is claimed by another process.
     */
    protected TokenEntry loadOrCreateToken(String processorName, int segment, EntityManager entityManager) {
        TokenEntry token = entityManager
                .find(TokenEntry.class, new TokenEntry.PK(processorName, segment), LockModeType.PESSIMISTIC_WRITE,
                      Collections.singletonMap("javax.persistence.query.timeout", 1));

        if (token == null) {
            token = new TokenEntry(processorName, segment, null, serializer);
            token.claim(nodeId, claimTimeout);
            entityManager.persist(token);
            // hibernate complains about updates in different transactions if this isn't flushed
            entityManager.flush();
        } else if (!token.claim(nodeId, claimTimeout)) {
            throw new UnableToClaimTokenException(
                    format("Unable to claim token '%s[%s]'. It is owned by '%s'", token.getProcessorName(),
                           token.getSegment(), token.getOwner()));
        }
        return token;
    }

}

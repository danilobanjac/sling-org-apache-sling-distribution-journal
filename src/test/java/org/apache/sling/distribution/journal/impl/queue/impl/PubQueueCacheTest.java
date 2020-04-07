/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.distribution.journal.impl.queue.impl;

import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.sling.distribution.journal.messages.Messages.PackageMessage.ReqType.ADD;
import static org.apache.sling.distribution.journal.messages.Messages.PackageMessage.ReqType.DELETE;
import static org.apache.sling.distribution.journal.messages.Messages.PackageMessage.ReqType.TEST;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.Closeable;
import java.io.IOException;
import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.stream.LongStream;

import org.apache.sling.commons.metrics.Counter;
import org.apache.sling.distribution.journal.HandlerAdapter;
import org.apache.sling.distribution.journal.MessageHandler;
import org.apache.sling.distribution.journal.MessageSender;
import org.apache.sling.distribution.journal.MessagingProvider;
import org.apache.sling.distribution.journal.Reset;
import org.apache.sling.distribution.journal.impl.queue.OffsetQueue;
import org.apache.sling.distribution.journal.impl.shared.DistributionMetricsService;
import org.apache.sling.distribution.journal.impl.shared.TestMessageInfo;
import org.apache.sling.distribution.journal.messages.Messages.PackageMessage;
import org.apache.sling.distribution.journal.messages.Messages.PackageMessage.ReqType;
import org.apache.sling.distribution.queue.DistributionQueueItem;
import org.awaitility.Awaitility;
import org.awaitility.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(MockitoJUnitRunner.class)
public class PubQueueCacheTest {

    private Logger log = LoggerFactory.getLogger(this.getClass());

    private static final String TOPIC = "package_topic";

    private static final String PUB_AGENT_NAME_1 = "pubAgentName1";

    private static final String PUB_AGENT_NAME_2 = "pubAgentName2";

    private static final String PUB_AGENT_NAME_3 = "pubAgentName3";

    private static final Random RAND = new Random();
    
    @Captor
    private ArgumentCaptor<PackageMessage> seedingMessageCaptor;

    @Captor
    private ArgumentCaptor<HandlerAdapter<PackageMessage>> tailHandlerCaptor;

    @Captor
    private ArgumentCaptor<HandlerAdapter<PackageMessage>> headHandlerCaptor;

    @Captor
    private ArgumentCaptor<String> headAssignCaptor;

    @Mock
    private EventAdmin eventAdmin;

    @Mock
    private MessagingProvider clientProvider;

    @Mock
    private DistributionMetricsService distributionMetricsService;

    @Mock
    private Counter counter;

    @Mock
    private MessageSender<PackageMessage> pkgSender;

    @Mock
    private Closeable poller;

    private PubQueueCache cache;

    private ExecutorService executor;

    private MessageHandler<PackageMessage> tailHandler;

    @Before
    public void before() {
        when(clientProvider.assignTo(anyLong())).then(
                answer -> "0:" + answer.getArguments()[0]);
        when(clientProvider.createPoller(
                eq(TOPIC),
                any(Reset.class),
                tailHandlerCaptor.capture()))
        .thenReturn(poller);

        when(clientProvider.createPoller(
                eq(TOPIC),
                any(Reset.class),
                headAssignCaptor.capture(),
                headHandlerCaptor.capture()))
                .thenReturn(poller);

        when(clientProvider.<PackageMessage>createSender())
        .thenReturn(pkgSender);

        when(distributionMetricsService.getQueueCacheFetchCount())
                .thenReturn(counter);

        cache = new PubQueueCache(clientProvider, eventAdmin, distributionMetricsService, TOPIC, 250);
        verify(pkgSender, timeout(5000)).send(Mockito.eq(TOPIC), seedingMessageCaptor.capture());
        
        executor = Executors.newFixedThreadPool(10);

        tailHandler = tailHandlerCaptor.getValue().getHandler();
    }

    @After
    public void after() throws IOException {
        executor.shutdownNow();
        cache.close();
    }

    @Test
    public void testSeedingFromNewPackageMessage() throws Exception {
        Future<OffsetQueue<DistributionQueueItem>> consumer = executor.submit(new Consumer(PUB_AGENT_NAME_1, 0));
        // The consumer is blocked until the cache is seeded
        assertFalse(consumer.isDone());
        // sending any package message seeds the cache
        simulateMessage(tailHandler, 0);
        consumer.get(15, SECONDS);
    }

    @Test
    public void testSeedingFromSeedingMessage() throws Exception {
        ArgumentCaptor<PackageMessage> seedingMsgCaptor = ArgumentCaptor.forClass(PackageMessage.class);
        Future<OffsetQueue<DistributionQueueItem>> consumer = executor.submit(new Consumer(PUB_AGENT_NAME_1, 0));
        // The consumer is blocked until the cache is seeded
        assertFalse(consumer.isDone());
        // wait until a seeding message is sent and captured
        verify(pkgSender, timeout(15000).atLeastOnce()).send(eq(TOPIC), seedingMsgCaptor.capture());
        // sending the captured seeding message seeds the cache
        simulateMessage(tailHandler, seedingMsgCaptor.getValue(), 0);
        consumer.get(15, SECONDS);
    }

    @Test
    public void testSeedingConcurrentConsumers() throws Exception {
        List<Future<OffsetQueue<DistributionQueueItem>>> consumers = new ArrayList<>();
        consumers.add(executor.submit(new Consumer(PUB_AGENT_NAME_1, 0)));
        consumers.add(executor.submit(new Consumer(PUB_AGENT_NAME_2, 0)));
        consumers.add(executor.submit(new Consumer(PUB_AGENT_NAME_3, 0)));
        // All consumers are blocked until the cache is seeded
        consumers.stream().forEach(future -> assertFalse(future.isDone()));
        // sending any package message seeds the cache
        simulateMessage(tailHandler, 0);
        consumers.stream().forEach(future -> assertNotNull(get(future)));
    }

    @Test
    public void testFetchWithSingleConsumer() throws Exception {
        // build a consumer form offset 100
        Future<OffsetQueue<DistributionQueueItem>> consumer = executor.submit(new Consumer(PUB_AGENT_NAME_1, 100));
        // seeding the cache with a message at offset 200
        simulateMessage(tailHandler, 200);
        // wait that the consumer has started fetching the offsets from 100 to 200
        awaitUntil(() -> headAssignCaptor.getValue() != null);
        awaitUntil(() -> headHandlerCaptor.getValue() != null);
        // simulate messages for the fetched offsets
        long fromOffset = offsetFromAssign(headAssignCaptor.getValue());
        simulateMessages(headHandlerCaptor.getValue().getHandler(), fromOffset, cache.getMinOffset());
        // the consumer returns the offset queue
        consumer.get(15, SECONDS);
        assertEquals(100, cache.getMinOffset());
    }

	@Test
    public void testFetchWithConcurrentConsumer() throws Exception {
        // build two consumers for same agent queue, from offset 100
        Future<OffsetQueue<DistributionQueueItem>> consumer1 = executor.submit(new Consumer(PUB_AGENT_NAME_1, 100));
        Future<OffsetQueue<DistributionQueueItem>> consumer2 = executor.submit(new Consumer(PUB_AGENT_NAME_1, 100));
        // seeding the cache with a message at offset 200
        simulateMessage(tailHandler, 200);
        // wait that one consumer has started fetching the offsets from 100 to 200
        awaitUntil(() -> headAssignCaptor.getValue() != null);
        awaitUntil(() -> headHandlerCaptor.getValue() != null);
        // simulate messages for the fetched offsets
        long fromOffset = offsetFromAssign(headAssignCaptor.getValue());
        simulateMessages(headHandlerCaptor.getValue().getHandler(), fromOffset, cache.getMinOffset());
        // both consumers returns the offset queue
        OffsetQueue<DistributionQueueItem> q1 = consumer1.get(5, SECONDS);
        OffsetQueue<DistributionQueueItem> q2 = consumer2.get(5, SECONDS);
        assertEquals(q1.getSize(), q2.getSize());
        assertEquals(100, cache.getMinOffset());
        // the offsets have been fetched only once
        assertEquals(1, headHandlerCaptor.getAllValues().size());
    }

    @Test
    public void testCacheSize() throws Exception {
        Future<OffsetQueue<DistributionQueueItem>> consumer = executor.submit(new Consumer(PUB_AGENT_NAME_1, 0));
        simulateMessage(tailHandler, PUB_AGENT_NAME_3, ADD, 0);
        simulateMessage(tailHandler, PUB_AGENT_NAME_3, DELETE, 1);
        simulateMessage(tailHandler, PUB_AGENT_NAME_1, ADD, 2);
        simulateMessage(tailHandler, PUB_AGENT_NAME_3, TEST, 3);    // TEST message does not increase the cache size
        simulateMessage(tailHandler, PUB_AGENT_NAME_2, TEST, 4);    // TEST message does not increase the cache size
        simulateMessage(tailHandler, PUB_AGENT_NAME_3, ADD, 5);
        consumer.get(15, SECONDS);
        assertEquals(4, cache.size());
    }

    @Test(expected = ExecutionException.class)
    public void testCloseUnseededPoller() throws Throwable {
        FutureTask<OffsetQueue<DistributionQueueItem>> task = new FutureTask<>(new Consumer(PUB_AGENT_NAME_1, 0));
        Thread th = new Thread(task);
        th.start();
        Awaitility.setDefaultPollDelay(Duration.ZERO);
        await().until(th::getState, equalTo(State.TIMED_WAITING));
        cache.close();
        task.get();
    }
    
    @Test
    public void testFetchWithOnlyTestMessage() throws Exception {
    	long requestedMinOffset = 0;
		PackageMessage seedingMessage = seedingMessageCaptor.getValue();
		simulateMessage(tailHandler, seedingMessage, 200000);
		Future<OffsetQueue<DistributionQueueItem>> consumer = executor.submit(new Consumer(PUB_AGENT_NAME_1, requestedMinOffset));
        awaitUntil(() -> headHandlerCaptor.getValue() != null);
        MessageHandler<PackageMessage> headHandler = headHandlerCaptor.getValue().getHandler();
        simulateMessage(headHandler, seedingMessage, 200000);
        consumer.get(10, SECONDS);
        assertEquals("After we fetched from 0 we expect this to be the cached min offset.", 
        		requestedMinOffset, cache.getMinOffset());
    }

    private void awaitUntil(Callable<Boolean> callable) {
		await().atMost(15, SECONDS).ignoreExceptions().until(callable);
	}

	private void simulateMessages(MessageHandler<PackageMessage> handler, long fromOffset, long toOffset) {
        LongStream.rangeClosed(fromOffset, toOffset).forEach(offset -> simulateMessage(handler, offset));
    }
    
    private void simulateMessage(MessageHandler<PackageMessage> handler, long offset) {
        simulateMessage(handler,
                pickAny(PUB_AGENT_NAME_1, PUB_AGENT_NAME_2, PUB_AGENT_NAME_3),
                pickAny(ADD, DELETE, TEST), offset);
    }

    private void simulateMessage(MessageHandler<PackageMessage> handler, String pubAgentName, ReqType reqType, long offset) {
        PackageMessage msg = PackageMessage.newBuilder()
                .setPkgType("pkgType")
                .setPkgId(UUID.randomUUID().toString())
                .setPubSlingId("pubSlingId")
                .setReqType(reqType)
                .setPubAgentName(pubAgentName)
                .build();
        simulateMessage(handler, msg, offset);
    }

    private void simulateMessage(MessageHandler<PackageMessage> handler, PackageMessage msg, long offset) {
        log.info("Simulate msg @ offset {}", offset);
        handler.handle(new TestMessageInfo(TOPIC, 0, offset, currentTimeMillis()), msg);
    }


    private class Consumer implements Callable<OffsetQueue<DistributionQueueItem>> {

        final String pubAgentName;

        final long minOffset;

        private Consumer(String pubAgentName, long minOffset) {
            this.pubAgentName = pubAgentName;
            this.minOffset = minOffset;
        }

        @Override
        public OffsetQueue<DistributionQueueItem> call() throws Exception {
            return cache.getOffsetQueue(pubAgentName, minOffset);
        }
    }

    private OffsetQueue<DistributionQueueItem> get(Future<OffsetQueue<DistributionQueueItem>> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SafeVarargs
    private final <T> T pickAny(T... c) {
        if (c == null || c.length == 0) {
            throw new IllegalArgumentException();
        }
        return c[RAND.nextInt(c.length)];
    }

    private Long offsetFromAssign(String assign) {
        String[] chunks = assign.split(":");
        return Long.parseLong(chunks[1]);
    }


}
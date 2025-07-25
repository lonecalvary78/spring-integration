/*
 * Copyright 2018-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.kafka.inbound;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.jspecify.annotations.Nullable;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.core.log.LogAccessor;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.acks.AcknowledgmentCallbackFactory;
import org.springframework.integration.core.Pausable;
import org.springframework.integration.endpoint.AbstractMessageSource;
import org.springframework.integration.support.AbstractIntegrationMessageBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ConsumerProperties;
import org.springframework.kafka.listener.ErrorHandlingUtils;
import org.springframework.kafka.listener.LoggingCommitCallback;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.DefaultKafkaHeaderMapper;
import org.springframework.kafka.support.JacksonPresent;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.LogIfLevelEnabled;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.kafka.support.converter.KafkaMessageHeaders;
import org.springframework.kafka.support.converter.MessagingMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.messaging.Message;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Polled message source for Apache Kafka.
 * Only one thread can poll for data (or acknowledge a message) at a time.
 * <p>
 * NOTE: If the application acknowledges messages out of order, the acks
 * will be deferred until all messages prior to the offset are ack'd.
 * If multiple records are retrieved and an earlier offset is requeued, records
 * from the subsequent offsets will be redelivered - even if they were
 * processed successfully. Applications should therefore implement idempotency.
 * <p>
 * Starting with version 3.1.2, this source implements {@link Pausable} which
 * allows you to pause and resume the {@link Consumer}. While the consumer is
 * paused, you must continue to call {@link #receive()} within
 * {@code max.poll.interval.ms}, to prevent a rebalance.
 *
 * @param <K> the key type.
 * @param <V> the value type.
 *
 * @author Gary Russell
 * @author Mark Norkin
 * @author Artem Bilan
 * @author Anshul Mehra
 * @author Christian Tzolov
 * @author Ngoc Nhan
 *
 * @since 5.4
 *
 */
public class KafkaMessageSource<K, V> extends AbstractMessageSource<Object>
		implements Pausable, BeanClassLoaderAware {

	private static final long MIN_ASSIGN_TIMEOUT = 2000L;

	private static final int DEFAULT_CLOSE_TIMEOUT = 30;

	/**
	 * The number of records remaining from the previous poll.
	 * @since 3.2
	 */
	public static final String REMAINING_RECORDS = KafkaHeaders.PREFIX + "remainingRecords";

	private final ConsumerFactory<K, V> consumerFactory;

	private final KafkaAckCallbackFactory<K, V> ackCallbackFactory;

	private final Lock receiveLock = new ReentrantLock();

	private final Lock consumerLock = new ReentrantLock();

	private final Map<TopicPartition, Set<KafkaAckInfo<K, V>>> inflightRecords = new ConcurrentHashMap<>();

	private final AtomicInteger remainingCount = new AtomicInteger();

	private final ConsumerProperties consumerProperties;

	private final Collection<TopicPartition> assignedPartitions = new LinkedHashSet<>();

	private final @Nullable Duration commitTimeout;

	private final Duration assignTimeout;

	private final Duration pollTimeout;

	private final AtomicBoolean running = new AtomicBoolean();

	private final AtomicBoolean pausing = new AtomicBoolean();

	private final AtomicBoolean paused = new AtomicBoolean();

	private final AtomicBoolean stopped = new AtomicBoolean();

	private RecordMessageConverter messageConverter = new MessagingMessageConverter();

	private @Nullable Class<?> payloadType;

	private boolean rawMessageHeader;

	private Duration closeTimeout = Duration.ofSeconds(DEFAULT_CLOSE_TIMEOUT);

	private boolean checkNullKeyForExceptions;

	private boolean checkNullValueForExceptions;

	private volatile @Nullable Consumer<K, V> consumer;

	private volatile @Nullable Iterator<ConsumerRecord<K, V>> recordsIterator;

	public volatile boolean newAssignment; // NOSONAR - direct access from inner

	private @Nullable ClassLoader classLoader;

	/**
	 * Construct an instance with the supplied parameters. Fetching multiple
	 * records per poll will be disabled.
	 * @param consumerFactory the consumer factory.
	 * @param consumerProperties the consumer properties.
	 * @see #KafkaMessageSource(ConsumerFactory, ConsumerProperties, KafkaAckCallbackFactory, boolean)
	 */
	public KafkaMessageSource(ConsumerFactory<K, V> consumerFactory, ConsumerProperties consumerProperties) {
		this(consumerFactory, consumerProperties, new KafkaAckCallbackFactory<>(consumerProperties), false);
	}

	/**
	 * Construct an instance with the supplied parameters. Set 'allowMultiFetch' to true
	 * to allow up to {@code max.poll.records} to be fetched on each poll. When false
	 * (default) {@code max.poll.records} is coerced to 1 if the consumer factory is a
	 * {@link DefaultKafkaConsumerFactory} or otherwise rejected with an
	 * {@link IllegalArgumentException}. IMPORTANT: When true, you must call
	 * {@link #receive()} at a sufficient rate to consume the number of records received
	 * within {@code max.poll.interval.ms}. When false, you must call {@link #receive()}
	 * within {@code max.poll.interval.ms}. {@link #pause()} will not take effect until
	 * the records from the previous poll are consumed.
	 * @param consumerFactory the consumer factory.
	 * @param consumerProperties the consumer properties.
	 * @param allowMultiFetch true to allow {@code max.poll.records > 1}.
	 */
	public KafkaMessageSource(ConsumerFactory<K, V> consumerFactory,
			ConsumerProperties consumerProperties,
			boolean allowMultiFetch) {

		this(consumerFactory, consumerProperties, new KafkaAckCallbackFactory<>(consumerProperties), allowMultiFetch);
	}

	/**
	 * Construct an instance with the supplied parameters. Fetching multiple
	 * records per poll will be disabled.
	 * @param consumerFactory the consumer factory.
	 * @param consumerProperties the consumer properties.
	 * @param ackCallbackFactory the ack callback factory.
	 * @see #KafkaMessageSource(ConsumerFactory, ConsumerProperties, KafkaAckCallbackFactory, boolean)
	 */
	public KafkaMessageSource(ConsumerFactory<K, V> consumerFactory,
			ConsumerProperties consumerProperties,
			KafkaAckCallbackFactory<K, V> ackCallbackFactory) {

		this(consumerFactory, consumerProperties, ackCallbackFactory, false);
	}

	/**
	 * Construct an instance with the supplied parameters. Set 'allowMultiFetch' to true
	 * to allow up to {@code max.poll.records} to be fetched on each poll. When false
	 * (default) {@code max.poll.records} is coerced to 1 if the consumer factory is a
	 * {@link DefaultKafkaConsumerFactory} or otherwise rejected with an
	 * {@link IllegalArgumentException}. IMPORTANT: When true, you must call
	 * {@link #receive()} at a sufficient rate to consume the number of records received
	 * within {@code max.poll.interval.ms}. When false, you must call {@link #receive()}
	 * within {@code max.poll.interval.ms}. {@link #pause()} will not take effect until
	 * the records from the previous poll are consumed.
	 * @param consumerFactory the consumer factory.
	 * @param consumerProperties the consumer properties.
	 * @param ackCallbackFactory the ack callback factory.
	 * @param allowMultiFetch true to allow {@code max.poll.records > 1}.
	 */
	@SuppressWarnings("removal")
	public KafkaMessageSource(ConsumerFactory<K, V> consumerFactory,
			ConsumerProperties consumerProperties,
			KafkaAckCallbackFactory<K, V> ackCallbackFactory,
			boolean allowMultiFetch) {

		Assert.notNull(consumerFactory, "'consumerFactory' must not be null");
		Assert.notNull(ackCallbackFactory, "'ackCallbackFactory' must not be null");
		Assert.isTrue(
				!ObjectUtils.isEmpty(consumerProperties.getTopics())
						|| !ObjectUtils.isEmpty(consumerProperties.getTopicPartitions())
						|| consumerProperties.getTopicPattern() != null,
				"topics, topicPattern, or topicPartitions must be provided"
		);
		this.consumerProperties = consumerProperties;

		this.consumerFactory = fixOrRejectConsumerFactory(consumerFactory, allowMultiFetch);
		this.ackCallbackFactory = ackCallbackFactory;
		this.pollTimeout = Duration.ofMillis(consumerProperties.getPollTimeout());
		this.assignTimeout =
				Duration.ofMillis(Math.max(this.pollTimeout.toMillis() * 20, MIN_ASSIGN_TIMEOUT)); // NOSONAR - magic
		this.commitTimeout = consumerProperties.getSyncCommitTimeout();

		MessagingMessageConverter messagingMessageConverter = (MessagingMessageConverter) this.messageConverter;
		// For consistency with the rest of Spring Integration channel adapters
		messagingMessageConverter.setGenerateMessageId(true);
		messagingMessageConverter.setGenerateTimestamp(true);

		if (JacksonPresent.isJackson2Present()) {
			DefaultKafkaHeaderMapper headerMapper = new DefaultKafkaHeaderMapper();
			headerMapper.addTrustedPackages(
					org.springframework.integration.support.json.JacksonJsonUtils.DEFAULT_TRUSTED_PACKAGES
							.toArray(new String[0]));
			messagingMessageConverter.setHeaderMapper(headerMapper);
		}
	}

	/**
	 * Return the currently assigned partitions.
	 * @return the partitions.
	 */
	public Collection<TopicPartition> getAssignedPartitions() {
		return Collections.unmodifiableCollection(this.assignedPartitions);
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	@Override
	protected void onInit() {
		if (!StringUtils.hasText(this.consumerProperties.getClientId())) {
			this.consumerProperties.setClientId(getComponentName());
		}

		Properties kafkaConsumerProperties = this.consumerProperties.getKafkaConsumerProperties();
		this.checkNullKeyForExceptions =
				this.consumerProperties.isCheckDeserExWhenKeyNull() ||
						ErrorHandlingUtils.checkDeserializer(this.consumerFactory, kafkaConsumerProperties, false,
								this.classLoader);
		this.checkNullValueForExceptions =
				this.consumerProperties.isCheckDeserExWhenValueNull() ||
						ErrorHandlingUtils.checkDeserializer(this.consumerFactory, kafkaConsumerProperties, true,
								this.classLoader);
	}

	/**
	 * Get a reference to the configured consumer properties; allows further
	 * customization of the properties before the source is started.
	 * @return the properties.
	 */
	public ConsumerProperties getConsumerProperties() {
		return this.consumerProperties;
	}

	protected @Nullable String getGroupId() {
		return this.consumerProperties.getGroupId();
	}

	protected String getClientId() {
		return this.consumerProperties.getClientId();
	}

	protected long getPollTimeout() {
		return this.pollTimeout.toMillis();
	}

	protected RecordMessageConverter getMessageConverter() {
		return this.messageConverter;
	}

	/**
	 * Set the message converter to replace the default
	 * {@link MessagingMessageConverter}.
	 * @param messageConverter the converter.
	 */
	public void setMessageConverter(RecordMessageConverter messageConverter) {
		this.messageConverter = messageConverter;
	}

	protected @Nullable Class<?> getPayloadType() {
		return this.payloadType;
	}

	/**
	 * Set the payload type.
	 * Only applies if a type-aware message converter is provided.
	 * @param payloadType the type to convert to.
	 */
	public void setPayloadType(Class<?> payloadType) {
		this.payloadType = payloadType;
	}

	protected @Nullable ConsumerRebalanceListener getRebalanceListener() {
		return this.consumerProperties.getConsumerRebalanceListener();
	}

	@Override
	public String getComponentType() {
		return "kafka:message-source";
	}

	protected boolean isRawMessageHeader() {
		return this.rawMessageHeader;
	}

	/**
	 * Set to true to include the raw {@link ConsumerRecord} as headers with keys
	 * {@link KafkaHeaders#RAW_DATA} and
	 * {@link IntegrationMessageHeaderAccessor#SOURCE_DATA}. enabling callers to have
	 * access to the record to process errors.
	 * @param rawMessageHeader true to include the header.
	 */
	public void setRawMessageHeader(boolean rawMessageHeader) {
		this.rawMessageHeader = rawMessageHeader;
	}

	protected @Nullable Duration getCommitTimeout() {
		return this.commitTimeout;
	}

	/**
	 * Set the close timeout - default 30 seconds.
	 * @param closeTimeout the close timeout.
	 */
	public void setCloseTimeout(Duration closeTimeout) {
		Assert.notNull(closeTimeout, "'closeTimeout' cannot be null");
		this.closeTimeout = closeTimeout;
	}

	private ConsumerFactory<K, V> fixOrRejectConsumerFactory(ConsumerFactory<K, V> suppliedConsumerFactory,
			boolean allowMultiFetch) {

		Object maxPoll = suppliedConsumerFactory.getConfigurationProperties()
				.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG);
		if (!allowMultiFetch && (maxPoll == null || maxPollGtrOne(maxPoll))) {
			if (!suppliedConsumerFactory.getClass().getName().equals(DefaultKafkaConsumerFactory.class.getName())) {
				throw new IllegalArgumentException("Custom consumer factory is not configured with '"
						+ ConsumerConfig.MAX_POLL_RECORDS_CONFIG + " = 1'");
			}
			this.logger.warn(() -> ConsumerConfig.MAX_POLL_RECORDS_CONFIG
					+ "' has been forced from " + (maxPoll == null ? "unspecified" : maxPoll)
					+ " to 1, to avoid having to seek after each record");
			return fixConsumerFactory(suppliedConsumerFactory);
		}
		else {
			return suppliedConsumerFactory;
		}
	}

	private boolean maxPollGtrOne(Object maxPoll) {
		return maxPollNumberGtrOne(maxPoll) || maxPollStringGtr1(maxPoll);
	}

	private boolean maxPollNumberGtrOne(Object maxPoll) {
		return maxPoll instanceof Number && ((Number) maxPoll).intValue() != 1;
	}

	private boolean maxPollStringGtr1(Object maxPoll) {
		return maxPoll instanceof String && Integer.parseInt((String) maxPoll) != 1;
	}

	@Override
	public boolean isRunning() {
		return this.running.get();
	}

	@Override
	public void start() {
		if (this.running.compareAndSet(false, true)) {
			this.stopped.set(false);
		}
	}

	@Override
	public void stop() {
		if (this.running.compareAndSet(true, false)) {
			stopConsumer();
			this.stopped.set(true);
		}
	}

	@Override
	public void pause() {
		this.pausing.set(true);
	}

	@Override
	public void resume() {
		this.pausing.set(false);
	}

	@Override
	public boolean isPaused() {
		return this.paused.get();
	}

	@Override // NOSONAR - not so complex
	protected @Nullable Object doReceive() {
		this.receiveLock.lock();
		try {

			if (this.stopped.get()) {
				this.logger.debug("Message source is stopped; no records will be returned");
				return null;
			}
			if (this.consumer == null) {
				createConsumer();
			}
			Assert.state(this.consumer != null, "'consumer' must not be null");
			if (this.pausing.get() && !this.paused.get() && !this.assignedPartitions.isEmpty()) {
				this.consumer.pause(this.assignedPartitions);
				this.paused.set(true);
			}
			else if (this.paused.get() && !this.pausing.get()) {
				this.consumer.resume(this.assignedPartitions);
				this.paused.set(false);
			}
			if (this.paused.get() && this.recordsIterator == null) {
				this.logger.debug("Consumer is paused; no records will be returned");
			}
		}
		finally {
			this.receiveLock.unlock();
		}

		ConsumerRecord<K, V> record = pollRecord();

		return record != null ? recordToMessage(record) : null;
	}

	protected void createConsumer() {
		this.consumerLock.lock();
		try {
			this.consumer = this.consumerFactory.createConsumer(this.consumerProperties.getGroupId(),
					this.consumerProperties.getClientId(), null, this.consumerProperties.getKafkaConsumerProperties());

			ConsumerRebalanceListener rebalanceCallback =
					new IntegrationConsumerRebalanceListener(this.consumerProperties.getConsumerRebalanceListener());

			Pattern topicPattern = this.consumerProperties.getTopicPattern();
			@Nullable TopicPartitionOffset @Nullable [] partitions = this.consumerProperties.getTopicPartitions();
			if (topicPattern != null) {
				this.consumer.subscribe(topicPattern, rebalanceCallback);
			}
			else if (partitions != null) {
				assignAndSeekPartitions(partitions);
			}
			else {
				this.consumer.subscribe(Arrays.asList(this.consumerProperties.getTopics()), // NOSONAR
						rebalanceCallback);
			}
		}
		finally {
			this.consumerLock.unlock();
		}
	}

	private void assignAndSeekPartitions(@Nullable TopicPartitionOffset[] partitions) {
		List<TopicPartition> topicPartitionsToAssign =
				Arrays.stream(partitions)
						.filter(Objects::nonNull)
						.map(TopicPartitionOffset::getTopicPartition)
						.collect(Collectors.toList());
		Assert.state(this.consumer != null, "'consumer' must not be null");
		this.consumer.assign(topicPartitionsToAssign);
		this.assignedPartitions.addAll(topicPartitionsToAssign);

		for (TopicPartitionOffset partition : partitions) {
			Assert.state(partition != null, "'partition' must not be null");
			if (TopicPartitionOffset.SeekPosition.BEGINNING.equals(partition.getPosition())) {
				this.consumer.seekToBeginning(Collections.singleton(partition.getTopicPartition()));
			}
			else if (TopicPartitionOffset.SeekPosition.END.equals(partition.getPosition())) {
				this.consumer.seekToEnd(Collections.singleton(partition.getTopicPartition()));
			}
			else {
				TopicPartition topicPartition = partition.getTopicPartition();
				Long offset = partition.getOffset();
				if (offset != null) {
					long newOffset;

					if (offset < 0) {
						if (!partition.isRelativeToCurrent()) {
							this.consumer.seekToEnd(Collections.singleton(topicPartition));
							continue;
						}
						newOffset = Math.max(0, this.consumer.position(topicPartition) + offset);
					}
					else if (partition.isRelativeToCurrent()) {
						newOffset = this.consumer.position(topicPartition) + offset;
					}
					else {
						newOffset = offset;
					}

					try {
						this.consumer.seek(topicPartition, newOffset);
					}
					catch (Exception ex) {
						var consumer = this.consumer;
						this.logger.error(ex, () -> "Failed to set initial offset for " + topicPartition
								+ " at " + newOffset + ". Position is " + consumer
								.position(topicPartition));
					}
				}
			}
		}
	}

	@Nullable
	private ConsumerRecord<K, V> pollRecord() {
		if (this.recordsIterator != null) {
			return nextRecord();
		}
		else {
			this.consumerLock.lock();
			try {
				try {
					Assert.state(this.consumer != null, "'consumer' must not be null");
					ConsumerRecords<K, V> records = this.consumer
							.poll(this.assignedPartitions.isEmpty() ? this.assignTimeout : this.pollTimeout);
					this.logger.debug(() -> records == null
							? "Received null"
							: "Received " + records.count() + " records");
					if (records == null || records.count() == 0) {
						return null;
					}
					this.remainingCount.set(records.count());
					this.recordsIterator = records.iterator();
					return nextRecord();
				}
				catch (WakeupException ex) {
					this.logger.debug("Woken");
					if (this.newAssignment) {
						this.newAssignment = false;
						return pollRecord();
					}
					return null;
				}
			}
			finally {
				this.consumerLock.unlock();
			}
		}
	}

	private ConsumerRecord<K, V> nextRecord() {
		ConsumerRecord<K, V> record;
		Assert.state(this.recordsIterator != null, "'recordsIterator' must not be null");
		record = this.recordsIterator.next();
		if (!this.recordsIterator.hasNext()) {
			this.recordsIterator = null;
		}
		this.remainingCount.decrementAndGet();
		return record;
	}

	private Object recordToMessage(ConsumerRecord<K, V> record) {
		if (record.value() == null && this.checkNullValueForExceptions) {
			checkDeserializationException(record, SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER);
		}
		if (record.key() == null && this.checkNullKeyForExceptions) {
			checkDeserializationException(record, SerializationUtils.KEY_DESERIALIZER_EXCEPTION_HEADER);
		}

		TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
		KafkaAckInfo<K, V> ackInfo = new KafkaAckInfoImpl(record, topicPartition);
		AcknowledgmentCallback ackCallback = this.ackCallbackFactory.createCallback(ackInfo);
		this.inflightRecords.computeIfAbsent(topicPartition, tp -> Collections.synchronizedSet(new TreeSet<>()))
				.add(ackInfo);
		Message<?> message = this.messageConverter.toMessage(record,
				ackCallback instanceof Acknowledgment ? (Acknowledgment) ackCallback : null, this.consumer,
				this.payloadType);
		if (message.getHeaders() instanceof KafkaMessageHeaders) {
			Map<String, Object> rawHeaders = ((KafkaMessageHeaders) message.getHeaders()).getRawHeaders();
			rawHeaders.put(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK, ackCallback);
			rawHeaders.put(REMAINING_RECORDS, this.remainingCount.get());
			if (this.rawMessageHeader) {
				rawHeaders.put(KafkaHeaders.RAW_DATA, record);
				rawHeaders.put(IntegrationMessageHeaderAccessor.SOURCE_DATA, record);
			}
			return message;
		}
		else {
			AbstractIntegrationMessageBuilder<?> builder = getMessageBuilderFactory().fromMessage(message)
					.setHeader(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK, ackCallback)
					.setHeader(REMAINING_RECORDS, this.remainingCount.get());
			if (this.rawMessageHeader) {
				builder.setHeader(KafkaHeaders.RAW_DATA, record);
				builder.setHeader(IntegrationMessageHeaderAccessor.SOURCE_DATA, record);
			}
			return builder;
		}
	}

	private void checkDeserializationException(ConsumerRecord<K, V> record, String headerName) {
		DeserializationException exception = SerializationUtils.getExceptionFromHeader(record, headerName, this.logger);
		if (exception != null) {
			throw exception;
		}
	}

	@Override
	public void destroy() {
		this.receiveLock.lock();
		try {
			stopConsumer();
		}
		finally {
			this.receiveLock.unlock();
		}
	}

	private void stopConsumer() {
		this.consumerLock.lock();
		try {
			if (this.consumer != null) {
				this.consumer.wakeup();
				this.consumer.close(this.closeTimeout);
				this.consumer = null;
				this.assignedPartitions.clear();
			}
		}
		finally {
			this.consumerLock.unlock();
		}
	}

	private static <K, V> ConsumerFactory<K, V> fixConsumerFactory(ConsumerFactory<K, V> suppliedConsumerFactory) {
		Map<String, Object> configs = new HashMap<>(suppliedConsumerFactory.getConfigurationProperties());
		configs.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
		DefaultKafkaConsumerFactory<K, V> fixedConsumerFactory = new DefaultKafkaConsumerFactory<>(configs);
		if (suppliedConsumerFactory.getKeyDeserializer() != null) {
			fixedConsumerFactory.setKeyDeserializer(suppliedConsumerFactory.getKeyDeserializer());
		}
		if (suppliedConsumerFactory.getValueDeserializer() != null) {
			fixedConsumerFactory.setValueDeserializer(suppliedConsumerFactory.getValueDeserializer());
		}
		return fixedConsumerFactory;
	}

	private class IntegrationConsumerRebalanceListener implements ConsumerRebalanceListener {

		private final @Nullable ConsumerRebalanceListener providedRebalanceListener;

		private final boolean isConsumerAware;

		IntegrationConsumerRebalanceListener(@Nullable ConsumerRebalanceListener providedRebalanceListener) {
			this.providedRebalanceListener = providedRebalanceListener;
			this.isConsumerAware = providedRebalanceListener instanceof ConsumerAwareRebalanceListener;
		}

		@Override
		public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
			KafkaMessageSource.this.assignedPartitions.removeAll(partitions);
			KafkaMessageSource.this.logger.info(() -> "Partitions revoked: " + partitions);
			if (this.providedRebalanceListener != null) {
				if (this.isConsumerAware) {
					Assert.state(KafkaMessageSource.this.consumer != null, "'consumer' must not be null");
					((ConsumerAwareRebalanceListener) this.providedRebalanceListener)
							.onPartitionsRevokedAfterCommit(KafkaMessageSource.this.consumer, partitions);
				}
				else {
					this.providedRebalanceListener.onPartitionsRevoked(partitions);
				}
			}

		}

		@Override
		public void onPartitionsLost(Collection<TopicPartition> partitions) {
			if (this.providedRebalanceListener != null) {
				this.providedRebalanceListener.onPartitionsLost(partitions);
			}
			onPartitionsRevoked(partitions);
		}

		@Override
		public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
			KafkaMessageSource.this.assignedPartitions.addAll(partitions);
			Assert.state(KafkaMessageSource.this.consumer != null, "'consumer' must not be null");
			if (KafkaMessageSource.this.paused.get()) {
				KafkaMessageSource.this.consumer.pause(KafkaMessageSource.this.assignedPartitions);
				KafkaMessageSource.this.logger.warn("Paused consumer resumed by Kafka due to rebalance; "
						+ "consumer paused again, so the initial poll() will never return any records");
			}
			KafkaMessageSource.this.logger.info(() -> "Partitions assigned: " + partitions);
			if (this.providedRebalanceListener != null) {
				if (this.isConsumerAware) {
					((ConsumerAwareRebalanceListener) this.providedRebalanceListener)
							.onPartitionsAssigned(KafkaMessageSource.this.consumer, partitions);
				}
				else {
					this.providedRebalanceListener.onPartitionsAssigned(partitions);
				}
			}
			KafkaMessageSource.this.consumer.wakeup();
			KafkaMessageSource.this.newAssignment = true;
		}

	}

	/**
	 * AcknowledgmentCallbackFactory for KafkaAckInfo.
	 * @param consumerProperties the properties.
	 * @param <K> the key type.
	 * @param <V> the value type.
	 *
	 */
	public record KafkaAckCallbackFactory<K, V>(ConsumerProperties consumerProperties)
			implements AcknowledgmentCallbackFactory<KafkaAckInfo<K, V>> {

		@Override
		public AcknowledgmentCallback createCallback(KafkaAckInfo<K, V> info) {
			return new KafkaAckCallback<>(info, this.consumerProperties);
		}

	}

	/**
	 * AcknowledgmentCallback for Kafka.
	 * @param <K> the key type.
	 * @param <V> the value type.
	 *
	 */
	public static class KafkaAckCallback<K, V> implements AcknowledgmentCallback, Acknowledgment {

		private final LogAccessor logger = new LogAccessor(LogFactory.getLog(getClass()));

		private final LogIfLevelEnabled commitLogger;

		private final KafkaAckInfo<K, V> ackInfo;

		private final @Nullable Duration commitTimeout;

		private final OffsetCommitCallback commitCallback;

		private final boolean isSyncCommits;

		private volatile boolean acknowledged;

		private boolean autoAckEnabled = true;

		/**
		 * Construct an instance with the provided properties.
		 * @param ackInfo the ack info.
		 * @param consumerProperties the consumer properties - only commit-related
		 * properties are used.
		 */
		public KafkaAckCallback(KafkaAckInfo<K, V> ackInfo, @Nullable ConsumerProperties consumerProperties) {
			Assert.notNull(ackInfo, "'ackInfo' cannot be null");
			this.ackInfo = ackInfo;
			this.commitTimeout = consumerProperties != null ? consumerProperties.getSyncCommitTimeout() : null;
			this.isSyncCommits = consumerProperties == null || consumerProperties.isSyncCommits();
			this.commitCallback =
					consumerProperties != null && consumerProperties.getCommitCallback() != null
							? consumerProperties.getCommitCallback()
							: new LoggingCommitCallback();
			this.commitLogger = new LogIfLevelEnabled(this.logger,
					consumerProperties != null
							? consumerProperties.getCommitLogLevel()
							: LogIfLevelEnabled.Level.DEBUG);
		}

		@Override
		public void acknowledge(Status status) {
			Assert.notNull(status, "'status' cannot be null");
			if (this.acknowledged) {
				throw new IllegalStateException("Already acknowledged");
			}
			synchronized (this.ackInfo.getConsumerMonitor()) {
				try {
					ConsumerRecord<K, V> record = this.ackInfo.getRecord();
					switch (status) {
						case ACCEPT, REJECT -> commitIfPossible(record);
						case REQUEUE -> rollback(record);
						default -> {
						}
					}
				}
				catch (WakeupException e) {
					throw new IllegalStateException(e);
				}
				finally {
					this.acknowledged = true;
					if (!this.ackInfo.isAckDeferred()) {
						var inflight = this.ackInfo.getOffsets().get(this.ackInfo.getTopicPartition());
						Assert.state(inflight != null, "'inflight' must not be null");
						inflight.remove(this.ackInfo);
					}
				}
			}
		}

		private void rollback(ConsumerRecord<K, V> record) {
			Assert.state(this.ackInfo.getConsumer() != null, "'consumer' must not be null");
			this.ackInfo.getConsumer().seek(this.ackInfo.getTopicPartition(), record.offset());
			Set<KafkaAckInfo<K, V>> inflight = this.ackInfo.getOffsets().get(this.ackInfo.getTopicPartition());
			Assert.state(inflight != null, "'infligt' must not be null");
			synchronized (inflight) {
				if (inflight.size() > 1) {
					List<Long> rewound =
							inflight.stream()
									.filter(i -> i.getRecord().offset() > record.offset())
									.map(i -> {
										i.setRolledBack(true);
										return i.getRecord().offset();
									})
									.toList();
					if (!rewound.isEmpty()) {
						this.logger.warn(() -> "Rolled back " + KafkaUtils.format(record)
								+ " later in-flight offsets "
								+ rewound + " will also be re-fetched");
					}
				}
			}
		}

		private void commitIfPossible(ConsumerRecord<K, V> record) { // NOSONAR
			if (this.ackInfo.isRolledBack()) {
				this.logger.warn(() -> "Cannot commit offset for "
						+ KafkaUtils.format(record)
						+ "; an earlier offset was rolled back");
			}
			else {
				Set<KafkaAckInfo<K, V>> candidates = this.ackInfo.getOffsets().get(this.ackInfo.getTopicPartition());
				Assert.state(candidates != null, "'candidates' must not be null");
				KafkaAckInfo<K, V> ackInformation = null;
				synchronized (candidates) {
					if (candidates.iterator().next().equals(this.ackInfo)) {
						// see if there are any pending acks for higher offsets
						List<KafkaAckInfo<K, V>> toCommit = new ArrayList<>();
						for (KafkaAckInfo<K, V> info : candidates) {
							if (!this.ackInfo.equals(info)) {
								if (info.isAckDeferred()) {
									toCommit.add(info);
								}
								else {
									break;
								}
							}
						}
						if (!toCommit.isEmpty()) {
							ackInformation = toCommit.get(toCommit.size() - 1);
							KafkaAckInfo<K, V> ackInformationToLog = ackInformation;
							this.commitLogger.log(() -> "Committing pending offsets for "
									+ KafkaUtils.format(record)
									+ " and all deferred to "
									+ KafkaUtils.format(ackInformationToLog.getRecord()));
							toCommit.forEach(candidates::remove);
						}
						else {
							ackInformation = this.ackInfo;
							this.commitLogger.log(() -> "Committing offset for " + KafkaUtils.format(record));
						}
					}
					else { // earlier offsets present
						this.ackInfo.setAckDeferred(true);
					}
					if (ackInformation != null) {
						Map<TopicPartition, OffsetAndMetadata> offset =
								Collections.singletonMap(ackInformation.getTopicPartition(),
										new OffsetAndMetadata(ackInformation.getRecord().offset() + 1));
						Assert.state(ackInformation.getConsumer() != null, "'consumer' must not be null");
						if (this.isSyncCommits) {
							if (this.commitTimeout == null) {
								ackInformation.getConsumer().commitSync(offset);
							}
							else {
								ackInformation.getConsumer().commitSync(offset, this.commitTimeout);
							}
						}
						else {
							ackInformation.getConsumer().commitAsync(offset, this.commitCallback);
						}
					}
					else {
						this.logger.debug("Deferring commit offset; earlier messages are in flight.");
					}
				}
			}
		}

		@Override
		public boolean isAcknowledged() {
			return this.acknowledged;
		}

		@Override
		public void acknowledge() {
			acknowledge(Status.ACCEPT);
		}

		@Override
		public void noAutoAck() {
			this.autoAckEnabled = false;
		}

		@Override
		public boolean isAutoAck() {
			return this.autoAckEnabled;
		}

	}

	/**
	 * Information for building an KafkaAckCallback.
	 */
	public class KafkaAckInfoImpl implements KafkaAckInfo<K, V> { // NOSONAR - no equals() impl

		private final ConsumerRecord<K, V> record;

		private final TopicPartition topicPartition;

		private volatile boolean rolledBack;

		private volatile boolean ackDeferred;

		KafkaAckInfoImpl(ConsumerRecord<K, V> record, TopicPartition topicPartition) {
			this.record = record;
			this.topicPartition = topicPartition;
		}

		@Override
		public Object getConsumerMonitor() {
			return KafkaMessageSource.this.consumerLock;
		}

		@Override
		public @Nullable String getGroupId() {
			return KafkaMessageSource.this.getGroupId();
		}

		@Override
		public @Nullable Consumer<K, V> getConsumer() {
			return KafkaMessageSource.this.consumer;
		}

		@Override
		public ConsumerRecord<K, V> getRecord() {
			return this.record;
		}

		@Override
		public TopicPartition getTopicPartition() {
			return this.topicPartition;
		}

		@Override
		public Map<TopicPartition, Set<KafkaAckInfo<K, V>>> getOffsets() {
			return KafkaMessageSource.this.inflightRecords;
		}

		@Override
		public boolean isRolledBack() {
			return this.rolledBack;
		}

		@Override
		public void setRolledBack(boolean rolledBack) {
			this.rolledBack = rolledBack;
		}

		@Override
		public boolean isAckDeferred() {
			return this.ackDeferred;
		}

		@Override
		public void setAckDeferred(boolean ackDeferred) {
			this.ackDeferred = ackDeferred;
		}

		@Override
		public int compareTo(KafkaAckInfo<K, V> other) {
			return Long.compare(this.record.offset(), other.getRecord().offset());
		}

		@Override
		public String toString() {
			return "KafkaAckInfo [record=" + this.record + ", rolledBack=" + this.rolledBack + ", ackDeferred="
					+ this.ackDeferred + "]";
		}

	}

	/**
	 * Information for building an KafkaAckCallback.
	 *
	 * @param <K> the key type.
	 * @param <V> the value type.
	 *
	 */
	public interface KafkaAckInfo<K, V> extends Comparable<KafkaAckInfo<K, V>> {

		Object getConsumerMonitor();

		@Nullable
		String getGroupId();

		@Nullable
		Consumer<K, V> getConsumer();

		ConsumerRecord<K, V> getRecord();

		TopicPartition getTopicPartition();

		Map<TopicPartition, Set<KafkaAckInfo<K, V>>> getOffsets();

		boolean isRolledBack();

		void setRolledBack(boolean rolledBack);

		boolean isAckDeferred();

		void setAckDeferred(boolean ackDeferred);

	}

}

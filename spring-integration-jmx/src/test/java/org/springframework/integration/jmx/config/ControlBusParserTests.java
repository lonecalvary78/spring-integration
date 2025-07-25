/*
 * Copyright 2002-present the original author or authors.
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

package org.springframework.integration.jmx.config;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.test.support.TestApplicationContextAware;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Mark Fisher
 * @author Gary Russell
 * @author Artem Bilan
 *
 * @since 2.0
 */
@SpringJUnitConfig
@DirtiesContext
public class ControlBusParserTests implements TestApplicationContextAware {

	@Autowired
	private ApplicationContext context;

	@Test
	public void testControlMessageToChannelMetrics() {
		MessageChannel control = this.context.getBean("controlChannel", MessageChannel.class);
		MessagingTemplate messagingTemplate = new MessagingTemplate();
		messagingTemplate.setBeanFactory(TEST_INTEGRATION_CONTEXT);
		Object value = messagingTemplate.convertSendAndReceive(control, "@cb.isRunning()", Object.class);
		assertThat(value).isEqualTo(Boolean.TRUE);
	}

}

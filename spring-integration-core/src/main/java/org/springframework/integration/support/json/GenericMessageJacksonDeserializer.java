/*
 * Copyright 2017-present the original author or authors.
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

package org.springframework.integration.support.json;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.integration.support.MutableMessageHeaders;
import org.springframework.messaging.support.GenericMessage;

/**
 * The {@link MessageJacksonDeserializer} implementation for the {@link GenericMessage}.
 *
 * @author Artem Bilan
 *
 * @since 4.3.10
 * @deprecated Since 7.0 in favor of {@link GenericMessageJsonDeserializer} for Jackson 3.
 */
@Deprecated(since = "7.0", forRemoval = true)
@SuppressWarnings("removal")
public class GenericMessageJacksonDeserializer extends MessageJacksonDeserializer<GenericMessage<?>> {

	private static final long serialVersionUID = 1L;

	@SuppressWarnings("unchecked")
	public GenericMessageJacksonDeserializer() {
		super((Class<GenericMessage<?>>) (Class<?>) GenericMessage.class);
	}

	@Override
	protected GenericMessage<?> buildMessage(MutableMessageHeaders headers, Object payload, JsonNode root,
			DeserializationContext ctxt) throws IOException {
		return new GenericMessage<Object>(payload, headers);
	}

}

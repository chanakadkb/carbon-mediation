/*
 *   Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.inbound.endpoint.protocol.http2;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.inbound.InboundEndpointConstants;
import org.apache.synapse.inbound.InboundResponseSender;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.nhttp.util.NhttpUtil;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.api.PassThroughInboundEndpointHandler;
import org.apache.synapse.transport.passthru.config.SourceConfiguration;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.wso2.caching.CachingConstants;
import org.wso2.caching.digest.DigestGenerator;
import org.wso2.carbon.inbound.endpoint.protocol.http2.common.InboundMessageHandler;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Sending response back to peer
 */
public class Http2ResponseWriter {
	private static final Log log = LogFactory.getLog(Http2ResponseWriter.class);
	Http2Connection connection;
	Http2ConnectionEncoder encoder;
	ChannelHandlerContext chContext; //this can be taken from msgcontext
	private DigestGenerator digestGenerator = CachingConstants.DEFAULT_XML_IDENTIFIER;
	private SourceConfiguration sourceConfiguration;
	private InboundHttp2Configuration config;

	/**
	 * writing a response on wire
	 *
	 * @param synCtx
	 * @throws AxisFault
	 */
	public void writeNormalResponse(MessageContext synCtx) throws AxisFault {
		org.apache.axis2.context.MessageContext msgContext =
				((Axis2MessageContext) synCtx).getAxis2MessageContext();
		Http2Headers transportHeaders = new DefaultHttp2Headers();
		InboundResponseSender responseSender =
				synCtx.getProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER) ==
				null ? null : (InboundHttp2ResponseSender) synCtx
						.getProperty(InboundEndpointConstants.INBOUND_ENDPOINT_RESPONSE_WORKER);
		try {
			sourceConfiguration =
					PassThroughInboundEndpointHandler.getPassThroughSourceConfiguration();
		} catch (Exception e) {
			throw new AxisFault("Error while building sourceConfiguration " + e);
		}

		//status
		try {
			int statusCode = PassThroughTransportUtils.determineHttpStatusCode(msgContext);
			if (statusCode > 0) {
				HttpResponseStatus status1 = HttpResponseStatus.valueOf(statusCode);
				transportHeaders.status(status1.codeAsText());
			}
		} catch (Exception e) {
			throw new AxisFault("Error occured while parsing response status", e);
		}
		//content_type
		Boolean noEntityBody = msgContext.getProperty(NhttpConstants.NO_ENTITY_BODY) != null ?
		                       (boolean) msgContext.getProperty(NhttpConstants.NO_ENTITY_BODY) :
		                       null;
		if (noEntityBody == null || Boolean.FALSE == noEntityBody) {
			Pipe pipe = (Pipe) msgContext.getProperty(PassThroughConstants.PASS_THROUGH_PIPE);
			if (pipe == null) {
				pipe = new Pipe(sourceConfiguration.getBufferFactory().getBuffer(), "Test",
				                sourceConfiguration);
				msgContext.setProperty(PassThroughConstants.PASS_THROUGH_PIPE, pipe);
				msgContext.setProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED, Boolean.TRUE);
			}

			OMOutputFormat format = NhttpUtil.getOMOutputFormat(msgContext);
			MessageFormatter messageFormatter =
					MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgContext);
			if (msgContext.getProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE) ==
			    null) {
				transportHeaders.add(HttpHeaderNames.CONTENT_TYPE, messageFormatter
						.getContentType(msgContext, format, msgContext.getSoapAction()));
			}
		}

		if (transportHeaders != null &&
		    msgContext.getProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE) != null) {
			if (msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE) !=
			    null &&
			    msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE)
			              .toString()
			              .contains(PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED)) {
				transportHeaders.add(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE,
				                     PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED);
			} else {
				Pipe pipe = (Pipe) msgContext.getProperty(PassThroughConstants.PASS_THROUGH_PIPE);
				if (pipe != null && !Boolean.TRUE.equals(msgContext.getProperty(
						PassThroughConstants.MESSAGE_BUILDER_INVOKED)) && msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE) !=
				    null) {
					transportHeaders.add(HttpHeaderNames.CONTENT_TYPE, msgContext
							.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE)
							.toString());
				}
			}
		}

		//Excess headers
		String excessProp = NhttpConstants.EXCESS_TRANSPORT_HEADERS;
		Map excessHeaders = msgContext.getProperty(excessProp) == null ? null :
		                    (Map) msgContext.getProperty(excessProp);
		if (excessHeaders != null) {
			for (Iterator iterator = excessHeaders.keySet().iterator(); iterator.hasNext(); ) {
				String key = (String) iterator.next();
				for (String excessVal : (Collection<String>) excessHeaders.get(key)) {
					transportHeaders.add(key.toLowerCase(), (String) excessVal);
				}
			}
		}

		boolean hasBody = false;
		if (!transportHeaders.contains(HttpHeaderNames.CONTENT_TYPE)) {
			String contentType = null;
			try {
				contentType = new InboundMessageHandler(responseSender, config)
						.getContentType(msgContext);
			} catch (Exception e) {
				throw new AxisFault("Error while parsing content type", e);
			}
			if (contentType != null) {
				transportHeaders.add(HttpHeaderNames.CONTENT_TYPE, contentType);
				hasBody = true;
			}
		} else
			hasBody = true;

		int streamId = (int) synCtx.getProperty("stream-id");
		ChannelHandlerContext c = (ChannelHandlerContext) synCtx.getProperty("stream-channel");
		if (c == null) {
			c = chContext;
		}
		ChannelPromise promise = c.newPromise();
		if (hasBody) {
			Pipe pipe = msgContext.getProperty("pass-through.pipe") == null ? null :
			            (Pipe) msgContext.getProperty("pass-through.pipe");
			encoder.writeHeaders(c, streamId, transportHeaders, 0, false, promise);
			http2Encoder pipeEncoder = new http2Encoder(c, streamId, encoder, c.newPromise());
			if (pipe != null) {
				pipe.attachConsumer(new Http2CosumerIoControl());
				try {
					if (Boolean.TRUE.equals(msgContext.getProperty(
							PassThroughConstants.MESSAGE_BUILDER_INVOKED))) {
						ByteArrayOutputStream out = new ByteArrayOutputStream();
						MessageFormatter formatter =
								MessageProcessorSelector.getMessageFormatter(msgContext);
						OMOutputFormat format =
								PassThroughTransportUtils.getOMOutputFormat(msgContext);
						formatter.writeTo(msgContext, format, out, false);
						OutputStream _out = pipe.getOutputStream();
						IOUtils.write(out.toByteArray(), _out);
					}
					int t = pipe.consume(pipeEncoder);
					if (t < 1)
						throw new AxisFault("Pipe consuming failed");
				} catch (Exception e) {
					throw new AxisFault("Error while writing built message back to pipe", e);
				}
			}
			c.flush();
		} else {
			encoder.writeHeaders(c, streamId, transportHeaders, 0, true, promise);
			c.flush();
		}
	}

	/**
	 * handles pushpromise responses
	 *
	 * @param synCtx
	 * @throws AxisFault
	 */
	public void writePushPromiseResponse(MessageContext synCtx) throws AxisFault {
		int promiseId = connection.local().incrementAndGetNextStreamId();
		int streamId = (int) synCtx.getProperty("stream-id");
		synCtx.setProperty("stream-id", promiseId);
		ChannelHandlerContext c = (ChannelHandlerContext) synCtx.getProperty("stream-channel");
		if (c == null) {
			c = chContext;
		}
		ChannelPromise promise = c.newPromise();
		encoder.writePushPromise(c, streamId, promiseId, null, 0, promise);
		writeNormalResponse(synCtx);
	}

	/**
	 * Handles connection closed responses
	 *
	 * @param synCtx
	 * @throws AxisFault
	 */
	public void writeGoAwayResponse(MessageContext synCtx) throws AxisFault {
		int streamId = (int) synCtx.getProperty("stream-id");
		ChannelHandlerContext c = (ChannelHandlerContext) synCtx.getProperty("stream-channel");
		if (c == null) {
			c = chContext;
		}
		ChannelPromise promise = c.newPromise();
		encoder.writeGoAway(c, streamId, connection.local().lastStreamKnownByPeer(), null, promise);
	}

	public void setConnection(Http2Connection connection) {
		this.connection = connection;
	}

	public void setEncoder(Http2ConnectionEncoder encoder) {
		this.encoder = encoder;
	}

	public void setChContext(ChannelHandlerContext chContext) {
		this.chContext = chContext;
	}

	public void setConfig(InboundHttp2Configuration config) {
		this.config = config;
	}
}

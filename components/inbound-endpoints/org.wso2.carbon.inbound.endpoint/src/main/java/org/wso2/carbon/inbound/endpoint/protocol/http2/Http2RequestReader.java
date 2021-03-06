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
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import org.apache.axis2.AxisFault;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.api.PassThroughInboundEndpointHandler;
import org.apache.synapse.transport.passthru.config.SourceConfiguration;
import org.wso2.carbon.inbound.endpoint.protocol.http2.common.InboundMessageHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Handle Http2 Request Frames
 */

public class Http2RequestReader {
	private static final Log log = LogFactory.getLog(Http2RequestReader.class);

	private ChannelHandlerContext chContext;
	private Map<Integer, Http2SourceRequest> requestMap;
	private InboundMessageHandler messageHandler;
	private SourceConfiguration sourceConfiguration;

	public Http2RequestReader() {
		requestMap = new TreeMap<>();
		try {
			sourceConfiguration =
					PassThroughInboundEndpointHandler.getPassThroughSourceConfiguration();
		} catch (Exception e) {
			log.warn("Cannot get PassThroughSourceConfiguration ", e);
		}
	}

	public void setMessageHandler(InboundMessageHandler messageHandler) {
		this.messageHandler = messageHandler;
	}

	/**
	 * Handles header frames
	 *
	 * @param frame
	 */
	public void onHeaderRead(Http2HeadersFrame frame) {
		Http2SourceRequest request;
		if (requestMap.containsKey(frame.streamId())) {
			request = requestMap.get(frame.streamId());
		} else {
			request = new Http2SourceRequest(frame.streamId(), chContext);
			request.setRequestType(Http2Constants.HTTP2_CLIENT_SENT_REQEUST);
			requestMap.put(frame.streamId(), request);
		}
		Set<CharSequence> headerSet = frame.headers().names();
		for (CharSequence header : headerSet) {
			request.setHeader(header.toString(), frame.headers().get(header).toString());
		}
		if (frame.isEndStream()) {
			try {
				messageHandler.processRequest(request);
				requestMap.remove(frame.streamId());
			} catch (Exception e) {
				log.error(e);
			}
		}
	}

	/**
	 * Handles data frames
	 *
	 * @param frame
	 */
	public void onDataRead(Http2DataFrame frame) {
		int streamId = frame.streamId();
		Http2SourceRequest request = null;

		if (!requestMap.containsKey(streamId)) {
			return;
		}

		request = requestMap.get(streamId);

		request.setChannel(chContext);

		Pipe pipe = request.getPipe();
		if (pipe == null) {
			pipe = new Pipe(new HTTP2Producer(), sourceConfiguration.getBufferFactory().getBuffer(),
			                "source", sourceConfiguration);
			request.setPipe(pipe);
		}
		try {
			pipe.produce(new HTTP2Decoder(frame));
		} catch (IOException e) {
			log.error(e);
		}
		if (!request.isProcessedReq()) {
			try {
				messageHandler.processRequest(request);
				request.setProcessedReq(true);
			} catch (AxisFault axisFault) {
				log.error(axisFault);
			}
		}
		if (frame.isEndStream())
			requestMap.remove(request.getStreamID());

	}

	/**
	 * Handles goaway frames
	 *
	 * @param frame
	 */
	public void onGoAwayRead(Http2GoAwayFrame frame) {
		chContext.close();
	}

	/**
	 * Handles stream termination
	 *
	 * @param frame
	 */
	public void onRstSteamRead(Http2ResetFrame frame) {
		if (requestMap.containsKey(frame.streamId())) {
			requestMap.remove(frame.streamId());
		}
	}

	public void setChContext(ChannelHandlerContext chContext) {
		this.chContext = chContext;
	}

}

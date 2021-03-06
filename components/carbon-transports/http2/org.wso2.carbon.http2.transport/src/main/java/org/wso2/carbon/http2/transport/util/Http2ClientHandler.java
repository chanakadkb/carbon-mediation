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

package org.wso2.carbon.http2.transport.util;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Settings;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.inbound.InboundResponseSender;
import org.apache.synapse.transport.passthru.config.TargetConfiguration;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sending requests and receiving responses from a backend server
 */
public class Http2ClientHandler extends ChannelDuplexHandler {

	private static final Log log = LogFactory.getLog(Http2ClientHandler.class);

	private Http2RequestWriter writer;
	private Http2ResponseReceiver receiver;
	private Http2Connection connection;
	private Http2ConnectionEncoder encoder;
	private ChannelHandlerContext chContext;
	private Map<Integer, MessageContext> sentRequests;
	private LinkedList<MessageContext> pollReqeusts;

	public Http2ClientHandler(Http2Connection connection) {
		this.connection = connection;
		sentRequests = new TreeMap<>();
		writer = new Http2RequestWriter(connection);
		pollReqeusts = new LinkedList<>();
	}

	/**
	 * Read respond frames from netty channel and pass to ResponseReceiver
	 *
	 * @param ctx
	 * @param msg
	 */
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Http2HeadersFrame) {
			Http2HeadersFrame frame = (Http2HeadersFrame) msg;
			if (!sentRequests.containsKey(frame.streamId())) {
				return;
			}
			receiver.onHeadersFrameRead(frame, sentRequests.get(frame.streamId()));
			if (frame.isEndStream()) {
				sentRequests.remove(frame.streamId());
			}
		} else if (msg instanceof Http2DataFrame) {
			Http2DataFrame frame = (Http2DataFrame) msg;
			if (!sentRequests.containsKey(frame.streamId())) {
				return;
			}
			receiver.onDataFrameRead(frame, sentRequests.get(frame.streamId()));
			if (frame.isEndStream()) {
				sentRequests.remove(frame.streamId());
			}
		} else if (msg instanceof Http2PushPromiseFrame) {
			Http2PushPromiseFrame frame = (Http2PushPromiseFrame) msg;
			if (!sentRequests.containsKey(frame.streamId())) {
				return;
			}
			MessageContext prevRequest = sentRequests.get(frame.streamId());

			//if the inbound is not accept push requests reject them
			if (!receiver.isServerPushAccepted()) {
				writer.writeRestSreamRequest(frame.getPushPromiseId(), Http2Error.REFUSED_STREAM);
				return;
			}

			sentRequests.put(frame.getPushPromiseId(), prevRequest);
			receiver.onPushPromiseFrameRead(frame, prevRequest);

		} else if (msg instanceof Http2Settings) {
			setChContext(ctx);
			receiver.onUnknownFrameRead(msg);

		} else if (msg instanceof Http2GoAwayFrame) {
			receiver.onUnknownFrameRead(msg);

		} else if (msg instanceof Http2ResetFrame) {
			if (sentRequests.containsKey(((Http2ResetFrame) msg).streamId())) {
				sentRequests.remove(((Http2ResetFrame) msg).streamId());
			}
			receiver.onUnknownFrameRead(msg);

		} else {
			receiver.onUnknownFrameRead(msg);

		}
	}

	/**
	 * Take requests from TransportSender and pass them to RequestWriter
	 *
	 * @param request
	 * @throws AxisFault
	 */
	public synchronized void channelWrite(MessageContext request) throws AxisFault {
		if (chContext == null) {
			pollReqeusts.add(request);
			return;
		}
		if (receiver.getInboundChannel() == null)
			receiver.setInboundChannel(
					(ChannelHandlerContext) request.getProperty(Http2Constants.STREAM_CHANNEL));

		String requestType = (String) request.getProperty(Http2Constants.HTTP2_REQUEST_TYPE);
		if (requestType == null || requestType.equals(Http2Constants.HTTP2_CLIENT_SENT_REQEUST)) {
			int streamId = writer.getNextStreamId();
			sentRequests.put(streamId, request);
			writer.writeSimpleReqeust(streamId, request);

		} else if (requestType.equals(Http2Constants.HTTP2_RESET_REQEUST)) {
			int id = (int) request.getProperty(Http2Constants.HTTP2_SERVER_STREAM_ID);
			Http2Error code = (Http2Error) request.getProperty(Http2Constants.HTTP2_ERROR_CODE);
			writer.writeRestSreamRequest(id, code);

		} else if (requestType
				.equals(Http2Constants.HTTP2_GO_AWAY_REQUEST)) {  //Basically GoAway caused to dispose handler
			int id = (int) request.getProperty(Http2Constants.HTTP2_SERVER_STREAM_ID);
			Http2Error code = (Http2Error) request.getProperty(Http2Constants.HTTP2_ERROR_CODE);
			writer.writeGoAwayReqeust(id, code);
		}
	}

	public Http2RequestWriter getWriter() {
		return writer;
	}

	public void setWriter(Http2RequestWriter writer) {
		this.writer = writer;
	}

	public Http2ResponseReceiver getReceiver() {
		return receiver;
	}

	public void setReceiver(Http2ResponseReceiver receiver) {
		this.receiver = receiver;
	}

	public Http2Connection getConnection() {
		return connection;
	}

	public void setConnection(Http2Connection connection) {
		this.connection = connection;
	}

	public Http2ConnectionEncoder getEncoder() {
		return encoder;
	}

	public void setEncoder(Http2ConnectionEncoder encoder) {
		this.encoder = encoder;
		writer.setEncoder(encoder);

	}

	public ChannelHandlerContext getChContext() {
		return chContext;
	}

	public void setChContext(ChannelHandlerContext chContext) {
		this.chContext = chContext;
		writer.setChannelHandlerContext(chContext);

		if (!pollReqeusts.isEmpty()) {
			Iterator<MessageContext> requests = pollReqeusts.iterator();
			while (requests.hasNext()) {
				try {
					channelWrite(requests.next());
				} catch (Exception e) {
					log.error("Error while sending polled messages before channel establishment",
					          e);
				}
			}
		}
	}

	/**
	 * Create new instance of Http2ResponseReceiver
	 *
	 * @param tenantDomain
	 * @param dispatchSequence
	 * @param errorSequence
	 * @param responseSender
	 * @param targetConfiguration
	 * @param serverPushAccept
	 */
	public void setResponseReceiver(String tenantDomain, String dispatchSequence,
	                                String errorSequence, InboundResponseSender responseSender,
	                                TargetConfiguration targetConfiguration,
	                                boolean serverPushAccept) {
		receiver = new Http2ResponseReceiver(tenantDomain, responseSender, serverPushAccept,
		                                     dispatchSequence, errorSequence, targetConfiguration);
	}
}

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

import io.netty.channel.*;
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
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.synapse.inbound.InboundResponseSender;
import org.apache.synapse.transport.passthru.config.TargetConfiguration;

import java.util.*;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class Http2ClientHandler extends ChannelDuplexHandler {

    private Http2RequestWriter writer;
    private Http2ResponseReceiver receiver;
    private Http2Connection connection;
    private Http2ConnectionEncoder encoder;
    private ChannelHandlerContext chContext;
    private Map<Integer, MessageContext> sentRequests;  //map reqeust with stream-id
    private LinkedList<MessageContext> pollReqeusts;  //Store requests until settings accepted
    private Map<Integer, Integer> serverPushes;      //map push-stream-id with request stream-id

    public Http2ClientHandler(Http2Connection connection) {
        this.connection = connection;
        sentRequests = new TreeMap<>();
        writer = new Http2RequestWriter(connection);
        pollReqeusts = new LinkedList<>();
        serverPushes = new ConcurrentHashMap<>();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Http2HeadersFrame) {
            Http2HeadersFrame frame = (Http2HeadersFrame) msg;
            if (!sentRequests.containsKey(frame.streamId()) && !serverPushes
                    .containsKey(frame.streamId())) {
                return;
            }
            try {
                int streamId = (serverPushes.containsKey(frame.streamId())) ?
                        serverPushes.get(frame.streamId()) :
                        frame.streamId();
                receiver.onHeadersFrameRead(frame, sentRequests.get(streamId));
            } catch (AxisFault axisFault) {
                axisFault.printStackTrace();
            }
            if (frame.isEndStream()) {
                if (serverPushes.containsKey(frame.streamId()))
                    serverPushes.remove(frame.streamId());
                else
                    sentRequests.remove(frame.streamId());
            }
        } else if (msg instanceof Http2DataFrame) {
            Http2DataFrame frame = (Http2DataFrame) msg;
            if (!sentRequests.containsKey(frame.streamId()) && !serverPushes
                    .containsKey(frame.streamId())) {
                return;
            }
            int streamId = (serverPushes.containsKey(frame.streamId())) ?
                    serverPushes.get(frame.streamId()) :
                    frame.streamId();
            receiver.onDataFrameRead(frame, sentRequests.get(streamId));
            if (frame.isEndStream()) {
                if (serverPushes.containsKey(frame.streamId()))
                    serverPushes.remove(frame.streamId());
                else
                    sentRequests.remove(frame.streamId());
            }
        } else if (msg instanceof Http2PushPromiseFrame) {
            Http2PushPromiseFrame frame = (Http2PushPromiseFrame) msg;
            if (!sentRequests.containsKey(frame.streamId())) {
                return;
            }
            //if the inbound is not accept push requests reject them
            if (!receiver.isServerPushAccepted()) {
                writer.writeRestSreamRequest(frame.getPushPromiseId(), Http2Error.REFUSED_STREAM);
                return;
            }
            serverPushes.put(frame.getPushPromiseId(), frame.streamId());
            receiver.onPushPromiseFrameRead(frame, null);

        } else if (msg instanceof Http2Settings) {
            setChContext(ctx);
            receiver.onUnknownFrameRead(msg);

        } else if (msg instanceof Http2GoAwayFrame) {
            receiver.onUnknownFrameRead(msg);

        } else if (msg instanceof Http2ResetFrame) {
            receiver.onUnknownFrameRead(msg);

        } else {
            receiver.onUnknownFrameRead(msg);

        }
    }

    public void channelWrite(MessageContext request) {
        if (chContext == null) {
            pollReqeusts.add(request);
            return;
        }
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

    public Http2Connection getConnection() {
        return connection;
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
                channelWrite(requests.next());
            }
        }
    }

    public void removeHandler() {
        if (chContext.channel().isActive() || chContext.channel().isOpen()) {
            chContext.channel().close();
            chContext.executor().shutdownGracefully();
        }
    }

    public void setResponseReceiver(String tenantDomain, InboundResponseSender responseSender,
            TargetConfiguration targetConfiguration, boolean serverPushAccept) {
        receiver = new Http2ResponseReceiver(tenantDomain, responseSender, serverPushAccept,
                targetConfiguration);
    }

}

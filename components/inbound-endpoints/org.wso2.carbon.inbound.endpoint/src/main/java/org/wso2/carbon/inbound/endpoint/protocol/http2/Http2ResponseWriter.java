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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.http.protocol.HTTP;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.inbound.InboundEndpointConstants;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.nhttp.util.NhttpUtil;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.caching.CachingConstants;
import org.wso2.caching.digest.DigestGenerator;
import org.wso2.carbon.inbound.endpoint.protocol.http2.common.InboundHttp2Constants;
import org.wso2.carbon.inbound.endpoint.protocol.http2.common.InboundMessageHandler;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import javax.xml.stream.XMLStreamException;

/**
 * Created by chanakabalasooriya on 12/6/16.
 */
public class Http2ResponseWriter {
    private static final Log log = LogFactory.getLog(Http2ResponseWriter.class);
    private DigestGenerator digestGenerator  = CachingConstants.DEFAULT_XML_IDENTIFIER;
    Http2Connection connection;
    Http2ConnectionEncoder encoder;
    ChannelHandlerContext chContext; //this can be taken from msgcontext

    public void writeNormalResponse(MessageContext synCtx){
        org.apache.axis2.context.MessageContext msgContext=((Axis2MessageContext) synCtx).getAxis2MessageContext();
        Http2Headers transportHeaders=new DefaultHttp2Headers();

        if (msgContext.getProperty(PassThroughConstants.HTTP_ETAG_ENABLED) != null
                && (Boolean) msgContext.getProperty(PassThroughConstants.HTTP_ETAG_ENABLED)) {

            try {
                RelayUtils.buildMessage(msgContext);
            } catch (IOException e) {
                log.error("IO Error occurred while building the message", e);
            } catch (XMLStreamException e) {
                log.error("XML Error occurred while building the message", e);
            }

            String hash = digestGenerator.getDigest(msgContext);
            Map headers = (Map) msgContext.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
            headers.put(HttpHeaders.ETAG,"\""+hash+"\"");

            Iterator<Map.Entry<String,String>> iterator=headers.entrySet().iterator();
            while (iterator.hasNext()){
                Map.Entry<String,String> head=iterator.next();
                transportHeaders.add(head.getKey(),head.getValue());
            }
        }
        try {
            int statusCode = PassThroughTransportUtils.determineHttpStatusCode(msgContext);
            //String status = PassThroughTransportUtils.determineHttpStatusLine(msgContext);
            if(statusCode>0){
                HttpResponseStatus status1 = HttpResponseStatus.valueOf(statusCode);
                transportHeaders.status(status1.codeAsText());
            }
        }catch (Exception e){
            log.error(e);
        }

        if (transportHeaders != null && msgContext.getProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE) != null) {
            if (msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE) != null
                    && msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE).toString().contains(PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED)) {
                transportHeaders.add(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE, PassThroughConstants.CONTENT_TYPE_MULTIPART_RELATED);
            } else {
                Pipe pipe = (Pipe) msgContext.getProperty(PassThroughConstants.PASS_THROUGH_PIPE);
                if (pipe != null && !Boolean.TRUE.equals(msgContext.getProperty(PassThroughConstants.MESSAGE_BUILDER_INVOKED))) {
                    transportHeaders.add(HttpHeaderNames.CONTENT_TYPE, msgContext.getProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE).toString());
                }
            }
        }
        Boolean noEntityBody = msgContext.getProperty(NhttpConstants.NO_ENTITY_BODY)!=null?
                (boolean)msgContext.getProperty(NhttpConstants.NO_ENTITY_BODY):null;
        if (noEntityBody == null || Boolean.FALSE == noEntityBody) {
            OMOutputFormat format = NhttpUtil.getOMOutputFormat(msgContext);
            //transportHeaders = new HashMap();
            MessageFormatter messageFormatter =
                    MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgContext);
            if(msgContext.getProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE) == null){
                transportHeaders.add(HTTP.CONTENT_TYPE, messageFormatter.getContentType(msgContext, format, msgContext.getSoapAction()));
            }
        }
        String excessProp = NhttpConstants.EXCESS_TRANSPORT_HEADERS;
        Map excessHeaders = msgContext.getProperty(excessProp)==null?null:(Map) msgContext.getProperty(excessProp);
        if (excessHeaders != null) {
            for (Iterator iterator = excessHeaders.keySet().iterator(); iterator.hasNext();) {
                String key = (String) iterator.next();
                for (String excessVal : (Collection<String>) excessHeaders.get(key)) {
                    transportHeaders.add(key, (String) excessVal);
                }
            }
        }

        boolean hasBody=false;
        if(!transportHeaders.contains(HttpHeaderNames.CONTENT_TYPE)){
            String contentType=null;
            try{
                contentType=new InboundMessageHandler(null,null).getContentType(msgContext);
            }catch (Exception e){
                log.error(e);
            }
            if(contentType!=null) {
                transportHeaders.add(HttpHeaderNames.CONTENT_TYPE, contentType);
                hasBody=true;
            }
        }else
            hasBody=true;


        int streamId=(int)synCtx.getProperty("stream-id");
        ChannelHandlerContext c=(ChannelHandlerContext)synCtx.getProperty("stream-channel");
        if(c==null){
            c=chContext;
        }
        ChannelPromise promise=c.newPromise();
        if(hasBody){
            http2Encoder pipeEncoder=new http2Encoder(c,streamId,encoder,promise);

            Pipe pipe = msgContext.getProperty("pass-through.pipe")==null?null:(Pipe) msgContext.getProperty("pass-through.pipe");
            encoder.writeHeaders(c,streamId,transportHeaders,0,false,promise);
            if (pipe != null) {
                pipe.attachConsumer(new Http2CosumerIoControl());
                try {
                    int t = pipe.consume(pipeEncoder);
                }catch (Exception e){
                    log.error(e);
                }
            }
            c.flush();
        }else{
            encoder.writeHeaders(c,streamId,transportHeaders,0,true,promise);
            c.flush();
        }
    }

    public void writePushPromiseResponse(MessageContext synCtx){
       // int promiseId=connection.local().incrementAndGetNextStreamId();
        int streamId=(int)synCtx.getProperty("stream-id");
       // synCtx.setProperty("stream-id",promiseId);
        ChannelHandlerContext c=(ChannelHandlerContext)synCtx.getProperty("stream-channel");
        if(c==null){
            c=chContext;
        }
        ChannelPromise promise=c.newPromise();
        LinkedList<Http2StreamFrame> pushFrames=(LinkedList<Http2StreamFrame>) ((Axis2MessageContext) synCtx).getAxis2MessageContext().getProperty(
                InboundHttp2Constants.HTTP2_PUSH_PROMISE_DATA);
        Map<Integer,Integer> promise_ids=new HashMap<>();
        Iterator<Http2StreamFrame> iterator=pushFrames.iterator();
        while (iterator.hasNext()){
            Http2StreamFrame frame=iterator.next();
            int promise_id=0;
            int id=frame.streamId();

            if(promise_ids.containsKey(id)){
                promise_id=promise_ids.get(id);
            }
            if(frame instanceof Http2HeadersFrame){
                if(promise_id==0)return;
                encoder.writeHeaders(c,promise_id,((Http2HeadersFrame)frame).headers(),0,((Http2HeadersFrame) frame).isEndStream(),promise);

            }else if(frame instanceof Http2DataFrame){
                if(promise_id==0)return;
                ByteBuf buf=((Http2DataFrame)frame).content().duplicate();
                encoder.writeData(c,promise_id,buf.copy(),0,((Http2DataFrame) frame).isEndStream(),promise);
            }else{
                if(!promise_ids.containsKey(id)){
                    promise_id=connection.local().incrementAndGetNextStreamId();
                    promise_ids.put(id,promise_id);
                    encoder.writePushPromise(c,streamId,promise_id,null,0,promise);
                }
            }
        }
        c.flush();
        writeNormalResponse(synCtx);
    }

    public void writeGoAwayResponse(MessageContext synCtx){
        int streamId=(int)synCtx.getProperty("stream-id");
        ChannelHandlerContext c=(ChannelHandlerContext)synCtx.getProperty("stream-channel");
        if(c==null){
            c=chContext;
        }
        ChannelPromise promise=c.newPromise();
        encoder.writeGoAway(c,streamId,connection.local().lastStreamKnownByPeer(),null,promise);
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
}
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

package org.wso2.carbon.http2.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import io.netty.util.CharsetUtil;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.OutTransportInfo;
import org.apache.axis2.transport.base.AbstractTransportSender;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.synapse.transport.http.conn.ProxyConfig;
import org.apache.synapse.transport.nhttp.config.ProxyConfigBuilder;
import org.apache.synapse.transport.nhttp.util.MessageFormatterDecoratorFactory;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.Pipe;
import org.apache.synapse.transport.passthru.config.TargetConfiguration;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.http2.transport.util.Http2ClientHandler;
import org.wso2.carbon.http2.transport.util.Http2ConnectionFactory;
import org.wso2.carbon.http2.transport.util.Http2CosumerIoControl;
import org.wso2.carbon.http2.transport.util.http2Encoder;

import javax.xml.stream.XMLStreamException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;

import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class Http2TransportSender extends AbstractTransportSender {
    private Http2ConnectionFactory connectionFactory;
    private ProxyConfig proxyConfig;

    private TargetConfiguration targetConfiguration;
    private static final Log log = LogFactory.getLog(Http2TransportSender.class);

    public void init(ConfigurationContext cfgCtx, TransportOutDescription transportOut)
            throws AxisFault {
        if (log.isDebugEnabled()) {
            log.debug("Initializing Http2 Connection Factory.");
        }
        super.init(cfgCtx, transportOut);
        connectionFactory = Http2ConnectionFactory.getInstance(transportOut);
        proxyConfig = new ProxyConfigBuilder().build(transportOut);
        log.info(proxyConfig.logProxyConfig());
        targetConfiguration = new TargetConfiguration(cfgCtx, transportOut, null, null,
                proxyConfig.createProxyAuthenticator());
        targetConfiguration.build();

    }

    public void sendMessage(MessageContext msgCtx, String targetEPR, OutTransportInfo trpOut)
            throws AxisFault {
        try {
            if (targetEPR.toLowerCase().contains("http2://")) {
                targetEPR = targetEPR.replaceFirst("http2://", "http://");
            }else if (targetEPR.toLowerCase().contains("https2://")) {
                targetEPR = targetEPR.replaceFirst("https2://", "https://");
            }
            URI uri = new URI(targetEPR);
            String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
            String hostname = uri.getHost();
            int port = uri.getPort();
            if (port == -1) {
                // use default
                if ("http".equals(scheme)) {
                    port = 80;
                } else if ("https".equals(scheme)) {
                    port = 443;
                }
            }
            HttpHost target = new HttpHost(hostname, port, scheme);
            boolean secure = "https".equals(target.getSchemeName());

            msgCtx.setProperty(PassThroughConstants.PROXY_PROFILE_TARGET_HOST,
                    target.getHostName());

            if (log.isDebugEnabled()) {
                log.debug("Fetching a Connection from the Http2(Https2) Connection Factory.");
            }
            ChannelHandlerContext channelCtx = (ChannelHandlerContext) msgCtx
                    .getProperty("stream-channel");

            Http2ClientHandler clientHandler = connectionFactory
                    .getChannelHandler(target, channelCtx.channel().id());
      //      clientHandler.setTargetConfig(targetConfiguration);

            /*RelayUtils.buildMessage(msgCtx, false);
            OMOutputFormat format = BaseUtils.getOMOutputFormat(msgCtx);
            MessageFormatter messageFormatter = MessageProcessorSelector
                    .getMessageFormatter(msgCtx);
            StringWriter sw = new StringWriter();
            OutputStream out = new WriterOutputStream(sw, format.getCharSetEncoding());
            messageFormatter.writeTo(msgCtx, format, out, true);
            String contentType = messageFormatter
                    .getContentType(msgCtx, format, msgCtx.getSoapAction());
            out.close();
            String msg = sw.toString();
*/
     ///       int streamId = clientHandler.getStreamId();
        //    Channel channel = clientHandler.getChannel();

            if (log.isDebugEnabled()) {
                log.debug("Channel created to send message");
            }

         //   if (channel.isActive()) {
         //       clientHandler.setRequest(streamId, msgCtx);
                if (log.isDebugEnabled()) {
                    log.debug("Sending message to backend... ");
                }
                String method = (msgCtx.getProperty(Constants.Configuration.HTTP_METHOD) != null ?
                        msgCtx.getProperty(Constants.Configuration.HTTP_METHOD).toString() :
                        POST.toString());
                //Set content type and required frames
                HttpMethod m = new HttpMethod(method);


                String msg="<Hi> this is wso2</Hi>";
                FullHttpRequest request;
                request = new DefaultFullHttpRequest(HTTP_1_1, m, uri.getPath(),
                        Unpooled.copiedBuffer(msg.getBytes(CharsetUtil.UTF_8)));
                if (!(m.equals(HttpMethod.GET) || m.equals(HttpMethod.DELETE))) {
                    request.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/xml");
                    request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
                    request.headers()
                            .add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
                }


                request.headers().add(HttpHeaderNames.HOST, new URI(targetEPR).getHost());
                request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(),
                        secure ? HttpScheme.HTTPS : HttpScheme.HTTP);
                //channel.writeAndFlush(request);



/*
                Http2Headers headers = new DefaultHttp2Headers().method(method)
                        .scheme(secure ? HttpScheme.HTTPS.toString() : HttpScheme.HTTP.toString());
                headers.path(uri.getPath());
                headers.authority( new URI(targetEPR).getHost());
                if (!(m.equals(HttpMethod.GET) || m.equals(HttpMethod.DELETE))) {
                    headers.add(HttpHeaderNames.CONTENT_TYPE, "text/xml");
                    headers.add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
                   headers.add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
                }
                channel.write(new DefaultHttp2HeadersFrame(headers,true));
                channel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(msg.getBytes()),true));*/
                /*MessageFormatter messageFormatter =
                        MessageFormatterDecoratorFactory.createMessageFormatterDecorator(msgCtx);
                OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(msgCtx);
                if (msgCtx.isServerSide()) {
                    OutTransportInfo transportInfo =
                            (OutTransportInfo) msgCtx.getProperty(Constants.OUT_TRANSPORT_INFO);

                    if (transportInfo != null) {
                        transportInfo.setContentType(
                                messageFormatter.getContentType(msgCtx, format, msgCtx.getSoapAction()));
                    } else {
                        throw new AxisFault(Constants.OUT_TRANSPORT_INFO + " has not been set");
                    }
                }*/

                Pipe pipe = (Pipe)msgCtx.getProperty("pass-through.pipe");
                if(pipe!=null){
                    pipe.attachConsumer(new Http2CosumerIoControl());
               //     int t=pipe.consume(new http2Encoder(channel,request));
                }

                //clientHandler.put(streamId, request);

                if (log.isDebugEnabled()) {
             //       log.debug("Request sent to backend with stream id:" + streamId);
                }
        //    }
        } catch (URISyntaxException e) {
            log.error("Error parsing the http2 endpoint url", e);
       // } catch (IOException e) {
            log.error("Error writting to the http2 channel", e);
        }
    }
}
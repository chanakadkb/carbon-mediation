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


import io.netty.channel.ChannelHandlerContext;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.transport.OutTransportInfo;
import org.apache.axis2.transport.base.AbstractTransportSender;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.synapse.transport.http.conn.ProxyConfig;
import org.apache.synapse.transport.nhttp.config.ProxyConfigBuilder;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.config.TargetConfiguration;
import org.wso2.carbon.http2.transport.util.Http2ClientHandler;
import org.wso2.carbon.http2.transport.util.Http2ConnectionFactory;
import org.wso2.carbon.http2.transport.util.Http2Constants;
import org.wso2.carbon.http2.transport.util.Http2TargetRequestUtil;

import java.net.URI;
import java.net.URISyntaxException;

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
        //log.info(proxyConfig.logProxyConfig());
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

            HttpHost proxy = proxyConfig.selectProxy(target);

            msgCtx.setProperty(PassThroughConstants.PROXY_PROFILE_TARGET_HOST,
                    target.getHostName());

            HttpRoute route;
            if (proxy != null) {
                route = new HttpRoute(target, null, proxy, secure);
            } else {
                route = new HttpRoute(target, null, secure);
            }
            Http2TargetRequestUtil util=new Http2TargetRequestUtil(targetConfiguration,route);
            msgCtx.setProperty(Http2Constants.PASSTHROUGH_TARGET,util);


            ChannelHandlerContext channelCtx = (ChannelHandlerContext) msgCtx
                    .getProperty("stream-channel");

            Http2ClientHandler clientHandler = connectionFactory
                    .getChannelHandler(target, channelCtx.channel().id());

            clientHandler.setTargetConfiguration(targetConfiguration);
            clientHandler.channelWrite(msgCtx);

            //Termination of a connection
            if(msgCtx.getProperty(Http2Constants.HTTP2_REQUEST_TYPE)!=null
                    && Http2Constants.HTTP2_GO_AWAY_REQUEST.equals((String)msgCtx.getProperty(
                            Http2Constants.HTTP2_REQUEST_TYPE))){
                connectionFactory.removeHanlder(channelCtx.channel().id());
            }

        } catch (URISyntaxException e){
            log.error("Error parsing the http2 endpoint url", e);
        }
    }
}
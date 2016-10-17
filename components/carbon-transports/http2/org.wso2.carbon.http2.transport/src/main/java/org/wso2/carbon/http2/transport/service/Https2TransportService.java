/**
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.http2.transport.service;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.core.transports.AbstractTransportService;
import org.wso2.carbon.core.transports.util.TransportParameter;

public class Https2TransportService extends AbstractTransportService {

    private static final Log log = LogFactory.getLog(Https2TransportService.class);

    public static final String TRANSPORT_NAME = "https2";
    public static final String TRANSPORT_CONF = "secure-http2-transports.xml";

    public Https2TransportService() {
        super(TRANSPORT_NAME);
    }

    public boolean dependenciesAvailable(TransportParameter[] params) {
        try {
            Class.forName("io.netty.handler.codec.http2.Http2ConnectionHandler");
            return true;
        } catch (ClassNotFoundException e) {
            log.error("Https2 protocol handlers not found");
            return false;
        }
    }
}













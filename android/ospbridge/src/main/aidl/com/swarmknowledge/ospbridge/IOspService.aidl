// OSP-Lite service contract (osp_lite_v05.html §5). Host apps such as
// harnessDroid bind to this service to submit queries and receive outcomes.
package com.swarmknowledge.ospbridge;

import com.swarmknowledge.ospbridge.IOspCallback;

interface IOspService {
    String submitQuery(in String text, in int stakesTier);      // → queryId
    void registerCallback(in String queryId, in IOspCallback cb);
    void advertiseCentroid(in byte[] cborCodebook);              // ≤ 1 KB
    byte[] fetchKeyBundle(in String nodeId);                     // signer key bundle (dev: HMAC id)
}
